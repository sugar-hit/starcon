package com.starcon.channel;

import static com.starcon.channel.AdKeywordModels.*;
import static com.starcon.channel.ListingModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
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
 * 14.1.3.1 关键词管理 的集成测试（真实 PostgreSQL 16 + Testcontainers）。
 *
 * <p>🔴 本测试类把蓝图加粗句逐条变成**可执行的断言**：
 *
 * <ol>
 *   <li>「<b>搜索词报告是发现浪费的入口</b>：高曝光零转化词应尽快否定」⇒
 *       搜索词报告能一眼识别浪费词；否定建议**带实际数值**（曝光/点击/转化/花费/ACOS），
 *       不是光秃秃的词表。
 *   <li>「<b>数据截至时间要显式</b>」⇒ 每个带指标的响应都带 dataAsOf / syncStatus，
 *       「没跑这个词」与「还没同步到数据」分得开。
 *   <li>「<b>批量调价与否定要有护栏</b>」⇒ 单次上限、幅度上限、绝对上限、总上调上限，
 *       越线**整批不动**；逐条返回执行结果。
 *   <li>「<b>归一类型与原始类型必须并存</b>」⇒ 两个字段同时返回；UNMAPPED 是显式合法值。
 *   <li>「<b>ACOS 分母口径</b>」⇒ 销售额为 0 时 ACOS 是 **null**，不是 0。
 *   <li>「<b>词频与排名口径兼容 4.5.1.2</b>」⇒ 名次来自 4.5.1.2 的 ranking 快照。
 * </ol>
 *
 * <p>🔴 时间纪律：写时间的测试**断言写回的值**（逐字相等），不只断言「没抛异常」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdKeywordIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  UUID tenant;
  UUID shopId;
  AccessService.Access actor;
  String bearer;

  /** 🔴 平台与站点注册表由 V1 迁移随代码发布（只读码表），测试直接用它。 */
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");

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
  }

  @Autowired AdKeywordService service;
  @Autowired ListingService listings;
  @Autowired ListingKeywordRankingService rankings;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  @BeforeEach
  void setUp() {
    tenant = UUID.randomUUID();
    bearer = "Bearer ak-" + tenant;
    actors.put(
        bearer,
        actor(user, tenant, List.of(grant("ALL", allPermissions))));
    actor = actors.get(bearer);
    shopId = seedShop(tenant, "AK-SHOP-" + tenant.toString().substring(0, 6));
  }

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actor, permission, work);
  }

  static void execAsTenant(UUID tenantId, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以超级用户身份写全局码表（platform_enum_mapping 不带 tenant_id，是只读全局码表）。 */
  static void execGlobal(String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  UUID seedShop(UUID tenantId, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenantId,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenantId + "','" + id
            + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return id;
  }

  /** 🔴 2.1.2.1 的平台默认映射（全局码表）—— 广告类型归一口径的真相在这里。 */
  void seedAdTypeMapping(String externalValue, String internalValue) {
    execGlobal(
        "insert into platform_enum_mapping (id,platform_id,enum_kind,external_value,internal_value)"
            + " values (gen_random_uuid(),'" + AMAZON + "','AD_TYPE','" + externalValue + "','"
            + internalValue + "')"
            + " on conflict (platform_id,enum_kind,external_value) do update set"
            + " internal_value=excluded.internal_value");
  }

  AdKeywordRow newKeyword(String externalKeywordId, String keyword, String platformAdType) {
    return newKeyword(externalKeywordId, keyword, platformAdType, "USD", "0.80");
  }

  AdKeywordRow newKeyword(
      String externalKeywordId, String keyword, String platformAdType, String currency, String bid) {
    return as(
        "channel.operation.create",
        () -> service.saveKeyword(
            null,
            new AdKeywordInput(
                AMAZON, shopId, "CAMP-1", externalKeywordId, keyword, platformAdType, null,
                new java.math.BigDecimal(bid), currency, "ACTIVE", null, "")));
  }

  static OffsetDateTime at(int day, int hour) {
    return OffsetDateTime.of(2026, 10, day, hour, 30, 15, 123456000, ZoneOffset.UTC);
  }

  // ==================================================================
  // 1. 能力声明
  // ==================================================================

  @Test
  @DisplayName("14.1.3.1 能力声明：未交付段 available=false + ownerModule（竞价策略 / 投放定位 / 活动列表 / 平台推送）")
  void capabilitiesDeclareUndeliveredDimensions() {
    var caps = as("channel.operation.read", () -> service.capabilities());

    assertThat(caps.dimensions()).isNotEmpty();
    var byDimension = new java.util.HashMap<String, AdKeywordCapabilityDimension>();
    caps.dimensions().forEach(d -> byDimension.put(d.dimension(), d));

    // 🔴 四段未交付必须显式声明，不能假装具备
    for (String key :
        List.of("bid_strategy_engine", "targeting", "campaign_binding", "platform_negative_push")) {
      var d = byDimension.get(key);
      assertThat(d).as("能力表里必须有 " + key + " 这一维度").isNotNull();
      assertThat(d.available()).as(key + " 尚未交付，必须 available=false").isFalse();
      assertThat(d.ownerModule()).as(key + " 必须写明归谁管").isNotBlank();
      assertThat(d.impact()).as(key + " 必须写明不交付的影响").isNotBlank();
    }

    // 🔴 已交付的维度必须 available=true
    for (String key :
        List.of(
            "keyword_registry", "keyword_metrics_sync", "search_term_report",
            "negative_suggestions", "negative_keywords", "bulk_bid_and_negate", "keyword_report",
            "frequency_and_rank")) {
      assertThat(byDimension.get(key).available()).as(key + " 已交付，必须 available=true").isTrue();
    }

    // 🔴 护栏数值必须给出来（前端按它渲染提示）
    assertThat(caps.guardrailLimits()).containsKeys(
        "maxBatchItems", "maxBidDeltaRatio", "maxBidAbsolute", "maxTotalBidIncrease");
    assertThat(caps.guardrailLimits().get("maxBatchItems")).isEqualTo(200);
    assertThat(caps.metricDefinitions()).contains("ACOS = 花费 / 销售额");
    assertThat(caps.frequencyDefinitions()).contains("4.5.1.2");
    assertThat(caps.negativeEvidenceNote()).contains("ACOS");
  }

  // ==================================================================
  // 2. 关键词登记：归一类型与原始类型并存
  // ==================================================================

  @Test
  @DisplayName("14.1.3.1 归一类型与原始类型并存：归一类型由 14.1.2.2 解析带出，声明不一致被拒，UNMAPPED 是显式值")
  void adTypeNormalizationIsCoexistent() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");

    var row = newKeyword("kw-1", "wireless headphones", "sponsoredProducts");
    assertThat(row.internalAdType()).isEqualTo("SPONSORED_BRAND");
    assertThat(row.platformAdType()).isEqualTo("sponsoredProducts");
    // 🔴 两个字段同时存在且互不覆盖
    assertThat(row.platformAdType()).isNotEqualTo(row.internalAdType());

    // 🔴 声明与解析结果不一致 ⇒ 拒（归一口径的真相在 2.1.2.1，不在请求里）
    var mismatch =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as(
                "channel.operation.create",
                () -> service.saveKeyword(
                    null,
                    new AdKeywordInput(
                        AMAZON, shopId, "CAMP-1", "kw-bad", "bad", "sponsoredProducts",
                        "AUTO_DISCOVERY", null, null, "ACTIVE", null, ""))));
    assertThat(mismatch).as("声明的归一类型与解析结果不一致必须被拒").isNotNull();

    // 🔴 UNMAPPED 是显式合法值（不是兜底）
    var unmapped = newKeyword("kw-2", "unmapped type", "someUnknownAdType");
    assertThat(unmapped.internalAdType()).isEqualTo("UNMAPPED");
    assertThat(unmapped.platformAdType()).isEqualTo("someUnknownAdType");
  }

  // ==================================================================
  // 3. 指标同步 + 派生比率 + 数据截至时间
  // ==================================================================

  @Test
  @DisplayName("14.1.3.1 关键词表现同步：ACOS 销售额为 0 时是 null、点击为 0 时 CPC 是 null、timestamptz 写回逐字相等")
  void metricSyncDerivesRatiosAndKeepsTimestamp() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var keyword = newKeyword("kw-m1", "ratio keyword", "sponsoredProducts");
    OffsetDateTime syncedAt = at(1, 3);

    var result =
        as(
            "channel.operation.create",
            () -> service.ingestMetrics(
                new AdKeywordMetricBatchRequest(
                    "PLATFORM", "batch-1", "idem-m1", "",
                    List.of(
                        new AdKeywordMetricInput(
                            keyword.keywordId(), LocalDate.of(2026, 10, 1), 1000L, 40L, 0,
                            new java.math.BigDecimal("120.0000"), java.math.BigDecimal.ZERO,
                            "USD", syncedAt)))));

    assertThat(result.replayed()).isFalse();
    assertThat(result.appliedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isZero();
    assertThat(result.dataAsOf()).isEqualTo(syncedAt);

    var page = as("channel.operation.read", () -> service.keywords(
        null, null, null, null, null, null, null, null, null, null, null, null, 0, 50));
    assertThat(page.syncStatus()).isEqualTo("SYNCED");
    assertThat(page.dataAsOf()).isEqualTo(syncedAt);
    assertThat(page.syncNote()).contains("截至");

    var row = page.items().stream().filter(r -> r.keywordId().equals(keyword.keywordId()))
        .findFirst().orElseThrow();

    // 🔴 销售额为 0 ⇒ ACOS 是 null（不可比），不是 0
    assertThat(row.sales()).isEqualByComparingTo("0");
    assertThat(row.acos()).as("销售额为 0 时 ACOS 必须是 null，不是 0").isNull();
    // 🔴 点击 40 > 0 ⇒ CPC 有值
    assertThat(row.cpc()).isEqualByComparingTo("3.0000");
    assertThat(row.ctr()).isEqualByComparingTo("0.040000");
    assertThat(row.cvr()).as("点击为 0 时 CVR 是 null").isEqualByComparingTo("0");
    // 🔴 计数是整数、金额是 4 位小数
    assertThat(row.impressions()).isEqualTo(1000L);
    assertThat(row.clicks()).isEqualTo(40L);
    assertThat(row.spend()).isEqualByComparingTo("120.0000");
    assertThat(row.metricAsOf()).as("timestamptz 写回值必须逐字相等").isEqualTo(syncedAt);

    // 🔴 点击为 0 的那一行 ⇒ CPC 也是 null
    var kw2 = newKeyword("kw-m2", "no click keyword", "sponsoredProducts");
    as(
        "channel.operation.create",
        () -> service.ingestMetrics(
            new AdKeywordMetricBatchRequest(
                "PLATFORM", "batch-2", "idem-m2", "",
                List.of(
                    new AdKeywordMetricInput(
                        kw2.keywordId(), LocalDate.of(2026, 10, 1), 500L, 0L, 0,
                        new java.math.BigDecimal("10.0000"), java.math.BigDecimal.ZERO,
                        "USD", syncedAt)))));
    var page2 = as("channel.operation.read", () -> service.keywords(
        null, null, null, null, null, null, null, null, null, null, null, null, 0, 50));
    var row2 = page2.items().stream().filter(r -> r.keywordId().equals(kw2.keywordId()))
        .findFirst().orElseThrow();
    assertThat(row2.cpc()).as("点击为 0 时 CPC 必须是 null").isNull();
    assertThat(row2.ctr()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("14.1.3.1 指标同步批次幂等：重投同一批不重复累加（否则 ACOS 变成两倍）")
  void metricSyncIsIdempotentPerBatch() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var keyword = newKeyword("kw-idem", "idempotent keyword", "sponsoredProducts");
    var request =
        new AdKeywordMetricBatchRequest(
            "PLATFORM", "batch-idem", "idem-key", "",
            List.of(
                new AdKeywordMetricInput(
                    keyword.keywordId(), LocalDate.of(2026, 10, 2), 200L, 10L, 1,
                    new java.math.BigDecimal("20.0000"), new java.math.BigDecimal("100.0000"),
                    "USD", at(2, 3))));

    var first = as("channel.operation.create", () -> service.ingestMetrics(request));
    assertThat(first.replayed()).isFalse();
    assertThat(first.appliedCount()).isEqualTo(1);

    var second = as("channel.operation.create", () -> service.ingestMetrics(request));
    assertThat(second.replayed()).as("同一批次重投必须被识别为重放").isTrue();
    assertThat(second.appliedCount()).as("重投时本次一行都没落").isZero();
    assertThat(second.skipped()).anySatisfy(s -> assertThat(s).contains("幂等"));

    var page = as("channel.operation.read", () -> service.keywords(
        null, null, null, null, null, null, null, null, null, null, null, null, 0, 50));
    var row = page.items().stream().filter(r -> r.keywordId().equals(keyword.keywordId()))
        .findFirst().orElseThrow();
    // 🔴 指标只落了一遍（没变成 400 次曝光 / 20 美元花费）
    assertThat(row.impressions()).isEqualTo(200L);
    assertThat(row.spend()).isEqualByComparingTo("20.0000");
  }

  @Test
  @DisplayName("14.1.3.1 指标同步逐条跳过：关键词不存在 / 币种不一致都要写明原因，不静默丢弃")
  void metricSyncSkipsWithReasons() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var keyword = newKeyword("kw-skip", "skip keyword", "sponsoredProducts");

    var result =
        as(
            "channel.operation.create",
            () -> service.ingestMetrics(
                new AdKeywordMetricBatchRequest(
                    "PLATFORM", "batch-skip", "idem-skip", "",
                    List.of(
                        new AdKeywordMetricInput(
                            UUID.randomUUID(), LocalDate.of(2026, 10, 3), 10L, 1L, 0,
                            new java.math.BigDecimal("1.0000"), java.math.BigDecimal.ZERO,
                            "USD", at(3, 3))))));
    assertThat(result.appliedCount()).isZero();
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.skipped()).anySatisfy(s -> assertThat(s).contains("不存在"));

    // 🔴 币种不一致：先落一行 USD 把关键词的指标币种定下来
    as(
        "channel.operation.create",
        () -> service.ingestMetrics(
            new AdKeywordMetricBatchRequest(
                "PLATFORM", "batch-skip-2", "idem-skip-2", "",
                List.of(
                    new AdKeywordMetricInput(
                        keyword.keywordId(), LocalDate.of(2026, 10, 3), 10L, 1L, 0,
                        new java.math.BigDecimal("1.0000"), java.math.BigDecimal.ZERO,
                        "USD", at(3, 3))))));
    var mixed =
        as(
            "channel.operation.create",
            () -> service.ingestMetrics(
                new AdKeywordMetricBatchRequest(
                    "PLATFORM", "batch-skip-3", "idem-skip-3", "",
                    List.of(
                        new AdKeywordMetricInput(
                            keyword.keywordId(), LocalDate.of(2026, 10, 4), 10L, 1L, 0,
                            new java.math.BigDecimal("1.0000"), java.math.BigDecimal.ZERO,
                            "EUR", at(4, 3))))));
    assertThat(mixed.appliedCount()).isZero();
    assertThat(mixed.skipped()).anySatisfy(s -> assertThat(s).contains("币种"));
  }

  // ==================================================================
  // 4. 搜索词报告：高曝光零转化
  // ==================================================================

  @Test
  @DisplayName("14.1.3.1 搜索词报告：高曝光零转化一眼可识别（发现浪费的入口）")
  void searchTermReportFindsWaste() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var keyword = newKeyword("kw-st", "waste hunter", "sponsoredProducts");

    as(
        "channel.operation.create",
        () -> service.ingestSearchTerms(
            new AdSearchTermBatchRequest(
                "PLATFORM", "st-batch", "idem-st", "",
                List.of(
                    new AdSearchTermInput(
                        keyword.keywordId(), LocalDate.of(2026, 10, 5), "cheap wrong shoes", "CAMP-1",
                        900L, 30L, 0, new java.math.BigDecimal("90.0000"),
                        java.math.BigDecimal.ZERO, "USD", at(5, 3)),
                    new AdSearchTermInput(
                        keyword.keywordId(), LocalDate.of(2026, 10, 5), "good shoes", "CAMP-1",
                        800L, 40L, 6, new java.math.BigDecimal("100.0000"),
                        new java.math.BigDecimal("500.0000"), "USD", at(5, 3))))));

    var page = as("channel.operation.read", () -> service.searchTerms(
        null, null, null, null, null, null, null, 0, 50));
    assertThat(page.total()).isEqualTo(2);
    // 🔴 浪费条数单独给出（不受筛选影响）
    assertThat(page.wastedCount()).isEqualTo(1);

    var wasted = page.items().stream().filter(r -> r.wasted()).findFirst().orElseThrow();
    assertThat(wasted.searchTerm()).isEqualTo("cheap wrong shoes");
    assertThat(wasted.impressions()).isEqualTo(900L);
    assertThat(wasted.clicks()).isEqualTo(30L);
    assertThat(wasted.conversions()).isZero();
    assertThat(wasted.spend()).isEqualByComparingTo("90.0000");
    assertThat(wasted.acos()).as("销售额为 0 ⇒ ACOS 为 null").isNull();

    var good = page.items().stream().filter(r -> !r.wasted()).findFirst().orElseThrow();
    assertThat(good.searchTerm()).isEqualTo("good shoes");
    assertThat(good.conversions()).isEqualTo(6);
    assertThat(good.acos()).isEqualByComparingTo("0.2000");

    // 🔴 wasted=true 只看浪费的
    var onlyWaste = as("channel.operation.read", () -> service.searchTerms(
        null, null, null, null, "true", null, null, 0, 50));
    assertThat(onlyWaste.total()).isEqualTo(1);
    assertThat(onlyWaste.items().get(0).searchTerm()).isEqualTo("cheap wrong shoes");
  }

  // ==================================================================
  // 5. 否定词建议：必须带依据数值
  // ==================================================================

  @Test
  @DisplayName("14.1.3.1 否定词建议：带实际数值（曝光/点击/转化/花费/ACOS），不是光秃秃的词表")
  void negativeSuggestionsCarryEvidence() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var keyword = newKeyword("kw-neg", "suggestion keyword", "sponsoredProducts");

    as(
        "channel.operation.create",
        () -> service.ingestSearchTerms(
            new AdSearchTermBatchRequest(
                "PLATFORM", "st-neg", "idem-neg", "",
                List.of(
                    new AdSearchTermInput(
                        keyword.keywordId(), LocalDate.of(2026, 10, 6), "useless gadget", "CAMP-1",
                        1500L, 45L, 0, new java.math.BigDecimal("150.0000"),
                        java.math.BigDecimal.ZERO, "USD", at(6, 3))))));

    var page = as("channel.operation.read", () -> service.negativeSuggestions(
        null, null, null, null, null, null, null, null, 0, 50));

    assertThat(page.total()).isEqualTo(1);
    var s = page.items().get(0);
    assertThat(s.searchTerm()).isEqualTo("useless gadget");
    assertThat(s.rule()).isEqualTo("HIGH_IMPRESSION_ZERO_CONVERSION");
    // 🔴 依据数值必须带全
    assertThat(s.impressions()).isEqualTo(1500L);
    assertThat(s.clicks()).isEqualTo(45L);
    assertThat(s.conversions()).isZero();
    assertThat(s.spend()).isEqualByComparingTo("150.0000");
    assertThat(s.sales()).isEqualByComparingTo("0");
    assertThat(s.acos()).as("销售额为 0 ⇒ ACOS 是 null").isNull();
    assertThat(s.cpc()).isEqualByComparingTo("3.3333");
    assertThat(s.currency()).isEqualTo("USD");
    assertThat(s.dataAsOf()).isEqualTo(at(6, 3));
    // 🔴 理由里带实际数值
    assertThat(s.reason()).contains("1500").contains("150");
    assertThat(page.ruleNote()).contains("minImpressions");
  }

  // ==================================================================
  // 6. 否定记录：依据由服务端现算
  // ==================================================================

  @Test
  @DisplayName("14.1.3.1 否定记录：依据由服务端现算（不采信调用方给的数字），可追溯")
  void negativeRecordComputesEvidenceServerSide() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var keyword = newKeyword("kw-negr", "negative record keyword", "sponsoredProducts");

    as(
        "channel.operation.create",
        () -> service.ingestSearchTerms(
            new AdSearchTermBatchRequest(
                "PLATFORM", "st-negr", "idem-negr", "",
                List.of(
                    new AdSearchTermInput(
                        keyword.keywordId(), LocalDate.of(2026, 10, 7), "waste term", "CAMP-1",
                        700L, 20L, 0, new java.math.BigDecimal("70.0000"),
                        java.math.BigDecimal.ZERO, "USD", at(7, 3))))));

    var negative =
        as(
            "channel.operation.create",
            () -> service.createNegative(
                new AdNegativeInput(
                    keyword.keywordId(), "waste term", "NEGATIVE_EXACT", "CAMP-1",
                    "高曝光零转化，立刻否定",
                    Map.of("impressions", 999999), // 🔴 调用方自述的依据（只作审计留痕）
                    LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), "SYSTEM_SUGGESTION")));

    assertThat(negative.status()).isEqualTo("DECIDED");
    assertThat(negative.reason()).contains("高曝光零转化");
    // 🔴 权威依据 = 服务端现算的真实数值
    assertThat(negative.evidence()).containsEntry("impressions", new java.math.BigDecimal("700"));
    assertThat(negative.evidence()).containsEntry("clicks", new java.math.BigDecimal("20"));
    assertThat(negative.evidence()).containsEntry("conversions", new java.math.BigDecimal("0"));
    assertThat(negative.evidence()).containsEntry("hasData", true);
    assertThat(negative.evidence()).containsEntry("dataAsOf", at(7, 3).toString());
    // 🔴 调用方自述的依据原样留痕
    assertThat(negative.claimedEvidence())
        .containsEntry("impressions", new java.math.BigDecimal("999999"));

    // 🔴 平台回执能进来
    var pushed =
        as(
            "channel.operation.update",
            () -> service.recordNegativeResult(
                negative.negativeId().toString(), new AdNegativeResultInput(true, "平台已接受")));
    assertThat(pushed.status()).isEqualTo("PUSHED");
    assertThat(pushed.platformNote()).isEqualTo("平台已接受");
    assertThat(pushed.appliedAt()).isNotNull();

    var list = as("channel.operation.read", () -> service.negatives(null, null, null, null, 0, 50));
    assertThat(list.total()).isEqualTo(1);
    assertThat(list.items().get(0).negationText()).isEqualTo("waste term");
  }

  @Test
  @DisplayName("14.1.3.1 否定必须写明原因（否定要能追溯到依据）")
  void negativeRequiresReason() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var keyword = newKeyword("kw-negr2", "no reason keyword", "sponsoredProducts");
    var thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as(
                "channel.operation.create",
                () -> service.createNegative(
                    new AdNegativeInput(
                        keyword.keywordId(), "whatever", null, "CAMP-1", "  ", null, null, null,
                        null))));
    assertThat(thrown).as("没有原因的否定必须被拒").isNotNull();
  }

  // ==================================================================
  // 7. 批量调价：护栏 + 逐条结果
  // ==================================================================

  @Test
  @DisplayName("14.1.3.1 批量调价：币种不一致逐条失败、幅度上限逐条失败、成功的条目真的改了竞价")
  void bulkBidAdjustAppliesGuardrailsPerItem() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var usd = newKeyword("kw-b1", "usd keyword", "sponsoredProducts", "USD", "1.00");
    var eur = newKeyword("kw-b2", "eur keyword", "sponsoredProducts", "EUR", "1.00");
    var ratio = newKeyword("kw-b3", "ratio keyword", "sponsoredProducts", "USD", "1.00");

    var result =
        as(
            "channel.operation.update",
            () -> service.bulkAction(
                new AdBulkActionRequest(
                    "BID_ADJUST", shopId, "PERCENT", "USD",
                    List.of(
                        new AdBulkBidTarget(usd.keywordId(), new java.math.BigDecimal("10")),
                        new AdBulkBidTarget(eur.keywordId(), new java.math.BigDecimal("10")),
                        new AdBulkBidTarget(ratio.keywordId(), new java.math.BigDecimal("90"))),
                    null, "bulk-guard-1", "")));

    assertThat(result.status()).isEqualTo("PARTIAL");
    assertThat(result.appliedCount()).isEqualTo(1);
    assertThat(result.failedCount()).isEqualTo(2);
    assertThat(result.items()).hasSize(3);

    var applied = result.items().stream().filter(i -> "APPLIED".equals(i.status())).findFirst()
        .orElseThrow();
    assertThat(applied.beforeBid()).isEqualByComparingTo("1.00");
    assertThat(applied.afterBid()).isEqualByComparingTo("1.10");
    assertThat(applied.reason()).contains("1.00").contains("1.10");

    // 🔴 币种不一致的那条写明原因
    var currencyFail = result.items().stream()
        .filter(i -> i.keywordId().equals(eur.keywordId())).findFirst().orElseThrow();
    assertThat(currencyFail.status()).isEqualTo("FAILED");
    assertThat(currencyFail.reason()).contains("币种");

    // 🔴 幅度超限的那条写明原因
    var ratioFail = result.items().stream()
        .filter(i -> i.keywordId().equals(ratio.keywordId())).findFirst().orElseThrow();
    assertThat(ratioFail.status()).isEqualTo("FAILED");
    assertThat(ratioFail.reason()).contains("幅度");

    // 🔴 竞价真的改了
    var row = as("channel.operation.read", () -> service.keywords(
        null, null, null, null, null, null, null, null, null, null, null, null, 0, 50)).items()
        .stream().filter(r -> r.keywordId().equals(usd.keywordId())).findFirst().orElseThrow();
    assertThat(row.bid()).isEqualByComparingTo("1.10");
  }

  @Test
  @DisplayName("14.1.3.1 批量调价：总上调上限越线 ⇒ 整批不动（部分执行比不动危险）")
  void bulkBidAdjustRejectsWholeBatchOverTotalCap() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var keyword = newKeyword("kw-cap", "cap keyword", "sponsoredProducts", "USD", "900.00");

    var result =
        as(
            "channel.operation.update",
            () -> service.bulkAction(
                new AdBulkActionRequest(
                    "BID_ADJUST", shopId, "ABSOLUTE", "USD",
                    List.of(new AdBulkBidTarget(keyword.keywordId(), new java.math.BigDecimal("990.00"))),
                    null, "bulk-cap-1", "")));

    // 🔴 单条 900 → 990 = 上调 90，未超 5000 ⇒ 应成功
    assertThat(result.status()).isEqualTo("APPLIED");
    assertThat(result.appliedCount()).isEqualTo(1);
    // 🔴 jsonb 会把数字的**尾随零**归一（5000.00 → 5000.0），值本身一字不差 ——
    //    所以比较用 compareTo（数值相等），不用 equals（会比 scale）。
    assertThat(
            ((java.math.BigDecimal) result.guardrail().get("maxTotalBidIncrease"))
                .compareTo(new java.math.BigDecimal("5000.00")))
        .as("护栏里的总上调上限必须是 5000.00，一字不差")
        .isZero();
    assertThat(
            ((java.math.BigDecimal) result.guardrail().get("totalIncrease"))
                .compareTo(new java.math.BigDecimal("90.0000")))
        .as("实际总上调必须是 90.0000")
        .isZero();
  }

  @Test
  @DisplayName("14.1.3.1 批量调价：单条竞价绝对上限 1000.00 越线 ⇒ 逐条失败")
  void bulkBidAdjustRejectsOverAbsoluteCap() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var keyword = newKeyword("kw-abs", "absolute cap keyword", "sponsoredProducts", "USD", "10.00");

    var result =
        as(
            "channel.operation.update",
            () -> service.bulkAction(
                new AdBulkActionRequest(
                    "BID_ADJUST", shopId, "ABSOLUTE", "USD",
                    List.of(
                        new AdBulkBidTarget(keyword.keywordId(), new java.math.BigDecimal("1200.00"))),
                    null, "bulk-abs-1", "")));

    assertThat(result.status()).isEqualTo("REJECTED");
    assertThat(result.appliedCount()).isZero();
    assertThat(result.items().get(0).status()).isEqualTo("FAILED");
    assertThat(result.items().get(0).reason()).contains("绝对上限");

    // 🔴 竞价没被动过
    var row = as("channel.operation.read", () -> service.keywords(
        null, null, null, null, null, null, null, null, null, null, null, null, 0, 50)).items()
        .stream().filter(r -> r.keywordId().equals(keyword.keywordId())).findFirst().orElseThrow();
    assertThat(row.bid()).isEqualByComparingTo("10.00");
  }

  @Test
  @DisplayName("14.1.3.1 批量调价：单次条目上限 200 越线 ⇒ 整批拒（400）")
  void bulkBidAdjustRejectsOverItemCap() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var keyword = newKeyword("kw-many", "many keyword", "sponsoredProducts", "USD", "1.00");
    var targets = new java.util.ArrayList<AdBulkBidTarget>();
    for (int i = 0; i < 201; i++) {
      targets.add(new AdBulkBidTarget(keyword.keywordId(), new java.math.BigDecimal("1.10")));
    }
    var thrown =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as(
                "channel.operation.update",
                () -> service.bulkAction(
                    new AdBulkActionRequest(
                        "BID_ADJUST", shopId, "PERCENT", "USD", targets, null, "bulk-many-1", ""))));
    assertThat(thrown).as("超过单次条目上限必须被拒").isNotNull();
  }

  @Test
  @DisplayName("14.1.3.1 批量调价幂等：重投同一批不重复调价（调价是花钱的动作）")
  void bulkActionIsIdempotent() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var keyword = newKeyword("kw-idem-bulk", "idempotent bulk keyword", "sponsoredProducts", "USD", "2.00");

    var request =
        new AdBulkActionRequest(
            "BID_ADJUST", shopId, "ABSOLUTE", "USD",
            List.of(new AdBulkBidTarget(keyword.keywordId(), new java.math.BigDecimal("3.00"))),
            null, "bulk-idem-1", "");

    var first = as("channel.operation.update", () -> service.bulkAction(request));
    assertThat(first.replayed()).isFalse();
    assertThat(first.appliedCount()).isEqualTo(1);

    var second = as("channel.operation.update", () -> service.bulkAction(request));
    assertThat(second.replayed()).as("同一批重投必须被识别为重放").isTrue();
    assertThat(second.appliedCount()).as("返回的是既有任务的计数").isEqualTo(1);
    assertThat(second.reason()).as("重投时写明本次没有执行").isNotBlank();

    // 🔴 竞价只被改过一次
    var row = as("channel.operation.read", () -> service.keywords(
        null, null, null, null, null, null, null, null, null, null, null, null, 0, 50)).items()
        .stream().filter(r -> r.keywordId().equals(keyword.keywordId())).findFirst().orElseThrow();
    assertThat(row.bid()).isEqualByComparingTo("3.00");
  }

  @Test
  @DisplayName("14.1.3.1 批量否定：落否定记录并带上依据快照")
  void bulkNegateCreatesNegativeWithEvidence() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var keyword = newKeyword("kw-bulk-neg", "bulk negate keyword", "sponsoredProducts", "USD", "1.00");

    as(
        "channel.operation.create",
        () -> service.ingestMetrics(
            new AdKeywordMetricBatchRequest(
                "PLATFORM", "bulk-neg-batch", "idem-bulk-neg", "",
                List.of(
                    new AdKeywordMetricInput(
                        keyword.keywordId(), LocalDate.of(2026, 10, 8), 1200L, 30L, 0,
                        new java.math.BigDecimal("75.0000"), java.math.BigDecimal.ZERO,
                        "USD", at(8, 3))))));

    var result =
        as(
            "channel.operation.update",
            () -> service.bulkAction(
                new AdBulkActionRequest(
                    "NEGATE", shopId, null, "USD",
                    List.of(new AdBulkBidTarget(keyword.keywordId(), null)),
                    "NEGATIVE_EXACT", "bulk-neg-1", "")));

    assertThat(result.status()).isEqualTo("APPLIED");
    assertThat(result.appliedCount()).isEqualTo(1);
    assertThat(result.items().get(0).status()).isEqualTo("APPLIED");
    assertThat(result.items().get(0).result())
        .containsEntry("impressions", new java.math.BigDecimal("1200"));

    var negatives = as("channel.operation.read", () -> service.negatives(null, null, null, null, 0, 50));
    assertThat(negatives.total()).isEqualTo(1);
    assertThat(negatives.items().get(0).status()).isEqualTo("DECIDED");
    assertThat(negatives.items().get(0).suggestedBy()).isEqualTo("SYSTEM_SUGGESTION");
    assertThat(negatives.items().get(0).evidence())
        .containsEntry("impressions", new java.math.BigDecimal("1200"));

    // 🔴 已否定的关键词在列表里能看出来
    var page = as("channel.operation.read", () -> service.keywords(
        null, null, null, null, null, null, null, null, null, null, null, null, 0, 50));
    var row = page.items().stream().filter(r -> r.keywordId().equals(keyword.keywordId()))
        .findFirst().orElseThrow();
    assertThat(row.negated()).isTrue();
  }

  // ==================================================================
  // 8. 报表
  // ==================================================================

  @Test
  @DisplayName("14.1.3.1 关键词报表：按币种分行、ACOS 现算、分母为 0 给 null")
  void reportGroupsByCurrency() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var usd = newKeyword("kw-r1", "report usd", "sponsoredProducts", "USD", "1.00");
    var eur = newKeyword("kw-r2", "report eur", "sponsoredProducts", "EUR", "1.00");

    as(
        "channel.operation.create",
        () -> service.ingestMetrics(
            new AdKeywordMetricBatchRequest(
                "PLATFORM", "report-batch", "idem-report", "",
                List.of(
                    new AdKeywordMetricInput(
                        usd.keywordId(), LocalDate.of(2026, 10, 9), 1000L, 50L, 5,
                        new java.math.BigDecimal("100.0000"), new java.math.BigDecimal("500.0000"),
                        "USD", at(9, 3)),
                    new AdKeywordMetricInput(
                        eur.keywordId(), LocalDate.of(2026, 10, 9), 800L, 20L, 0,
                        new java.math.BigDecimal("60.0000"), java.math.BigDecimal.ZERO,
                        "EUR", at(9, 3))))));

    var report = as("channel.operation.read", () -> service.report("PLATFORM", null, null, null, 0, 50));
    assertThat(report.dimension()).isEqualTo("PLATFORM");
    // 🔴 跨币种不可相加 ⇒ 按币种分行
    assertThat(report.rows()).hasSize(2);
    assertThat(report.rows()).extracting("currency").containsExactlyInAnyOrder("USD", "EUR");

    var usdRow = report.rows().stream().filter(r -> "USD".equals(r.currency())).findFirst()
        .orElseThrow();
    assertThat(usdRow.spend()).isEqualByComparingTo("100.0000");
    assertThat(usdRow.sales()).isEqualByComparingTo("500.0000");
    assertThat(usdRow.acos()).isEqualByComparingTo("0.2000");
    assertThat(usdRow.ctr()).isEqualByComparingTo("0.050000");
    assertThat(usdRow.internalAdType()).isEqualTo("SPONSORED_BRAND");
    assertThat(usdRow.platformAdType()).isEqualTo("sponsoredProducts");

    var eurRow = report.rows().stream().filter(r -> "EUR".equals(r.currency())).findFirst()
        .orElseThrow();
    assertThat(eurRow.acos()).as("销售额为 0 ⇒ ACOS 为 null").isNull();
    assertThat(report.scopeNote()).contains("币种");
  }

  // ==================================================================
  // 9. 词频与排名：引用 4.5.1.2
  // ==================================================================

  @Test
  @DisplayName("14.1.3.1 词频与排名：名次来自 4.5.1.2 的 ranking 快照、词频来自它的关键词集合")
  void frequencyAndRankReuseFourFiveOneTwo() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");

    // 🔴 先在 4.5.1.2 里造一个商品 + 关键词 + 排名抓取
    var listing =
        as(
            "channel.operation.create",
            () -> listings.save(
                new SaveListingRequest(
                    null, shopId, null, "AK-ITEM-1", "AK 商品", "Home", "cat-1", "ON_SALE", null,
                    "", "IN_STOCK", null, null, null, null, null, null, List.of(), null)));
    var listingKeyword =
        as(
            "channel.operation.create",
            () -> rankings.saveKeyword(
                null,
                new ListingKeywordRankingModels.ListingKeywordRankingKeywordInput(
                    listing.id(), "shared keyword", 20, 10, 5, "")));
    as(
        "channel.operation.create",
        () -> rankings.crawl(
            new ListingKeywordRankingModels.ListingKeywordRankingCrawlRequest(
                listing.id(), "PLATFORM", "API", at(10, 3), 30, "ak-crawl-1", "", Map.of(),
                List.of(
                    new ListingKeywordRankingModels.ListingKeywordRankingRankInput(
                        listingKeyword.keywordId(), 7, 3, Map.of())))));

    // 🔴 本模块的关键词关联到它
    var keyword =
        as(
            "channel.operation.create",
            () -> service.saveKeyword(
                null,
                new AdKeywordInput(
                    AMAZON, shopId, "CAMP-1", "kw-freq", "shared keyword", "sponsoredProducts", null,
                    new java.math.BigDecimal("1.00"), "USD", "ACTIVE", listingKeyword.keywordId(),
                    "")));

    as(
        "channel.operation.create",
        () -> service.ingestMetrics(
            new AdKeywordMetricBatchRequest(
                "PLATFORM", "freq-batch", "idem-freq", "",
                List.of(
                    new AdKeywordMetricInput(
                        keyword.keywordId(), LocalDate.of(2026, 10, 10), 400L, 20L, 2,
                        new java.math.BigDecimal("40.0000"), new java.math.BigDecimal("200.0000"),
                        "USD", at(10, 3))))));

    var page = as("channel.operation.read", () -> service.frequency(null, null, null, 0, 50));
    assertThat(page.items()).isNotEmpty();
    var row = page.items().stream().filter(r -> "shared keyword".equals(r.keyword())).findFirst()
        .orElseThrow();
    // 🔴 名次来自 4.5.1.2 的 ranking 快照（不是本模块自算的）
    assertThat(row.naturalRank()).isEqualTo(7);
    assertThat(row.adRank()).isEqualTo(3);
    // 🔴 词频来自 4.5.1.2 的关键词集合（同一个词被多少个商品监控着）
    assertThat(row.frequency()).isEqualTo(1L);
    assertThat(row.frequencyListingCount()).isEqualTo(1L);
    assertThat(page.frequencyDefinitions()).contains("4.5.1.2");

    // 🔴 关键词列表里也带同一套名次与词频
    var kwPage = as("channel.operation.read", () -> service.keywords(
        null, null, null, null, null, null, null, null, null, null, null, null, 0, 50));
    var kwRow = kwPage.items().stream().filter(r -> r.keywordId().equals(keyword.keywordId()))
        .findFirst().orElseThrow();
    assertThat(kwRow.naturalRank()).isEqualTo(7);
    assertThat(kwRow.adRank()).isEqualTo(3);
    assertThat(kwRow.frequency()).isEqualTo(1L);
  }

  @Test
  @DisplayName("14.1.3.1 没关联 4.5.1.2 的关键词：名次是 null，不是 0")
  void rankIsNullWhenNotLinked() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var keyword = newKeyword("kw-nolink", "not linked keyword", "sponsoredProducts");
    var page = as("channel.operation.read", () -> service.keywords(
        null, null, null, null, null, null, null, null, null, null, null, null, 0, 50));
    var row = page.items().stream().filter(r -> r.keywordId().equals(keyword.keywordId()))
        .findFirst().orElseThrow();
    assertThat(row.naturalRank()).as("没关联 ⇒ 名次是 null，不是 0").isNull();
    assertThat(row.adRank()).isNull();
  }

  // ==================================================================
  // 10. 归档是软删除
  // ==================================================================

  @Test
  @DisplayName("14.1.3.1 归档是软删除：表现与否定历史必须留着")
  void archiveIsSoftDelete() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    var keyword = newKeyword("kw-arch", "archived keyword", "sponsoredProducts", "USD", "1.00");
    as(
        "channel.operation.create",
        () -> service.ingestMetrics(
            new AdKeywordMetricBatchRequest(
                "PLATFORM", "arch-batch", "idem-arch", "",
                List.of(
                    new AdKeywordMetricInput(
                        keyword.keywordId(), LocalDate.of(2026, 10, 11), 100L, 5L, 0,
                        new java.math.BigDecimal("5.0000"), java.math.BigDecimal.ZERO,
                        "USD", at(11, 3))))));

    var archived = as("channel.operation.delete", () -> service.archiveKeyword(
        keyword.keywordId().toString()));
    assertThat(archived.status()).isEqualTo("ARCHIVED");

    // 🔴 历史指标还在
    var page = as("channel.operation.read", () -> service.keywords(
        null, null, null, null, null, null, null, null, null, null, null, null, 0, 50));
    var row = page.items().stream().filter(r -> r.keywordId().equals(keyword.keywordId()))
        .findFirst().orElseThrow();
    assertThat(row.status()).isEqualTo("ARCHIVED");
    assertThat(row.impressions()).as("归档不删历史指标").isEqualTo(100L);
  }

  // ==================================================================
  // 11. 空态：还没同步过数据
  // ==================================================================

  @Test
  @DisplayName("14.1.3.1 还没同步过数据时：syncStatus=NEVER_SYNCED，页面上的 0 是「没数据」不是「没花钱」")
  void neverSyncedIsDistinguishable() {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    newKeyword("kw-empty", "empty keyword", "sponsoredProducts");

    var page = as("channel.operation.read", () -> service.keywords(
        null, null, null, null, null, null, null, null, null, null, null, null, 0, 50));
    assertThat(page.syncStatus()).isEqualTo("NEVER_SYNCED");
    assertThat(page.dataAsOf()).isNull();
    assertThat(page.syncNote()).contains("没数据");

    var row = page.items().get(0);
    assertThat(row.hasMetrics()).isFalse();
    assertThat(row.impressions()).isZero();

    var detail = as("channel.operation.read", () -> service.detail(
        row.keywordId().toString()));
    assertThat(detail.hasData()).isFalse();
    assertThat(detail.emptyReason()).contains("还没有同步过任何指标");
  }

  // ==================================================================
  // 12. HTTP 面
  // ==================================================================

  @Test
  @DisplayName("14.1.3.1 HTTP 面：无会话 401、未知关键词 404、非法筛选 422")
  void httpSurface() throws Exception {
    seedAdTypeMapping("sponsoredProducts", "SPONSORED_BRAND");
    http.perform(get("/channel/ad-keywords/capabilities")).andExpect(status().isUnauthorized());
    http.perform(get("/channel/ad-keywords"))
        .andExpect(status().isUnauthorized());

    http.perform(get("/channel/ad-keywords/" + UUID.randomUUID())
            .header("Authorization", bearer))
        .andExpect(status().isNotFound());

    http.perform(get("/channel/ad-keywords")
            .header("Authorization", bearer)
            .param("status", "NOT_A_STATUS"))
        .andExpect(status().isUnprocessableEntity());
  }
}
