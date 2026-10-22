package com.starcon.channel;

import static com.starcon.channel.AdCampaignModels.*;
import static com.starcon.channel.AdGroupKeywordReportModels.*;
import static com.starcon.channel.AdKeywordModels.*;
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
 * 14.3.1.2 广告组与词报表。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户
 *   <li><b>🔴 组级指标不冒充</b>：组维度只给结构事实，指标列<b>全为 null</b>（不是 0），
 *       能力声明里 {@code group_metrics} 显式 available=false + ownerModule 指向 14.1.2.1
 *   <li><b>🔴 汇总先汇总再相除</b>：不是各行比率的平均
 *   <li><b>🔴 分母为 0 给空值</b>：不可比 ≠ 0
 *   <li><b>🔴 不同币种不合并</b>：没有汇率源 ⇒ 汇总按币种拆开
 *   <li><b>🔴 否定后仍有数据 ≠ 0</b>：识别出来并标注，而不是当成 0；
 *       没有生效日时判「无法评估」，不是「否定后没数据」
 *   <li><b>🔴 真分页真聚合</b>：分页真的分页，汇总统计的是全集而不是当前页
 *   <li><b>🔴 缓存有明确失效依据</b>：首次 MISS、再次 HIT；数据变了 ⇒ STALE；
 *       没有水位 ⇒ BYPASS（不返回一个「不知道什么时候过期」的缓存）
 *   <li><b>🔴 时间范围必填 + 跨度封顶</b>
 *   <li><b>🔴 导出留痕记下「选了哪几条」</b>；CSV 表头带口径
 *   <li><b>timestamptz 往返</b>断言<b>写回值</b>逐字相等（AGENTS.md 要求）
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：
 * 用墙钟会让「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdGroupKeywordReportIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 🔴 每个用例一对**全新的租户**（共用租户会让前一个用例的数据被后一个用例数进去）。 */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  /** 🔴 钉死的时刻。 */
  static final OffsetDateTime SYNCED_AT = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
  /** 🔴 更晚的一次同步（水位往前推，用来验证缓存会作废）。 */
  static final OffsetDateTime LATER_SYNCED_AT = OffsetDateTime.parse("2026-10-01T10:00:00+08:00");
  static final OffsetDateTime DATA_AS_OF = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
  static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-10-01T08:00:00+08:00");
  static final OffsetDateTime FINISHED_AT = OffsetDateTime.parse("2026-10-01T09:05:00+08:00");
  static final OffsetDateTime STRUCTURE_AS_OF = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");

  static final LocalDate DAY_1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY_2 = LocalDate.of(2026, 10, 2);
  static final LocalDate DAY_3 = LocalDate.of(2026, 10, 3);

  // 🔴 用 V1 平台目录里**真实存在**的 id：...-101 = AMAZON、...-104 = TIKTOK_SHOP。
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

  /** 播种平台默认映射（全局码表）。 */
  @BeforeEach
  void seedGlobalCatalog() {
    exec("insert into platform_enum_mapping (id,platform_id,enum_kind,external_value,internal_value)"
        + " values ('" + UUID.randomUUID() + "','" + AMAZON + "','AD_TYPE','sponsored_products','AUTO_DISCOVERY')"
        + " on conflict (platform_id,enum_kind,external_value) do nothing");
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

  @Autowired AdGroupKeywordReportService service;
  @Autowired AdCampaignService campaigns;
  @Autowired AdKeywordService keywords;
  @Autowired AdCampaignDetailService details;
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

  /** 播种一个店铺（幂等）。 */
  String seedShop(UUID tenant, UUID platformId, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
            + "','" + platformId + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return code;
  }

  /** 播种一个 listing（4.1.1.1，同库同服务）。 */
  UUID seedListing(UUID tenant, String shopCode, String externalItemId, String title) {
    UUID shopId =
        UUID.fromString(
            execOneAsTenant(
                tenant,
                "select id from shop where tenant_id = '" + tenant + "' and code = '" + shopCode + "'"));
    UUID id = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into listing (id,tenant_id,shop_id,external_item_id,title,status)"
            + " values ('" + id + "','" + tenant + "','" + shopId + "','" + externalItemId + "','" + title
            + "','ON_SALE')");
    return id;
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

  /** 通过**上游 14.1.1.1** 登记一个活动（本模块的事实来源之一）。 */
  UUID seedCampaign(
      AccessService.Access who, UUID platformId, String shop, UUID profileId, String externalId,
      String name, String currency) {
    as(who, "channel.operation.create",
        () -> campaigns.importCampaigns(new ImportRequest(
            platformId, shop,
            new ImportSyncRun("SUCCESS", DATA_AS_OF, STARTED_AT, FINISHED_AT, "", "batch-report"),
            List.of(
                new ImportCampaign(
                    externalId, name, profileId, "ENABLED", currency,
                    new BigDecimal("100.0000"), null, null, List.of(), "测试登记")))));
    return UUID.fromString(
        execOneAsTenant(
            who.tenantId(),
            "select id from ad_campaign where tenant_id = '" + who.tenantId()
                + "' and external_campaign_id = '" + externalId + "'"));
  }

  /** 通过**上游 14.1.3.1** 登记一个投放关键词。 */
  UUID seedKeyword(
      AccessService.Access who, UUID platformId, String shopCode, String externalCampaignId,
      String externalKeywordId, String keyword) {
    UUID shopId =
        UUID.fromString(
            execOneAsTenant(
                who.tenantId(),
                "select id from shop where tenant_id = '" + who.tenantId()
                    + "' and code = '" + shopCode + "'"));
    return as(who, "channel.operation.create",
            () -> keywords.saveKeyword(null,
                new AdKeywordInput(
                    platformId, shopId, externalCampaignId, externalKeywordId, keyword,
                    "sponsored_products", null, new BigDecimal("1.5000"), "USD", "ACTIVE", null, "")))
        .keywordId();
  }

  /** 通过**上游 14.1.3.1** 登记一批关键词表现（真实写入口）。 */
  void seedMetrics(
      AccessService.Access who, UUID keywordId, String batchKey, List<AdKeywordMetricInput> metrics) {
    as(who, "channel.operation.create",
        () -> keywords.ingestMetrics(
            new AdKeywordMetricBatchRequest("PLATFORM", "batch-" + batchKey, batchKey, "", metrics)));
  }

  /** 通过**上游 14.1.3.1** 登记一批搜索词报告（真实写入口）。 */
  void seedSearchTerms(
      AccessService.Access who, String batchKey, List<AdSearchTermInput> terms) {
    as(who, "channel.operation.create",
        () -> keywords.ingestSearchTerms(
            new AdSearchTermBatchRequest("PLATFORM", "batch-" + batchKey, batchKey, "", terms)));
  }

  /** 一条关键词指标行。 */
  AdKeywordMetricInput metric(
      UUID keywordId, LocalDate day, String impressions, String clicks, String conversions,
      String spend, String sales, String currency) {
    return new AdKeywordMetricInput(
        keywordId, day, Long.parseLong(impressions), Long.parseLong(clicks),
        Integer.parseInt(conversions), new BigDecimal(spend), new BigDecimal(sales), currency,
        SYNCED_AT);
  }

  /** 一条关键词指标行（显式指定数据截至时间）。 */
  AdKeywordMetricInput metricAt(
      UUID keywordId, LocalDate day, String impressions, String clicks, String conversions,
      String spend, String sales, String currency, OffsetDateTime syncedAt) {
    return new AdKeywordMetricInput(
        keywordId, day, Long.parseLong(impressions), Long.parseLong(clicks),
        Integer.parseInt(conversions), new BigDecimal(spend), new BigDecimal(sales), currency,
        syncedAt);
  }

  /** 一条搜索词报告行。 */
  AdSearchTermInput term(
      UUID keywordId, LocalDate day, String searchTerm, String externalCampaignId,
      String impressions, String clicks, String conversions, String spend, String sales,
      String currency) {
    return new AdSearchTermInput(
        keywordId, day, searchTerm, externalCampaignId, Long.parseLong(impressions),
        Long.parseLong(clicks), Integer.parseInt(conversions), new BigDecimal(spend),
        new BigDecimal(sales), currency, SYNCED_AT);
  }

  /** 🔴 排序白名单是**按维度**分的 ⇒ 各维度用各自合法的排序。 */
  static String sortOf(String dimension) {
    return switch (dimension) {
      case "GROUP" -> "NAME_ASC";
      case "NEGATIVE_EFFECT" -> "RECENT_FIRST";
      default -> "SPEND_DESC";
    };
  }

  /** 跑一次报表查询（走服务层的归一化与校验）。 */
  AdGroupKeywordReportPage report(
      AccessService.Access who, String dimension, String granularity, LocalDate from, LocalDate to,
      int page, int size) {
    return as(who, "channel.operation.read",
        () -> service.report(
            service.normalize(
                dimension, granularity, sortOf(dimension), from, to, null, null, null, null, null,
                null, List.of(), page, size)));
  }

  /**
   * 播种一条标准数据集：两个词 + 各自的指标 + 搜索词。
   *
   * <p>🔴 词 1：花费 120、销售额 600 ⇒ ACOS = 0.2；词 2：花费 100、销售额 200 ⇒ ACOS = 0.5；
   *   词 3：全 0 ⇒ 所有比率**不可比**（null），不是 0。
   */
  void seedStandard(AccessService.Access who, UUID tenant, String shopPrefix) {
    String shop = seedShop(tenant, AMAZON, shopPrefix);
    UUID profile = saveProfile(who, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    UUID campaignId =
        seedCampaign(who, AMAZON, shop, profile, "EXT-" + shopPrefix + "-C1", "活动一", "USD");

    UUID k1 = seedKeyword(who, AMAZON, shop, "EXT-" + shopPrefix + "-C1", "KW-1", "蓝牙耳机");
    UUID k2 = seedKeyword(who, AMAZON, shop, "EXT-" + shopPrefix + "-C1", "KW-2", "运动鞋");
    UUID k3 = seedKeyword(who, AMAZON, shop, "EXT-" + shopPrefix + "-C1", "KW-3", "零值词");

    seedMetrics(who, k1, shopPrefix + "-m1", List.of(
        metric(k1, DAY_1, "1000", "50", "5", "120.0000", "600.0000", "USD")));
    seedMetrics(who, k2, shopPrefix + "-m2", List.of(
        metric(k2, DAY_1, "500", "25", "2", "100.0000", "200.0000", "USD")));
    seedMetrics(who, k3, shopPrefix + "-m3", List.of(
        metric(k3, DAY_1, "0", "0", "0", "0.0000", "0.0000", "USD")));

    seedSearchTerms(who, shopPrefix + "-s1", List.of(
        term(k1, DAY_1, "蓝牙耳机 保护套", "EXT-" + shopPrefix + "-C1",
            "800", "40", "0", "90.0000", "0.0000", "USD"),
        term(k1, DAY_1, "蓝牙耳机", "EXT-" + shopPrefix + "-C1",
            "200", "10", "5", "30.0000", "600.0000", "USD")));

    // 登记结构（广告组 + 投放商品）
    UUID listingId = seedListing(tenant, shop, "ASIN-" + shopPrefix + "-1", "投放商品");
    as(who, "channel.operation.create",
        () -> details.importStructure(
            campaignId.toString(),
            new AdCampaignDetailModels.AdCampaignStructureImportRequest(
                STRUCTURE_AS_OF,
                List.of(
                    new AdCampaignDetailModels.AdCampaignGroupImport(
                        "GRP-" + shopPrefix + "-1", "组一", "ENABLED", new BigDecimal("1.5000"),
                        "USD", new BigDecimal("50.0000"), "AUTO",
                        List.of(
                            new AdCampaignDetailModels.AdCampaignProductImport(listingId, null, null)),
                        List.of())),
                List.of())));
  }

  /** 直接写一条否定记录（要精确控制 applied_at / evidence 区间，服务层用墙钟）。 */
  void seedNegative(
      AccessService.Access who, UUID tenant, UUID keywordId, String shopCode,
      String externalCampaignId, String negationText, String negationType,
      LocalDate evidenceFrom, LocalDate evidenceTo, String appliedAt) {
    execAsTenant(tenant,
        "insert into ad_negative_keyword (id,tenant_id,keyword_id,shop_id,external_campaign_id,"
            + " negation_text,negation_type,status,reason,evidence,evidence_from,evidence_to,"
            + " suggested_by,applied_at,created_at,updated_at)"
            + " select '" + UUID.randomUUID() + "','" + tenant + "','" + keywordId + "',s.id,'"
            + externalCampaignId + "','" + negationText + "','" + negationType + "','PUSHED','测试否定',"
            + " '{\"impressions\":100,\"clicks\":5,\"spend\":10.0000,\"sales\":0.0000}'::jsonb,"
            + (evidenceFrom == null ? "null" : "'" + evidenceFrom + "'") + ","
            + (evidenceTo == null ? "null" : "'" + evidenceTo + "'") + ","
            + "'MANUAL'," + (appliedAt == null ? "null" : "'" + appliedAt + "'::timestamptz")
            + ",now(),now()"
            + " from shop s where s.tenant_id='" + tenant + "' and s.code='" + shopCode + "'");
  }

  // ==================================================================
  // 1. 能力声明：组级指标必须显式未交付
  // ==================================================================

  @Test
  @DisplayName("14.3.1.2 能力声明：组级指标 / 跨币种统一 / 归因显式 available=false")
  void capabilitiesDeclareUndeliveredParts() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    Map<String, AdGroupReportCapabilityDimension> dims =
        caps.dimensions().stream()
            .collect(java.util.stream.Collectors.toMap(AdGroupReportCapabilityDimension::dimension, d -> d));

    assertThat(dims.get("keyword_report").available()).isTrue();
    assertThat(dims.get("search_term_report").available()).isTrue();
    assertThat(dims.get("negative_effect_report").available()).isTrue();
    assertThat(dims.get("group_report").available()).isTrue();
    assertThat(dims.get("batch_export").available()).isTrue();
    assertThat(dims.get("report_cache").available()).isTrue();

    // 🔴 组级指标：**没有事实源** ⇒ available=false + 责任模块 + 影响说明。
    assertThat(dims.get("group_metrics").available()).isFalse();
    assertThat(dims.get("group_metrics").ownerModule()).contains("14.1.2.1");
    assertThat(dims.get("group_metrics").impact()).contains("没有组级指标表");
    assertThat(dims.get("group_metrics").impact()).contains("物理上无法归到组");

    // 🔴 跨币种统一：本仓没有汇率源。
    assertThat(dims.get("multi_currency_unification").available()).isFalse();
    assertThat(dims.get("multi_currency_unification").impact()).contains("按币种拆开");
    assertThat(dims.get("attribution").available()).isFalse();

    // 🔴 逐指标口径随能力声明一起返回。
    Map<String, AdGroupReportMetricDefinition> metrics =
        caps.metricDefinitions().stream()
            .collect(java.util.stream.Collectors.toMap(AdGroupReportMetricDefinition::metric, m -> m));
    assertThat(metrics.get("acos").available()).isTrue();
    assertThat(metrics.get("acos").formula()).contains("ACOS = Σ花费 / Σ销售额");
    assertThat(metrics.get("roas").formula()).contains("ROAS = Σ销售额 / Σ花费");
    assertThat(metrics.get("cpc").formula()).contains("CPC = Σ花费 / Σ点击");
    assertThat(metrics).containsKeys("spend", "impressions", "clicks", "conversions", "ctr", "cpc",
        "cvr", "sales", "acos", "roas", "group_metrics");
    // 🔴 组级指标在指标定义里也是「不可用」，并写明原因。
    assertThat(metrics.get("group_metrics").available()).isFalse();
    assertThat(metrics.get("group_metrics").unavailableReason()).contains("没有组级指标表");

    // 🔴 否定效果评估的口径必须公示。
    assertThat(caps.negativeEffectNote()).contains("否定生效日");
    assertThat(caps.negativeEffectNote()).contains("否定后仍有数据");
    assertThat(caps.negativeEffectNote()).contains("不把它当成 0");
  }

  // ==================================================================
  // 2. 单词效果真的出数
  // ==================================================================

  @Test
  @DisplayName("14.3.1.2 单词效果真的落库出数：指标齐全且口径正确")
  void keywordReportReadsRealMetrics() {
    seedStandard(actorA, a, "KW-AMZ-1");

    var page = report(actorA, "KEYWORD", "TOTAL", DAY_1, DAY_1, 0, 50);

    assertThat(page.total()).isEqualTo(3);
    assertThat(page.dimension()).isEqualTo("KEYWORD");

    AdGroupKeywordReportRow row =
        page.items().stream()
            .filter(r -> "蓝牙耳机".equals(r.keywordText()))
            .findFirst()
            .orElseThrow();

    assertThat(row.currency()).isEqualTo("USD");
    assertThat(row.impressions()).isEqualByComparingTo("1000");
    assertThat(row.clicks()).isEqualByComparingTo("50");
    assertThat(row.conversions()).isEqualByComparingTo("5");
    assertThat(row.spend()).isEqualByComparingTo("120.0000");
    assertThat(row.sales()).isEqualByComparingTo("600.0000");
    // 🔴 CTR = 50/1000 = 0.05；CPC = 120/50 = 2.4；转化率 = 5/50 = 0.1
    assertThat(row.ctr()).isEqualByComparingTo("0.05");
    assertThat(row.cpc()).isEqualByComparingTo("2.4");
    assertThat(row.cvr()).isEqualByComparingTo("0.1");
    // 🔴 ACOS = 120/600 = 0.2；ROAS = 600/120 = 5
    assertThat(row.acos()).isEqualByComparingTo("0.2");
    assertThat(row.roas()).isEqualByComparingTo("5");
    assertThat(row.activeDays()).isEqualTo(1L);
    assertThat(row.metricFrom()).isEqualTo(DAY_1);
    assertThat(row.metricTo()).isEqualTo(DAY_1);
    // 🔴 数据截至时间显式（不是猜的）。
    assertThat(row.dataAsOf()).isEqualTo(SYNCED_AT);
    assertThat(page.dataAsOf()).isEqualTo(SYNCED_AT);
  }

  // ==================================================================
  // 3. 🔴 汇总先汇总再相除
  // ==================================================================

  @Test
  @DisplayName("14.3.1.2 汇总先汇总再相除：整体 ACOS 是 27.5% 而不是各行平均的 35%")
  void summaryAggregatesBeforeDividing() {
    seedStandard(actorA, a, "SUM-AMZ-1");

    var page = report(actorA, "KEYWORD", "TOTAL", DAY_1, DAY_1, 0, 50);

    assertThat(page.summaries()).hasSize(1);
    AdGroupKeywordReportSummary sum = page.summaries().get(0);
    assertThat(sum.currency()).isEqualTo("USD");
    assertThat(sum.entityCount()).isEqualTo(3);
    assertThat(sum.totalSpend()).isEqualByComparingTo("220.0000");
    assertThat(sum.totalSales()).isEqualByComparingTo("800.0000");
    // 🔴 Σ消耗 / Σ销售额 = 220/800 = 0.275；各行 ACOS（0.2 / 0.5 / 不可比）的平均是 0.35 —— 那是数学错误。
    assertThat(sum.acos()).isEqualByComparingTo("0.275");
    assertThat(sum.roas()).isEqualByComparingTo("3.636364");
    assertThat(sum.ctr()).isEqualByComparingTo("0.05");
    assertThat(sum.cpc()).isEqualByComparingTo("2.933333");
    assertThat(sum.cvr()).isEqualByComparingTo("0.093333");
    assertThat(sum.metricDefinitionNote()).contains("先汇总再相除");
  }

  // ==================================================================
  // 4. 🔴 分母为 0 给空值（不可比），不是 0
  // ==================================================================

  @Test
  @DisplayName("14.3.1.2 分母为 0 的比率返回空值（不可比），不是 0")
  void zeroDenominatorGivesNullNotZero() {
    seedStandard(actorA, a, "ZERO-AMZ-1");

    var page = report(actorA, "KEYWORD", "TOTAL", DAY_1, DAY_1, 0, 50);

    AdGroupKeywordReportRow zero =
        page.items().stream()
            .filter(r -> "零值词".equals(r.keywordText()))
            .findFirst()
            .orElseThrow();

    assertThat(zero.impressions()).isEqualByComparingTo("0");
    assertThat(zero.spend()).isEqualByComparingTo("0");
    // 🔴 全部不可比 ⇒ null，**不是 0**。
    assertThat(zero.ctr()).isNull();
    assertThat(zero.cpc()).isNull();
    assertThat(zero.cvr()).isNull();
    assertThat(zero.acos()).isNull();
    assertThat(zero.roas()).isNull();
  }

  // ==================================================================
  // 5. 🔴 不同币种不合并
  // ==================================================================

  @Test
  @DisplayName("14.3.1.2 不同币种不合并：汇总按币种拆开")
  void currenciesAreNotMerged() {
    String shop = seedShop(a, AMAZON, "CUR-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    seedCampaign(actorA, AMAZON, shop, profile, "EXT-CUR-1", "活动一", "USD");

    UUID usd = seedKeyword(actorA, AMAZON, shop, "EXT-CUR-1", "KW-USD", "美元词");
    UUID eur = seedKeyword(actorA, AMAZON, shop, "EXT-CUR-1", "KW-EUR", "欧元词");

    seedMetrics(actorA, usd, "cur-usd", List.of(
        metric(usd, DAY_1, "1000", "50", "5", "120.0000", "600.0000", "USD")));
    seedMetrics(actorA, eur, "cur-eur", List.of(
        metric(eur, DAY_1, "500", "25", "2", "100.0000", "200.0000", "EUR")));

    var page = report(actorA, "KEYWORD", "TOTAL", DAY_1, DAY_1, 0, 50);

    // 🔴 两个币种 ⇒ 两份汇总，**绝不**加成一个假总数。
    assertThat(page.summaries()).hasSize(2);
    assertThat(page.summaries()).extracting(AdGroupKeywordReportSummary::currency)
        .containsExactlyInAnyOrder("USD", "EUR");
    for (AdGroupKeywordReportSummary sum : page.summaries()) {
      assertThat(sum.totalSpend()).isEqualByComparingTo(sum.currency().equals("USD") ? "120" : "100");
    }
  }

  // ==================================================================
  // 6. 搜索词质量
  // ==================================================================

  @Test
  @DisplayName("14.3.1.2 搜索词质量：高曝光零转化被标成浪费入口")
  void searchTermReportFindsWaste() {
    seedStandard(actorA, a, "ST-AMZ-1");

    var page = report(actorA, "SEARCH_TERM", "TOTAL", DAY_1, DAY_1, 0, 50);

    assertThat(page.total()).isEqualTo(2);
    AdGroupKeywordReportRow waste =
        page.items().stream()
            .filter(r -> "蓝牙耳机 保护套".equals(r.searchTerm()))
            .findFirst()
            .orElseThrow();
    // 🔴 800 曝光、0 转化、90 块花费 ⇒ 高曝光零转化。
    assertThat(waste.zeroConversionWaste()).isTrue();
    assertThat(waste.impressions()).isEqualByComparingTo("800");
    assertThat(waste.clicks()).isEqualByComparingTo("40");
    assertThat(waste.conversions()).isEqualByComparingTo("0");
    assertThat(waste.spend()).isEqualByComparingTo("90.0000");
    // 🔴 销售额为 0 ⇒ ACOS 不可比（null），不是 0。
    assertThat(waste.acos()).isNull();
    assertThat(waste.sourceKeywordCount()).isEqualTo(1L);
    assertThat(waste.sourceCampaignCount()).isEqualTo(1L);

    AdGroupKeywordReportRow good =
        page.items().stream()
            .filter(r -> "蓝牙耳机".equals(r.searchTerm()))
            .findFirst()
            .orElseThrow();
    assertThat(good.zeroConversionWaste()).isFalse();
    assertThat(good.acos()).isEqualByComparingTo("0.05");
  }

  // ==================================================================
  // 7. 🔴 否定效果评估
  // ==================================================================

  @Test
  @DisplayName("14.3.1.2 否定后仍有数据被识别出来并标注，而不是当成 0")
  void negativeEffectDetectsStillReporting() {
    String shop = seedShop(a, AMAZON, "NEG-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    seedCampaign(actorA, AMAZON, shop, profile, "EXT-NEG-1", "活动一", "USD");
    UUID kw = seedKeyword(actorA, AMAZON, shop, "EXT-NEG-1", "KW-NEG", "蓝牙耳机");

    // 否定生效日 = 2026-10-02（applied_at 的 UTC 日期）
    seedNegative(actorA, a, kw, shop, "EXT-NEG-1", "蓝牙耳机 保护套", "NEGATIVE_EXACT",
        DAY_1, DAY_1, "2026-10-02T00:00:00Z");
    // 生效日**之后**仍有搜索词数据（花费 90、点击 40）⇒ 否定没生效
    seedSearchTerms(actorA, "neg-still", List.of(
        term(kw, DAY_3, "蓝牙耳机 保护套", "EXT-NEG-1", "800", "40", "0", "90.0000", "0.0000",
            "USD")));

    var page = report(actorA, "NEGATIVE_EFFECT", "TOTAL", DAY_1, DAY_3, 0, 50);

    assertThat(page.total()).isEqualTo(1);
    AdGroupKeywordReportRow row = page.items().get(0);
    assertThat(row.negationText()).isEqualTo("蓝牙耳机 保护套");
    assertThat(row.negationType()).isEqualTo("NEGATIVE_EXACT");
    assertThat(row.effectiveDate()).isEqualTo(LocalDate.of(2026, 10, 2));
    assertThat(row.evaluationOutcome()).isEqualTo("STILL_REPORTING");
    assertThat(row.postStillReporting()).isTrue();
    assertThat(row.postSpend()).isEqualByComparingTo("90.0000");
    assertThat(row.postClicks()).isEqualByComparingTo("40");
    assertThat(row.evaluationNote()).contains("仍有数据");
    // 🔴 不是 0 —— 否定后仍有数据被当成 0 才是最严重的错。
    assertThat(row.postSpend()).isNotEqualByComparingTo("0");
  }

  @Test
  @DisplayName("14.3.1.2 否定生效后没有数据：判「否定后无数据」，并写明它不等于「省下了多少」")
  void negativeEffectReportsNoDataAfter() {
    String shop = seedShop(a, AMAZON, "NEG2-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    seedCampaign(actorA, AMAZON, shop, profile, "EXT-NEG2-1", "活动一", "USD");
    UUID kw = seedKeyword(actorA, AMAZON, shop, "EXT-NEG2-1", "KW-NEG2", "运动鞋");

    // 生效日 = 2026-10-02；生效日之后没有任何搜索词数据
    seedNegative(actorA, a, kw, shop, "EXT-NEG2-1", "运动鞋 折扣", "NEGATIVE_EXACT",
        DAY_1, DAY_1, "2026-10-02T00:00:00Z");
    seedSearchTerms(actorA, "neg2-before", List.of(
        term(kw, DAY_1, "运动鞋 折扣", "EXT-NEG2-1", "500", "20", "1", "50.0000", "100.0000",
            "USD")));

    var page = report(actorA, "NEGATIVE_EFFECT", "TOTAL", DAY_1, DAY_3, 0, 50);

    AdGroupKeywordReportRow row = page.items().get(0);
    assertThat(row.evaluationOutcome()).isEqualTo("NO_DATA_AFTER");
    assertThat(row.postStillReporting()).isFalse();
    // 🔴 否定**前**的表现仍然有（那是依据区间里的数）。
    assertThat(row.preSpend()).isEqualByComparingTo("50.0000");
    assertThat(row.preClicks()).isEqualByComparingTo("20");
    assertThat(row.evaluationNote()).contains("不等于");
  }

  @Test
  @DisplayName("14.3.1.2 没有生效日（无 applied_at 且无 evidence_to）：判「无法评估」，不是「否定后没数据」")
  void negativeEffectNotEvaluableWithoutEffectiveDate() {
    String shop = seedShop(a, AMAZON, "NEG3-AMZ-1");
    UUID profile = saveProfile(actorA, AMAZON, "sponsored_products", "AUTO_DISCOVERY");
    seedCampaign(actorA, AMAZON, shop, profile, "EXT-NEG3-1", "活动一", "USD");
    UUID kw = seedKeyword(actorA, AMAZON, shop, "EXT-NEG3-1", "KW-NEG3", "零值词");

    seedNegative(actorA, a, kw, shop, "EXT-NEG3-1", "零值词", "NEGATIVE_EXACT",
        null, null, null);

    var page = report(actorA, "NEGATIVE_EFFECT", "TOTAL", DAY_1, DAY_3, 0, 50);

    AdGroupKeywordReportRow row = page.items().get(0);
    assertThat(row.effectiveDate()).isNull();
    assertThat(row.evaluationOutcome()).isEqualTo("NOT_EVALUABLE");
    assertThat(row.postStillReporting()).isNull();
    assertThat(row.evaluationNote()).contains("无法评估");
  }

  // ==================================================================
  // 8. 🔴 组维度：结构事实有，组级指标没有
  // ==================================================================

  @Test
  @DisplayName("14.3.1.2 组维度只给结构事实：组级指标为空（不是 0）且能力声明 available=false")
  void groupReportShowsStructureWithoutMetrics() {
    seedStandard(actorA, a, "GRP-AMZ-1");

    var page = report(actorA, "GROUP", "TOTAL", DAY_1, DAY_1, 0, 50);

    assertThat(page.total()).isEqualTo(1);
    AdGroupKeywordReportRow group = page.items().get(0);
    // 🔴 结构事实是真的。
    assertThat(group.dimensionLabel()).isEqualTo("组一");
    assertThat(group.externalGroupId()).isEqualTo("GRP-GRP-AMZ-1-1");
    assertThat(group.groupStatus()).isEqualTo("ENABLED");
    assertThat(group.campaignName()).isEqualTo("活动一");
    assertThat(group.productCount()).isEqualTo(1L);
    assertThat(group.defaultBid()).isEqualByComparingTo("1.5000");
    assertThat(group.bidCurrency()).isEqualTo("USD");
    // 🔴 组级指标**没有事实源** ⇒ 全部 null，**不是 0**。
    assertThat(group.spend()).isNull();
    assertThat(group.sales()).isNull();
    assertThat(group.impressions()).isNull();
    assertThat(group.acos()).isNull();
    assertThat(group.note()).contains("组级指标未接入");
    // 🔴 汇总里也没有金额（不是 0）。
    assertThat(page.summaries()).hasSize(1);
    assertThat(page.summaries().get(0).totalSpend()).isNull();
    assertThat(page.summaries().get(0).entityCount()).isEqualTo(1);
  }

  // ==================================================================
  // 9. 🔴 真分页
  // ==================================================================

  @Test
  @DisplayName("14.3.1.2 真分页：每页一行，翻页拿到不同的行，汇总统计的是全集")
  void paginationIsReal() {
    seedStandard(actorA, a, "PAGE-AMZ-1");

    var first = report(actorA, "KEYWORD", "TOTAL", DAY_1, DAY_1, 0, 1);
    var second = report(actorA, "KEYWORD", "TOTAL", DAY_1, DAY_1, 1, 1);
    var third = report(actorA, "KEYWORD", "TOTAL", DAY_1, DAY_1, 2, 1);

    assertThat(first.total()).isEqualTo(3);
    assertThat(second.total()).isEqualTo(3);
    assertThat(first.items()).hasSize(1);
    assertThat(second.items()).hasSize(1);
    assertThat(third.items()).hasSize(1);
    // 🔴 三页拿到三个不同的词（不是同一行重复三遍）。
    assertThat(List.of(first.items().get(0), second.items().get(0), third.items().get(0)))
        .extracting(AdGroupKeywordReportRow::keywordText)
        .doesNotHaveDuplicates();
    // 🔴 汇总统计的是**全集**，不受分页影响。
    assertThat(first.summaries().get(0).totalSpend()).isEqualByComparingTo("220.0000");
  }

  // ==================================================================
  // 10. 🔴 时间范围必填 + 跨度封顶
  // ==================================================================

  @Test
  @DisplayName("14.3.1.2 不给时间范围直接 400（不做「悄悄给个默认区间」）")
  void timeRangeIsRequired() {
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.read",
                    () -> service.normalize("KEYWORD", "TOTAL", "SPEND_DESC", null, null, null,
                        null, null, null, null, null, List.of(), 0, 50)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("必填");

    // 🔴 跨度太大直接拒，不悄悄截断。
    assertThatThrownBy(
            () ->
                as(
                    actorA,
                    "channel.operation.read",
                    () -> service.normalize("KEYWORD", "TOTAL", "SPEND_DESC",
                        LocalDate.of(2020, 1, 1), LocalDate.of(2026, 10, 1), null, null, null,
                        null, null, null, List.of(), 0, 50)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("跨度过大");
  }

  // ==================================================================
  // 11. 🔴 缓存：有明确的失效依据
  // ==================================================================

  @Test
  @DisplayName("14.3.1.2 缓存：首次 MISS、再次 HIT；数据变了 ⇒ STALE；失效依据与过期时刻一起返回")
  void cacheHasExplicitInvalidation() {
    seedStandard(actorA, a, "CACHE-AMZ-1");

    var first = report(actorA, "KEYWORD", "TOTAL", DAY_1, DAY_1, 0, 50);
    assertThat(first.cache().status()).isEqualTo("MISS");
    assertThat(first.cache().dataAsOf()).isEqualTo(SYNCED_AT);
    assertThat(first.cache().sourceWatermark()).isNotBlank();
    assertThat(first.cache().expiresAt()).isAfter(OffsetDateTime.now());
    assertThat(first.cache().note()).contains("失效依据");

    var second = report(actorA, "KEYWORD", "TOTAL", DAY_1, DAY_1, 0, 50);
    assertThat(second.cache().status()).isEqualTo("HIT");
    // 🔴 命中时的数与首次算出来的一致。
    assertThat(second.total()).isEqualTo(first.total());
    assertThat(second.summaries().get(0).totalSpend())
        .isEqualByComparingTo(first.summaries().get(0).totalSpend());

    // 🔴 落一批新数据（水位往前推）⇒ **同一个查询**的缓存作废（STALE），不是「继续用旧的」。
    //   注意：必须是**同一个查询**（同一个缓存键）才看得到 STALE ——
    //   换��间就是另一个缓存键，那是 MISS 不是 STALE。
    UUID kwId =
        UUID.fromString(
            execOneAsTenant(
                a,
                "select id from ad_keyword where tenant_id = '" + a
                    + "' and external_keyword_id = 'KW-3'"));
    seedMetrics(actorA, kwId, "cache-extra", List.of(
        metricAt(kwId, DAY_1, "100", "10", "1", "10.0000", "50.0000", "USD", LATER_SYNCED_AT)));

    var third = report(actorA, "KEYWORD", "TOTAL", DAY_1, DAY_1, 0, 50);
    assertThat(third.cache().status()).isEqualTo("STALE");
    // 🔴 水位往前推了，而且数是**重算**出来的（不是缓存里的旧数）。
    assertThat(third.cache().dataAsOf()).isEqualTo(LATER_SYNCED_AT);
    assertThat(third.total()).isEqualTo(3);
    assertThat(third.summaries().get(0).totalSpend()).isEqualByComparingTo("230.0000");
  }

  @Test
  @DisplayName("14.3.1.2 没有任何数据时缓存 BYPASS —— 不返回一个「不知道什么时候过期」的缓存")
  void cacheBypassesWithoutWatermark() {
    var page = report(actorA, "KEYWORD", "TOTAL", DAY_1, DAY_1, 0, 50);

    assertThat(page.cache().status()).isEqualTo("BYPASS");
    assertThat(page.cache().dataAsOf()).isNull();
    assertThat(page.cache().expiresAt()).isNull();
    assertThat(page.cache().note()).contains("没有失效依据");
    // 🔴 空态是「还没同步到数据」，不是「这段时间没有投放」。
    assertThat(page.emptyReason()).isEqualTo("NO_SYNCED_DATA");
    assertThat(page.emptyNote()).contains("不等于");
  }

  // ==================================================================
  // 12. 🔴 导出留痕 + CSV 表头带口径
  // ==================================================================

  @Test
  @DisplayName("14.3.1.2 批量选中导出：留痕记下「选了哪几条」，CSV 表头带口径")
  void exportRecordsSelectionAndHeader() {
    seedStandard(actorA, a, "EXP-AMZ-1");

    var page = report(actorA, "KEYWORD", "TOTAL", DAY_1, DAY_1, 0, 50);
    List<String> selected = page.items().stream()
        .filter(r -> "蓝牙耳机".equals(r.keywordText()))
        .map(AdGroupKeywordReportRow::dimensionKey)
        .toList();
    assertThat(selected).hasSize(1);

    var query =
        as(actorA, "channel.operation.read",
            () -> service.normalize("KEYWORD", "TOTAL", "SPEND_DESC", DAY_1, DAY_1, null, null,
                null, null, null, null, selected, 0, 50));
    var trace = as(actorA, "channel.operation.read", () -> service.recordExport(query));

    assertThat(trace.rowCount()).isEqualTo(1);
    assertThat(trace.selection()).containsExactlyElementsOf(selected);
    assertThat(trace.dimension()).isEqualTo("KEYWORD");
    assertThat(trace.dataAsOf()).isEqualTo(SYNCED_AT);
    assertThat(trace.metricDefinitionNote()).contains("先汇总再相除");

    // 🔴 留痕在库里的形态与返回值一致。
    var stored = as(actorA, "channel.operation.read", () -> service.exportById(trace.id()));
    assertThat(stored.selection()).containsExactlyElementsOf(selected);
    assertThat(stored.rowCount()).isEqualTo(1);

    // 🔴 CSV 表头带口径（拿去做跨部门沟通时，CSV 自己就说明了「ACOS 是哪个除哪个」）。
    //   streamCsv 每一批自己开租户事务（与 controller 的调用方式一致）。
    StringBuilder csv = new StringBuilder();
    service.streamCsv(actorA, query, csv::append);
    String text = csv.toString();
    assertThat(text).contains("ACOS(Σ花费/Σ销售额)");
    assertThat(text).contains("ROAS(Σ销售额/Σ花费)");
    assertThat(text).contains("CPC(Σ花费/Σ点击)");
    // 🔴 **只导出选中的那一条**（选中进了 SQL 的 WHERE，不是服务层筛）。
    assertThat(text).contains("蓝牙耳机");
    assertThat(text).doesNotContain("运动鞋");
    assertThat(text).doesNotContain("零值词");

    // 🔴 不带选中 = 导出当前筛选的全部 ⇒ 不可比的比率写「不可比」，不是 0。
    var allQuery =
        as(actorA, "channel.operation.read",
            () -> service.normalize("KEYWORD", "TOTAL", "SPEND_DESC", DAY_1, DAY_1, null, null,
                null, null, null, null, List.of(), 0, 50));
    StringBuilder allCsv = new StringBuilder();
    service.streamCsv(actorA, allQuery, allCsv::append);
    String allText = allCsv.toString();
    assertThat(allText).contains("蓝牙耳机");
    assertThat(allText).contains("运动鞋");
    assertThat(allText).contains("零值词");
    assertThat(allText).contains("不可比");
  }

  // ==================================================================
  // 13. 端点：形状与状态码
  // ==================================================================

  @Test
  @DisplayName("14.3.1.2 端点：不给时间范围 400；无会话 401；能力声明可读")
  void endpointsBehave() throws Exception {
    http.perform(get("/channel/ad-group-keyword-reports/rows")
            .header("Authorization", bearerA)
            .param("dimension", "KEYWORD"))
        .andExpect(status().isBadRequest());

    http.perform(get("/channel/ad-group-keyword-reports/rows")
            .param("from", DAY_1.toString())
            .param("to", DAY_1.toString()))
        .andExpect(status().isUnauthorized());

    http.perform(get("/channel/ad-group-keyword-reports/capabilities")
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions[?(@.dimension=='group_metrics')].available").value(false));
  }

  // ==================================================================
  // 14. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("14.3.1.2 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    seedStandard(actorA, a, "ISO-AMZ-1");
    seedStandard(actorB, b, "ISO-AMZ-2");

    var pageA = report(actorA, "KEYWORD", "TOTAL", DAY_1, DAY_1, 0, 50);
    var pageB = report(actorB, "KEYWORD", "TOTAL", DAY_1, DAY_1, 0, 50);

    assertThat(pageA.total()).isEqualTo(3);
    assertThat(pageB.total()).isEqualTo(3);
    assertThat(pageA.summaries().get(0).totalSpend()).isEqualByComparingTo("220.0000");
    assertThat(pageB.summaries().get(0).totalSpend()).isEqualByComparingTo("220.0000");
    // 🔴 A 的缓存不能被 B 读到（缓存键含租户，查询也带租户锚点）。
    assertThat(pageA.cache().cacheKey()).isNotNull();

    // 🔴 导出留痕也互不可见。
    var exportsA = as(actorA, "channel.operation.read", () -> service.exports(0, 50));
    var exportsB = as(actorB, "channel.operation.read", () -> service.exports(0, 50));
    assertThat(exportsA.total()).isZero();
    assertThat(exportsB.total()).isZero();
  }

  @Test
  @DisplayName("14.3.1.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    seedStandard(actorA, a, "FORGE-AMZ-1");
    seedStandard(actorB, b, "FORGE-AMZ-2");

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的数据。
    http.perform(
            get("/channel/ad-group-keyword-reports/rows")
                .header("Authorization", bearerA)
                .header("X-Tenant-Id", b.toString())
                .param("dimension", "KEYWORD")
                .param("from", DAY_1.toString())
                .param("to", DAY_1.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(3));

    // 🔴 店铺名证明读到的是 A 的数据（A 的店铺是 FORGE-AMZ-1）。
    http.perform(
            get("/channel/ad-group-keyword-reports/rows")
                .header("Authorization", bearerA)
                .header("X-Tenant-Id", b.toString())
                .param("dimension", "KEYWORD")
                .param("from", DAY_1.toString())
                .param("to", DAY_1.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].shopName").value("测试店铺 FORGE-AMZ-1"));

    // 无会话一律 401。
    http.perform(
            get("/channel/ad-group-keyword-reports/rows")
                .header("X-Tenant-Id", b.toString())
                .param("from", DAY_1.toString())
                .param("to", DAY_1.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("14.3.1.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    seedStandard(actorA, a, "POOL-AMZ-1");
    seedStandard(actorB, b, "POOL-AMZ-2");

    for (int round = 0; round < 3; round++) {
      var pageA = report(actorA, "KEYWORD", "TOTAL", DAY_1, DAY_1, 0, 50);
      assertThat(pageA.total()).isEqualTo(3);
      assertThat(pageA.summaries().get(0).totalSpend()).isEqualByComparingTo("220.0000");
      assertThat(pageA.items().get(0).shopName()).isEqualTo("测试店铺 POOL-AMZ-1");

      var pageB = report(actorB, "KEYWORD", "TOTAL", DAY_1, DAY_1, 0, 50);
      assertThat(pageB.total()).isEqualTo(3);
      assertThat(pageB.summaries().get(0).totalSpend()).isEqualByComparingTo("220.0000");
      assertThat(pageB.items().get(0).shopName()).isEqualTo("测试店铺 POOL-AMZ-2");

      // 🔴 A 的导出留痕不能被 B 看见。
      var exportsA = as(actorA, "channel.operation.read", () -> service.exports(0, 50));
      assertThat(exportsA.total()).isZero();
    }
  }
}
