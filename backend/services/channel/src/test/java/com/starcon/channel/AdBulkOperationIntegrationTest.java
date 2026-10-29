package com.starcon.channel;

import static com.starcon.channel.AdBulkOperationModels.*;
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
 * 14.1.1.3 批量操作。
 *
 * <p>本测试类钉住本模块最容易做丢的几件事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户
 *   <li><b>🔴 护栏越限 ⇒ 整批拒绝</b>：不是逐条拒绝、不是「跳过超限的那几条」——
 *       蓝图那句「误操作一次可能烧掉整月预算」要的就是这个
 *   <li><b>🔴 总额上限必须跨条目累加</b>：一批「有的加有的减」净变化是 0，
 *       但动的是两倍的钱 —— 只按净变化判上限就绕过去了
 *   <li><b>🔴 预览不落库</b>：预演之后活动一个字都不能变
 *   <li><b>🔴 异步任务</b>：提交返回任务，逐条结果事后查；本地改动要标「待回写平台」
 *   <li><b>🔴 竞价落在广告组上</b>：改的是 default_bid，不是活动预算
 *   <li><b>时间字段写回值正确</b>（AGENTS.md 要求断言写回值，不能只断言没抛异常）
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：用墙钟会让
 * 「这次写入落在哪个断言分支上」成为一个 flaky 断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdBulkOperationIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 🔴 每个用例一对**全新的租户**（共用租户会让前一个用例的数据被后一个用例数进去）。 */
  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA;

  /** 🔴 钉死的时刻。 */
  static final OffsetDateTime DATA_AS_OF = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
  static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-10-01T08:00:00+08:00");
  static final OffsetDateTime FINISHED_AT = OffsetDateTime.parse("2026-10-01T09:05:00+08:00");
  static final OffsetDateTime STRUCTURE_AS_OF = OffsetDateTime.parse("2026-10-01T09:10:00+08:00");
  static final LocalDate DAY_1 = LocalDate.of(2026, 10, 1);

  /** 🔴 一个明确的未来结束时刻（用于 end_at 往返断言）。 */
  static final OffsetDateTime END_AT = OffsetDateTime.parse("2026-12-31T23:59:59+08:00");

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
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    String bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);
  }

  @BeforeEach
  void seedGlobalCatalog() {
    exec("insert into platform (id,code,name,integration,support_status,position)"
        + " values ('" + AMAZON + "','AMAZON','Amazon','OFFICIAL_API','AVAILABLE',1)"
        + " on conflict (id) do nothing");
    // 🔴 归一类型的真相在 2.1.2.1（平台枚举映射），不在请求里 ——
    //   不播种这条映射，登记广告类型档案会被 14.1.2.2 拒掉。
    seedPlatformMapping(AMAZON, "sponsored_products", "AUTO_DISCOVERY");
  }

  /** 播种一条 2.1.2.1 的平台默认映射（全局码表）。 */
  static void seedPlatformMapping(UUID platformId, String external, String internal) {
    exec("insert into platform_enum_mapping (id,platform_id,enum_kind,external_value,internal_value)"
        + " values ('" + UUID.randomUUID() + "','" + platformId + "','AD_TYPE','" + external
        + "','" + internal + "')"
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

  @Autowired AdBulkOperationService service;
  @Autowired AdBulkOperationExecutor executor;
  @Autowired AdCampaignService campaigns;
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
      s.execute("select set_config('app.permission','channel.operation.read',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      var rs = s.executeQuery(sql);
      rs.next();
      return rs.getString(1);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 播种一个店铺（幂等）。 */
  String seedShop(UUID tenant, String code) {
    execAsTenant(tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','"
            + UUID.randomUUID() + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')"
            + " on conflict (tenant_id,code) do nothing");
    return code;
  }

  /** 通过**上游 14.1.2.2** 登记一个广告类型档案。 */
  UUID saveProfile(AccessService.Access who, String external, String internal) {
    return as(who, "channel.operation.create",
        () -> adTypes.saveProfile(new AdTypeDiffModels.SaveProfileRequest(
            null, AMAZON, external, internal, external + " 展示名", "CPC", "SALES",
            true, true, true, true, false,
            "UNMAPPED".equals(internal) ? "UNMAPPED" : "PLATFORM_DEFAULT", "测试登记")))
        .id();
  }

  /** 通过**上游 14.1.1.1** 登记活动（真实写入口）。 */
  UUID seedCampaign(
      AccessService.Access who, String shop, UUID profile, String externalId, String status,
      String budget) {
    as(who, "channel.operation.create",
        () -> campaigns.importCampaigns(new AdCampaignModels.ImportRequest(
            AMAZON, shop,
            new AdCampaignModels.ImportSyncRun("SUCCESS", DATA_AS_OF, STARTED_AT, FINISHED_AT, "",
                "batch-1"),
            List.of(new AdCampaignModels.ImportCampaign(
                externalId, "活动 " + externalId, profile, status, "USD",
                budget == null ? null : new BigDecimal(budget),
                null, null,
                List.of(new AdCampaignModels.ImportDailyMetric(
                    DAY_1, new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("1"),
                    new BigDecimal("10.0000"), new BigDecimal("50.0000"))),
                "测试登记")))));
    String id =
        execOneAsTenant(who.tenantId(),
            "select id from ad_campaign where tenant_id = '" + who.tenantId()
                + "' and external_campaign_id = '" + externalId + "'");
    return UUID.fromString(id);
  }

  /** 通过**上游 14.1.1.2** 登记广告组（竞价的落点）。 */
  void seedGroups(AccessService.Access who, UUID campaignId, String... externalIds) {
    List<AdCampaignDetailModels.AdCampaignGroupImport> groups = new java.util.ArrayList<>();
    for (String external : externalIds) {
      groups.add(new AdCampaignDetailModels.AdCampaignGroupImport(
          external, "组 " + external, "ENABLED", new BigDecimal("1.5000"), "USD",
          new BigDecimal("50.0000"), "AUTO", List.of(), List.of()));
    }
    as(who, "channel.operation.create",
        () -> details.importStructure(
            campaignId.toString(),
            new AdCampaignDetailModels.AdCampaignStructureImportRequest(
                STRUCTURE_AS_OF, groups, List.of())));
  }

  /** 构造一次「按筛选全选」的请求。 */
  AdBulkOperationRequest filterRequest(
      String shopCode, String operation, BigDecimal delta, String targetType) {
    return new AdBulkOperationRequest(
        null, shopCode, null, null, null, null, List.of(), targetType, operation,
        null, delta, null, null, null, null, "测试");
  }

  /** 构造一次「显式勾选」的请求。 */
  AdBulkOperationRequest explicitRequest(
      List<UUID> ids, String operation, String targetType, BigDecimal delta, BigDecimal bid) {
    return new AdBulkOperationRequest(
        null, null, null, null, null, null, ids, targetType, operation,
        null, delta, bid, null, null, null, "测试");
  }

  /** 受理一次批量任务（复刻控制器的三段式：创建 → 领取 → 跑）。 */
  AdBulkTaskDetail submit(AccessService.Access who, AdBulkOperationRequest request) {
    var created = as(who, "channel.operation.update", () -> service.createTask(request));
    executor.accept(who, "channel.operation.update", created.id());
    return await(who, created.id());
  }

  /** 等任务跑到终态（真实异步：轮询而不是假装同步）。 */
  AdBulkTaskDetail await(AccessService.Access who, UUID taskId) {
    long deadline = System.currentTimeMillis() + 20_000;
    while (System.currentTimeMillis() < deadline) {
      var detail = as(who, "channel.operation.read", () -> service.task(taskId.toString(), 0, 500));
      String status = detail.task().status();
      if ("DONE".equals(status)
          || "PARTIAL".equals(status)
          || "FAILED".equals(status)
          || "REJECTED".equals(status)) {
        return detail;
      }
      try {
        Thread.sleep(30);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("批量任务 " + taskId + " 在超时内没有跑到终态");
  }

  String budgetOf(AccessService.Access who, UUID campaignId) {
    return execOneAsTenant(who.tenantId(),
        "select coalesce(daily_budget::text,'<null>') from ad_campaign where tenant_id = '"
            + who.tenantId() + "' and id = '" + campaignId + "'");
  }

  // ==================================================================
  // 1. 能力声明与护栏上限
  // ==================================================================

  @Test
  @DisplayName("14.1.1.3 能力声明：四项能力已交付；平台回写 / 消耗预估 / 广告组管理显式未交付")
  void capabilitiesDeclareUndeliveredParts() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());
    Map<String, AdBulkCapabilityDimension> dims =
        caps.dimensions().stream()
            .collect(java.util.stream.Collectors.toMap(AdBulkCapabilityDimension::dimension, d -> d));

    assertThat(dims.get("bulk_select_by_filter").available()).isTrue();
    assertThat(dims.get("bulk_preview").available()).isTrue();
    assertThat(dims.get("bulk_async_task").available()).isTrue();
    assertThat(dims.get("bulk_budget_adjust").available()).isTrue();
    assertThat(dims.get("bulk_bid_adjust").available()).isTrue();
    assertThat(dims.get("bulk_status_switch").available()).isTrue();
    assertThat(dims.get("bulk_set_end_at").available()).isTrue();

    // 🔴 未交付的三项必须显式 false + ownerModule + 影响
    assertThat(dims.get("platform_writeback").available()).isFalse();
    assertThat(dims.get("spend_estimate").available()).isFalse();
    assertThat(dims.get("ad_group_management").available()).isFalse();

    // 🔴 护栏上限必须给出来，界面上要能写明天花板
    assertThat(caps.guardrails().maxSingleBudgetDelta()).isEqualByComparingTo("5000");
    assertThat(caps.guardrails().maxTotalBudgetDelta()).isEqualByComparingTo("20000");
    assertThat(caps.guardrails().maxCampaignBudget()).isEqualByComparingTo("100000");
    assertThat(caps.guardrails().maxBid()).isEqualByComparingTo("1000");
    assertThat(caps.guardrails().maxSingleBidDelta()).isEqualByComparingTo("200");
    assertThat(caps.guardrails().maxTotalBidDelta()).isEqualByComparingTo("20000");
    assertThat(caps.guardrails().maxCampaignsPerTask()).isEqualTo(200);
    assertThat(caps.guardrails().maxGroupsPerTask()).isEqualTo(500);
  }

  // ==================================================================
  // 2. 按筛选结果全选：服务端解析
  // ==================================================================

  @Test
  @DisplayName("14.1.1.3 按筛选结果全选由服务端解析：返回 id 集合与命中计数，不让前端拉全表")
  void selectionResolvesOnTheServer() {
    seedShop(a, "SEL-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    for (int i = 1; i <= 3; i++) {
      seedCampaign(actorA, "SEL-AMZ-1", profile, "EXT-SEL-" + i, "ENABLED", "100.0000");
    }

    var selection =
        as(actorA, "channel.operation.read",
            () -> service.selection(null, "SEL-AMZ-1", null, null, null, null, "CAMPAIGN", null, 50));

    assertThat(selection.total()).isEqualTo(3);
    assertThat(selection.items()).hasSize(3);
    assertThat(selection.truncated()).isFalse();
    // 🔴 解析出来的每一项都带活动 id 与名 —— 前端拿到的就是可执行的目标集合
    assertThat(selection.items()).extracting(AdBulkSelectedRow::campaignId).doesNotContainNull();
    assertThat(selection.items()).extracting(AdBulkSelectedRow::campaignName)
        .allMatch(n -> n.startsWith("活动 "));

    // 🔴 游标分页：第二页从游标之后继续，不重不漏
    var first =
        as(actorA, "channel.operation.read",
            () -> service.selection(null, "SEL-AMZ-1", null, null, null, null, "CAMPAIGN", null, 2));
    assertThat(first.items()).hasSize(2);
    assertThat(first.nextCursor()).isNotNull();
    var second =
        as(actorA, "channel.operation.read",
            () -> service.selection(null, "SEL-AMZ-1", null, null, null, null, "CAMPAIGN",
                first.nextCursor(), 2));
    assertThat(second.items()).hasSize(1);
    assertThat(second.nextCursor()).isNull();
    assertThat(first.items()).extracting(AdBulkSelectedRow::campaignId)
        .doesNotContainAnyElementsOf(
            second.items().stream().map(AdBulkSelectedRow::campaignId).toList());
  }

  // ==================================================================
  // 3. 预览：不落库
  // ==================================================================

  @Test
  @DisplayName("14.1.1.3 预览不落库：预演之后活动一个字都没变")
  void previewDoesNotWrite() {
    seedShop(a, "PV-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    UUID id = seedCampaign(actorA, "PV-AMZ-1", profile, "EXT-PV-1", "ENABLED", "100.0000");

    var preview =
        as(actorA, "channel.operation.read",
            () -> service.preview(filterRequest("PV-AMZ-1", "ADJUST_BUDGET",
                new BigDecimal("50.0000"), "CAMPAIGN")));

    assertThat(preview.allowed()).isTrue();
    assertThat(preview.targetCount()).isEqualTo(1);
    assertThat(preview.totalBudgetDelta()).isEqualByComparingTo("50");
    assertThat(preview.items()).hasSize(1);
    AdBulkPreviewItem item = preview.items().get(0);
    assertThat(item.beforeBudget()).isEqualByComparingTo("100.0000");
    assertThat(item.afterBudget()).isEqualByComparingTo("150.0000");
    assertThat(item.verdict()).isEqualTo("APPLY");

    // 🔴 预演之后预算原封不动
    assertThat(budgetOf(actorA, id)).isEqualTo("100.0000");
  }

  @Test
  @DisplayName("14.1.1.3 预览报总预算增量：净变化与绝对敞口分开")
  void previewReportsBudgetTotals() {
    seedShop(a, "TOT-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    seedCampaign(actorA, "TOT-AMZ-1", profile, "EXT-TOT-1", "ENABLED", "100.0000");
    seedCampaign(actorA, "TOT-AMZ-1", profile, "EXT-TOT-2", "ENABLED", "100.0000");

    var preview =
        as(actorA, "channel.operation.read",
            () -> service.preview(filterRequest("TOT-AMZ-1", "ADJUST_BUDGET",
                new BigDecimal("50.0000"), "CAMPAIGN")));

    assertThat(preview.targetCount()).isEqualTo(2);
    assertThat(preview.totalBudgetDelta()).isEqualByComparingTo("100");
    assertThat(preview.totalBudgetDeltaAbs()).isEqualByComparingTo("100");
    // 🔴 竞价调整没有消耗预估模型 ⇒ 恒为 false，**不编**一个假的消耗预估
    assertThat(preview.spendImpactEstimated()).isFalse();
    assertThat(preview.spendImpactNote()).contains("不编");
  }

  // ==================================================================
  // 4. 护栏：整批拒绝
  // ==================================================================

  @Test
  @DisplayName("14.1.1.3 护栏：单次上限越限 ⇒ 整批拒绝（不是逐条拒、不是跳过超限的那几条）")
  void singleLimitRejectsTheWholeBatch() {
    seedShop(a, "LIM-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    UUID id1 = seedCampaign(actorA, "LIM-AMZ-1", profile, "EXT-LIM-1", "ENABLED", "100.0000");
    UUID id2 = seedCampaign(actorA, "LIM-AMZ-1", profile, "EXT-LIM-2", "ENABLED", "100.0000");

    var preview =
        as(actorA, "channel.operation.read",
            () -> service.preview(filterRequest("LIM-AMZ-1", "ADJUST_BUDGET",
                new BigDecimal("6000.0000"), "CAMPAIGN")));
    assertThat(preview.allowed()).isFalse();
    assertThat(preview.rejectionReason()).contains("单次上限");
    // 🔴 明细仍然列出来：要让运营看到「正是哪一条越了上限」
    assertThat(preview.items()).isNotEmpty();
    assertThat(preview.items()).extracting(AdBulkPreviewItem::verdict)
        .contains("REJECT_LIMIT");

    var task =
        submit(actorA, filterRequest("LIM-AMZ-1", "ADJUST_BUDGET",
            new BigDecimal("6000.0000"), "CAMPAIGN"));
    assertThat(task.task().status()).isEqualTo("REJECTED");
    assertThat(task.task().appliedCount()).isZero();
    assertThat(task.task().message()).contains("整批拒绝");
    // 🔴 一条都没生效：两个活动的预算都原样
    assertThat(budgetOf(actorA, id1)).isEqualTo("100.0000");
    assertThat(budgetOf(actorA, id2)).isEqualTo("100.0000");
    assertThat(task.items()).allMatch(i -> "REJECTED".equals(i.result()));
  }

  @Test
  @DisplayName("14.1.1.3 护栏：总额上限跨条目累加 —— 一次误操作不能烧掉整月预算")
  void totalLimitAccumulatesAcrossItems() {
    seedShop(a, "ACC-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    for (int i = 1; i <= 5; i++) {
      seedCampaign(actorA, "ACC-AMZ-1", profile, "EXT-ACC-" + i, "ENABLED", "100.0000");
    }

    // 🔴 每条 5000 都在单次上限内，但五条合计 25000 > 总额上限 20000
    var preview =
        as(actorA, "channel.operation.read",
            () -> service.preview(filterRequest("ACC-AMZ-1", "ADJUST_BUDGET",
                new BigDecimal("5000.0000"), "CAMPAIGN")));
    assertThat(preview.allowed()).isFalse();
    assertThat(preview.rejectionReason()).contains("总额上限");
    assertThat(preview.totalBudgetDeltaAbs()).isEqualByComparingTo("25000");

    var task =
        submit(actorA, filterRequest("ACC-AMZ-1", "ADJUST_BUDGET",
            new BigDecimal("5000.0000"), "CAMPAIGN"));
    assertThat(task.task().status()).isEqualTo("REJECTED");
    assertThat(task.task().appliedCount()).isZero();
  }

  @Test
  @DisplayName("14.1.1.3 护栏：净变化为 0 的一批「有的加有的减」也绕不过总额上限")
  void absoluteExposureIsWhatTheGuardrailChecks() {
    seedShop(a, "MIX-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    // 设为同一个预算 ⇒ 有的加有的减：每条 |Δ| 恰好 5000（不越单次上限），
    // 但三条加、三条减 ⇒ 净变化 0、绝对敞口 30000。
    for (int i = 1; i <= 3; i++) {
      seedCampaign(actorA, "MIX-AMZ-1", profile, "EXT-MIX-UP-" + i, "ENABLED", "5000.0000");
    }
    for (int i = 1; i <= 3; i++) {
      seedCampaign(actorA, "MIX-AMZ-1", profile, "EXT-MIX-DOWN-" + i, "ENABLED", "15000.0000");
    }

    var request =
        new AdBulkOperationRequest(null, "MIX-AMZ-1", null, null, null, null, List.of(),
            "CAMPAIGN", "SET_BUDGET", new BigDecimal("10000.0000"), null, null, null, null, null, "");

    var preview = as(actorA, "channel.operation.read", () -> service.preview(request));
    // 🔴 净变化是 0，绝对敞口是 30000 —— 护栏判的是**绝对敞口**
    assertThat(preview.totalBudgetDelta()).isEqualByComparingTo("0");
    assertThat(preview.totalBudgetDeltaAbs()).isEqualByComparingTo("30000");
    // 🔴 如果护栏按净变化判，这一批就会被放行 —— 那正是「有的加有的减」绕过去的方式
    assertThat(preview.allowed()).isFalse();
    assertThat(preview.rejectionReason()).contains("总额上限");

    var task = submit(actorA, request);
    assertThat(task.task().status()).isEqualTo("REJECTED");
    assertThat(task.task().appliedCount()).isZero();
  }

  // ==================================================================
  // 5. 异步任务：预算 / 状态 / 结束时间
  // ==================================================================

  @Test
  @DisplayName("14.1.1.3 异步任务：受理即返回任务，跑完逐条有结果，本地改动标「待回写平台」")
  void asyncTaskAppliesAndRecordsPerItemResults() {
    seedShop(a, "ASY-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    UUID id = seedCampaign(actorA, "ASY-AMZ-1", profile, "EXT-ASY-1", "ENABLED", "100.0000");

    var task =
        submit(actorA, filterRequest("ASY-AMZ-1", "ADJUST_BUDGET",
            new BigDecimal("50.0000"), "CAMPAIGN"));

    assertThat(task.task().status()).isEqualTo("DONE");
    assertThat(task.task().appliedCount()).isEqualTo(1);
    assertThat(task.task().rejectedCount()).isZero();
    assertThat(task.task().targetCount()).isEqualTo(1);
    assertThat(task.task().totalBudgetDelta()).isEqualByComparingTo("50");
    assertThat(task.task().finishedAt()).isNotNull();

    // 🔴 逐条结果
    assertThat(task.items()).hasSize(1);
    AdBulkTaskItemRow item = task.items().get(0);
    assertThat(item.result()).isEqualTo("APPLIED");
    assertThat(item.beforeBudget()).isEqualByComparingTo("100.0000");
    assertThat(item.afterBudget()).isEqualByComparingTo("150.0000");
    assertThat(item.executedAt()).isNotNull();
    // 🔴 「已生效」= 本地已生效：界面必须显式标出还没回写平台
    assertThat(item.pendingPlatformWriteback()).isTrue();
    assertThat(item.message()).contains("尚未回写平台");

    // 🔴 活动真的改了，并且标了待回写平台
    assertThat(budgetOf(actorA, id)).isEqualTo("150.0000");
    String pending =
        execOneAsTenant(a,
            "select pending_platform_writeback::text from ad_campaign where tenant_id = '"
                + a + "' and id = '" + id + "'");
    assertThat(pending).isEqualTo("true");
  }

  @Test
  @DisplayName("14.1.1.3 状态切换：暂停真的把活动变成 PAUSED")
  void statusSwitchApplies() {
    seedShop(a, "ST-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    UUID id = seedCampaign(actorA, "ST-AMZ-1", profile, "EXT-ST-1", "ENABLED", "100.0000");

    var task = submit(actorA, filterRequest("ST-AMZ-1", "PAUSE", null, "CAMPAIGN"));
    assertThat(task.task().status()).isEqualTo("DONE");
    assertThat(task.items().get(0).afterStatus()).isEqualTo("PAUSED");

    String status = execOneAsTenant(a,
        "select status from ad_campaign where tenant_id = '" + a + "' and id = '" + id + "'");
    assertThat(status).isEqualTo("PAUSED");
  }

  @Test
  @DisplayName("14.1.1.3 结束时间设置：真的写 end_at，写回值逐字相等（timestamptz 往返）")
  void endAtIsWrittenAndRoundTrips() {
    seedShop(a, "END-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    UUID id = seedCampaign(actorA, "END-AMZ-1", profile, "EXT-END-1", "ENABLED", "100.0000");

    var request =
        new AdBulkOperationRequest(null, "END-AMZ-1", null, null, null, null, List.of(),
            "CAMPAIGN", "SET_END_AT", null, null, null, null, END_AT, null, "大促排期");
    var task = submit(actorA, request);

    assertThat(task.task().status()).isEqualTo("DONE");
    AdBulkTaskItemRow item = task.items().get(0);
    assertThat(item.result()).isEqualTo("APPLIED");
    assertThat(item.afterEndAt().toInstant()).isEqualTo(END_AT.toInstant());

    // 🔴 断言**写回的值**：用 epoch 秒比，否则 +08:00 的偏移会被比掉。
    //   这里如果用 Timestamp.valueOf(LocalDateTime...) 绑定，偏 8 小时，断言立刻红。
    //   （PostgreSQL 的 timestamptz 文本格式不是 ISO-8601，直接 OffsetDateTime.parse 会失败 ——
    //   所以取 epoch 秒这个**时区无关**的表示来比。）
    String written = execOneAsTenant(a,
        "select extract(epoch from end_at)::bigint::text from ad_campaign where tenant_id = '"
            + a + "' and id = '" + id + "'");
    assertThat(Long.parseLong(written)).isEqualTo(END_AT.toInstant().getEpochSecond());
  }

  @Test
  @DisplayName("14.1.1.3 结束时间设到过去 ⇒ 逐条被拒（不是护栏，不整批拒绝）")
  void endAtInThePastIsRejectedPerItem() {
    seedShop(a, "PAST-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    UUID id = seedCampaign(actorA, "PAST-AMZ-1", profile, "EXT-PAST-1", "ENABLED", "100.0000");

    var request =
        new AdBulkOperationRequest(null, "PAST-AMZ-1", null, null, null, null, List.of(),
            "CAMPAIGN", "SET_END_AT", null, null, null, null,
            OffsetDateTime.now().minusDays(1), null, "写错了");
    var task = submit(actorA, request);

    assertThat(task.task().status()).isEqualTo("PARTIAL");
    assertThat(task.task().rejectedCount()).isEqualTo(1);
    assertThat(task.items().get(0).result()).isEqualTo("REJECTED");
    assertThat(task.items().get(0).message()).contains("必须晚于当前时刻");
    // 🔴 活动没被改
    String written = execOneAsTenant(a,
        "select coalesce(end_at::text,'<null>') from ad_campaign where tenant_id = '" + a
            + "' and id = '" + id + "'");
    assertThat(written).isEqualTo("<null>");
  }

  // ==================================================================
  // 6. 竞价：落点是广告组
  // ==================================================================

  @Test
  @DisplayName("14.1.1.3 跨活动批量竞价：改的是广告组的 default_bid，并留组级审计")
  void bidAdjustmentLandsOnAdGroups() {
    seedShop(a, "BID-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    UUID c1 = seedCampaign(actorA, "BID-AMZ-1", profile, "EXT-BID-1", "ENABLED", "100.0000");
    UUID c2 = seedCampaign(actorA, "BID-AMZ-1", profile, "EXT-BID-2", "ENABLED", "100.0000");
    seedGroups(actorA, c1, "G-1", "G-2");
    seedGroups(actorA, c2, "G-3");

    var request =
        new AdBulkOperationRequest(null, "BID-AMZ-1", null, null, null, null, List.of(),
            "AD_GROUP", "ADJUST_BID", null, null, new BigDecimal("3.0000"), null, null, null, "统一提价");
    var task = submit(actorA, request);

    assertThat(task.task().status()).isEqualTo("DONE");
    // 🔴 一次请求里改了**多个活动的多个组**
    assertThat(task.task().targetCount()).isEqualTo(3);
    assertThat(task.items()).allMatch(i -> "APPLIED".equals(i.result()));
    assertThat(task.items()).extracting(AdBulkTaskItemRow::groupId).doesNotContainNull();

    // 🔴 组的 default_bid 真的改了
    String bids = execOneAsTenant(a,
        "select string_agg(default_bid::text, ',' order by external_group_id)"
            + " from ad_campaign_group where tenant_id = '" + a + "'");
    assertThat(bids).isEqualTo("3.0000,3.0000,3.0000");

    // 🔴 组级审计留痕（V277 的 ad_campaign_group_bid_log）
    String logs = execOneAsTenant(a,
        "select count(*)::text from ad_campaign_group_bid_log where tenant_id = '" + a
            + "' and result = 'APPLIED'");
    assertThat(logs).isEqualTo("3");

    // 🔴 广告组表没有 pending_platform_writeback 列 ⇒ 信号记在活动上
    String pending = execOneAsTenant(a,
        "select count(*)::text from ad_campaign where tenant_id = '" + a
            + "' and pending_platform_writeback");
    assertThat(pending).isEqualTo("2");
  }

  @Test
  @DisplayName("14.1.1.3 竞价调整的落点必须是广告组：记成活动级一律 400")
  void bidRequiresAdGroupTarget() {
    var request =
        new AdBulkOperationRequest(null, null, null, null, null, null, List.of(),
            "CAMPAIGN", "ADJUST_BID", null, null, new BigDecimal("3.0000"), null, null, null, "");
    assertThatThrownBy(() -> as(actorA, "channel.operation.read", () -> service.preview(request)))
        .hasMessageContaining("广告组");
  }

  // ==================================================================
  // 7. 逐条业务规则 vs 整批护栏
  // ==================================================================

  @Test
  @DisplayName("14.1.1.3 逐条业务规则只拒那一条（任务 PARTIAL），与整批护栏拒绝分开")
  void perItemRuleRejectsOnlyThatItem() {
    seedShop(a, "PART-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    seedCampaign(actorA, "PART-AMZ-1", profile, "EXT-PART-1", "ENABLED", "100.0000");
    seedCampaign(actorA, "PART-AMZ-1", profile, "EXT-PART-2", "ARCHIVED", "100.0000");

    var task = submit(actorA, filterRequest("PART-AMZ-1", "PAUSE", null, "CAMPAIGN"));

    assertThat(task.task().status()).isEqualTo("PARTIAL");
    assertThat(task.task().appliedCount()).isEqualTo(1);
    assertThat(task.task().rejectedCount()).isEqualTo(1);
    assertThat(task.items().stream().filter(i -> "REJECTED".equals(i.result())).findFirst()
        .orElseThrow().message()).contains("已结束或已归档");
  }

  // ==================================================================
  // 8. 显式勾选
  // ==================================================================

  @Test
  @DisplayName("14.1.1.3 显式勾选：id 必须全部存在，不存在的整批 400（页面过期是客户端错误）")
  void explicitSelectionRejectsUnknownIds() {
    seedShop(a, "EXP-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    UUID id = seedCampaign(actorA, "EXP-AMZ-1", profile, "EXT-EXP-1", "ENABLED", "100.0000");

    assertThatThrownBy(() -> as(actorA, "channel.operation.read",
        () -> service.preview(explicitRequest(
            List.of(id, UUID.randomUUID()), "PAUSE", "CAMPAIGN", null, null))))
        .hasMessageContaining("不存在或不属于当前租户");
  }

  @Test
  @DisplayName("14.1.1.3 显式勾选：只改选中的那几条，没选的一个都不动")
  void explicitSelectionOnlyTouchesSelected() {
    seedShop(a, "EXP2-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    UUID picked = seedCampaign(actorA, "EXP2-AMZ-1", profile, "EXT-EXP2-1", "ENABLED", "100.0000");
    UUID untouched =
        seedCampaign(actorA, "EXP2-AMZ-1", profile, "EXT-EXP2-2", "ENABLED", "100.0000");

    var task =
        submit(actorA, explicitRequest(List.of(picked), "PAUSE", "CAMPAIGN", null, null));

    assertThat(task.task().status()).isEqualTo("DONE");
    assertThat(task.task().selectionMode()).isEqualTo("EXPLICIT");
    assertThat(task.task().targetCount()).isEqualTo(1);
    String pickedStatus = execOneAsTenant(a,
        "select status from ad_campaign where tenant_id = '" + a + "' and id = '" + picked + "'");
    String otherStatus = execOneAsTenant(a,
        "select status from ad_campaign where tenant_id = '" + a + "' and id = '" + untouched + "'");
    assertThat(pickedStatus).isEqualTo("PAUSED");
    assertThat(otherStatus).isEqualTo("ENABLED");
  }

  // ==================================================================
  // 9. 任务查询
  // ==================================================================

  @Test
  @DisplayName("14.1.1.3 任务状态可查：列表里能查到刚提交的那个任务")
  void tasksAreQueryable() {
    seedShop(a, "Q-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    seedCampaign(actorA, "Q-AMZ-1", profile, "EXT-Q-1", "ENABLED", "100.0000");

    var task = submit(actorA, filterRequest("Q-AMZ-1", "PAUSE", null, "CAMPAIGN"));

    var page = as(actorA, "channel.operation.read", () -> service.tasks(0, 20));
    assertThat(page.total()).isGreaterThanOrEqualTo(1);
    assertThat(page.items().stream().map(AdBulkTaskRow::id)).contains(task.task().id());
    AdBulkTaskRow row = page.items().stream()
        .filter(t -> t.id().equals(task.task().id())).findFirst().orElseThrow();
    assertThat(row.status()).isEqualTo("DONE");
    assertThat(row.selectionMode()).isEqualTo("FILTER");
    assertThat(row.selectionSummary()).contains("按筛选条件");
  }

  @Test
  @DisplayName("14.1.1.3 跨租户与不存在返回逐字相同的 404")
  void crossTenantAndMissingAreIdentical() {
    seedShop(a, "404-AMZ-1");
    UUID profile = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    seedCampaign(actorA, "404-AMZ-1", profile, "EXT-404-1", "ENABLED", "100.0000");
    var task = submit(actorA, filterRequest("404-AMZ-1", "PAUSE", null, "CAMPAIGN"));

    String missing = "99999999-9999-9999-9999-999999999999";
    var crossTenant =
        catchThrowable(() -> as(actorB, "channel.operation.read",
            () -> service.task(task.task().id().toString(), 0, 20)));
    var notThere =
        catchThrowable(() -> as(actorB, "channel.operation.read",
            () -> service.task(missing, 0, 20)));
    assertThat(crossTenant).isNotNull();
    assertThat(notThere).isNotNull();
    assertThat(crossTenant).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(notThere).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(crossTenant.getMessage()).isEqualTo(notThere.getMessage());
  }

  // ==================================================================
  // 10. 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("14.1.1.3 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    seedShop(a, "ISO-AMZ-1");
    seedShop(b, "ISO-AMZ-2");
    UUID profileA = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    UUID profileB = saveProfile(actorB, "sponsored_products", "AUTO_DISCOVERY");
    seedCampaign(actorA, "ISO-AMZ-1", profileA, "EXT-ISO-A", "ENABLED", "100.0000");
    seedCampaign(actorB, "ISO-AMZ-2", profileB, "EXT-ISO-B", "ENABLED", "100.0000");

    // 选择结果互不可见
    assertThat(as(actorA, "channel.operation.read",
        () -> service.selection(null, "ISO-AMZ-1", null, null, null, null, "CAMPAIGN", null, 50))
        .items()).extracting(AdBulkSelectedRow::campaignName).containsExactly("活动 EXT-ISO-A");
    assertThat(as(actorB, "channel.operation.read",
        () -> service.selection(null, "ISO-AMZ-2", null, null, null, null, "CAMPAIGN", null, 50))
        .items()).extracting(AdBulkSelectedRow::campaignName).containsExactly("活动 EXT-ISO-B");

    // 任务列表互不可见
    var taskA = submit(actorA, filterRequest("ISO-AMZ-1", "PAUSE", null, "CAMPAIGN"));
    assertThat(as(actorA, "channel.operation.read", () -> service.tasks(0, 50)).items())
        .extracting(AdBulkTaskRow::id).contains(taskA.task().id());
    assertThat(as(actorB, "channel.operation.read", () -> service.tasks(0, 50)).items())
        .extracting(AdBulkTaskRow::id).doesNotContain(taskA.task().id());

    // 任务详情跨租户拿不到
    assertThatThrownBy(() -> as(actorB, "channel.operation.read",
        () -> service.task(taskA.task().id().toString(), 0, 20)))
        .hasMessageContaining("不存在");
  }

  @Test
  @DisplayName("14.1.1.3 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    seedShop(a, "FORGE-AMZ-1");
    seedShop(b, "FORGE-AMZ-2");
    UUID profileA = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    UUID profileB = saveProfile(actorB, "sponsored_products", "AUTO_DISCOVERY");
    seedCampaign(actorA, "FORGE-AMZ-1", profileA, "EXT-FORGE-A", "ENABLED", "100.0000");
    seedCampaign(actorB, "FORGE-AMZ-2", profileB, "EXT-FORGE-B", "ENABLED", "100.0000");

    // A 的会话 + B 的头 ⇒ 仍然只看得见 A 的活动
    http.perform(get("/channel/ad-bulk-operations/selection")
            .param("shopCode", "FORGE-AMZ-1")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].campaignName",
            org.hamcrest.Matchers.contains("活动 EXT-FORGE-A")));

    // 伪造租户头也不能让 B 的活动被 A 读到
    http.perform(get("/channel/ad-bulk-operations/selection")
            .param("shopCode", "FORGE-AMZ-2")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", org.hamcrest.Matchers.empty()));

    // 无会话一律 401
    http.perform(get("/channel/ad-bulk-operations/selection")
            .param("shopCode", "FORGE-AMZ-1")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("14.1.1.3 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    seedShop(a, "POOL-AMZ-1");
    seedShop(b, "POOL-AMZ-2");
    UUID profileA = saveProfile(actorA, "sponsored_products", "AUTO_DISCOVERY");
    UUID profileB = saveProfile(actorB, "sponsored_products", "AUTO_DISCOVERY");
    seedCampaign(actorA, "POOL-AMZ-1", profileA, "EXT-POOL-A", "ENABLED", "100.0000");
    seedCampaign(actorB, "POOL-AMZ-2", profileB, "EXT-POOL-B", "ENABLED", "100.0000");

    var taskA = submit(actorA, filterRequest("POOL-AMZ-1", "PAUSE", null, "CAMPAIGN"));
    var taskB = submit(actorB, filterRequest("POOL-AMZ-2", "PAUSE", null, "CAMPAIGN"));

    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "channel.operation.read",
          () -> service.selection(null, "POOL-AMZ-1", null, null, null, null, "CAMPAIGN", null, 50))
          .items()).extracting(AdBulkSelectedRow::campaignName).containsExactly("活动 EXT-POOL-A");
      assertThat(as(actorB, "channel.operation.read",
          () -> service.selection(null, "POOL-AMZ-2", null, null, null, null, "CAMPAIGN", null, 50))
          .items()).extracting(AdBulkSelectedRow::campaignName).containsExactly("活动 EXT-POOL-B");
      // 🔴 任务也互不可见（连接池只有 1 条连接，最容易串的地方）
      assertThat(as(actorA, "channel.operation.read", () -> service.tasks(0, 50)).items())
          .extracting(AdBulkTaskRow::id).contains(taskA.task().id())
          .doesNotContain(taskB.task().id());
      assertThat(as(actorB, "channel.operation.read", () -> service.tasks(0, 50)).items())
          .extracting(AdBulkTaskRow::id).contains(taskB.task().id())
          .doesNotContain(taskA.task().id());
    }
  }

  // ==================================================================
  // 11. 端点契约
  // ==================================================================

  @Test
  @DisplayName("14.1.1.3 端点：能力声明 / 选项 / 预览 / 任务都真的在")
  void endpointsAreWired() throws Exception {
    http.perform(get("/channel/ad-bulk-operations/capabilities")
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.guardrails.maxTotalBudgetDelta").value(20000));

    http.perform(get("/channel/ad-bulk-operations/options")
            .header("Authorization", bearerA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.operations", org.hamcrest.Matchers.hasSize(6)));

    // 预览走 HTTP：参数不对要 400，不能静默接受
    http.perform(post("/channel/ad-bulk-operations/preview")
            .header("Authorization", bearerA)
            .contentType("application/json")
            .content("{\"operation\":\"NOT_A_REAL_OPERATION\"}"))
        .andExpect(status().isBadRequest());
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
    pg.stop();
  }
}
