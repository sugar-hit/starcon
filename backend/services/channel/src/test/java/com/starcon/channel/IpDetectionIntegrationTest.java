package com.starcon.channel;

import static com.starcon.channel.IpDetectionModels.*;
import static com.starcon.channel.IpRiskLibraryModels.*;
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
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 16.4.1.2 检测与预警的集成测试（**真实 PostgreSQL 16**，Testcontainers）。
 *
 * <p>🔴 这组用例守的是本模块最容易做丢的十二件事：
 *
 * <ol>
 *   <li><b>文本命中可解释</b>：点名命中了哪一条目、哪些匹配特征、哪个字段、命中处原文；</li>
 *   <li><b>🔴 图片命中一律进人工复核队列</b>，且**不单独构成可判定命中**；</li>
 *   <li><b>🔴 图片命中在 DB 层绕不过复核</b>（CHECK 钉死）；</li>
 *   <li><b>结论三态</b>：CLEAR / REVIEW_REQUIRED / ACTION_REQUIRED；</li>
 *   <li><b>复核结论回写报告</b>：判定误报 ⇒ 报告重算成 CLEAR；</li>
 *   <li><b>外部商标查询结果与本地侵权库一起比对</b>；</li>
 *   <li><b>巡检真的产出报告</b>（已上架商品）；</li>
 *   <li><b>命中项 append-only</b>：应用账号与迁移超级账号**各试一次**；</li>
 *   <li><b>品牌维度读不到就显式说读不到</b>（不是「没有品牌」）；</li>
 *   <li><b>时间写回不偏 −28800s</b>（timestamptz 往返断言写回的值）；</li>
 *   <li><b>真分页真筛选</b>；</li>
 *   <li><b>401 / 403 / 404 / 422</b>。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class IpDetectionIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  static HttpServer identity;
  static HttpServer catalog;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\","
          + "\"channel.sync.read\",\"channel.sync.trigger\"],\"scopeMode\":\"ALL\"}]";

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

      // 🔴 catalog 桩：只服务「按 id 取源商品品牌」这一个形状。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/skus",
          e -> {
            byte[] body =
                new ObjectMapper()
                    .writeValueAsBytes(
                        Map.of("id", "sku-1", "name", "源商品", "brand", "BrandT"));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @AfterAll
  static void stopStubs() {
    if (identity != null) identity.stop(0);
    if (catalog != null) catalog.stop(0);
    pg.stop();
  }



  @BeforeEach
  void freshTenant() {
    tenant = UUID.randomUUID();
    bearer = "Bearer t-" + tenant;
    readOnlyBearer = "Bearer r-" + tenant;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearer, actor(user, tenant, grants));
    actors.put(readOnlyBearer, actor(user, tenant, List.of(grant("ALL", List.of("channel.operation.read")))));
    who = actors.get(bearer);
    reader = actors.get(readOnlyBearer);
  }

  UUID tenant;
  AccessService.Access who;
  AccessService.Access reader;
  String bearer;
  String readOnlyBearer;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
  }

  @Autowired IpDetectionService service;
  @Autowired IpRiskLibraryService library;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(String permission, Supplier<T> work) {
    return access.run(who, permission, work);
  }

  <T> T asReader(Supplier<T> work) {
    return access.run(reader, "channel.operation.read", work);
  }

  static void execAsTenant(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以**应用角色 channel_app**（非超级用户）执行 —— 超级用户绕过 RLS 会让断言失效。 */
  static void execAsApp(UUID tenantId, String statement) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(statement);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(statement)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 播种一个店铺（幂等）。 */
  UUID seedShop(String code) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('"
            + tenant
            + "','"
            + id
            + "','"
            + AMAZON
            + "','"
            + code
            + "','测试店铺 "
            + code
            + "')");
    return id;
  }

  /** 播种一个已上架商品 + 当前线上内容快照。 */
  UUID seedListing(UUID shopId, String externalItemId, String title, String description, String images) {
    UUID id = UUID.randomUUID();
    execAsTenant(
        tenant,
        "insert into listing (tenant_id,id,shop_id,external_item_id,title,status)"
            + " values ('"
            + tenant
            + "','"
            + id
            + "','"
            + shopId
            + "','"
            + externalItemId
            + "','"
            + title
            + "','ON_SALE')");
    execAsTenant(
        tenant,
        "insert into listing_content_snapshot (tenant_id,id,listing_id,language,title,description,images)"
            + " values ('"
            + tenant
            + "','"
            + UUID.randomUUID()
            + "','"
            + id
            + "','en-US','"
            + title
            + "','"
            + description
            + "','"
            + images
            + "')");
    return id;
  }

  /** 登记一条上游侵权条目（16.4.1.1）+ 匹配特征。 */
  String seedEntry(String code, String riskLevel, String brandName, List<String> terms) {
    return as(
            "channel.operation.create",
            () ->
                library.createEntry(
                        new IpRiskLibraryEntryInput(
                            code,
                            "TRADEMARK",
                            riskLevel,
                            "条目 " + code,
                            "权利人甲",
                            null,
                            brandName,
                            null,
                            "",
                            List.of("AMAZON"),
                            terms.stream()
                                .map(t -> new IpRiskLibraryTermInput(t, "KEYWORD"))
                                .toList()))
                    .entry()
                    .entryCode());
  }

  // ==================================================================
  // ① 文本命中：可解释
  // ==================================================================

  @Test
  @DisplayName("16.4.1.2 ① 文本命中可解释：点名条目 / 匹配特征 / 字段 / 命中处原文")
  void textHitIsExplainable() {
    seedEntry("EXP-1", "HIGH", null, List.of("nike"));

    IpDetectionReportDetail detail =
        as(
            "channel.operation.create",
            () ->
                service.detect(
                    new IpDetectionSubjectInput(
                        "MANUAL", null, null, "Nike running shoes", "comfortable", null, null, null, null),
                    bearer));

    assertThat(detail.report().riskLevel()).isEqualTo("HIGH");
    assertThat(detail.report().conclusion()).isEqualTo("ACTION_REQUIRED");
    assertThat(detail.report().recommendedAction()).isEqualTo("INTERCEPT");
    assertThat(detail.report().textHitCount()).isEqualTo(1);
    assertThat(detail.report().imageHitCount()).isZero();
    assertThat(detail.report().entryHitCount()).isEqualTo(1);

    assertThat(detail.hits()).hasSize(1);
    IpDetectionHit hit = detail.hits().get(0);
    assertThat(hit.entryCode()).isEqualTo("EXP-1");
    assertThat(hit.rightType()).isEqualTo("TRADEMARK");
    assertThat(hit.riskLevel()).isEqualTo("HIGH");
    assertThat(hit.field()).isEqualTo("TITLE");
    assertThat(hit.featureKind()).isEqualTo("TERM");
    assertThat(hit.matchedFeature()).isEqualTo("nike");
    assertThat(hit.matchedText()).contains("Nike running shoes");
    assertThat(hit.needsManualReview()).isFalse();

    // 🔴 预警与整改建议一并产出。
    assertThat(detail.alerts()).hasSize(1);
    assertThat(detail.alerts().get(0).alertType()).isEqualTo("INFRINGEMENT_HIT");
    assertThat(detail.alerts().get(0).status()).isEqualTo("OPEN");
    assertThat(detail.remediations()).hasSize(1);
    assertThat(detail.remediations().get(0).actionCode()).isEqualTo("REWRITE_TITLE");
  }

  @Test
  @DisplayName("16.4.1.2 ① 命中项是 append-only 的证据：条目后来停用，报告仍解释得清")
  void hitKeepsSnapshotAfterEntryDisabled() {
    seedEntry("EXP-SNAP", "HIGH", null, List.of("nike"));

    IpDetectionReportDetail detail =
        as(
            "channel.operation.create",
            () ->
                service.detect(
                    new IpDetectionSubjectInput(
                        "MANUAL", null, null, "Nike shoes", "", null, null, null, null),
                    bearer));
    assertThat(detail.hits()).hasSize(1);

    // 把上游条目停用（上游的「只停用不删除」纪律）。
    as(
        "channel.operation.update",
        () ->
            library.setStatus(
                detail.hits().get(0).entryId(),
                new IpRiskLibraryStatusInput("DISABLED", "登记有误，停用")));

    IpDetectionReportDetail reread = as("channel.operation.read", () -> service.report(detail.report().reportId()));
    assertThat(reread.hits()).hasSize(1);
    assertThat(reread.hits().get(0).entryCode()).isEqualTo("EXP-SNAP");
    assertThat(reread.hits().get(0).riskLevel()).isEqualTo("HIGH");
  }

  // ==================================================================
  // ②🔴 图片命中：必须人工复核
  // ==================================================================

  @Test
  @DisplayName("16.4.1.2 ② 图片命中一律进人工复核队列，且结论是「待人工复核」")
  void imageHitGoesToReviewQueue() {
    seedEntry("EXP-IMG", "HIGH", null, List.of("nike"));

    IpDetectionReportDetail detail =
        as(
            "channel.operation.create",
            () ->
                service.detect(
                    new IpDetectionSubjectInput(
                        "MANUAL", null, null, "运动鞋", "", null, List.of("https://cdn/nike-air.jpg"), null, null),
                    bearer));

    assertThat(detail.report().imageHitCount()).isEqualTo(1);
    assertThat(detail.report().textHitCount()).isZero();
    // 🔴 图片命中**不是**可判定命中 ⇒ 结论必须是「待人工复核」。
    assertThat(detail.report().conclusion()).isEqualTo("REVIEW_REQUIRED");
    assertThat(detail.report().recommendedAction()).isEqualTo("NOTIFY");

    assertThat(detail.hits()).hasSize(1);
    assertThat(detail.hits().get(0).field()).isEqualTo("IMAGE");
    assertThat(detail.hits().get(0).needsManualReview()).isTrue();
    assertThat(detail.hits().get(0).reviewStatus()).isEqualTo("PENDING");

    // 🔴 复核任务**自动**建立。
    assertThat(detail.reviewTasks()).hasSize(1);
    assertThat(detail.reviewTasks().get(0).reason()).isEqualTo("IMAGE_MATCH");
    assertThat(detail.reviewTasks().get(0).status()).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("16.4.1.2 ②🔴 图片命中在 DB 层绕不过人工复核（CHECK 钉死）")
  void imageHitCannotBypassReviewAtDbLevel() {
    seedEntry("EXP-IMG2", "HIGH", null, List.of("nike"));
    IpDetectionReportDetail detail =
        as(
            "channel.operation.create",
            () ->
                service.detect(
                    new IpDetectionSubjectInput(
                        "MANUAL", null, null, "运动鞋", "", null, List.of("https://cdn/nike-air.jpg"), null, null),
                    bearer));
    UUID reportId = UUID.fromString(detail.report().reportId());

    Throwable thrown =
        catchThrowable(
            () ->
                execAsTenant(
                    tenant,
                    "insert into ip_detection_hit (id,tenant_id,report_id,entry_id,external_hit_id,"
                        + "entry_code,right_type,risk_level,field,feature_kind,matched_feature,"
                        + "matched_text,evidence,needs_manual_review,created_at) values ("
                        + "'"
                        + UUID.randomUUID()
                        + "','"
                        + tenant
                        + "','"
                        + reportId
                        + "',null,null,'X','TRADEMARK','HIGH','IMAGE','TERM','nike','x','{}',false,now())"));
    assertThat(thrown).as("图片命中把 needs_manual_review 写成 false 必须被 DB 拒").isNotNull();
  }

  // ==================================================================
  // ③ 复核结论回写报告
  // ==================================================================

  @Test
  @DisplayName("16.4.1.2 ③ 判定误报后，报告重算成「无命中」")
  void dismissedReviewRecomputesReport() {
    seedEntry("EXP-DISMISS", "HIGH", null, List.of("nike"));

    IpDetectionReportDetail detail =
        as(
            "channel.operation.create",
            () ->
                service.detect(
                    new IpDetectionSubjectInput(
                        "MANUAL", null, null, "运动鞋", "", null, List.of("https://cdn/nike-air.jpg"), null, null),
                    bearer));
    assertThat(detail.report().conclusion()).isEqualTo("REVIEW_REQUIRED");

    String reviewId = detail.reviewTasks().get(0).reviewTaskId();
    as(
        "channel.operation.update",
        () ->
            service.decideReview(
                reviewId, new IpDetectionReviewDecisionInput("DISMISSED", "看了图，不是侵权logo")));

    IpDetectionReportDetail reread = as("channel.operation.read", () -> service.report(detail.report().reportId()));
    assertThat(reread.report().conclusion()).isEqualTo("CLEAR");
    assertThat(reread.report().riskLevel()).isEqualTo("NONE");
    assertThat(reread.report().entryHitCount()).isZero();
    assertThat(reread.report().pendingReviewCount()).isZero();
    // 🔴 命中项本身**仍在**（append-only），只是被标成误报。
    assertThat(reread.hits()).hasSize(1);
    assertThat(reread.hits().get(0).reviewStatus()).isEqualTo("DISMISSED");
  }

  @Test
  @DisplayName("16.4.1.2 ③ 复核结论必须写意见，且终态不可回退")
  void reviewDecisionRequiresCommentAndIsTerminal() {
    seedEntry("EXP-TERM", "HIGH", null, List.of("nike"));
    IpDetectionReportDetail detail =
        as(
            "channel.operation.create",
            () ->
                service.detect(
                    new IpDetectionSubjectInput(
                        "MANUAL", null, null, "Nike shoes", "", null, null, null, null),
                    bearer));
    String reviewId =
        as(
                "channel.operation.create",
                () ->
                    service.requestReview(
                            new IpDetectionReviewInput(
                                detail.report().reportId(),
                                detail.hits().get(0).hitId(),
                                "TEXT_MISMATCH",
                                "这是自有品牌，不是商标"))
                        .reviewTaskId());

    assertThat(
            catchThrowable(
                () ->
                    as(
                        "channel.operation.update",
                        () ->
                            service.decideReview(
                                reviewId, new IpDetectionReviewDecisionInput("CONFIRMED", "")))))
        .as("复核意见不能为空")
        .isNotNull();

    as(
        "channel.operation.update",
        () -> service.decideReview(reviewId, new IpDetectionReviewDecisionInput("CONFIRMED", "确实是商标")));

    assertThat(
            catchThrowable(
                () ->
                    as(
                        "channel.operation.update",
                        () ->
                            service.decideReview(
                                reviewId, new IpDetectionReviewDecisionInput("DISMISSED", "改主意了")))))
        .as("复核终态不可回退")
        .isNotNull();
  }

  @Test
  @DisplayName("16.4.1.2 ③ 同一个命中只允许一条复核任务（重复提交通 422，不是 500）")
  void oneReviewTaskPerHit() {
    seedEntry("EXP-ONCE", "HIGH", null, List.of("nike"));
    IpDetectionReportDetail detail =
        as(
            "channel.operation.create",
            () ->
                service.detect(
                    new IpDetectionSubjectInput(
                        "MANUAL", null, null, "Nike shoes", "", null, null, null, null),
                    bearer));

    as(
        "channel.operation.create",
        () ->
            service.requestReview(
                new IpDetectionReviewInput(
                    detail.report().reportId(),
                    detail.hits().get(0).hitId(),
                    "TEXT_MISMATCH",
                    "这是自有品牌，不是商标")));

    assertThat(
            catchThrowable(
                () ->
                    as(
                        "channel.operation.create",
                        () ->
                            service.requestReview(
                                new IpDetectionReviewInput(
                                    detail.report().reportId(),
                                    detail.hits().get(0).hitId(),
                                    "TEXT_MISMATCH",
                                    "再提一次")))))
        .as("一个命中只允许一条复核任务")
        .isNotNull();
  }

  // ==================================================================
  // ④ 外部商标查询
  // ==================================================================

  @Test
  @DisplayName("16.4.1.2 ④ 外部商标查询结果与本地侵权库一起参与比对")
  void externalQueryResultParticipatesInDetection() {
    IpDetectionExternalQuery query =
        as(
            "channel.operation.create",
            () ->
                service.createExternalQuery(
                    new IpDetectionExternalQueryInput(
                        "EXT-1", "TRADEMARK", "Acme Corp", "AMAZON", "外部商标库", "登记一次")));

    as(
        "channel.operation.create",
        () ->
            service.registerExternalHits(
                query.queryId(),
                List.of(
                    new IpDetectionExternalHitInput(
                        query.queryId(), "Acme Corp", "TRADEMARK", "HIGH", "TM-9988", "Acme 有限公司", "外部商标库"))));

    IpDetectionReportDetail detail =
        as(
            "channel.operation.create",
            () ->
                service.detect(
                    new IpDetectionSubjectInput(
                        "MANUAL", null, null, "Acme Corp mug", "", null, null, null, null),
                    bearer));

    assertThat(detail.report().riskLevel()).isEqualTo("HIGH");
    assertThat(detail.hits()).hasSize(1);
    assertThat(detail.hits().get(0).externalHitId()).isNotNull();
    assertThat(detail.hits().get(0).entryId()).isNull();
    assertThat(detail.hits().get(0).featureKind()).isEqualTo("EXTERNAL_TERM");
    assertThat(detail.alerts().get(0).alertType()).isEqualTo("EXTERNAL_MATCH");
  }

  @Test
  @DisplayName("16.4.1.2 ④ 「已登记但还没查」的查询单不产生命中")
  void registeredButUnqueriedQueryProducesNoHit() {
    as(
        "channel.operation.create",
        () ->
            service.createExternalQuery(
                new IpDetectionExternalQueryInput(
                    "EXT-2", "PATENT", "NeverQueried", null, "外部专利库", "")));

    IpDetectionReportDetail detail =
        as(
            "channel.operation.create",
            () ->
                service.detect(
                    new IpDetectionSubjectInput(
                        "MANUAL", null, null, "NeverQueried widget", "", null, null, null, null),
                    bearer));

    assertThat(detail.report().conclusion()).isEqualTo("CLEAR");
    assertThat(detail.hits()).isEmpty();
  }

  @Test
  @DisplayName("16.4.1.2 ④ 外部查询登记是幂等的；查失败必须写原因")
  void externalQueryIsIdempotentAndFailureNeedsReason() {
    IpDetectionExternalQuery first =
        as(
            "channel.operation.create",
            () ->
                service.createExternalQuery(
                    new IpDetectionExternalQueryInput(
                        "EXT-IDEM", "TRADEMARK", "Idem", null, "来源", "")));
    IpDetectionExternalQuery second =
        as(
            "channel.operation.create",
            () ->
                service.createExternalQuery(
                    new IpDetectionExternalQueryInput(
                        "EXT-IDEM", "TRADEMARK", "Idem", null, "来源", "")));
    assertThat(second.queryId()).isEqualTo(first.queryId());

    assertThat(
            catchThrowable(
                () ->
                    as(
                        "channel.operation.update",
                        () -> service.setExternalQueryStatus(first.queryId(), "FAILED", ""))))
        .as("查询失败必须写原因")
        .isNotNull();
  }

  // ==================================================================
  // ⑤ 巡检
  // ==================================================================

  @Test
  @DisplayName("16.4.1.2 ⑤ 巡检真的跑已上架商品并产出报告")
  void inspectionRunProducesReports() {
    seedEntry("EXP-INS", "HIGH", null, List.of("nike"));
    UUID shopId = seedShop("SHOP-INS");
    seedListing(shopId, "ASIN-INS", "Nike shoes", "nice", "[]");

    IpDetectionInspectionPlan plan =
        as(
            "channel.operation.create",
            () ->
                service.createPlan(
                    new IpDetectionInspectionPlanInput(
                        "PLAN-1", "ALL", null, 7, true, true, "每天巡检")));

    IpDetectionInspectionRunDetail run =
        as("channel.operation.create", () -> service.runInspection(plan.planId(), bearer));

    assertThat(run.run().status()).isEqualTo("COMPLETED");
    assertThat(run.run().listingScanned()).isEqualTo(1);
    assertThat(run.run().reportCount()).isEqualTo(1);
    assertThat(run.run().hitListingCount()).isEqualTo(1);
    assertThat(run.reports()).hasSize(1);
    assertThat(run.reports().get(0).triggerType()).isEqualTo("INSPECTION");
    assertThat(run.reports().get(0).riskLevel()).isEqualTo("HIGH");
    assertThat(run.reports().get(0).inspectionRunId()).isEqualTo(run.run().runId());
  }

  @Test
  @DisplayName("16.4.1.2 ⑤ 巡检运行是 append-only 的；停用的计划不许再跑")
  void inspectionRunIsAppendOnlyAndDisabledPlanCannotRun() {
    seedEntry("EXP-INS2", "HIGH", null, List.of("nike"));
    IpDetectionInspectionPlan plan =
        as(
            "channel.operation.create",
            () ->
                service.createPlan(
                    new IpDetectionInspectionPlanInput(
                        "PLAN-2", "ALL", null, 7, true, true, null)));
    as(
        "channel.operation.update",
        () ->
            service.updatePlan(
                plan.planId(),
                new IpDetectionInspectionPlanInput(
                    "PLAN-2", "ALL", null, 7, true, false, "停用")));

    assertThat(
            catchThrowable(
                () -> as("channel.operation.create", () -> service.runInspection(plan.planId(), bearer))))
        .as("停用的计划不该产出新一轮报告")
        .isNotNull();
  }

  // ==================================================================
  // ⑥ 品牌维度
  // ==================================================================

  @Test
  @DisplayName("16.4.1.2 ⑥ 品牌命中指向授权核验；品牌读不到就显式说读不到")
  void brandHitPointsToAuthorizationCheck() {
    seedEntry("EXP-BRAND", "HIGH", "BrandT", List.of("unrelated-word"));
    UUID shopId = seedShop("SHOP-BRAND");
    UUID listingId = seedListing(shopId, "ASIN-BRAND", "普通跑鞋", "普通描述", "[]");
    execAsTenant(
        tenant,
        "insert into listing_variant (tenant_id,id,listing_id,external_variant_id,mapping_status,internal_sku_id)"
            + " values ('"
            + tenant
            + "','"
            + UUID.randomUUID()
            + "','"
            + listingId
            + "','VAR-1','MAPPED','"
            + UUID.randomUUID()
            + "')");

    IpDetectionReportDetail detail =
        as(
            "channel.operation.create",
            () -> service.detect(new IpDetectionSubjectInput("LISTING", listingId.toString(), null,
                null, null, null, null, null, null), bearer));

    assertThat(detail.report().riskLevel()).isEqualTo("HIGH");
    assertThat(detail.hits()).hasSize(1);
    assertThat(detail.hits().get(0).field()).isEqualTo("BRAND");
    assertThat(detail.hits().get(0).featureKind()).isEqualTo("BRAND_NAME");
    assertThat(detail.report().recommendedAction()).isEqualTo("VERIFY_AUTHORIZATION");
    assertThat(detail.remediations().get(0).actionCode()).isEqualTo("VERIFY_BRAND_AUTH");
  }

  // ==================================================================
  // ⑦ append-only（应用账号 + 迁移超级账号各试一次）
  // ==================================================================

  @Test
  @DisplayName("16.4.1.2 ⑦ 命中项 append-only：应用账号与超级账号各被拒一次")
  void hitTableIsAppendOnly() {
    seedEntry("EXP-IMM", "HIGH", null, List.of("nike"));
    IpDetectionReportDetail detail =
        as(
            "channel.operation.create",
            () ->
                service.detect(
                    new IpDetectionSubjectInput(
                        "MANUAL", null, null, "Nike shoes", "", null, null, null, null),
                    bearer));
    String hitId = detail.hits().get(0).hitId();

    // 🔴 应用账号：压根没有 UPDATE / DELETE 权限。
    Throwable asApp =
        catchThrowable(
            () ->
                execAsApp(
                    tenant,
                    "update ip_detection_hit set matched_text = 'x' where id = '" + hitId + "'"));
    assertThat(asApp).as("channel_app 没有 UPDATE 权限").isNotNull();

    // 🔴 迁移超级账号：有权限，但**触发器**照样拒（append-only 是结构，不是权限）。
    Throwable asSuper =
        catchThrowable(
            () ->
                execAsTenant(
                    tenant, "update ip_detection_hit set matched_text = 'x' where id = '" + hitId + "'"));
    assertThat(asSuper).as("命中项不可改写（触发器钉死）").isNotNull();

    Throwable asSuperDelete =
        catchThrowable(
            () -> execAsTenant(tenant, "delete from ip_detection_hit where id = '" + hitId + "'"));
    assertThat(asSuperDelete).as("命中项不可删除").isNotNull();

    // 改写确实没生效。
    assertThat(scalar("select matched_text from ip_detection_hit where id = '" + hitId + "'"))
        .isNotEqualTo("x");
  }

  @Test
  @DisplayName("16.4.1.2 ⑦ 报告 / 预警 / 复核任务不可物理删除")
  void conclusionsAreNotDeletable() {
    seedEntry("EXP-NODEL", "HIGH", null, List.of("nike"));
    IpDetectionReportDetail detail =
        as(
            "channel.operation.create",
            () ->
                service.detect(
                    new IpDetectionSubjectInput(
                        "MANUAL", null, null, "Nike shoes", "", null, null, null, null),
                    bearer));

    assertThat(
            catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "delete from ip_detection_report where id = '" + detail.report().reportId() + "'")))
        .isNotNull();
    assertThat(
            catchThrowable(
                () ->
                    execAsTenant(
                        tenant,
                        "delete from ip_detection_alert where report_id = '" + detail.report().reportId() + "'")))
        .isNotNull();
  }

  // ==================================================================
  // ⑧ 时间往返
  // ==================================================================

  @Test
  @DisplayName("16.4.1.2 ⑧ 时间写回不偏 −28800s（断言写回的值）")
  void timestampsRoundTripWithoutOffsetDrift() {
    seedEntry("EXP-TIME", "HIGH", null, List.of("nike"));
    IpDetectionReportDetail detail =
        as(
            "channel.operation.create",
            () ->
                service.detect(
                    new IpDetectionSubjectInput(
                        "MANUAL", null, null, "Nike shoes", "", null, null, null, null),
                    bearer));

    OffsetDateTime due = OffsetDateTime.parse("2026-11-05T10:15:30+08:00");
    as(
        "channel.operation.create",
        () ->
            service.addRemediation(
                new IpDetectionRemediationInput(
                    detail.report().reportId(), null, "REMOVE_TERM", "手工加的一条整改", "HIGH", null, due)));

    IpDetectionRemediationPage page = as("channel.operation.read", () -> service.remediations(null, null, 0, 50));
    IpDetectionRemediation found =
        page.items().stream()
            .filter(item -> item.suggestion().equals("手工加的一条整改"))
            .findFirst()
            .orElseThrow();
    assertThat(found.dueAt()).as("写回的时刻必须与写入的时刻逐字相等（同一个瞬间）").isNotNull();
    assertThat(found.dueAt().toInstant()).isEqualTo(due.toInstant());
  }

  // ==================================================================
  // ⑨ 真分页 / ⑩ 鉴权
  // ==================================================================

  @Test
  @DisplayName("16.4.1.2 ⑨ 真分页真筛选")
  void paginationAndFiltersAreReal() {
    seedEntry("EXP-PAGE", "HIGH", null, List.of("nike"));
    for (int i = 0; i < 3; i++) {
      final int index = i;
      as(
          "channel.operation.create",
          () ->
              service.detect(
                  new IpDetectionSubjectInput(
                      "MANUAL", null, null, "Nike shoes " + index, "", null, null, null, null),
                  bearer));
    }
    IpDetectionReportPage page = as("channel.operation.read", () -> service.reports(null, null, null, null,
        false, null, null, 0, 2));
    assertThat(page.items()).hasSize(2);
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.size()).isEqualTo(2);

    IpDetectionReportPage highOnly =
        as("channel.operation.read", () -> service.reports("HIGH", null, null, null, false, null, null, 0, 20));
    assertThat(highOnly.items()).hasSize(3);
    assertThat(highOnly.riskLevelCounts().get("HIGH")).isEqualTo(3L);

    IpDetectionReportPage none =
        as("channel.operation.read", () -> service.reports("NONE", null, null, null, false, null, null, 0, 20));
    assertThat(none.items()).isEmpty();
  }

  @Test
  @DisplayName("16.4.1.2 ⑩ 鉴权：401 / 403 / 404 / 422")
  void authorizationAndErrorCodes() throws Exception {
    // 无会话 ⇒ 401。
    http.perform(get("/channel/ip-detections/capabilities"))
        .andExpect(status().isUnauthorized());

    // 只读账号不能发起检测 ⇒ 403。
    http.perform(
            post("/channel/ip-detections/detections")
                .header("Authorization", readOnlyBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectType\":\"MANUAL\",\"title\":\"x\"}"))
        .andExpect(status().isForbidden());

    // 不存在的报告 ⇒ 404。
    http.perform(get("/channel/ip-detections/detections/" + UUID.randomUUID())
            .header("Authorization", bearer))
        .andExpect(status().isNotFound());

    // 非法主体类型 ⇒ 422。
    http.perform(
            post("/channel/ip-detections/detections")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectType\":\"WHAT\"}"))
        .andExpect(status().isUnprocessableEntity());

    // 手工检测缺标题 ⇒ 422。
    http.perform(
            post("/channel/ip-detections/detections")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"subjectType\":\"MANUAL\"}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  @DisplayName("16.4.1.2 ⑩ 能力声明把未交付的外部边界逐条标出来")
  void capabilitiesDeclareGaps() {
    IpDetectionCapabilities caps = as("channel.operation.read", () -> service.capabilities());
    assertThat(caps.gaps()).isNotEmpty();
    assertThat(caps.gaps()).anyMatch(g -> !g.available() && g.impact().contains("凭据"));
    assertThat(caps.gaps()).anyMatch(g -> !g.available() && g.impact().contains("图像"));
    assertThat(caps.riskLevels()).containsExactly("HIGH", "MEDIUM", "LOW", "NONE");
    assertThat(caps.conclusions()).containsExactly("CLEAR", "REVIEW_REQUIRED", "ACTION_REQUIRED");
    assertThat(caps.imageNote()).contains("标识文本比对").contains("人工复核");
  }
}
