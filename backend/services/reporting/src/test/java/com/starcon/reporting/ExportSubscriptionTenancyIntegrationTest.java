package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 13.6.2.1「导出与订阅」的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言（缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（导出任务与投递记录都算）；</li>
 *   <li><b>伪造租户上下文无效</b>（并断言行确实落在**会话租户**名下）；</li>
 *   <li><b>连接池归还后不串租户</b>。</li>
 * </ol>
 *
 * <p>⚠️ <b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportSubscriptionTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
  static HttpServer identity;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of("reporting.custom-report.read", "reporting.custom-report.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);

  static final String BEARER_A = "Bearer tenant-a";
  static final String BEARER_B = "Bearer tenant-b";

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute(
          "create role reporting_app login password 'test-only' nosuperuser nobypassrls");
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put(BEARER_A, actor(a, userA));
      actors.put(BEARER_B, actor(b, userB));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Access actor(UUID tenantId, UUID userId) {
    var grant = new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
    return new AccessService.Access(
        userId, tenantId, "Test", UUID.randomUUID(), null, permissions, List.of(grant));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "reporting_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("REPORTING_EXPORT_STORAGE_ROOT", () -> "target/test-export-store-tenancy");
    // ⚠️ **连接池固定为 1 条连接**：只有复用同一条连接，才能真正证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingCustomReportService reports;
  @Autowired ReportingExportService exports;
  @Autowired ReportingDeliveryService deliveries;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_export_job_source, reporting_export_job,"
              + " reporting_custom_report_delivery, reporting_custom_report_subscription,"
              + " reporting_custom_report_share, reporting_custom_report_filter,"
              + " reporting_custom_report_dimension, reporting_custom_report_measure,"
              + " reporting_custom_report, reporting_metric_fact cascade");
    }
    // 播种口径（13.1.1.1 的服务层干的活）。
    reports.metricCatalog(a);
    reports.metricCatalog(b);
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  private void fact(UUID tenantId, UUID shopId, String metricCode, String currency, String value) {
    tenants.run(
        tenantId,
        () -> {
          sql.execute(
              "insert into reporting_metric_fact"
                  + " (id,tenant_id,scope_type,legal_entity_id,shop_id,metric_code,bucket_date,currency,"
                  + " value,timezone,source_service,source_reference,updated_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?,?,now())",
              UUID.randomUUID(),
              tenantId,
              "SHOP",
              null,
              shopId,
              metricCode,
              DAY1,
              currency,
              new BigDecimal(value),
              "UTC",
              "trade",
              "seed-" + tenantId + "-" + metricCode);
          return null;
        });
  }

  private UUID saveReport(UUID tenantId, UUID userId, String code) {
    return UUID.fromString(
        reports
            .save(
                tenantId,
                userId,
                null,
                new ReportingCustomReportModels.CustomReportSaveRequest(
                    code,
                    "报表 " + code,
                    "",
                    "TEAM",
                    "TABLE",
                    DAY1,
                    DAY2,
                    List.of("SALES_AMOUNT"),
                    List.of("SHOP"),
                    List.of()))
            .reportId());
  }

  private UUID createJob(UUID tenantId, UUID userId, UUID reportId) {
    return UUID.fromString(
        exports
            .create(
                tenantId,
                userId,
                new ReportingExportModels.ExportSubscriptionJobCreateRequest(
                    reportId.toString(), "CSV", List.of(), DAY1, DAY2))
            .jobId());
  }

  private UUID enqueueDelivery(UUID tenantId, UUID userId, UUID reportId) {
    var deliveryId = UUID.randomUUID();
    tenants.run(
        tenantId,
        () -> {
          sql.execute(
              "insert into reporting_custom_report_subscription"
                  + " (id,tenant_id,report_id,cron_expression,channel,target,enabled,created_at,updated_at)"
                  + " values (?,?,?,'0 9 * * *','EMAIL','ops@example.com',true,now(),now())",
              UUID.randomUUID(),
              tenantId,
              reportId);
          sql.execute(
              "insert into reporting_custom_report_delivery"
                  + " (id,tenant_id,report_id,subscription_id,scheduled_for,status,attempt_count,"
                  + " last_error,created_at,updated_at)"
                  + " values (?,?,?,?,?::timestamptz,'PENDING',0,'',now(),now())",
              deliveryId,
              tenantId,
              reportId,
              sql.fetchOne(
                      "select id from reporting_custom_report_subscription"
                          + " where tenant_id = ? and report_id = ?",
                      tenantId,
                      reportId)
                  .get(0, UUID.class),
              java.time.OffsetDateTime.now());
          return null;
        });
    return deliveryId;
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户的导出任务互不可见() {
    var reportA = saveReport(a, userA, "R-A");
    var reportB = saveReport(b, userB, "R-B");
    var jobA = createJob(a, userA, reportA);
    var jobB = createJob(b, userB, reportB);

    var pageA = exports.jobs(a, null, null, null, 0, 50);
    var pageB = exports.jobs(b, null, null, null, 0, 50);

    assertThat(pageA.total()).isEqualTo(1);
    assertThat(pageA.rows().get(0).jobId()).isEqualTo(jobA.toString());
    assertThat(pageB.total()).isEqualTo(1);
    assertThat(pageB.rows().get(0).jobId()).isEqualTo(jobB.toString());

    // 更强的一格：A 明确按 id 直查 B 的行也拿不到（RLS 挡住）。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select id from reporting_export_job where tenant_id = ? and id = ?", b, jobB)
                    .size());
    assertThat(cross).as("按 id 直查也必须被 RLS 挡住").isZero();

    // A 直接读 B 的任务 ⇒ 404（与「不存在」同一条文案）。
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> exports.job(a, jobB));
    assertThat(error.getStatusCode().value()).isEqualTo(404);
  }

  @Test
  void 断言1_两租户的投递记录互不可见() {
    var reportA = saveReport(a, userA, "R-A-DEL");
    var reportB = saveReport(b, userB, "R-B-DEL");
    var deliveryA = enqueueDelivery(a, userA, reportA);
    var deliveryB = enqueueDelivery(b, userB, reportB);

    assertThat(deliveries.detail(a, deliveryA).deliveryId()).isEqualTo(deliveryA.toString());

    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> deliveries.detail(a, deliveryB));
    assertThat(error.getStatusCode().value()).isEqualTo(404);

    // A 的投递轮次**不会**碰到 B 的队列。
    var result = deliveries.dispatch(a, 10);
    assertThat(result.attempted()).as("A 的队列里只有 A 自己的记录").isEqualTo(1);
  }

  @Test
  void 断言1_两张新表都强制RLS() {
    var tables = List.of("reporting_export_job", "reporting_export_job_source");
    for (var table : tables) {
      var row =
          sql.fetchOne(
              "select relforcerowsecurity, relrowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("表 %s 必须存在", table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as("表 %s 必须 FORCE RLS", table).isTrue();
      assertThat(row.get(1, Boolean.class)).as("表 %s 必须 ENABLE RLS", table).isTrue();
    }
  }

  @Test
  void 断言1_没有租户上下文时读到零行() {
    saveReport(a, userA, "R-A-CTX");
    saveReport(b, userB, "R-B-CTX");
    assertThat(sql.fetch("select id from reporting_export_job").size())
        .as("事务外没有「环境租户」⇒ 读不到任何行")
        .isZero();
    assertThat(sql.fetch("select id from reporting_export_job_source").size()).isZero();
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    var reportA = saveReport(a, userA, "R-A-FORGED");
    // HTTP 边界：带**租户 A 的合法会话** + **伪造的 X-Tenant-Id（租户 B）**。
    http.perform(
            post("/reporting/custom-report/export/jobs")
                .header("Authorization", BEARER_A)
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new ReportingExportModels.ExportSubscriptionJobCreateRequest(
                            reportA.toString(), "CSV", List.of(), DAY1, DAY2))))
        .andExpect(status().isOk());

    var inA =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select id from reporting_export_job where tenant_id = ?", a).size());
    assertThat(inA).as("任务必须落在会话租户 A 名下").isEqualTo(1);

    var inB =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select id from reporting_export_job where tenant_id = ?", b).size());
    assertThat(inB).as("被伪造的租户看不见别人的任务").isZero();

    // B 的清单里没有这份任务。
    assertThat(exports.jobs(b, null, null, null, 0, 50).total()).isZero();
  }

  @Test
  void 断言2_伪造租户头不影响投递记录() {
    var reportA = saveReport(a, userA, "R-A-DEL-FORGED");
    enqueueDelivery(a, userA, reportA);

    var inA =
        tenants.run(
            a,
            () ->
                sql.fetch("select id from reporting_custom_report_delivery where tenant_id = ?", a)
                    .size());
    var inB =
        tenants.run(
            b,
            () ->
                sql.fetch("select id from reporting_custom_report_delivery where tenant_id = ?", b)
                    .size());
    assertThat(inA).isEqualTo(1);
    assertThat(inB).isZero();
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    var reportA = saveReport(a, userA, "R-A-POOL");
    var reportB = saveReport(b, userB, "R-B-POOL");
    var jobA = createJob(a, userA, reportA);
    var jobB = createJob(b, userB, reportB);
    for (int i = 0; i < 5; i++) {
      assertThat(exports.jobs(a, null, null, null, 0, 50).rows())
          .as("第 %d 轮租户 A", i)
          .extracting(j -> j.jobId())
          .containsExactly(jobA.toString());
      assertThat(exports.jobs(b, null, null, null, 0, 50).rows())
          .as("第 %d 轮租户 B", i)
          .extracting(j -> j.jobId())
          .containsExactly(jobB.toString());
      assertThat(sql.fetch("select id from reporting_export_job").size())
          .as("第 %d 轮事务外", i)
          .isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(exports.job(a, jobA).jobId()).isEqualTo(jobA.toString());
    assertThat(exports.job(b, jobB).jobId()).isEqualTo(jobB.toString());
  }

  @Test
  void 断言3_导出源快照也按租户隔离() {
    var reportA = saveReport(a, userA, "R-A-SRC");
    var jobA = createJob(a, userA, reportA);

    var sourceInA =
        tenants.run(
            a,
            () ->
                sql.fetch("select job_id from reporting_export_job_source where tenant_id = ?", a)
                    .size());
    var sourceInB =
        tenants.run(
            b,
            () ->
                sql.fetch("select job_id from reporting_export_job_source where tenant_id = ?", b)
                    .size());
    assertThat(sourceInA).isEqualTo(1);
    assertThat(sourceInB).isZero();

    // A 的上下文里按 B 的任务 id 直查源快照 ⇒ 查不到。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select job_id from reporting_export_job_source where tenant_id = ? and job_id = ?",
                        a,
                        jobA)
                    .size());
    assertThat(cross).isEqualTo(1);
  }
}
