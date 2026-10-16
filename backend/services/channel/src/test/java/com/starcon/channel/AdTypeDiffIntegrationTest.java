package com.starcon.channel;

import static com.starcon.channel.AdTypeDiffModels.*;
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
 * 14.1.2.2 广告类型差异。
 *
 * <p>本测试类钉八组事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户
 *   <li><b>🔴 归一口径的真相在 2.1.2.1</b>：租户覆盖 &gt; 平台默认 &gt; 未映射；声明与解析结果不一致要被拒；
 *       声称某一层却在那张表里查无此行，DB 层兜底拒
 *   <li><b>🔴 原始类型标记一个字节都不改</b>：原始标记、归一类型、原始报文原文三者并存
 *   <li><b>🔴 保真审计不可伪造</b>：服务层谎报 {@code rawOnlyFieldKeys} / {@code mappingStatus} 会被 DB
 *       触发器改写回来；谎报「已归一」一个原文里没有的键会被直接拒绝
 *   <li><b>🔴 未映射不兜底</b>：UNMAPPED 类型不进可比汇总，但在可比视图里带完整原始标记单列
 *   <li><b>特有字段补充展示</b>：原始值与归一落点同条给出，未定义的原始键显式露出
 *   <li><b>时间字段写回值正确</b>（AGENTS.md 要求断言写回值，不能只断言没抛异常）
 *   <li><b>真分页真筛选</b>；跨租户与不存在返回逐字相同的 404
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让「这次写入
 * 落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdTypeDiffIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  /**
   * 🔴 每个用例一对**全新的租户**。
   *
   * <p>刻意不给整个测试类共用一对租户 UUID：同一个 Testcontainers 容器里所有用例共享一个库，
   * 共用租户就意味着前一个用例建的档案会被后一个用例的 total / 列表断言数进去 ——
   * 那不是隔离被破坏，而是**测试之间互相污染**，症状是「断言期望 0 却拿到 1」这种
   * 与被测行为无关的红。
   */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  /** 🔴 钉死的时刻。用墙钟会让「写回值对不对」变成 flaky 断言。 */
  static final OffsetDateTime RAW_FETCHED_AT = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
  static final OffsetDateTime START_AT = OffsetDateTime.parse("2026-10-02T00:00:00+08:00");
  static final OffsetDateTime END_AT = OffsetDateTime.parse("2026-11-02T00:00:00+08:00");

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final UUID TIKTOK = UUID.fromString("22222222-2222-2222-2222-222222222222");
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
      List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 每个用例一对新租户 + 对应的会话（MockMvc 用的 bearer 也随之更新）。 */
  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    String bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);
  }

  /**
   * 播种全局码表（平台目录 + 平台默认映射）。
   *
   * <p>🔴 刻意**不在静态块里**播种：静态块早于 Spring 上下文刷新，而 Flyway 是在上下文刷新
   * 时才建表的 —— 在静态块写 {@code platform} 会拿到「relation platform does not exist」。
   *
   * <p>全部 {@code on conflict do nothing}：这些是全局共享码表，同一个测试类里多个用例会
   * 反复播种同一条，不幂等就会撞唯一约束。
   */
  @BeforeEach
  void seedGlobalCatalog() {
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + AMAZON + "','AMAZON','Amazon','OFFICIAL_API','AVAILABLE',1)"
        + " on conflict (id) do nothing");
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + TIKTOK + "','TIKTOK','TikTok Shop','OFFICIAL_API','AVAILABLE',2)"
        + " on conflict (id) do nothing");
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

  @Autowired AdTypeDiffService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** 以管理员（超级用户，RLS 不生效）连接执行 SQL，用来验证 DB 层的兜底。 */
  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 以「已认证的租户操作者」身份执行 SQL：绑定租户 + actor + 权限 + 数据范围。
   *
   * <p>🔴 店铺表上除了 RLS 还有一条 {@code data_scope} RESTRICTIVE 策略读 {@code app.grants}，
   *   只 {@code set app.current_tenant} 是不够的 —— 漏一项会得到一个「看起来像 RLS 挂了」的假象。
   */
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

  /**
   * 播种一条 2.1.2.1 的**平台默认**映射（全局码表，channel_app 无写权限，故用超级用户）。
   *
   * <p>🔴 幂等：多个用例会反复播种同一条映射，不幂等就会撞
   * {@code UNIQUE(platform_id, enum_kind, external_value)}。
   */
  static void seedPlatformMapping(UUID platformId, String external, String internal) {
    exec("insert into platform_enum_mapping (id,platform_id,enum_kind,external_value,internal_value)"
        + " values ('" + UUID.randomUUID() + "','" + platformId + "','AD_TYPE','" + external
        + "','" + internal + "')"
        + " on conflict (platform_id,enum_kind,external_value) do nothing");
  }

  /**
   * 播种一条 2.1.2.1 的**租户覆盖**（V3 表，channel_app 可写）。
   *
   * <p>🔴 同样幂等：同一用例可能重复覆盖同一外部值，重复插入会撞
   * {@code UNIQUE(tenant_id, platform_id, enum_kind, external_value)}。
   */
  void seedTenantOverride(UUID tenant, UUID platformId, String external, String internal) {
    execAsTenant(tenant,
        "insert into platform_enum_override (id,tenant_id,platform_id,enum_kind,external_value,"
            + "internal_value) values ('" + UUID.randomUUID() + "','" + tenant + "','" + platformId
            + "','AD_TYPE','" + external + "','" + internal + "')"
            + " on conflict (tenant_id,platform_id,enum_kind,external_value) do nothing");
  }

  /**
   * 播种一个店铺。
   *
   * <p>🔴 幂等：店铺码是 {@code UNIQUE(tenant_id, code)}，而多个用例会复用同一个码做不同的
   *   广告数据，不幂等就会在第二个用例里撞唯一约束 —— 症状是「测试之间互相污染」。
   */
  String seedShop(UUID tenant, UUID platformId, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + platformId + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return code;
  }

  /** 登记一个档案（默认从 2.1.2.1 解析，不传 id / version）。 */
  UUID saveProfile(AccessService.Access who, UUID platformId, String external, String internal) {
    return as(who, "channel.operation.create",
        () -> service.saveProfile(new SaveProfileRequest(
            null, platformId, external, internal, external + " 展示名", "CPC", "SALES",
            true, true, true, true, false,
            "UNMAPPED".equals(internal) ? "UNMAPPED" : "PLATFORM_DEFAULT", "测试登记")))
        .id();
  }

  static com.fasterxml.jackson.databind.JsonNode json(String raw) {
    try {
      return JSON.readTree(raw);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  static SavePayloadRequest payloadRequest(
      UUID profileId, String shop, String externalAdId, String raw, List<String> normalized) {
    return new SavePayloadRequest(
        null, profileId, shop, externalAdId, "CAMP-" + externalAdId, externalAdId + " 广告",
        "ENABLED", "USD", new BigDecimal("100.00"), new BigDecimal("1.25"), new BigDecimal("0.10"),
        START_AT, END_AT,
        new BigDecimal("2000"), new BigDecimal("100"), new BigDecimal("5"),
        new BigDecimal("125.0000"), new BigDecimal("500.0000"),
        json(raw), RAW_FETCHED_AT, normalized, "test-batch-1", "集成测试");
  }

  // ==================================================================
  // 1. 归一口径走 2.1.2.1 两层
  // ==================================================================

  @Test
  @DisplayName("14.1.2.2 归一口径走 2.1.2.1 两层：平台默认能解析，未映射显式报 UNMAPPED")
  void resolveFollowsTwoLayersOfUpstream() {
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");

    var hit = as(actorA, "channel.operation.read",
        () -> service.resolve(AMAZON, "sponsored_display"));
    assertThat(hit.mapped()).isTrue();
    assertThat(hit.internalAdType()).isEqualTo("SPONSORED_DISPLAY");
    assertThat(hit.mappingSource()).isEqualTo("PLATFORM_DEFAULT");
    // 🔴 原始标记原样回显：解析不能顺手把它折叠成内部类型
    assertThat(hit.platformAdType()).isEqualTo("sponsored_display");

    // 🔴 没登记过 ⇒ 显式未映射，且**必须给出理由**
    var miss = as(actorA, "channel.operation.read", () -> service.resolve(AMAZON, "legacy_promo"));
    assertThat(miss.mapped()).isFalse();
    assertThat(miss.internalAdType()).isEqualTo("UNMAPPED");
    assertThat(miss.unmappedReason()).contains("platform_enum_mapping").contains("legacy_promo");
  }

  @Test
  @DisplayName("14.1.2.2 租户覆盖优先于平台默认，且只影响本租户")
  void tenantOverrideBeatsPlatformDefault() {
    seedPlatformMapping(TIKTOK, "video_promotion", "PROMOTION_EVENT");
    seedTenantOverride(a, TIKTOK, "video_promotion", "DSP_PROGRAMMATIC");

    // A 看到自己的覆盖
    var forA = as(actorA, "channel.operation.read", () -> service.resolve(TIKTOK, "video_promotion"));
    assertThat(forA.internalAdType()).isEqualTo("DSP_PROGRAMMATIC");
    assertThat(forA.mappingSource()).isEqualTo("TENANT_OVERRIDE");

    // 🔴 B 没有覆盖 ⇒ 落回平台默认，全局码表不受 A 的覆盖污染
    var forB = as(actorB, "channel.operation.read", () -> service.resolve(TIKTOK, "video_promotion"));
    assertThat(forB.internalAdType()).isEqualTo("PROMOTION_EVENT");
    assertThat(forB.mappingSource()).isEqualTo("PLATFORM_DEFAULT");
  }

  @Test
  @DisplayName("14.1.2.2 声明的归一类型与 2.1.2.1 解析结果不一致时被拒 —— 真相在 2.1.2.1，不在请求里")
  void declaredNormalizationMustMatchUpstream() {
    seedPlatformMapping(AMAZON, "sponsored_brand", "SPONSORED_BRAND");

    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.saveProfile(new SaveProfileRequest(
            null, AMAZON, "sponsored_brand", "DSP_PROGRAMMATIC", "错配", "CPC", "SALES",
            false, false, false, false, false, "PLATFORM_DEFAULT", ""))))
        .hasMessageContaining("与 2.1.2.1 解析结果");

    // 来源也必须对得上
    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.saveProfile(new SaveProfileRequest(
            null, AMAZON, "sponsored_brand", "SPONSORED_BRAND", "错配来源", "CPC", "SALES",
            false, false, false, false, false, "TENANT_OVERRIDE", ""))))
        .hasMessageContaining("实际命中的层");

    // 正确的登记能过
    UUID id = saveProfile(actorA, AMAZON, "sponsored_brand", "SPONSORED_BRAND");
    assertThat(id).isNotNull();
  }

  @Test
  @DisplayName("14.1.2.2 绕过服务层写 SQL：声称的归一口径来源查无此行时 DB 层兜底拒")
  void databaseRejectsPhantomMappingSource() {
    UUID id = UUID.randomUUID();
    assertThatThrownBy(() -> execAsTenant(a,
        "insert into ad_type_profile (tenant_id,id,platform_id,platform_ad_type,internal_ad_type,"
            + "display_name,auction_model,objective,mapping_source)"
            + " values ('" + a + "','" + id + "','" + AMAZON + "','ghost_type','SPONSORED_BRAND',"
            + "'幽灵','CPC','SALES','PLATFORM_DEFAULT')"))
        .hasMessageContaining("查无此行");
  }

  // ==================================================================
  // 2. 原始类型标记一个字节都不改
  // ==================================================================

  @Test
  @DisplayName("14.1.2.2 原始标记 / 归一类型 / 原始报文三者并存，归一只多一列")
  void originalMarkerAndRawPayloadSurviveNormalization() {
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    String shop = seedShop(a, AMAZON, "SHOP-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");

    String raw = "{\"bid\":1.5,\"audienceSegments\":[\"sports\"],\"placement\":\"top\"}";
    var saved = as(actorA, "channel.operation.create",
        () -> service.savePayload(payloadRequest(profile, shop, "AD-1", raw, List.of("bid"))));

    // 🔴 原始标记保留
    assertThat(saved.row().platformAdType()).isEqualTo("sponsored_display");
    // 🔴 归一类型并存
    assertThat(saved.row().internalAdType()).isEqualTo("SPONSORED_DISPLAY");

    var detail = as(actorA, "channel.operation.read", () -> service.payload(saved.row().id().toString()));
    // 🔴 原始报文原文没被改写。🔴 用 JsonNode 比较而不是字符串：jsonb **不保留键的书写顺序**
    //   （PG 会按键长度再按字节排序），比字符串会把「原文没改」误判成失败。
    //   内容（含全部键与值）与原文逐字一致才是本测试要证明的事。
    assertThat(detail.rawPayload()).isEqualTo(json(raw));
  }

  @Test
  @DisplayName("14.1.2.2 派生比率由 DB 算：分母为 0 给 null（不可比），不是 0")
  void derivedRatiosComeFromDatabase() {
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    String shop = seedShop(a, AMAZON, "SHOP-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");

    var saved = as(actorA, "channel.operation.create", () -> service.savePayload(
        payloadRequest(profile, shop, "AD-RATIO", "{\"bid\":1.5}", List.of("bid"))));

    // 2000 展示 / 100 点击 / 5 转化 / 125 花费 / 500 归因
    assertThat(saved.row().ctr()).isEqualByComparingTo("0.05");
    assertThat(saved.row().cpc()).isEqualByComparingTo("1.25");
    assertThat(saved.row().cvr()).isEqualByComparingTo("0.05");
    assertThat(saved.row().acos()).isEqualByComparingTo("0.25");

    // 🔴 零展示 ⇒ 比率是 null（不可比），绝不是 0（0 会被读成「可比但真的是零」）
    execAsTenant(a, "update ad_type_payload set impressions=0, clicks=0, conversions=0,"
        + " spend=0, attributed_revenue=null where id='" + saved.row().id() + "'");
    var zeroed = as(actorA, "channel.operation.read", () -> service.payload(saved.row().id().toString()));
    assertThat(zeroed.payload().ctr()).isNull();
    assertThat(zeroed.payload().cpc()).isNull();
    assertThat(zeroed.payload().acos()).isNull();
  }

  @Test
  @DisplayName("14.1.2.2 时间字段写回值正确（timestamptz 不被静默偏 -28800s）")
  void timestampRoundTripsWithoutShift() {
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    String shop = seedShop(a, AMAZON, "SHOP-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");

    var saved = as(actorA, "channel.operation.create",
        () -> service.savePayload(payloadRequest(profile, shop, "AD-TIME", "{\"bid\":1}", List.of("bid"))));

    // 🔴 断言**写回的值**（AGENTS.md：不能只断言没抛异常）—— 同一瞬间，不偏移 8 小时
    assertThat(saved.row().startAt().toInstant()).isEqualTo(START_AT.toInstant());
    assertThat(saved.row().endAt().toInstant()).isEqualTo(END_AT.toInstant());
    assertThat(saved.row().rawFetchedAt().toInstant()).isEqualTo(RAW_FETCHED_AT.toInstant());
  }

  // ==================================================================
  // 3. 保真审计不可伪造
  // ==================================================================

  @Test
  @DisplayName("14.1.2.2 服务层谎报保真结果会被 DB 改写回来（服务层声称什么都不作数）")
  void databaseDerivesFidelityOverServiceClaims() {
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    String shop = seedShop(a, AMAZON, "SHOP-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    UUID payloadId = UUID.randomUUID();

    // 🔴 手工写一行：normalized 只声明了 bid，raw 里还有两个键没有落点，
    //   并**故意**把 raw_only_field_keys 与 mapping_status 谎报成「全归一了」
    execAsTenant(a,
        "insert into ad_type_payload (tenant_id,id,ad_type_profile_id,shop_code,external_ad_id,"
            + "ad_status,raw_payload,raw_fetched_at,normalized_field_keys,raw_only_field_keys,"
            + "mapping_status,ingested_at) values ('" + a + "','" + payloadId + "','" + profile
            + "','" + shop + "','AD-LIE','ENABLED',"
            + "'{\"bid\":1.5,\"placement\":\"top\",\"nicheFlag\":true}'::jsonb,'"
            + RAW_FETCHED_AT + "',ARRAY['bid'],ARRAY['i_lied_on_purpose'],'NORMALIZED','"
            + RAW_FETCHED_AT + "')");

    var detail = as(actorA, "channel.operation.read", () -> service.payload(payloadId.toString()));
    // 🔴 谎报的 raw_only_field_keys 被改写成原文派生出来的真实差集
    assertThat(detail.payload().rawOnlyFieldKeys()).containsExactly("nicheFlag", "placement");
    // 🔴 谎报的 NORMALIZED 被改写成 PARTIAL_FIELD_COVERAGE
    assertThat(detail.payload().mappingStatus()).isEqualTo("PARTIAL_FIELD_COVERAGE");
    assertThat(detail.payload().normalizedFieldKeys()).containsExactly("bid");
  }

  @Test
  @DisplayName("14.1.2.2 声称归一了原始报文里不存在的键 ⇒ 覆盖率虚报，直接拒")
  void databaseRejectsInflatedCoverageClaim() {
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    String shop = seedShop(a, AMAZON, "SHOP-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");

    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.savePayload(
            payloadRequest(profile, shop, "AD-GHOST", "{\"bid\":1.5}", List.of("bid", "neverSent")))))
        .hasMessageContaining("不在原始报文里");
  }

  @Test
  @DisplayName("14.1.2.2 声称可比却没有内部落点 ⇒ DB 的 CHECK 拒")
  void comparableWithoutInternalRepresentationIsRejected() {
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");

    assertThatThrownBy(() -> as(actorA, "channel.operation.create",
        () -> service.saveFieldSpec(profile.toString(), new SaveFieldSpecRequest(
            null, "audienceSegments", "受众包", "LIST", "", "", true, "", false, 10, ""))))
        .hasMessageContaining("内部落点");

    // 绕过服务层写 SQL 也一样被 CHECK 拒
    assertThatThrownBy(() -> execAsTenant(a,
        "insert into ad_type_field_spec (tenant_id,id,ad_type_profile_id,field_key,field_label,"
            + "data_type,comparable) values ('" + a + "','" + UUID.randomUUID() + "','" + profile
            + "','audienceSegments','受众包','LIST',true)"))
        .hasMessageContaining("ad_type_field_spec_check");
  }

  // ==================================================================
  // 4. 特有字段补充展示
  // ==================================================================

  @Test
  @DisplayName("14.1.2.2 特有字段补充展示：原始值与归一落点同条给出，未定义的原始键显式露出")
  void extraFieldsAreAssembledServerSide() {
    seedPlatformMapping(AMAZON, "sponsored_brand", "SPONSORED_BRAND");
    String shop = seedShop(a, AMAZON, "SHOP-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_brand", "SPONSORED_BRAND");

    as(actorA, "channel.operation.create",
        () -> service.saveFieldSpec(profile.toString(), new SaveFieldSpecRequest(
            null, "bid", "竞价", "MONEY", "USD", "", true, "default_bid", false, 10, "")));
    as(actorA, "channel.operation.create",
        () -> service.saveFieldSpec(profile.toString(), new SaveFieldSpecRequest(
            null, "headlineText", "广告标题", "STRING", "", "", false, "", false, 20, "")));

    var saved = as(actorA, "channel.operation.create", () -> service.savePayload(
        payloadRequest(profile, shop, "AD-SB",
            "{\"bid\":1.5,\"headlineText\":\"Buy Now\",\"secretSauce\":true}",
            List.of("bid"))));

    var detail = as(actorA, "channel.operation.read", () -> service.payload(saved.row().id().toString()));

    assertThat(detail.extraFields()).extracting(ExtraFieldView::fieldKey)
        .containsExactly("bid", "headlineText");

    // 🔴 有内部落点的字段：原始值 + 归一后的落点都在
    var bid = detail.extraFields().get(0);
    assertThat(bid.rawValue()).isEqualTo("1.5");
    assertThat(bid.normalized()).isTrue();
    assertThat(bid.internalRepresentation()).isEqualTo("default_bid");

    // 🔴 没有内部落点的字段：值原样可查，但明确标成不参与可比
    var headline = detail.extraFields().get(1);
    assertThat(headline.rawValue()).isEqualTo("Buy Now");
    assertThat(headline.normalized()).isFalse();
    assertThat(headline.comparabilityNote()).contains("没有内部落点");

    // 🔴 没有任何字段定义覆盖的原始键，必须显式露出而不是悄悄忽略
    assertThat(detail.undocumentedKeys()).containsExactly("secretSauce");
    // 🔴 它同时进了 raw_only_field_keys（因为原文里声明只归一了 bid）
    assertThat(detail.payload().rawOnlyFieldKeys()).containsExactly("headlineText", "secretSauce");

    // 档案详情里也能查到「未被定义覆盖的键」
    var profileDetail = as(actorA, "channel.operation.read", () -> service.profile(profile.toString()));
    assertThat(profileDetail.undocumentedKeys()).containsExactly("secretSauce");
  }

  @Test
  @DisplayName("14.1.2.2 原始报文必须是 JSON 对象，数组 / 标量一律拒")
  void rawPayloadMustBeJsonObject() {
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    String shop = seedShop(a, AMAZON, "SHOP-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");

    assertThatThrownBy(() -> as(actorA, "channel.operation.create", () -> service.savePayload(
        payloadRequest(profile, shop, "AD-ARR", "[1,2,3]", List.of()))))
        .hasMessageContaining("必须是 JSON 对象");
  }

  // ==================================================================
  // 5. 未映射不兜底 + 跨平台可比
  // ==================================================================

  @Test
  @DisplayName("14.1.2.2 未映射类型显式暴露：带完整原始标记单列，且不进可比汇总")
  void unmappedTypesAreVisibleButNotComparable() {
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    String shop = seedShop(a, AMAZON, "SHOP-AMZ-1");
    UUID mapped = saveProfile(actorA, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    // 🔴 这一条在 2.1.2.1 里没有任何映射 ⇒ 必须落 UNMAPPED
    UUID unmapped = saveProfile(actorA, AMAZON, "mystery_legacy_type", "UNMAPPED");

    as(actorA, "channel.operation.create",
        () -> service.savePayload(payloadRequest(mapped, shop, "AD-OK", "{\"bid\":1}", List.of("bid"))));
    as(actorA, "channel.operation.create",
        () -> service.savePayload(payloadRequest(unmapped, shop, "AD-UNK", "{\"x\":1}", List.of("x"))));

    var view = as(actorA, "channel.operation.read", () -> service.comparable());

    // 未映射的不在可比汇总里
    assertThat(view.types()).extracting(ComparableTypeSummary::internalAdType)
        .containsExactly("SPONSORED_DISPLAY");
    // 🔴 但它带着完整原始标记单列出来 —— 从表里删掉会让人以为它不存在
    assertThat(view.unmappedTypes()).extracting(UnmappedTypeSummary::platformAdType)
        .containsExactly("mystery_legacy_type");
    assertThat(view.unmappedTypes().get(0).reason()).contains("platform_enum_mapping");
    assertThat(view.unmappedTypes().get(0).adCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("14.1.2.2 跨平台可比：同一内部类型下各平台的原始标记成对出现")
  void comparableViewKeepsPerPlatformOriginalMarkers() {
    // Amazon 的 SD 与 TikTok 的 video_promotion 都归到 SPONSORED_DISPLAY ⇒ 可比
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    seedPlatformMapping(TIKTOK, "display_video", "SPONSORED_DISPLAY");
    String amzShop = seedShop(a, AMAZON, "SHOP-AMZ-1");
    String ttShop = seedShop(a, TIKTOK, "SHOP-TT-1");
    UUID amzProfile = saveProfile(actorA, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    UUID ttProfile = saveProfile(actorA, TIKTOK, "display_video", "SPONSORED_DISPLAY");

    as(actorA, "channel.operation.create",
        () -> service.savePayload(payloadRequest(amzProfile, amzShop, "AD-A", "{\"bid\":1}", List.of("bid"))));
    as(actorA, "channel.operation.create",
        () -> service.savePayload(payloadRequest(ttProfile, ttShop, "AD-B", "{\"bid\":2}", List.of("bid"))));

    var view = as(actorA, "channel.operation.read", () -> service.comparable());
    assertThat(view.types()).hasSize(1);
    var sd = view.types().get(0);

    assertThat(sd.internalAdType()).isEqualTo("SPONSORED_DISPLAY");
    assertThat(sd.adCount()).isEqualTo(2);
    assertThat(sd.platformCount()).isEqualTo(2);
    // 🔴 归一让两条可比，但两个平台的原始标记都还在
    assertThat(sd.markers()).extracting(PlatformTypeMarker::platformAdType)
        .containsExactlyInAnyOrder("sponsored_display", "display_video");
    // 拍卖方式与投放目标一并列出：横向比较成立的前提是它们也归一到同一口径
    assertThat(sd.auctionModels()).containsExactly("CPC");
    assertThat(sd.impressions()).isEqualByComparingTo("4000");
  }

  @Test
  @DisplayName("14.1.2.2 保真概览把「系统没有归一哪些字段」列成明账")
  void fidelityOverviewListsRawOnlyFields() {
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    String shop = seedShop(a, AMAZON, "SHOP-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");

    as(actorA, "channel.operation.create",
        () -> service.savePayload(payloadRequest(profile, shop, "AD-F1",
            "{\"bid\":1,\"placement\":\"top\",\"audienceSegments\":[\"a\"]}", List.of("bid"))));
    as(actorA, "channel.operation.create",
        () -> service.savePayload(payloadRequest(profile, shop, "AD-F2",
            "{\"bid\":2,\"placement\":\"side\"}", List.of("bid"))));

    var overview = as(actorA, "channel.operation.read", () -> service.fidelity());
    assertThat(overview.totalAds()).isGreaterThanOrEqualTo(2);
    assertThat(overview.partialAds()).isGreaterThanOrEqualTo(2);
    assertThat(overview.rawOnlyFields()).extracting(RawOnlyFieldCount::fieldKey)
        .contains("audienceSegments", "placement");
    assertThat(overview.rawOnlyFields().stream()
        .filter(f -> f.fieldKey().equals("placement")).findFirst().orElseThrow()
        .occurrences()).isEqualTo(2);
  }

  // ==================================================================
  // 6. 真分页真筛选 / 404
  // ==================================================================

  @Test
  @DisplayName("14.1.2.2 真分页真筛选：汇总不受 limit/offset 影响")
  void paginationAndFiltersAreReal() {
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    seedPlatformMapping(TIKTOK, "display_video", "SPONSORED_DISPLAY");
    String amzShop = seedShop(a, AMAZON, "PAGE-AMZ-1");
    UUID p1 = saveProfile(actorA, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    UUID p2 = saveProfile(actorA, TIKTOK, "display_video", "SPONSORED_DISPLAY");

    for (int i = 0; i < 5; i++) {
      String id = "AD-PAGE-" + i;
      as(actorA, "channel.operation.create",
          () -> service.savePayload(payloadRequest(p1, amzShop, id, "{\"bid\":1}", List.of("bid"))));
    }

    var firstPage = as(actorA, "channel.operation.read",
        () -> service.payloads(AMAZON, "SPONSORED_DISPLAY", null, amzShop, "EXTERNAL_ID", 0, 2));
    assertThat(firstPage.items()).hasSize(2);
    // 🔴 total 不受 limit/offset 影响
    assertThat(firstPage.total()).isGreaterThanOrEqualTo(5);

    var secondPage = as(actorA, "channel.operation.read",
        () -> service.payloads(AMAZON, "SPONSORED_DISPLAY", null, amzShop, "EXTERNAL_ID", 1, 2));
    assertThat(secondPage.items()).extracting(AdTypePayloadRow::externalAdId)
        .containsExactly("AD-PAGE-2", "AD-PAGE-3");

    // 按平台筛选真的收窄
    var tiktokOnly = as(actorA, "channel.operation.read",
        () -> service.payloads(TIKTOK, null, null, null, "INGESTED_DESC", 0, 50));
    assertThat(tiktokOnly.items()).isEmpty();
    assertThat(tiktokOnly.total()).isZero();
    assertThat(p2).isNotNull();
  }

  @Test
  @DisplayName("14.1.2.2 跨租户与不存在返回逐字相同的 404")
  void crossTenantAndMissingAreIdenticalNotFound() {
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    String shopA = seedShop(a, AMAZON, "ISO-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    var saved = as(actorA, "channel.operation.create",
        () -> service.savePayload(payloadRequest(profile, shopA, "AD-ISO", "{\"bid\":1}", List.of("bid"))));

    var crossTenant = catchThrowable(() ->
        as(actorB, "channel.operation.read", () -> service.profile(profile.toString())));
    var missing = catchThrowable(() ->
        as(actorB, "channel.operation.read", () -> service.profile(UUID.randomUUID().toString())));
    // 🔴 两个都要真的抛了，且理由逐字相同 —— 否则「跨租户 404」可能被「恰好也报同样的话」骗过
    assertThat(crossTenant).isNotNull();
    assertThat(missing).isNotNull();
    assertThat(crossTenant.getMessage()).isEqualTo(missing.getMessage());

    var payloadCross = catchThrowable(() ->
        as(actorB, "channel.operation.read", () -> service.payload(saved.row().id().toString())));
    var payloadMissing = catchThrowable(() ->
        as(actorB, "channel.operation.read", () -> service.payload(UUID.randomUUID().toString())));
    assertThat(payloadCross).isNotNull();
    assertThat(payloadMissing).isNotNull();
    assertThat(payloadCross.getMessage()).isEqualTo(payloadMissing.getMessage());

    // B 的列表里看不到 A 的任何东西
    assertThat(as(actorB, "channel.operation.read",
        () -> service.profiles(null, null, null, "PLATFORM", 0, 50)).total()).isZero();
  }

  @Test
  @DisplayName("14.1.2.2 端点：401 / 403 与真实业务往返")
  void endpointsWork() throws Exception {
    // 无会话一律 401
    http.perform(get("/channel/ad-type-diffs/capabilities")).andExpect(status().isUnauthorized());
    http.perform(get("/channel/ad-type-diffs/comparable")).andExpect(status().isUnauthorized());

    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    http.perform(get("/channel/ad-type-diffs/capabilities")
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.internalAdTypes", org.hamcrest.Matchers.hasItem("SPONSORED_DISPLAY")))
        .andExpect(jsonPath("$.unmappedNote").value(org.hamcrest.Matchers.containsString("未映射")));

    http.perform(get("/channel/ad-type-diffs/resolve")
            .header("Authorization", bearerA)
            .param("platformId", AMAZON.toString())
            .param("platformAdType", "sponsored_display"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mapped").value(true))
        .andExpect(jsonPath("$.internalAdType").value("SPONSORED_DISPLAY"));

    http.perform(get("/channel/ad-type-diffs/fidelity")
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rawOnlyFields").isArray());

    http.perform(get("/channel/ad-type-diffs/profiles/not-a-uuid")
            .header("Authorization", bearerA))
        .andExpect(status().isNotFound());
  }

  // ==================================================================
  // 7. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("14.1.2.2 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    String shopA = seedShop(a, AMAZON, "ISO2-AMZ-1");
    String shopB = seedShop(b, AMAZON, "ISO2-AMZ-2");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    UUID profileB = saveProfile(actorB, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    as(actorA, "channel.operation.create",
        () -> service.savePayload(payloadRequest(profileA, shopA, "AD-A", "{\"bid\":1}", List.of("bid"))));
    as(actorB, "channel.operation.create",
        () -> service.savePayload(payloadRequest(profileB, shopB, "AD-B", "{\"bid\":2}", List.of("bid"))));

    // 档案互不可见
    assertThat(as(actorA, "channel.operation.read",
        () -> service.profiles(null, null, null, "PLATFORM", 0, 50)).items())
        .extracting(AdTypeProfileRow::id).containsExactly(profileA);
    assertThat(as(actorB, "channel.operation.read",
        () -> service.profiles(null, null, null, "PLATFORM", 0, 50)).items())
        .extracting(AdTypeProfileRow::id).containsExactly(profileB);

    // 广告数据互不可见
    assertThat(as(actorA, "channel.operation.read",
        () -> service.payloads(null, null, null, null, "INGESTED_DESC", 0, 50)).items())
        .extracting(AdTypePayloadRow::externalAdId).containsExactly("AD-A");
    assertThat(as(actorB, "channel.operation.read",
        () -> service.payloads(null, null, null, null, "INGESTED_DESC", 0, 50)).items())
        .extracting(AdTypePayloadRow::externalAdId).containsExactly("AD-B");

    // 🔴 可比视图也只看得到自己的广告（虽然归一后的内部类型名是全局共用的）
    var viewA = as(actorA, "channel.operation.read", () -> service.comparable());
    assertThat(viewA.types().get(0).adCount()).isEqualTo(1);
    var viewB = as(actorB, "channel.operation.read", () -> service.comparable());
    assertThat(viewB.types().get(0).adCount()).isEqualTo(1);

    // 租户覆盖也互不可见：A 的覆盖不影响 B
    seedTenantOverride(a, TIKTOK, "iso_only", "DSP_PROGRAMMATIC");
    assertThat(as(actorA, "channel.operation.read", () -> service.resolve(TIKTOK, "iso_only")).mapped()).isTrue();
    assertThat(as(actorB, "channel.operation.read", () -> service.resolve(TIKTOK, "iso_only")).mapped()).isFalse();
  }

  @Test
  @DisplayName("14.1.2.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    String shopA = seedShop(a, AMAZON, "FORGE-AMZ-1");
    String shopB = seedShop(b, AMAZON, "FORGE-AMZ-2");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    UUID profileB = saveProfile(actorB, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    as(actorA, "channel.operation.create",
        () -> service.savePayload(payloadRequest(profileA, shopA, "AD-FORGE-A", "{\"bid\":1}", List.of("bid"))));
    as(actorB, "channel.operation.create",
        () -> service.savePayload(payloadRequest(profileB, shopB, "AD-FORGE-B", "{\"bid\":2}", List.of("bid"))));

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的广告
    http.perform(get("/channel/ad-type-diffs/payloads")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].externalAdId",
            org.hamcrest.Matchers.contains("AD-FORGE-A")));

    // 伪造租户头也不能让 B 的档案被 A 读到
    http.perform(get("/channel/ad-type-diffs/profiles/" + profileB)
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());

    // 无会话一律 401
    http.perform(get("/channel/ad-type-diffs/payloads")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("14.1.2.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    seedPlatformMapping(AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    String shopA = seedShop(a, AMAZON, "POOL-AMZ-1");
    String shopB = seedShop(b, AMAZON, "POOL-AMZ-2");
    UUID profileA = saveProfile(actorA, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    UUID profileB = saveProfile(actorB, AMAZON, "sponsored_display", "SPONSORED_DISPLAY");
    as(actorA, "channel.operation.create",
        () -> service.savePayload(payloadRequest(profileA, shopA, "AD-POOL-A", "{\"bid\":1}", List.of("bid"))));
    as(actorB, "channel.operation.create",
        () -> service.savePayload(payloadRequest(profileB, shopB, "AD-POOL-B", "{\"bid\":2}", List.of("bid"))));

    for (int i = 0; i < 3; i++) {
      // 档案 / 广告 / 可比 / 保真是同一个连接池上的四个入口
      assertThat(as(actorA, "channel.operation.read",
          () -> service.profiles(null, null, null, "PLATFORM", 0, 50)).items())
          .extracting(AdTypeProfileRow::id).containsExactly(profileA);
      assertThat(as(actorB, "channel.operation.read",
          () -> service.profiles(null, null, null, "PLATFORM", 0, 50)).items())
          .extracting(AdTypeProfileRow::id).containsExactly(profileB);
      assertThat(as(actorA, "channel.operation.read",
          () -> service.payloads(null, null, null, null, "INGESTED_DESC", 0, 50)).items())
          .extracting(AdTypePayloadRow::externalAdId).containsExactly("AD-POOL-A");
      assertThat(as(actorB, "channel.operation.read",
          () -> service.payloads(null, null, null, null, "INGESTED_DESC", 0, 50)).items())
          .extracting(AdTypePayloadRow::externalAdId).containsExactly("AD-POOL-B");
      assertThat(as(actorA, "channel.operation.read", () -> service.comparable())
          .types().get(0).adCount()).isEqualTo(1);
      assertThat(as(actorB, "channel.operation.read", () -> service.fidelity())
          .totalAds()).isGreaterThanOrEqualTo(1);
    }
  }
}
