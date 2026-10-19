package com.starcon.channel;

import static com.starcon.channel.ListingEditorModels.*;
import static com.starcon.channel.ListingPublishJobModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
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
 * 4.3.1.3 发布任务（单次发布任务的进度视图）。
 *
 * <p>本测试类钉住这几组事：
 *
 * <ol>
 *   <li><b>🔴 本模块是进度视图，不是另一个发布系统</b>：任务列表里同时出现 4.3.1.1 的发布任务
 *       与 4.3.1.2 的队列任务，但每行都带 {@code taskKind} 与**原始** state，
 *       状态不揉（归一化的 stage 只用于筛选与展示）。
 *   <li><b>🔴 步骤时间线按蓝图阶段序</b>：目标级步骤（套模板 / 填充内容 / 建立映射 / 上传媒体 /
 *       提交审核 / 平台结果）与作业级步骤（价格 / 库存）进同一条时间线。
 *   <li><b>🔴 蓝图六阶段里「平台创建」「变体创建」没有独立步骤行</b>：能力声明如实报出
 *       （stepKeys 为空 + note 写明归属 4.3.1.1），<b>不凭空造步骤</b>。
 *   <li><b>🔴 错误明细三来源分得开</b>：任务级（失败 / 驳回原因）、步骤级（步骤的 detail）、
 *       尝试级（某一次重试没成的原因）。
 *   <li><b>🔴 可重试与不可重试都写清理由</b>：MEDIA / SUBMIT / RESULT 可重试；
 *       内容快照已冻结的步骤（CONTENT 等）被拒并说明「重跑会改掉当时发的是什么」；
 *       被平台驳回的任务被拒并说明「重发同一份内容没有意义」。
 *   <li><b>🔴 重试留痕是真的</b>：每次重试落一条尝试记录（attempt_no 递增），
 *       成功与失败都留痕。
 *   <li><b>🔴 时间字段逐字往返</b>：写进去的 timestamptz 读回来是同一个瞬间
 *       （jOOQ 3.19 把 OffsetDateTime 绑成 varchar、或有人用 {@code Timestamp.valueOf}，
 *       都会在这里露馅 —— 后者会静默偏 −28800s）。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingPublishTaskIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID tenant;
  AccessService.Access actor;
  String bearer;

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  UUID siteUs;

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
                          ? java.util.stream.Stream.concat(
                              java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete"),
                              java.util.stream.Stream.empty())
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

  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    actors.put(bearer, actor(user, tenant, List.of(grant("ALL", allPermissions))));
    actor = actors.get(bearer);
    siteUs = marketplaceId(AMAZON, "US");
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
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("WAREHOUSE_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired ListingPublishJobService publishes;
  @Autowired ListingPublishTaskService tasks;
  @Autowired ListingEditorService editor;
  @Autowired AccessService access;
  @Autowired MockMvc http;

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

  static String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  UUID seedShop(UUID tenantId, String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenantId,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenantId + "','" + id
            + "','" + AMAZON + "','" + code + "','测试店铺 " + code + "')");
    execAsTenant(tenantId,
        "insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status)"
            + " values ('" + tenantId + "','" + id + "','" + siteUs + "','ACTIVE')");
    return id;
  }

  UUID editorSubmission(UUID shopId, String title) {
    EditorDraftRow draft =
        as(
            "channel.operation.create",
            () ->
                editor.save(
                    new EditorDraftRequest(
                        shopId,
                        siteUs,
                        "en-US",
                        UUID.randomUUID(),
                        null,
                        "Electronics > Audio",
                        List.of(
                            new EditorFieldValue("TITLE", title, null, null, null),
                            new EditorFieldValue("DESCRIPTION", "描述正文", null, null, null),
                            new EditorFieldValue("IMAGES", null, List.of("https://img/1.jpg"), null, null)),
                        "")));
    return as(
            "channel.operation.update",
            () -> editor.submit(draft.draftId().toString(), "idem-" + draft.draftId()))
        .submissionId();
  }

  /** 建一个一键刊登作业（1 个目标），返回作业行。 */
  ListingPublishJobRow createJob(UUID shopId, String key) {
    UUID submissionId = editorSubmission(shopId, "任务视图内容 " + key);
    return as(
        "channel.operation.create",
        () ->
            publishes.create(
                new CreateListingPublishJobRequest(
                    UUID.randomUUID(),
                    List.of(new ListingPublishTargetInput(shopId, siteUs, "en-US", "")),
                    "EDITOR_SUBMISSION",
                    List.of(submissionId),
                    "PRICING_SERVICE",
                    null,
                    null,
                    "KEEP_UNSET",
                    null,
                    key,
                    ""),
                "Bearer ignored-by-this-path",
                user));
  }

  /** 建一个 4.3.1.2 的队列任务（批量发布那条通道），返回 publish_task.id。 */
  UUID seedBatchTask(UUID shopId, String code) {
    UUID listingId = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into listing (id,tenant_id,shop_id,external_item_id,title)"
            + " values ('" + listingId + "','" + tenant + "','" + shopId + "','EXT-" + code
            + "','批量任务商品')");
    UUID batchId = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into publish_batch (id,tenant_id,listing_ids,limit_count,total_count,pending_count)"
            + " values ('" + batchId + "','" + tenant + "','{\"" + listingId + "\"}',1,1,1)");
    UUID taskId = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into publish_task (id,tenant_id,batch_id,listing_id,platform_id,shop_id,position,state,"
            + " dispatched_at,completed_at,failure_reason)"
            + " values ('" + taskId + "','" + tenant + "','" + batchId + "','" + listingId + "','"
            + AMAZON + "','" + shopId + "',0,'FAILED', now(), now(),'平台队列提交失败')");
    return taskId;
  }

  // ==================================================================
  // 1. 能力声明：如实报出没有独立步骤行的阶段
  // ==================================================================

  @Test
  @DisplayName("4.3.1.3 能力声明：蓝图六阶段里没有独立步骤行的阶段如实报空，不凭空造步骤")
  void capabilitiesAreHonest() {
    var caps = as("channel.operation.read", () -> tasks.capabilities());

    var platformCreate =
        caps.phases().stream().filter(p -> p.phase().equals("平台创建")).findFirst().orElseThrow();
    assertThat(platformCreate.stepKeys())
        .as("4.3.1.1 没有为「平台创建」单独记步骤行 —— 报空才是真的")
        .isEmpty();
    assertThat(platformCreate.note()).contains("4.3.1.1");

    var variant =
        caps.phases().stream().filter(p -> p.phase().equals("变体创建")).findFirst().orElseThrow();
    assertThat(variant.stepKeys()).isEmpty();
    assertThat(variant.note()).contains("4.3.1.1");

    // 有步骤行的阶段照实列出
    var media = caps.phases().stream().filter(p -> p.phase().equals("媒体上传")).findFirst().orElseThrow();
    assertThat(media.stepKeys()).containsExactly("MEDIA");
    var price =
        caps.phases().stream().filter(p -> p.phase().equals("价格库存设置")).findFirst().orElseThrow();
    assertThat(price.stepKeys()).containsExactly("PRICE", "STOCK");
    var submit =
        caps.phases().stream().filter(p -> p.phase().equals("提交审核")).findFirst().orElseThrow();
    assertThat(submit.stepKeys()).containsExactly("SUBMIT");

    // 未交付段 available=false + ownerModule
    var unavailable =
        caps.dimensions().stream()
            .filter(d -> !d.available())
            .collect(java.util.stream.Collectors.toList());
    assertThat(unavailable).isNotEmpty();
    assertThat(unavailable)
        .allSatisfy(d -> assertThat(d.ownerModule()).isNotBlank());
    assertThat(unavailable)
        .extracting(d -> d.dimension())
        .contains("platform_create_and_variant_phases", "batch_task_retry");

    // 🔴 平台提交审核是异步的：这句话必须出现在能力声明里
    assertThat(caps.asyncReviewNote()).contains("异步").contains("4.3.2.2");
  }

  // ==================================================================
  // 2. 任务列表：两条上游聚在一起，状态不揉
  // ==================================================================

  @Test
  @DisplayName("4.3.1.3 任务列表：4.3.1.1 与 4.3.1.2 的发布任务都出现，但 taskKind 与原始 state 分得开")
  void listShowsBothUpstreams() {
    UUID shopId = seedShop(tenant, "LIST-1");
    var job = createJob(shopId, "list-one");
    UUID batchTaskId = seedBatchTask(shopId, "B1");

    var page = as("channel.operation.read", () -> tasks.tasks(null, null, null, null, 0, 50));
    assertThat(page.total()).isGreaterThanOrEqualTo(2);

    var oneClick =
        page.rows().stream()
            .filter(r -> r.jobId() != null && r.jobId().equals(job.jobId()))
            .findFirst();
    assertThat(oneClick).isPresent();
    assertThat(oneClick.get().taskKind()).isEqualTo("ONECLICK");
    // 原始状态保留（DRAFT），归一化阶段是 PENDING
    assertThat(oneClick.get().state()).isEqualTo("DRAFT");
    assertThat(oneClick.get().stage()).isEqualTo("PENDING");
    // 步骤数真的数出来了（目标级 6 步）
    assertThat(oneClick.get().stepCount()).isEqualTo(6);
    assertThat(oneClick.get().failedStepCount()).isZero();

    var batch =
        page.rows().stream().filter(r -> r.taskId().equals(batchTaskId)).findFirst();
    assertThat(batch).isPresent();
    assertThat(batch.get().taskKind()).isEqualTo("BATCH");
    // 原始状态保留（FAILED），归一化阶段也是 FAILED
    assertThat(batch.get().state()).isEqualTo("FAILED");
    assertThat(batch.get().stage()).isEqualTo("FAILED");
    assertThat(batch.get().failureReason()).contains("平台队列提交失败");
    // 🔴 批量发布那条通道没有步骤行
    assertThat(batch.get().stepTimelineAvailable()).isFalse();
    assertThat(batch.get().stepCount()).isZero();
  }

  // ==================================================================
  // 3. 步骤时间线：按蓝图阶段序
  // ==================================================================

  @Test
  @DisplayName("4.3.1.3 步骤时间线：目标级步骤与作业级步骤进同一条线，按蓝图阶段序")
  void timelineFollowsPhaseOrder() {
    UUID shopId = seedShop(tenant, "TL-1");
    var job = createJob(shopId, "timeline");
    var targets = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    UUID targetId = targets.get(0).targetId();

    var detail = as("channel.operation.read", () -> tasks.task(targetId.toString()));
    assertThat(detail.stepTimelineAvailable()).isTrue();
    assertThat(detail.timeline()).extracting(s -> s.stepKey())
        .containsExactly(
            "TEMPLATE", "CONTENT", "MAPPING", "MEDIA", "PRICE", "STOCK", "SUBMIT", "RESULT");
    // 阶段序也对
    assertThat(detail.timeline()).extracting(s -> s.phase())
        .containsExactly(
            "内容校验", "内容校验", "建立映射", "媒体上传", "价格库存设置", "价格库存设置",
            "提交审核", "平台结果");
    // 作业级步骤的归属标成 JOB
    assertThat(detail.timeline())
        .filteredOn(s -> s.stepKey().equals("PRICE") || s.stepKey().equals("STOCK"))
        .allSatisfy(s -> assertThat(s.scope()).isEqualTo("JOB"));
    assertThat(detail.timeline())
        .filteredOn(s -> s.stepKey().equals("TEMPLATE"))
        .allSatisfy(s -> assertThat(s.scope()).isEqualTo("TARGET"));
    // 冻结的内容标题能读到（给人看「当时发的是什么」）
    assertThat(detail.contentSnapshotTitle()).contains("任务视图内容");
  }

  // ==================================================================
  // 4. 错误明细：任务级 / 步骤级 / 尝试级分得开
  // ==================================================================

  @Test
  @DisplayName("4.3.1.3 错误明细：平台回执失败后，步骤级原因与任务级原因都进明细")
  void failedStepsCarryErrorDetails() {
    UUID shopId = seedShop(tenant, "ERR-1");
    var job = createJob(shopId, "errors");
    var targets = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    UUID targetId = targets.get(0).targetId();

    as("channel.operation.update", () -> publishes.dispatch(job.jobId().toString()));
    as(
        "channel.operation.update",
        () ->
            publishes.report(
                targetId.toString(),
                new ListingPublishTargetReportRequest("FAILED", "图片尺寸超限", "", "")));

    var detail = as("channel.operation.read", () -> tasks.task(targetId.toString()));
    assertThat(detail.task().state()).isEqualTo("FAILED");
    assertThat(detail.task().stage()).isEqualTo("FAILED");

    // 步骤级错误：MEDIA / SUBMIT / RESULT 三步都失败，各自带原因
    var failedSteps =
        detail.timeline().stream().filter(s -> s.state().equals("FAILED")).toList();
    assertThat(failedSteps).hasSize(3);
    assertThat(failedSteps).allSatisfy(s -> assertThat(s.detail()).isNotBlank());

    // 错误明细里既有任务级也有步骤级
    assertThat(detail.errors()).extracting(e -> e.scope()).contains("TASK", "STEP");
    // 提交审核那一步带的是平台给的具体原因
    assertThat(detail.errors())
        .filteredOn(e -> e.scope().equals("STEP") && e.stepKey().equals("SUBMIT"))
        .allSatisfy(e -> assertThat(e.reason()).contains("图片尺寸超限"));
    // 任务级那条是 4.3.1.1 记在目标行上的失败原因
    assertThat(detail.errors())
        .filteredOn(e -> e.scope().equals("TASK"))
        .allSatisfy(e -> assertThat(e.reason()).contains("图片尺寸超限"));
  }

  // ==================================================================
  // 5. 重试：可重试的步骤
  // ==================================================================

  @Test
  @DisplayName("4.3.1.3 步骤级重试：MEDIA / SUBMIT / RESULT 可重试，重试后步骤回到进行中并留痕")
  void retryableStepCanBeRetried() {
    UUID shopId = seedShop(tenant, "RETRY-1");
    var job = createJob(shopId, "retry");
    var targets = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    UUID targetId = targets.get(0).targetId();

    as("channel.operation.update", () -> publishes.dispatch(job.jobId().toString()));
    as(
        "channel.operation.update",
        () ->
            publishes.report(
                targetId.toString(),
                new ListingPublishTargetReportRequest("FAILED", "平台提交失败：网络超时", "", "")));

    var before = as("channel.operation.read", () -> tasks.task(targetId.toString()));
    var submitBefore =
        before.timeline().stream().filter(s -> s.stepKey().equals("SUBMIT")).findFirst().orElseThrow();
    assertThat(submitBefore.state()).isEqualTo("FAILED");
    assertThat(submitBefore.retryable()).isTrue();
    assertThat(submitBefore.retryBlockedReason()).isEmpty();

    var result =
        as("channel.operation.update", () -> tasks.retryStep(targetId.toString(), "SUBMIT"));
    assertThat(result.outcome()).isEqualTo("SUCCEEDED");
    assertThat(result.attemptNo()).isEqualTo(1);
    assertThat(result.reason()).contains("重新提交");

    var after = as("channel.operation.read", () -> tasks.task(targetId.toString()));
    var submitAfter =
        after.timeline().stream().filter(s -> s.stepKey().equals("SUBMIT")).findFirst().orElseThrow();
    assertThat(submitAfter.state()).isEqualTo("RUNNING");
    assertThat(submitAfter.attemptCount()).isEqualTo(1);
    assertThat(submitAfter.attempts()).hasSize(1);
    assertThat(submitAfter.attempts().get(0).outcome()).isEqualTo("SUCCEEDED");

    // 🔴 重试 = 已重新提交平台，**不等于**平台审核通过
    assertThat(after.task().stage()).isEqualTo("IN_REVIEW");
    assertThat(after.task().state()).isEqualTo("SUBMITTED");
  }

  // ==================================================================
  // 6. 重试：不可重试的步骤，理由写清
  // ==================================================================

  @Test
  @DisplayName("4.3.1.3 不可重试的步骤被拒，并写清原因（内容快照已冻结 / 价格取价未交付）")
  void frozenSnapshotStepsAreNotRetryable() {
    UUID shopId = seedShop(tenant, "FROZEN-1");
    var job = createJob(shopId, "frozen");
    var targets = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    UUID targetId = targets.get(0).targetId();

    var detail = as("channel.operation.read", () -> tasks.task(targetId.toString()));
    var content =
        detail.timeline().stream().filter(s -> s.stepKey().equals("CONTENT")).findFirst().orElseThrow();
    assertThat(content.retryable()).isFalse();
    assertThat(content.retryBlockedReason()).contains("内容快照已冻结");

    // PRICE 是作业级步骤，取价来源 5.1.1.1 未交付 ⇒ 被挡住
    var price = detail.timeline().stream().filter(s -> s.stepKey().equals("PRICE")).findFirst().orElseThrow();
    assertThat(price.state()).isEqualTo("BLOCKED");
    assertThat(price.retryable()).isFalse();
    assertThat(price.retryBlockedReason()).contains("5.1.1.1");

    // 重试一个不可重试的步骤 ⇒ 422 + 理由
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as("channel.operation.update", () -> tasks.retryStep(targetId.toString(), "CONTENT")));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("内容快照已冻结");

    var priceError =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as("channel.operation.update", () -> tasks.retryStep(targetId.toString(), "PRICE")));
    assertThat(priceError).isNotNull();
    assertThat(priceError.getMessage()).contains("5.1.1.1");
  }

  @Test
  @DisplayName("4.3.1.3 平台驳回的任务不能重试（内容没改，重发只会再被驳回一次）")
  void rejectedTaskCannotBeRetried() {
    UUID shopId = seedShop(tenant, "REJ-1");
    var job = createJob(shopId, "rejected");
    var targets = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    UUID targetId = targets.get(0).targetId();

    as("channel.operation.update", () -> publishes.dispatch(job.jobId().toString()));
    as(
        "channel.operation.update",
        () ->
            publishes.report(
                targetId.toString(),
                new ListingPublishTargetReportRequest("REJECTED", "类目审核未通过", "", "")));

    var detail = as("channel.operation.read", () -> tasks.task(targetId.toString()));
    assertThat(detail.task().state()).isEqualTo("REJECTED");
    assertThat(detail.timeline())
        .filteredOn(s -> s.stepKey().equals("SUBMIT"))
        .allSatisfy(
            s -> {
              assertThat(s.retryable()).isFalse();
              assertThat(s.retryBlockedReason()).contains("驳回");
            });

    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as("channel.operation.update", () -> tasks.retryStep(targetId.toString(), "SUBMIT")));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("驳回");
  }

  // ==================================================================
  // 7. 重试留痕
  // ==================================================================

  @Test
  @DisplayName("4.3.1.3 每次重试都留痕（attempt_no 递增），失败的重试也留痕")
  void everyRetryIsAudited() {
    UUID shopId = seedShop(tenant, "AUDIT-1");
    var job = createJob(shopId, "audit");
    var targets = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    UUID targetId = targets.get(0).targetId();

    as("channel.operation.update", () -> publishes.dispatch(job.jobId().toString()));
    as(
        "channel.operation.update",
        () ->
            publishes.report(
                targetId.toString(),
                new ListingPublishTargetReportRequest("FAILED", "平台提交失败：限流", "", "")));

    var first = as("channel.operation.update", () -> tasks.retryStep(targetId.toString(), "MEDIA"));
    assertThat(first.attemptNo()).isEqualTo(1);
    assertThat(first.outcome()).isEqualTo("SUCCEEDED");

    // 第二次：再失败一次（再发一次回执失败）
    as(
        "channel.operation.update",
        () ->
            publishes.report(
                targetId.toString(),
                new ListingPublishTargetReportRequest("FAILED", "平台提交失败：仍然限流", "", "")));
    var second = as("channel.operation.update", () -> tasks.retryStep(targetId.toString(), "MEDIA"));
    assertThat(second.attemptNo()).isEqualTo(2);

    var detail = as("channel.operation.read", () -> tasks.task(targetId.toString()));
    assertThat(detail.attempts()).hasSize(2);
    assertThat(detail.attempts()).extracting(a -> a.attemptNo()).containsExactly(1, 2);
    // 每次尝试都带步骤键
    assertThat(detail.attempts()).allSatisfy(a -> assertThat(a.stepKey()).isEqualTo("MEDIA"));
    // 库里真的落了两条
    assertThat(
            scalar(
                "select count(*) from listing_publish_step_attempt where tenant_id='"
                    + tenant
                    + "' and task_id='"
                    + targetId
                    + "'"))
        .isEqualTo("2");
  }

  // ==================================================================
  // 8. 任务级重试（派发就失败时的唯一出路）
  // ==================================================================

  @Test
  @DisplayName("4.3.1.3 任务级重试：派发就失败时（没有失败的步骤），整条任务可重试")
  void taskLevelRetryWorksWhenNoStepFailed() {
    UUID shopId = seedShop(tenant, "TASKRETRY-1");
    var job = createJob(shopId, "task-retry");
    var targets = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    UUID targetId = targets.get(0).targetId();

    // 模拟 4.3.1.1 的**派发失败**：目标从 DRAFT 直接落到 FAILED，
    // 步骤还停在 PENDING（没有一步是 FAILED）—— 触发器允许 DRAFT → FAILED。
    execAsTenant(tenant,
        "update listing_publish_job_target set state='FAILED',"
            + " failure_reason='派发失败：回写任务写不进去', completed_at=now(), updated_at=now()"
            + " where id='" + targetId + "'");

    var detail = as("channel.operation.read", () -> tasks.task(targetId.toString()));
    assertThat(detail.task().state()).isEqualTo("FAILED");
    assertThat(detail.timeline()).noneMatch(s -> s.state().equals("FAILED"));
    // 任务级重试仍可用（步骤级无从谈起）
    var result = as("channel.operation.update", () -> tasks.retryTask(targetId.toString()));
    assertThat(result.outcome()).isEqualTo("SUCCEEDED");
    assertThat(result.attemptNo()).isEqualTo(1);
    // 留痕是任务级（stepKey 为空）
    var after = as("channel.operation.read", () -> tasks.task(targetId.toString()));
    assertThat(after.attempts()).hasSize(1);
    assertThat(after.attempts().get(0).stepKey()).isNull();
    assertThat(after.attempts().get(0).scope()).isEqualTo("TASK");
  }

  // ==================================================================
  // 9. 4.3.1.2 的队列任务：没有步骤时间线，如实说明
  // ==================================================================

  @Test
  @DisplayName("4.3.1.3 批量发布的队列任务没有步骤行：如实报「不可用 + 原因」，不拿空数组冒充")
  void batchTaskHasNoStepTimeline() {
    UUID shopId = seedShop(tenant, "BATCH-1");
    UUID batchTaskId = seedBatchTask(shopId, "B2");

    var detail = as("channel.operation.read", () -> tasks.task(batchTaskId.toString()));
    assertThat(detail.task().taskKind()).isEqualTo("BATCH");
    assertThat(detail.stepTimelineAvailable()).isFalse();
    assertThat(detail.stepTimelineNote()).contains("4.3.1.2");
    assertThat(detail.timeline()).isEmpty();
    // 失败原因仍要显示
    assertThat(detail.errors()).extracting(e -> e.reason()).contains("平台队列提交失败");

    // 队列任务不归本模块重试
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as("channel.operation.update", () -> tasks.retryTask(batchTaskId.toString())));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("4.3.1.2");
  }

  // ==================================================================
  // 10. 平台侧发布留痕
  // ==================================================================

  @Test
  @DisplayName("4.3.1.3 发布成功后能对到平台侧留痕（listing_publish_record）")
  void publishRecordsAreSurfaced() {
    UUID shopId = seedShop(tenant, "REC-1");
    var job = createJob(shopId, "records");
    var targets = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    UUID targetId = targets.get(0).targetId();

    as("channel.operation.update", () -> publishes.dispatch(job.jobId().toString()));
    as(
        "channel.operation.update",
        () ->
            publishes.report(
                targetId.toString(),
                new ListingPublishTargetReportRequest(
                    "SUCCEEDED", "", "ASIN-12345", "REQ-999")));

    UUID listingId = UUID.randomUUID();
    execAsTenant(tenant,
        "insert into listing (id,tenant_id,shop_id,external_item_id,title)"
            + " values ('" + listingId + "','" + tenant + "','" + shopId + "','ASIN-12345','已发布商品')");
    execAsTenant(tenant,
        "insert into listing_publish_record (id,tenant_id,listing_id,action,outcome,platform_request_id,reason)"
            + " values ('" + UUID.randomUUID() + "','" + tenant + "','" + listingId
            + "','PUBLISH','SUCCEEDED','REQ-999','')");

    var detail = as("channel.operation.read", () -> tasks.task(targetId.toString()));
    assertThat(detail.publishRecords()).hasSize(1);
    assertThat(detail.publishRecords().get(0).action()).isEqualTo("PUBLISH");
    assertThat(detail.publishRecords().get(0).outcome()).isEqualTo("SUCCEEDED");
    assertThat(detail.publishRecords().get(0).platformRequestId()).isEqualTo("REQ-999");
    assertThat(detail.externalItemId()).isEqualTo("ASIN-12345");
  }

  // ==================================================================
  // 11. 筛选与真分页
  // ==================================================================

  @Test
  @DisplayName("4.3.1.3 列表支持真筛选与真分页（total 是真实总数，不是当前页条数）")
  void listSupportsRealFiltersAndPagination() {
    UUID shopId = seedShop(tenant, "PAGE-1");
    createJob(shopId, "page-1");
    createJob(shopId, "page-2");
    seedBatchTask(shopId, "P1");

    var all = as("channel.operation.read", () -> tasks.tasks(null, null, null, null, 0, 50));
    assertThat(all.total()).isGreaterThanOrEqualTo(3);

    // 按来源筛
    var oneClick = as("channel.operation.read", () -> tasks.tasks("ONECLICK", null, null, null, 0, 50));
    assertThat(oneClick.rows()).allSatisfy(r -> assertThat(r.taskKind()).isEqualTo("ONECLICK"));
    var batch = as("channel.operation.read", () -> tasks.tasks("BATCH", null, null, null, 0, 50));
    assertThat(batch.rows()).allSatisfy(r -> assertThat(r.taskKind()).isEqualTo("BATCH"));
    assertThat(batch.total()).isEqualTo(1);

    // 按店铺筛
    UUID otherShop = seedShop(tenant, "PAGE-2");
    var byShop = as("channel.operation.read", () -> tasks.tasks(null, null, null, otherShop.toString(), 0, 50));
    assertThat(byShop.total()).isZero();
    assertThat(byShop.rows()).isEmpty();

    // 真分页：每页 1 条，total 不变
    var firstPage = as("channel.operation.read", () -> tasks.tasks(null, null, null, null, 0, 1));
    assertThat(firstPage.rows()).hasSize(1);
    assertThat(firstPage.total()).isEqualTo(all.total());
    var secondPage = as("channel.operation.read", () -> tasks.tasks(null, null, null, null, 1, 1));
    assertThat(secondPage.rows()).hasSize(1);
    assertThat(secondPage.rows().get(0).taskId())
        .as("第二页必须是另一条任务（真分页，不是同一页重复）")
        .isNotEqualTo(firstPage.rows().get(0).taskId());

    // 非法筛选值 ⇒ 422（不是静默忽略）
    var error =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> as("channel.operation.read", () -> tasks.tasks("NOT_A_KIND", null, null, null, 0, 50)));
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("未知的任务来源");
  }

  // ==================================================================
  // 12. 时间字段逐字往返
  // ==================================================================

  @Test
  @DisplayName("4.3.1.3 timestamptz 逐字往返：写进去的 OffsetDateTime 读回来是同一个瞬间")
  void timestampRoundTripKeepsTheInstant() {
    UUID shopId = seedShop(tenant, "TS-1");
    var job = createJob(shopId, "ts");
    var targets = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    UUID targetId = targets.get(0).targetId();

    // 写入一个带非零时区偏移的瞬间
    OffsetDateTime moment = OffsetDateTime.parse("2026-03-04T05:06:07.123456+08:00");
    execAsTenant(tenant,
        "insert into listing_publish_step_attempt"
            + " (id,tenant_id,task_id,job_id,step_key,scope,attempt_no,trigger_kind,outcome,reason,"
            + "  requested_at,finished_at,created_at)"
            + " values ('" + UUID.randomUUID() + "','" + tenant + "','" + targetId + "','"
            + job.jobId() + "','SUBMIT','TARGET',99,'STEP_RETRY','SUCCEEDED','时间往返',"
            + " '" + moment + "'::timestamptz, '" + moment + "'::timestamptz, now())");

    String stored =
        scalar(
            "select to_char(requested_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS.US')"
                + " from listing_publish_step_attempt where tenant_id='" + tenant
                + "' and task_id='" + targetId + "' and attempt_no=99");
    assertThat(stored).isEqualTo("2026-03-03 21:06:07.123456");

    // 🔴 发起时刻与完成时刻是同一个瞬间 ⇒ 读回来必须逐字相等
    assertThat(
            scalar(
                "select (requested_at = finished_at)::text from listing_publish_step_attempt"
                    + " where tenant_id='" + tenant + "' and task_id='" + targetId + "' and attempt_no=99"))
        .isEqualTo("true");

    // 服务层自己写的时间也必须自洽（写与读是同一个瞬间，没有 −28800s 偏移）
    as("channel.operation.update", () -> publishes.dispatch(job.jobId().toString()));
    as(
        "channel.operation.update",
        () ->
            publishes.report(
                targetId.toString(),
                new ListingPublishTargetReportRequest("FAILED", "时间往返", "", "")));
    var result = as("channel.operation.update", () -> tasks.retryStep(targetId.toString(), "SUBMIT"));
    assertThat(result.outcome()).isEqualTo("SUCCEEDED");
    var detail = as("channel.operation.read", () -> tasks.task(targetId.toString()));
    var attempt = detail.attempts().get(detail.attempts().size() - 1);
    assertThat(attempt.requestedAt()).isNotNull();
    assertThat(attempt.finishedAt()).isNotNull();
    assertThat(attempt.requestedAt()).isEqualTo(attempt.finishedAt());
    // 🔴 写进去的必须是**现在**这个瞬间：有人用 Timestamp.valueOf(LocalDateTime) 就会
    //    静默偏 −28800s（东八区），这里按「瞬间」比，偏移一定会露馅。
    assertThat(attempt.requestedAt().toInstant())
        .isCloseTo(
            java.time.Instant.now(),
            org.assertj.core.api.Assertions.within(5, java.time.temporal.ChronoUnit.MINUTES));
  }

  // ==================================================================
  // 13. 端点往返
  // ==================================================================

  @Test
  @DisplayName("4.3.1.3 端点往返：列表 / 详情 / 步骤 / 能力声明 / 重试都走真 HTTP")
  void endpointsRoundTrip() throws Exception {
    UUID shopId = seedShop(tenant, "HTTP-1");
    var job = createJob(shopId, "http");
    var targets = as("channel.operation.read", () -> publishes.targets(job.jobId().toString(), null));
    UUID targetId = targets.get(0).targetId();

    http.perform(get("/channel/listing-publish-tasks/capabilities").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phases.length()").value(8))
        .andExpect(jsonPath("$.taskKinds").isArray());

    http.perform(get("/channel/listing-publish-tasks/tasks").header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows").isArray())
        .andExpect(jsonPath("$.total").isNumber());

    http.perform(get("/channel/listing-publish-tasks/tasks/" + targetId).header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.task.taskId").value(targetId.toString()))
        .andExpect(jsonPath("$.timeline.length()").value(8))
        .andExpect(jsonPath("$.stepTimelineAvailable").value(true));

    http.perform(get("/channel/listing-publish-tasks/tasks/" + targetId + "/steps")
            .header("Authorization", bearer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(8));

    // 不存在的任务 ⇒ 404
    http.perform(get("/channel/listing-publish-tasks/tasks/" + UUID.randomUUID())
            .header("Authorization", bearer))
        .andExpect(status().isNotFound());

    // 无会话 ⇒ 401
    http.perform(get("/channel/listing-publish-tasks/tasks")).andExpect(status().isUnauthorized());
  }

}
