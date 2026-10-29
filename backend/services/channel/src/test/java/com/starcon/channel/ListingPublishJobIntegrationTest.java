package com.starcon.channel;

import static com.starcon.channel.ListingEditorModels.*;
import static com.starcon.channel.ListingPublishJobModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 4.3.1.1 一键刊登。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 跨平台发布不是一个事务</b>：一个目标的派发失败（DB 真的抛错）只改它自己那一行，
 *       另一个目标照旧派发成功；作业的总体结论是**聚合**出来的，不是"整体失败"。
 *   <li><b>🔴 幂等</b>：同一个 {@code idempotency_key} 重复提交返回同一条作业，
 *       库里只有一条作业、目标不翻倍。
 *   <li><b>🔴 内容快照</b>：入队那一刻的标题 / 描述 / 图片 / 价格 / 库存被冻结；
 *       直接改快照会被 DB 拒（触发器级证据）。
 *   <li><b>🔴 取价不可用时如实标</b>：5.1.1.1 未交付 ⇒ 价格是 {@code UNAVAILABLE} + 归属模块，
 *       <b>不是</b> 0、也不是"没有这一栏"。
 *   <li><b>🔴 库存读不到就是读不到</b>：warehouse 挂掉时建作业直接失败，<b>不</b>把可售量折成 0。
 *   <li><b>🔴 模板来源存规则不存值</b>：源商品属性改了，重新发布求出来的是新值。
 *   <li><b>能力声明如实</b>：5.1.1.1 / 4.2.3.2 / 4.3.1.3 / 4.1.1.3 四段 available=false + ownerModule。
 *   <li><b>🔴 时刻基准同源</b>：{@code dispatched_at} 记的是「写入它的那一次事务的 DB 时刻」
 *       （PG 的 {@code now()} = 事务开始时刻），断言的参照也取自 DB —— <b>不跟 JVM 墙钟比</b>。
 *       跨时钟域比较 + 只留 2 秒余量 = 全量跑时必假红（见 {@code dispatchedAtBaselineIsDbSourced}）。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingPublishJobIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static StubCatalogServer catalog;
  static StubWarehouse warehouse;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID tenant;
  AccessService.Access actor;
  String bearer;

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static final UUID SHOPIFY = UUID.fromString("11111111-1111-1111-1111-111111111102");
  static final UUID TIKTOK = UUID.fromString("11111111-1111-1111-1111-111111111104");
  UUID siteUs, siteShopifyUs;

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
      catalog = new StubCatalogServer();
      warehouse = new StubWarehouse();
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
    siteUs = marketplaceId(AMAZON, "US");
    siteShopifyUs = marketplaceId(SHOPIFY, "US");
    warehouse.reset();
    catalog.skuAttributes.clear();
  }

  static UUID marketplaceId(UUID platformId, String code) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(
            "select id from marketplace where platform_id='" + platformId + "' and code='" + code + "'")) {
      return rs.next() ? UUID.fromString(rs.getString(1)) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
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
    r.add("CATALOG_URL", () -> catalog.base());
    r.add("WAREHOUSE_URL", () -> warehouse.base());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired ListingPublishJobService publishes;
  @Autowired ListingEditorService editor;
  @Autowired AccessService access;
  @Autowired MockMvc http;
  @Autowired PlatformTransactionManager txManager;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, java.util.function.Supplier<T> work) {
    return access.run(actor, permission, work);
  }

  static void exec(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  void execAsTenant(String sql) {
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

  static String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static long count(String sql) {
    String value = scalar(sql);
    return value == null ? 0L : Long.parseLong(value);
  }

  /**
   * 从 DB 读一个 {@code timestamptz} 值（DB 侧原样读回，不做时区换算）。
   *
   * <p>🔴 时刻基准必须**同源**：参照时刻也取自 DB，才不会跟 JVM 墙钟跨时钟域比较。
   * {@code clock_timestamp()} 是**语句时刻**（DB 时钟），{@code now()} 是**事务开始时刻**。
   */
  OffsetDateTime dbTimestamp(String sql) {
    String raw = scalar(sql);
    return raw == null ? null : OffsetDateTime.parse(raw.replace(' ', 'T'));
  }

  UUID seedShop(String code, UUID platformId, UUID marketplaceId) {
    UUID id = UUID.randomUUID();
    execAsTenant("insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenant + "','" + id
        + "','" + platformId + "','" + code + "','测试店铺 " + code + "')");
    execAsTenant("insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status) values ('" + tenant
        + "','" + id + "','" + marketplaceId + "','ACTIVE')");
    return id;
  }

  /** 用 4.2.1.1 刊登编辑器建一份内容并提交入队（真实走一遍上游，不是自己插表）。 */
  UUID editorSubmission(UUID shopId, UUID marketplaceId, String title) {
    EditorDraftRow draft =
        as(
            "channel.operation.create",
            () ->
                editor.save(
                    new EditorDraftRequest(
                        shopId,
                        marketplaceId,
                        "en-US",
                        UUID.randomUUID(),
                        null,
                        "Electronics > Audio",
                        List.of(
                            new EditorFieldValue("TITLE", title, null, null, null),
                            new EditorFieldValue("DESCRIPTION", "描述正文", null, null, null),
                            new EditorFieldValue("IMAGES", null, List.of("https://img/1.jpg"), null, null)),
                        "")));
    EditorSubmissionRow submission =
        as(
            "channel.operation.update",
            () -> editor.submit(draft.draftId().toString(), "idem-" + draft.draftId()));
    return submission.submissionId();
  }

  CreateListingPublishJobRequest request(
      UUID skuId,
      String contentSource,
      List<UUID> sourceIds,
      List<ListingPublishTargetInput> targets,
      String idempotencyKey) {
    return new CreateListingPublishJobRequest(
        skuId,
        targets,
        contentSource,
        sourceIds,
        "PRICING_SERVICE",
        null,
        null,
        "KEEP_UNSET",
        null,
        idempotencyKey,
        "");
  }

  ListingPublishTargetInput target(UUID shopId, UUID marketplaceId, String locale) {
    return new ListingPublishTargetInput(shopId, marketplaceId, locale, "");
  }

  // ==================================================================
  // 一、能力声明
  // ==================================================================

  @Test
  @DisplayName("4.3.1.1 能力声明：未交付的四段 available=false + ownerModule + 影响")
  void capabilitiesDeclareUnavailableSegments() {
    var caps = as("channel.operation.read", () -> publishes.capabilities());
    Map<String, ListingPublishCapabilityDimension> byDimension = new LinkedHashMap<>();
    for (var dimension : caps.dimensions()) {
      byDimension.put(dimension.dimension(), dimension);
    }
    assertThat(byDimension.get("price_source").available())
        .as("5 定价（5.1.1.1）未交付，取价能力必须是 false").isFalse();
    assertThat(byDimension.get("price_source").ownerModule()).isEqualTo("5.1.1.1");
    assertThat(byDimension.get("price_source").impact()).contains("5.1.1.1");

    assertThat(byDimension.get("compliance_precheck").available())
        .as("4.2.3.2 合规预检此刻还不存在").isFalse();
    assertThat(byDimension.get("compliance_precheck").ownerModule()).isEqualTo("4.2.3.2");

    assertThat(byDimension.get("publish_task_timeline").available()).isFalse();
    assertThat(byDimension.get("publish_task_timeline").ownerModule()).isEqualTo("4.3.1.3");

    assertThat(byDimension.get("listing_lifecycle_delist").available()).isFalse();
    assertThat(byDimension.get("listing_lifecycle_delist").ownerModule()).isEqualTo("4.1.1.3");

    assertThat(byDimension.get("non_transactional_targets").available())
        .as("跨平台发布不是一个事务是本模块自己的不变量").isTrue();
    assertThat(byDimension.get("idempotent_create").available()).isTrue();
    assertThat(byDimension.get("content_snapshot").available()).isTrue();
  }

  // ==================================================================
  // 二、建作业 + 幂等 + 快照
  // ==================================================================

  @Test
  @DisplayName("4.3.1.1 建作业：每个目标一个任务，内容快照被冻结")
  void createJobFreezesPerTargetSnapshots() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID shopifyShop = seedShop("SHP-" + shortId(), SHOPIFY, siteShopifyUs);
    UUID amazonSubmission = editorSubmission(amazonShop, siteUs, "Amazon 标题");
    UUID shopifySubmission = editorSubmission(shopifyShop, siteShopifyUs, "Shopify 标题");

    var job =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    request(
                        UUID.randomUUID(),
                        "EDITOR_SUBMISSION",
                        List.of(amazonSubmission, shopifySubmission),
                        List.of(
                            target(amazonShop, siteUs, "en-US"),
                            target(shopifyShop, siteShopifyUs, "en-US")),
                        "job-1"),
                    bearer,
                    user));

    assertThat(job.progress().total()).as("两个目标 ⇒ 两个发布任务").isEqualTo(2);
    assertThat(job.progress().draft()).isEqualTo(2);
    assertThat(job.state()).isEqualTo("DRAFT");

    var targets = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    assertThat(targets).hasSize(2);
    assertThat(targets).extracting(ListingPublishTargetRow::state).containsOnly("DRAFT");
    assertThat(targets)
        .extracting(ListingPublishTargetRow::contentSnapshot)
        .allSatisfy(
            snapshot -> {
              assertThat(snapshot).isNotNull();
              assertThat(snapshot.sourceKind()).isEqualTo("EDITOR_SUBMISSION");
              assertThat(snapshot.sourceId()).isNotNull();
            });
    assertThat(targets.stream().map(t -> t.contentSnapshot().title()).toList())
        .as("每个目标冻结的是它自己那份内容")
        .containsExactlyInAnyOrder("Amazon 标题", "Shopify 标题");
  }

  @Test
  @DisplayName("4.3.1.1 幂等：同一个幂等键重复提交返回同一条，不产生第二条")
  void createIsIdempotent() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID submission = editorSubmission(amazonShop, siteUs, "幂等标题");
    UUID skuId = UUID.randomUUID();
    var targets = List.of(target(amazonShop, siteUs, "en-US"));

    var first =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    request(skuId, "EDITOR_SUBMISSION", List.of(submission), targets, "same-key"),
                    bearer,
                    user));
    var second =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    request(skuId, "EDITOR_SUBMISSION", List.of(submission), targets, "same-key"),
                    bearer,
                    user));

    assertThat(second.jobId()).as("重复提交返回同一条作业").isEqualTo(first.jobId());
    assertThat(count("select count(*) from listing_publish_job where tenant_id='" + tenant + "'"))
        .as("库里只有一条作业").isEqualTo(1);
    assertThat(count("select count(*) from listing_publish_job_target where tenant_id='" + tenant + "'"))
        .as("目标不翻倍").isEqualTo(1);
  }

  @Test
  @DisplayName("4.3.1.1 内容快照不可变：直接改快照被 DB 拒（触发器级证据）")
  void contentSnapshotIsImmutable() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID submission = editorSubmission(amazonShop, siteUs, "冻结标题");
    var job =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    request(
                        UUID.randomUUID(),
                        "EDITOR_SUBMISSION",
                        List.of(submission),
                        List.of(target(amazonShop, siteUs, "en-US")),
                        "frozen-1"),
                    bearer,
                    user));
    var target = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null)).get(0);

    Throwable thrown =
        catchThrowable(
            () ->
                execAsTenant(
                    "update listing_publish_job_target set content_snapshot = "
                        + "'{\"title\":\"改过了\"}'::jsonb where tenant_id='" + tenant
                        + "' and id='" + target.targetId() + "'"));
    assertThat(thrown).as("改快照必须被 DB 拒").isNotNull();
    assertThat(thrown.getMessage()).contains("frozen");
  }

  @Test
  @DisplayName("4.3.1.1 目标找不到内容来源时整体拒绝，不建半份作业")
  void unmatchedTargetIsRejected() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID shopifyShop = seedShop("SHP-" + shortId(), SHOPIFY, siteShopifyUs);
    UUID amazonSubmission = editorSubmission(amazonShop, siteUs, "Amazon 标题");

    Throwable thrown =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        publishes.create(
                            request(
                                UUID.randomUUID(),
                                "EDITOR_SUBMISSION",
                                List.of(amazonSubmission),
                                List.of(
                                    target(amazonShop, siteUs, "en-US"),
                                    target(shopifyShop, siteShopifyUs, "en-US")),
                                "unmatched-1"),
                            bearer,
                            user)));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("找不到内容来源");
    assertThat(count("select count(*) from listing_publish_job where tenant_id='" + tenant + "'"))
        .as("建作业失败 ⇒ 库里不留半份作业").isZero();
  }

  // ==================================================================
  // 三、价格 / 库存来源策略
  // ==================================================================

  @Test
  @DisplayName("4.3.1.1 取价不可用时如实标 UNAVAILABLE + 归属 5.1.1.1，不编一个价")
  void priceSourceUnavailableIsDeclaredNotFaked() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID submission = editorSubmission(amazonShop, siteUs, "价格标题");
    var job =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    request(
                        UUID.randomUUID(),
                        "EDITOR_SUBMISSION",
                        List.of(submission),
                        List.of(target(amazonShop, siteUs, "en-US")),
                        "price-1"),
                    bearer,
                    user));

    assertThat(job.priceState()).isEqualTo("UNAVAILABLE");
    assertThat(job.priceAmount()).as("没有价就是没有价，不是 0").isNull();
    assertThat(job.priceStateReason()).contains("5.1.1.1");

    var steps = as("channel.operation.read", () -> publishes.steps(job.jobId().toString()));
    var priceStep =
        steps.stream().filter(s -> "PRICE".equals(s.stepKey())).findFirst().orElseThrow();
    assertThat(priceStep.state()).as("价格这一步是「被挡住」，不是「成功」").isEqualTo("BLOCKED");
    assertThat(priceStep.detail()).contains("5.1.1.1");
  }

  @Test
  @DisplayName("4.3.1.1 运营手填的价格被冻结成金额 + 币种（NUMERIC，不用浮点）")
  void manualPriceIsFrozen() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID submission = editorSubmission(amazonShop, siteUs, "价格标题");
    var job =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    new CreateListingPublishJobRequest(
                        UUID.randomUUID(),
                        List.of(target(amazonShop, siteUs, "en-US")),
                        "EDITOR_SUBMISSION",
                        List.of(submission),
                        "MANUAL",
                        new BigDecimal("19.99"),
                        "usd",
                        "KEEP_UNSET",
                        null,
                        "price-2",
                        ""),
                    bearer,
                    user));

    assertThat(job.priceState()).isEqualTo("SET");
    assertThat(job.priceAmount()).isEqualByComparingTo("19.99");
    assertThat(job.priceCurrency()).isEqualTo("USD");
    assertThat(scalar("select price_amount::text from listing_publish_job where tenant_id='" + tenant + "'"))
        .as("库里是 NUMERIC，不是浮点")
        .isEqualTo("19.9900");
  }

  @Test
  @DisplayName("4.3.1.1 库存来源：可售量从 warehouse 读并冻结")
  void stockSnapshotComesFromWarehouse() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID submission = editorSubmission(amazonShop, siteUs, "库存标题");
    UUID skuId = UUID.randomUUID();
    warehouse.put(skuId, "7.500000");

    var job =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    new CreateListingPublishJobRequest(
                        skuId,
                        List.of(target(amazonShop, siteUs, "en-US")),
                        "EDITOR_SUBMISSION",
                        List.of(submission),
                        "PRICING_SERVICE",
                        null,
                        null,
                        "WAREHOUSE_AVAILABLE",
                        null,
                        "stock-1",
                        ""),
                    bearer,
                    user));

    assertThat(job.stockState()).isEqualTo("SET");
    assertThat(job.stockQuantity()).isEqualByComparingTo("7.5");
    assertThat(scalar("select stock_quantity::text from listing_publish_job where tenant_id='" + tenant + "'"))
        .isEqualTo("7.500000");
  }

  @Test
  @DisplayName("4.3.1.1 warehouse 读不到时建作业直接失败，不把可售量折成 0")
  void stockReadFailureFailsTheJob() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID submission = editorSubmission(amazonShop, siteUs, "库存标题");
    warehouse.fail = true;

    Throwable thrown =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        publishes.create(
                            new CreateListingPublishJobRequest(
                                UUID.randomUUID(),
                                List.of(target(amazonShop, siteUs, "en-US")),
                                "EDITOR_SUBMISSION",
                                List.of(submission),
                                "PRICING_SERVICE",
                                null,
                                null,
                                "WAREHOUSE_AVAILABLE",
                                null,
                                "stock-2",
                                ""),
                            bearer,
                            user)));
    assertThat(thrown).as("读不到可售量必须报错，不能降级成 0").isNotNull();
    assertThat(count("select count(*) from listing_publish_job where tenant_id='" + tenant + "'"))
        .as("建作业失败 ⇒ 库里不留作业").isZero();
  }

  // ==================================================================
  // 四、模板来源（4.2.2.1）
  // ==================================================================

  @Test
  @DisplayName("4.3.1.1 模板来源：按规则对源 SKU 实时求值（模板存规则不存值）")
  void templateSourceResolvesFromRules() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID skuId = UUID.randomUUID();
    catalog.putSku(skuId, "material", "棉质");
    UUID templateId = seedTemplate(amazonShop, "TPL-" + shortId(), "material", "{{material}} 针织衫");

    var job =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    request(
                        skuId,
                        "TEMPLATE",
                        List.of(templateId),
                        List.of(target(amazonShop, siteUs, "en-US")),
                        "tpl-1"),
                    bearer,
                    user));

    var targets = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    assertThat(targets).hasSize(1);
    assertThat(targets.get(0).contentSnapshot().sourceKind()).isEqualTo("TEMPLATE");
    assertThat(targets.get(0).contentSnapshot().title()).isEqualTo("棉质 针织衫");

    // 🔴 模板存规则不存值：源商品属性改了，重新发布求出来的是**新值**
    catalog.putSku(skuId, "material", "亚麻");
    var second =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    request(
                        skuId,
                        "TEMPLATE",
                        List.of(templateId),
                        List.of(target(amazonShop, siteUs, "en-US")),
                        "tpl-2"),
                    bearer,
                    user));
    var secondTargets =
        as("channel.operation.read", () -> publishes.targets(second.jobId().toString(), null));
    assertThat(secondTargets.get(0).contentSnapshot().title()).isEqualTo("亚麻 针织衫");
  }

  @Test
  @DisplayName("4.3.1.1 模板适用不上目标时整体拒绝，并说清为什么")
  void templateScopeMismatchIsRejected() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID shopifyShop = seedShop("SHP-" + shortId(), SHOPIFY, siteShopifyUs);
    UUID skuId = UUID.randomUUID();
    catalog.putSku(skuId, "material", "棉");
    UUID templateId = seedTemplate(amazonShop, "TPL-" + shortId(), "material", "{{material}}");

    Throwable thrown =
        catchThrowable(
            () ->
                as(
                    "channel.operation.create",
                    () ->
                        publishes.create(
                            request(
                                skuId,
                                "TEMPLATE",
                                List.of(templateId),
                                List.of(target(shopifyShop, siteShopifyUs, "en-US")),
                                "tpl-3"),
                            bearer,
                            user)));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("没有适用于该目标的刊登模板");
  }

  // ==================================================================
  // 五、派发：跨平台不是一个事务
  // ==================================================================

  @Test
  @DisplayName("4.3.1.1 派发：一个目标失败不影响另一个目标（跨平台发布不是一个事务）")
  void dispatchIsNonTransactionalAcrossTargets() {
    UUID okShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID failShop = seedShop("FAILSHOP-" + shortId(), SHOPIFY, siteShopifyUs);
    UUID okSubmission = editorSubmission(okShop, siteUs, "正常标题");
    UUID failSubmission = editorSubmission(failShop, siteShopifyUs, "失败标题");

    var job =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    request(
                        UUID.randomUUID(),
                        "EDITOR_SUBMISSION",
                        List.of(okSubmission, failSubmission),
                        List.of(
                            target(okShop, siteUs, "en-US"),
                            target(failShop, siteShopifyUs, "en-US")),
                        "dispatch-1"),
                    bearer,
                    user));

    // 🔴 用一个真的 DB 触发器让「失败店铺」的回写任务插入失败（模拟某个平台发不出去）
    exec("create or replace function fail_publish_for_shop() returns trigger language plpgsql as $$ "
        + "begin if new.payload->>'shopCode' like 'FAILSHOP%' then "
        + "raise exception 'simulated platform publish failure'; end if; return new; end $$");
    exec("drop trigger if exists t_fail_publish on platform_operation");
    exec("create trigger t_fail_publish before insert on platform_operation "
        + "for each row execute function fail_publish_for_shop()");

    ListingPublishDispatchResponse dispatch;
    try {
      dispatch = as("channel.operation.update", () -> publishes.dispatch(job.jobId().toString()));
    } finally {
      exec("drop trigger if exists t_fail_publish on platform_operation");
    }

    assertThat(dispatch.entries()).hasSize(2);
    assertThat(dispatch.entries())
        .filteredOn(e -> "DISPATCHED".equals(e.result()))
        .hasSize(1);
    assertThat(dispatch.entries())
        .filteredOn(e -> "FAILED".equals(e.result()))
        .hasSize(1);

    var targets = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    var failed = targets.stream().filter(t -> "FAILED".equals(t.state())).findFirst().orElseThrow();
    var ok = targets.stream().filter(t -> "SUBMITTED".equals(t.state())).findFirst().orElseThrow();
    assertThat(failed.failureReason()).contains("simulated platform publish failure");
    assertThat(ok.state()).as("另一个平台照旧派发成功").isEqualTo("SUBMITTED");

    var progress = as("channel.operation.read", () -> publishes.job(job.jobId().toString())).progress();
    assertThat(progress.total()).isEqualTo(2);
    assertThat(progress.failed()).isEqualTo(1);
    assertThat(progress.inReview()).isEqualTo(1);
    assertThat(progress.outcome()).as("一半还在审核中，总体不是「已完成」也不是「整体失败」")
        .isEqualTo("NOT_DISPATCHED");
  }

  @Test
  @DisplayName("4.3.1.1 每个目标各自一条回写任务（2.5.1.1 的 platform_operation）")
  void dispatchCreatesOnePlatformOperationPerTarget() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID shopifyShop = seedShop("SHP-" + shortId(), SHOPIFY, siteShopifyUs);
    UUID amazonSubmission = editorSubmission(amazonShop, siteUs, "A 标题");
    UUID shopifySubmission = editorSubmission(shopifyShop, siteShopifyUs, "B 标题");
    var job =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    request(
                        UUID.randomUUID(),
                        "EDITOR_SUBMISSION",
                        List.of(amazonSubmission, shopifySubmission),
                        List.of(
                            target(amazonShop, siteUs, "en-US"),
                            target(shopifyShop, siteShopifyUs, "en-US")),
                        "ops-1"),
                    bearer,
                    user));

    as("channel.operation.update", () -> publishes.dispatch(job.jobId().toString()));

    assertThat(count("select count(*) from platform_operation where tenant_id='" + tenant
            + "' and operation='PUBLISH'"))
        .as("每个目标一条回写任务").isEqualTo(2);
    assertThat(count("select count(distinct idempotency_key) from platform_operation where tenant_id='"
            + tenant + "'"))
        .as("回写任务的幂等键按目标拆开").isEqualTo(2);
  }

  // ==================================================================
  // 六、回执与独立重试
  // ==================================================================

  @Test
  @DisplayName("4.3.1.1 平台驳回只改那一行，驳回必须带原因")
  void rejectionOnlyAffectsThatTarget() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID shopifyShop = seedShop("SHP-" + shortId(), SHOPIFY, siteShopifyUs);
    UUID amazonSubmission = editorSubmission(amazonShop, siteUs, "A 标题");
    UUID shopifySubmission = editorSubmission(shopifyShop, siteShopifyUs, "B 标题");
    var job =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    request(
                        UUID.randomUUID(),
                        "EDITOR_SUBMISSION",
                        List.of(amazonSubmission, shopifySubmission),
                        List.of(
                            target(amazonShop, siteUs, "en-US"),
                            target(shopifyShop, siteShopifyUs, "en-US")),
                        "reject-1"),
                    bearer,
                    user));
    as("channel.operation.update", () -> publishes.dispatch(job.jobId().toString()));
    var targets = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));

    var rejected =
        as(
            "channel.operation.update",
            () ->
                publishes.report(
                    targets.get(0).targetId().toString(),
                    new ListingPublishTargetReportRequest(
                        "REJECTED", "标题超出平台上限", "", "req-1")));

    assertThat(rejected.state()).isEqualTo("REJECTED");
    assertThat(rejected.failureReason()).isEqualTo("标题超出平台上限");

    var after = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    assertThat(after.stream().map(ListingPublishTargetRow::state).toList())
        .as("另一个目标不受影响").containsExactlyInAnyOrder("REJECTED", "SUBMITTED");

    var progress = as("channel.operation.read", () -> publishes.job(job.jobId().toString())).progress();
    assertThat(progress.rejected()).isEqualTo(1);
    assertThat(progress.inReview()).isEqualTo(1);
  }

  @Test
  @DisplayName("4.3.1.1 独立重试：只重试失败的那一个，内容快照不变")
  void retryOnlyOneFailedTarget() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID shopifyShop = seedShop("SHP-" + shortId(), SHOPIFY, siteShopifyUs);
    UUID amazonSubmission = editorSubmission(amazonShop, siteUs, "A 标题");
    UUID shopifySubmission = editorSubmission(shopifyShop, siteShopifyUs, "B 标题");
    var job =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    request(
                        UUID.randomUUID(),
                        "EDITOR_SUBMISSION",
                        List.of(amazonSubmission, shopifySubmission),
                        List.of(
                            target(amazonShop, siteUs, "en-US"),
                            target(shopifyShop, siteShopifyUs, "en-US")),
                        "retry-1"),
                    bearer,
                    user));
    as("channel.operation.update", () -> publishes.dispatch(job.jobId().toString()));
    var targets = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    String snapshotBefore = targets.get(0).contentSnapshot().title();

    as(
        "channel.operation.update",
        () ->
            publishes.report(
                targets.get(0).targetId().toString(),
                new ListingPublishTargetReportRequest("FAILED", "平台 500", "", "")));

    var retried =
        as("channel.operation.update", () -> publishes.retry(targets.get(0).targetId().toString()));
    assertThat(retried.state()).isEqualTo("SUBMITTED");
    assertThat(retried.attempts()).as("尝试次数按目标累加").isEqualTo(2);
    assertThat(retried.contentSnapshot().title()).as("重试发的是同一份内容").isEqualTo(snapshotBefore);

    var after = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    var untouched = after.stream().filter(t -> !t.targetId().equals(targets.get(0).targetId()))
        .findFirst().orElseThrow();
    assertThat(untouched.attempts()).as("另一个目标的尝试次数不受影响").isEqualTo(1);
  }

  @Test
  @DisplayName("4.3.1.1 被驳回的目标不能重试（内容没改，重发只会再被驳回一次）")
  void rejectedTargetCannotBeRetried() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID submission = editorSubmission(amazonShop, siteUs, "A 标题");
    var job =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    request(
                        UUID.randomUUID(),
                        "EDITOR_SUBMISSION",
                        List.of(submission),
                        List.of(target(amazonShop, siteUs, "en-US")),
                        "retry-2"),
                    bearer,
                    user));
    as("channel.operation.update", () -> publishes.dispatch(job.jobId().toString()));
    var target = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null)).get(0);
    as(
        "channel.operation.update",
        () ->
            publishes.report(
                target.targetId().toString(),
                new ListingPublishTargetReportRequest("REJECTED", "图片不合规", "", "")));

    Throwable thrown =
        catchThrowable(
            () -> as("channel.operation.update", () -> publishes.retry(target.targetId().toString())));
    assertThat(thrown).isNotNull();
    assertThat(thrown.getMessage()).contains("驳回");
  }

  // ==================================================================
  // 七、端点与时刻
  // ==================================================================

  @Test
  @DisplayName("4.3.1.1 端点往返：建作业 → 派发 → 回执，HTTP 层都通")
  void endpointsRoundTrip() throws Exception {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID submission = editorSubmission(amazonShop, siteUs, "端点标题");

    http.perform(get("/channel/one-click-publish/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions").isArray());

    http.perform(get("/channel/one-click-publish/target-options").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    http.perform(get("/channel/one-click-publish/content-sources").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.editorSubmissions.length()").value(1));

    String body =
        JSON.writeValueAsString(
            Map.of(
                "sourceSkuId", UUID.randomUUID().toString(),
                "targets",
                    List.of(
                        Map.of(
                            "shopId", amazonShop.toString(),
                            "marketplaceId", siteUs.toString(),
                            "locale", "en-US")),
                "contentSource", "EDITOR_SUBMISSION",
                "contentSourceIds", List.of(submission.toString()),
                "priceSource", "PRICING_SERVICE",
                "stockSource", "KEEP_UNSET",
                "idempotencyKey", "http-1"));

    String created =
        http.perform(post("/channel/one-click-publish/jobs")
                .header("Authorization", bearer)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("DRAFT"))
        .andExpect(jsonPath("$.progress.total").value(1))
        .andReturn()
        .getResponse()
        .getContentAsString();
    String jobId = JSON.readTree(created).get("jobId").asText();

    http.perform(post("/channel/one-click-publish/jobs/" + jobId + "/dispatch")
            .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries.length()").value(1))
        .andExpect(jsonPath("$.entries[0].result").value("DISPATCHED"));

    http.perform(get("/channel/one-click-publish/jobs/" + jobId + "/targets")
            .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].state").value("SUBMITTED"));
  }

  @Test
  @DisplayName("4.3.1.1 timestamptz 往返：派发时刻写回的值逐字相等")
  void dispatchedAtRoundTrips() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID submission = editorSubmission(amazonShop, siteUs, "时刻标题");
    var job =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    request(
                        UUID.randomUUID(),
                        "EDITOR_SUBMISSION",
                        List.of(submission),
                        List.of(target(amazonShop, siteUs, "en-US")),
                        "time-1"),
                    bearer,
                    user));
    as("channel.operation.update", () -> publishes.dispatch(job.jobId().toString()));
    var target = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null)).get(0);

    assertThat(target.dispatchedAt()).isNotNull();

    // 🔴 基准换源：**同源时钟**，不跟 JVM 墙钟比。
    //    服务层写的是 dispatched_at = now()，而 PG 的 now() 是**事务开始时刻**、不是语句时刻；
    //    同一条 UPDATE 里 dispatched_at 与 updated_at 是同一个 now() 的两次落笔
    //    ⇒ updated_at 就是「写入 dispatched_at 的那一次事务的时刻」（同一次写入、同一个事务）。
    //    上界再取 DB 自己的时钟（clock_timestamp = 语句时刻）。
    //    🔴 为什么不再用「JVM 墙钟 - 2 秒」：那是**跨时钟域**比较（DB 的时刻 vs JVM 的墙钟）
    //    且只留 2 秒余量 ⇒ 事务一开久、或 DB 与 JVM 时钟本身有偏斜，就必然假红。
    //    全量跑时它已经打红过整整一轮（1133 例里红 1 例），隔离复跑却 18/18 全绿。
    OffsetDateTime txInstant =
        dbTimestamp(
            "select updated_at from listing_publish_job_target where tenant_id='"
                + tenant
                + "' and id='"
                + target.targetId()
                + "'");
    OffsetDateTime dbNow = dbTimestamp("select clock_timestamp()");
    assertThat(target.dispatchedAt().toInstant())
        .as("派发时刻 = 写入它的那次事务的 DB 时刻（同源时钟；事务开多久都不影响判定）")
        .isEqualTo(txInstant.toInstant());
    assertThat(target.dispatchedAt())
        .as("派发时刻不在 DB 自己的当前时刻之后（DB 时钟，不是 JVM 墙钟）")
        .isBeforeOrEqualTo(dbNow);

    String raw =
        scalar(
            "select dispatched_at from listing_publish_job_target where tenant_id='"
                + tenant
                + "' and id='"
                + target.targetId()
                + "'");
    assertThat(raw).isNotNull();
    OffsetDateTime stored = OffsetDateTime.parse(raw.replace(' ', 'T'));
    assertThat(stored.toInstant())
        .as("库里写回的时刻与服务端读到的一致")
        .isEqualTo(target.dispatchedAt().toInstant());
  }

  /**
   * 🔴 确定性证明：新的时刻基准（DB 同源时钟）在两个**会翻车的现场**下依然成立。
   *
   * <p>缺陷本质是「跨时钟域比较 + 余量太小」，所以这里把两个现场都**造出来**：
   *
   * <ol>
   *   <li><b>老事务现场</b>：派发发生在一次**已经开了 3 秒**的事务里（真实服务 + 外层事务）。
   *       PG 的 now() 是事务开始时刻 ⇒ 写进去的 dispatched_at 就比「现在」老 3 秒 ⇒
   *       旧的「JVM 墙钟 - 2 秒」余量必然不够。
   *   <li><b>可控的跨时钟域倒挂</b>：显式构造一个「JVM 墙钟比 DB 时钟快 50 秒」的倒挂，
   *       复现全量跑时打红的形状（DB 侧时刻比 JVM 侧早几十秒）。
   * </ol>
   *
   * <p>两个现场里：旧写法<b>必须红</b>（用 {@code assertThatThrownBy} 钉死，不许有人把它搬回来），
   * 新写法<b>必须绿</b>。
   */
  @Test
  @DisplayName("4.3.1.1 时刻基准同源：老事务现场 + 跨时钟域倒挂，新基准都不翻车")
  void dispatchedAtBaselineIsDbSourced() {
    UUID amazonShop = seedShop("AMZ-" + shortId(), AMAZON, siteUs);
    UUID submission = editorSubmission(amazonShop, siteUs, "基准标题");
    var job =
        as(
            "channel.operation.create",
            () ->
                publishes.create(
                    request(
                        UUID.randomUUID(),
                        "EDITOR_SUBMISSION",
                        List.of(submission),
                        List.of(target(amazonShop, siteUs, "en-US")),
                        "clock-1"),
                    bearer,
                    user));

    // ---- 现场一：派发发生在一次「已经开了很久」的事务里 ----------------------------
    // 用**真实服务**造这个现场：外层先开一个事务并把它留活，再在里面派发
    // （access.run 的 TransactionTemplate 是 REQUIRED ⇒ 会并进这个外层事务）。
    new TransactionTemplate(txManager)
        .execute(
            status -> {
              // 先在这个事务里跑一条语句 ⇒ BEGIN 真的发出去 ⇒ 事务开始时刻 = 此刻
              as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
              // 让这个事务真的变老 3 秒（DB 侧真实等待）
              exec("select pg_sleep(3)");
              as("channel.operation.update", () -> publishes.dispatch(job.jobId().toString()));
              return null;
            });

    var target = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null)).get(0);
    OffsetDateTime dispatchedAt = target.dispatchedAt();
    assertThat(dispatchedAt).isNotNull();

    // 老事务现场的可验证证据：全部用 DB 自己的时钟 ⇒ 与 JVM 墙钟无关。
    OffsetDateTime dbNow = dbTimestamp("select clock_timestamp()");
    Duration age = Duration.between(dispatchedAt, dbNow);
    assertThat(age.toMillis())
        .as("现场确实是老事务：写进去的时刻比 DB 当前时刻老了整段事务年龄")
        .isGreaterThanOrEqualTo(3000L);

    // 旧写法在「JVM 墙钟与 DB 时钟一致」的常态下，在这个现场**必红**。
    // 为了可复现（不依赖两台机器的时钟恰好对齐），这里用「DB 时钟 - 2 秒」代替
    // 「JVM 墙钟 - 2 秒」—— 旧写法的失效点就是这个 2 秒余量本身。
    assertThatThrownBy(() -> assertThat(dispatchedAt).isAfter(dbNow.minusSeconds(2)))
        .as("老事务现场 + 2 秒余量 ⇒ 旧写法（JVM 墙钟基准）必红")
        .isInstanceOf(AssertionError.class);

    // ---- 现场二：可控的跨时钟域倒挂 ------------------------------------------------
    // 显式构造「JVM 墙钟比 DB 时钟快 50 秒」的倒挂 = 全量跑时打红的形状。
    OffsetDateTime invertedJvmClock = dbNow.plusSeconds(50);
    assertThatThrownBy(() -> assertThat(dispatchedAt).isAfter(invertedJvmClock.minusSeconds(2)))
        .as("跨时钟域倒挂下，旧写法（拿 JVM 墙钟比）必红")
        .isInstanceOf(AssertionError.class);

    // 两个现场里，新基准都必须绿：它只用 DB 自己的时钟，跟 JVM 墙钟毫无关系。
    OffsetDateTime txInstant =
        dbTimestamp(
            "select updated_at from listing_publish_job_target where tenant_id='"
                + tenant
                + "' and id='"
                + target.targetId()
                + "'");
    assertThat(dispatchedAt.toInstant())
        .as("新基准：派发时刻 = 写入它的那次事务的 DB 时刻（老事务 / 倒挂都成立）")
        .isEqualTo(txInstant.toInstant());
    assertThat(dispatchedAt)
        .as("新基准：派发时刻不早于写入它的那次事务的 DB 时刻（下界方向）")
        .isAfterOrEqualTo(txInstant);
    assertThat(dispatchedAt)
        .as("新基准：派发时刻不晚于 DB 当前时刻（上界方向）")
        .isBeforeOrEqualTo(dbNow);
  }

  // ==================================================================
  // 辅助：种子与桩
  // ==================================================================

  UUID seedTemplate(UUID shopId, String code, String attributeCode, String expression) {
    UUID templateId = UUID.randomUUID();
    execAsTenant(
        "insert into listing_template (tenant_id,id,code,name,description,scope,platform_id,"
            + "marketplace_id,category_path,shop_id,language,status) values ('"
            + tenant
            + "','"
            + templateId
            + "','"
            + code
            + "','测试模板 "
            + code
            + "','','SHOP','"
            + AMAZON
            + "',null,null,'"
            + shopId
            + "','en-US','ACTIVE')");
    execAsTenant(
        "insert into listing_template_field (tenant_id,id,template_id,field,source_kind,source_ref,"
            + "value_expression,required,slot_no) values ('"
            + tenant
            + "','"
            + UUID.randomUUID()
            + "','"
            + templateId
            + "','TITLE','PRODUCT_ATTRIBUTE','"
            + attributeCode
            + "','"
            + expression
            + "',true,0)");
    return templateId;
  }

  static String shortId() {
    return UUID.randomUUID().toString().substring(0, 6);
  }

  /**
   * 07 库存可售量的本地 HTTP 服务。
   *
   * <p>🔴 这不是 mock 桩：它跑在真的 socket 上、按 warehouse 的路由形状回 JSON。
   * 本模块最要紧的不变量之一是「读不到可售量 ≠ 可售量是 0」——
   * 把端口打桩成固定返回值，这条不变量就测不出来。
   */
  static final class StubWarehouse implements AutoCloseable {
    private final HttpServer server;
    private final String base;
    final Map<UUID, String> sellable = new ConcurrentHashMap<>();
    volatile boolean fail = false;

    StubWarehouse() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      base = "http://127.0.0.1:" + server.getAddress().getPort();
      server.createContext("/warehouse/inventory/sellable/skus", this::skus);
      server.setExecutor(null);
      server.start();
    }

    String base() {
      return base;
    }

    void reset() {
      sellable.clear();
      fail = false;
    }

    void put(UUID skuId, String quantity) {
      sellable.put(skuId, quantity);
    }

    private void skus(com.sun.net.httpserver.HttpExchange e) throws IOException {
      if (fail) {
        byte[] body = "{\"message\":\"upstream failure\"}".getBytes();
        e.getResponseHeaders().add("Content-Type", "application/json");
        e.sendResponseHeaders(500, body.length);
        e.getResponseBody().write(body);
        e.close();
        return;
      }
      String query = e.getRequestURI().getRawQuery();
      UUID skuId = null;
      if (query != null) {
        for (String pair : query.split("&")) {
          if (pair.startsWith("skuId=")) {
            skuId = UUID.fromString(pair.substring("skuId=".length()));
          }
        }
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      if (skuId != null && sellable.containsKey(skuId)) {
        String quantity = sellable.get(skuId);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("skuId", skuId.toString());
        row.put("skuCode", "SKU-" + skuId.toString().substring(0, 8));
        row.put("skuName", "测试商品");
        row.put("onHand", new BigDecimal(quantity));
        row.put("reserved", BigDecimal.ZERO);
        row.put("available", new BigDecimal(quantity));
        row.put("sellableBeforeBuffer", new BigDecimal(quantity));
        row.put("safetyBuffer", BigDecimal.ZERO);
        row.put("sellable", new BigDecimal(quantity));
        rows.add(row);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("rows", rows);
      payload.put("total", rows.size());
      payload.put("page", 0);
      payload.put("size", 50);
      payload.put("note", "");
      byte[] body = JSON.writeValueAsBytes(payload);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, body.length);
      e.getResponseBody().write(body);
      e.close();
    }

    @Override
    public void close() {
      server.stop(0);
    }
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
    if (catalog != null) catalog.close();
    pg.stop();
  }
}
