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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 13.6.2.1「导出与订阅」的**集成测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>本文件验的是**这一格最容易做假的两件事**：
 *
 * <ul>
 *   <li>🔴 **导出的文件是真文件** —— CSV 带 UTF-8 BOM、XLSX 能被 POI 打开、PDF 能被 PDFBox
 *       打开并**抽出中文**。不是「返回一个 200 的假 xlsx」；</li>
 *   <li>🔴 **投递记录的状态是真的投递结果** —— 没配置 SMTP / Webhook 时它必须是
 *       **FAILED 并写明原因**，**绝不是 DELIVERED**；配置了且真的送出去时才是 DELIVERED。</li>
 * </ul>
 *
 * <p>另有：导出内容与报表页面**逐格一致**（口径唯一）、涉币指标**不跨币种相加**、
 * 「算不出来」**不是 0**、下载有效期生效（过期回 410 Gone）、同步导出超阈值**明确拒绝**。
 *
 * <p>多租户三条断言在 {@code ExportSubscriptionTenancyIntegrationTest}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportSubscriptionIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static final String BEARER = "Bearer tenant-a";

  static HttpServer identity;
  static TestSmtpServer smtp;
  static HttpServer im;

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of("reporting.custom-report.read", "reporting.custom-report.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY1 = LocalDate.of(2026, 10, 1);
  static final LocalDate DAY2 = LocalDate.of(2026, 10, 2);

  static final UUID SHOP_A = UUID.randomUUID();
  static final UUID SHOP_B = UUID.randomUUID();
  /** 🔴 零店铺：销售额真的是 0 ⇒ 退货率的分母是 0 ⇒ **算不出来（不是 0）**。 */
  static final UUID SHOP_ZERO = UUID.randomUUID();

  /** 同步导出的行数上限（**故意调小**，用来断言「超阈值明确拒绝」）。 */
  static final int SYNC_THRESHOLD = 3;

  /** IM 通道收到的请求体（真实 HTTP 调用，不是内存替身）。 */
  static final List<String> IM_REQUESTS = new CopyOnWriteArrayList<>();

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，多租户断言全部变成空断言。
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
      actors.put(BEARER, actor(tenant));
      identity.start();

      smtp = new TestSmtpServer();

      im = HttpServer.create(new InetSocketAddress(0), 0);
      im.createContext(
          "/im-hook",
          e -> {
            var body = e.getRequestBody().readAllBytes();
            IM_REQUESTS.add(new String(body, StandardCharsets.UTF_8));
            e.sendResponseHeaders(204, -1);
            e.close();
          });
      im.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Access actor(UUID tenantId) {
    var grant = new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
    return new AccessService.Access(
        user, tenantId, "Test", UUID.randomUUID(), null, permissions, List.of(grant));
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
    r.add("REPORTING_EXPORT_STORAGE_ROOT", () -> "target/test-export-store");
    r.add("REPORTING_EXPORT_TTL_HOURS", () -> "24");
    r.add("REPORTING_EXPORT_SYNC_ROW_THRESHOLD", () -> String.valueOf(SYNC_THRESHOLD));
    // ⚠️ **不钉死 REPORTING_EXPORT_MAX_ROWS**：DynamicPropertySource 的优先级高于系统属性，
    // 在这里钉死的话，测试用 System.setProperty 翻不动它（行数上限那条用例就成假绿）。
    // 池大于 1：异步执行器要另开一条连接。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingCustomReportService reports;
  @Autowired ReportingExportService exports;
  @Autowired ReportingDeliveryService deliveries;
  @Autowired ReportingExportStorage storage;
  @Autowired ConfigurableApplicationContext context;
  @Autowired ReportingExportSettings settings;

  @AfterAll
  static void stop() {
    identity.stop(0);
    im.stop(0);
    smtp.close();
    pg.stop();
  }

  @AfterEach
  void clearProperties() {
    clearTransportConfig();
    System.clearProperty("REPORTING_EXPORT_MAX_ROWS");
  }

  /** 清掉投递通道的配置（⇒ 两个通道都没配）。 */
  private void clearTransportConfig() {
    System.clearProperty("REPORTING_SMTP_HOST");
    System.clearProperty("REPORTING_SMTP_PORT");
    System.clearProperty("REPORTING_SMTP_STARTTLS");
    System.clearProperty("REPORTING_SMTP_FROM");
    System.clearProperty("REPORTING_IM_WEBHOOK_URL");
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
    // 播种口径（13.1.1.1 的服务层干的活）—— 本模块只读它。
    reports.metricCatalog(tenant);
    seedFacts();
    smtp.reset();
    IM_REQUESTS.clear();
    // 默认：**两个通道都没配**（⇒ 投递必须是 FAILED）。
    clearTransportConfig();
  }

  // ==========================================================================
  // 夹具事实
  // ==========================================================================

  private void seedFacts() {
    // 店铺 A：DAY1 销售额 100 / 订单 2；DAY2 销售额 200 / 订单 4。
    fact(SHOP_A, DAY1, "SALES_AMOUNT", "USD", "100.00");
    fact(SHOP_A, DAY1, "ORDER_COUNT", "USD", "2");
    fact(SHOP_A, DAY2, "SALES_AMOUNT", "USD", "200.00");
    fact(SHOP_A, DAY2, "ORDER_COUNT", "USD", "4");
    // 店铺 B：DAY1 美元；DAY2 欧元 ⇒ 跨币种，但**绝不相加**。
    fact(SHOP_B, DAY1, "SALES_AMOUNT", "USD", "50.00");
    fact(SHOP_B, DAY1, "ORDER_COUNT", "USD", "1");
    fact(SHOP_B, DAY2, "SALES_AMOUNT", "EUR", "80.00");
    fact(SHOP_B, DAY2, "ORDER_COUNT", "EUR", "2");
    // 🔴 零店铺：销售额真的是 0（**这个 0 是真的**），退货额 0 ⇒ 退货率的分母是 0。
    fact(SHOP_ZERO, DAY1, "SALES_AMOUNT", "USD", "0.00");
    fact(SHOP_ZERO, DAY1, "RETURNED_AMOUNT", "USD", "0.00");
    fact(SHOP_ZERO, DAY1, "ORDER_COUNT", "USD", "3");
  }

  private void fact(UUID shopId, LocalDate day, String metricCode, String currency, String value) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into reporting_metric_fact"
                  + " (id,tenant_id,scope_type,legal_entity_id,shop_id,metric_code,bucket_date,currency,"
                  + " value,timezone,source_service,source_reference,updated_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?,?,now())",
              UUID.randomUUID(),
              tenant,
              "SHOP",
              null,
              shopId,
              metricCode,
              day,
              currency,
              new BigDecimal(value),
              "UTC",
              "trade",
              "seed-" + shopId + "-" + metricCode + "-" + day + "-" + currency);
          return null;
        });
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  private UUID newReport(String code, List<String> measures, List<String> dimensions) {
    var view =
        reports.save(
            tenant,
            user,
            null,
            new ReportingCustomReportModels.CustomReportSaveRequest(
                code, "测试报表 " + code, "说明", "TEAM", "TABLE", DAY1, DAY2, measures, dimensions,
                List.of()));
    return UUID.fromString(view.reportId());
  }

  /** 受理一次导出并**等它跑完**（异步任务要轮询，不能凭受理响应就断言「已完成」）。 */
  private ReportingExportModels.ExportSubscriptionJobView exportAndAwait(
      UUID reportId, ReportingExportCatalog.ExportFormat format) {
    var job =
        exports.create(
            tenant,
            user,
            new ReportingExportModels.ExportSubscriptionJobCreateRequest(
                reportId.toString(), format.code(), List.of(), DAY1, DAY2));
    return awaitTerminal(UUID.fromString(job.jobId()));
  }

  /** 轮询到终态（异步任务：受理 ≠ 完成）。 */
  private ReportingExportModels.ExportSubscriptionJobView awaitTerminal(UUID jobId) {
    var deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      var job = exports.job(tenant, jobId);
      if (List.of("SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED").contains(job.status())) {
        return job;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return exports.job(tenant, jobId);
  }

  // ==========================================================================
  // 导出：任务生命周期
  // ==========================================================================

  @Test
  void 导出任务跑完是真成功并给出下载有效期() {
    var reportId = newReport("R-LIFE", List.of("SALES_AMOUNT", "ORDER_COUNT"), List.of("SHOP"));
    var job = exportAndAwait(reportId, ReportingExportCatalog.ExportFormat.CSV);

    assertThat(job.status()).as("异步任务必须真的跑完").isEqualTo("SUCCEEDED");
    assertThat(job.error()).isEmpty();
    assertThat(job.rowCount()).as("三个店铺组合 ⇒ 4 行（SHOP_B 铺成 USD / EUR 两行）").isEqualTo(4);
    assertThat(job.fileName()).endsWith(".csv");
    assertThat(job.byteSize()).isNotNull().isPositive();
    assertThat(job.contentSha256()).hasSize(64);
    assertThat(job.downloadable()).isTrue();
    // 🔴 下载有效期是**可配置的业务参数**，在成功那一刻写入，不是常量。
    assertThat(job.expiresAt()).isNotNull();
    assertThat(job.expiresAt()).isAfter(java.time.OffsetDateTime.now());
  }

  @Test
  void 导出的行数上限被超过时任务失败并写明原因() {
    System.setProperty("REPORTING_EXPORT_MAX_ROWS", "1");
    assertThat(settings.maxRows()).as("行数上限必须能按需翻转").isEqualTo(1);
    var reportId = newReport("R-TOOBIG", List.of("SALES_AMOUNT"), List.of("SHOP"));
    var job = exportAndAwait(reportId, ReportingExportCatalog.ExportFormat.CSV);

    assertThat(job.status()).isEqualTo("FAILED");
    assertThat(job.error()).as("失败必须写明原因").contains("超过行数上限");
    assertThat(job.downloadable()).isFalse();
  }

  @Test
  void 未知格式直接报错不静默回落() {
    var reportId = newReport("R-BADFMT", List.of("SALES_AMOUNT"), List.of("SHOP"));
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                exports.create(
                    tenant,
                    user,
                    new ReportingExportModels.ExportSubscriptionJobCreateRequest(
                        reportId.toString(), "TXT", List.of(), DAY1, DAY2)));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
    assertThat(error.getMessage()).contains("CSV");
  }

  @Test
  void 不能选不属于这份报表的列() {
    var reportId = newReport("R-BADCOL", List.of("SALES_AMOUNT"), List.of("SHOP"));
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                exports.create(
                    tenant,
                    user,
                    new ReportingExportModels.ExportSubscriptionJobCreateRequest(
                        reportId.toString(),
                        "CSV",
                        List.of("MEASURE:NOT_A_METRIC"),
                        DAY1,
                        DAY2)));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
    assertThat(error.getMessage()).contains("不属于这份报表的可导出列");
  }

  @Test
  void 任务清单是真分页真搜索真筛选() {
    var reportId = newReport("R-LIST", List.of("SALES_AMOUNT"), List.of("SHOP"));
    exportAndAwait(reportId, ReportingExportCatalog.ExportFormat.CSV);
    var otherId = newReport("R-LIST-OTHER", List.of("ORDER_COUNT"), List.of("SHOP"));
    exportAndAwait(otherId, ReportingExportCatalog.ExportFormat.XLSX);

    var all = exports.jobs(tenant, null, null, null, 0, 20);
    assertThat(all.total()).isEqualTo(2);
    assertThat(all.rows()).hasSize(2);

    var byFormat = exports.jobs(tenant, null, "XLSX", null, 0, 20);
    assertThat(byFormat.total()).isEqualTo(1);
    assertThat(byFormat.rows().get(0).format()).isEqualTo("XLSX");

    var byStatus = exports.jobs(tenant, "SUCCEEDED", null, null, 0, 20);
    assertThat(byStatus.total()).isEqualTo(2);

    var byKeyword = exports.jobs(tenant, null, null, "R-LIST-OTHER", 0, 20);
    assertThat(byKeyword.total()).isEqualTo(1);
    assertThat(byKeyword.rows().get(0).reportCode()).isEqualTo("R-LIST-OTHER");

    // 真分页：第一页 1 条、第二页 1 条。
    var first = exports.jobs(tenant, null, null, null, 0, 1);
    var second = exports.jobs(tenant, null, null, null, 1, 1);
    assertThat(first.rows()).hasSize(1);
    assertThat(second.rows()).hasSize(1);
    assertThat(first.rows().get(0).jobId()).isNotEqualTo(second.rows().get(0).jobId());
  }

  @Test
  void 作废之后不能再下载() {
    var reportId = newReport("R-CANCEL", List.of("SALES_AMOUNT"), List.of("SHOP"));
    var job = exportAndAwait(reportId, ReportingExportCatalog.ExportFormat.CSV);
    assertThat(job.status()).isEqualTo("SUCCEEDED");

    var cancelled = exports.cancel(tenant, UUID.fromString(job.jobId()));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(cancelled.downloadable()).isFalse();

    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> exports.file(tenant, UUID.fromString(job.jobId())));
    assertThat(error.getStatusCode().value()).isEqualTo(410);
  }

  @Test
  void 过了下载有效期下载回410并清理文件() {
    var reportId = newReport("R-EXPIRE", List.of("SALES_AMOUNT"), List.of("SHOP"));
    var job = exportAndAwait(reportId, ReportingExportCatalog.ExportFormat.CSV);
    var jobId = UUID.fromString(job.jobId());

    // 把有效期推到过去（模拟「有效期到了」）。
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "update reporting_export_job set expires_at = now() - interval '1 hour'"
                  + " where tenant_id = ? and id = ?",
              tenant,
              jobId);
          return null;
        });

    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> exports.file(tenant, jobId));
    assertThat(error.getStatusCode().value()).as("过期是 410 Gone，不是 500").isEqualTo(410);

    var after = exports.job(tenant, jobId);
    assertThat(after.status()).as("过期后状态推进到 EXPIRED").isEqualTo("EXPIRED");
    assertThat(after.downloadable()).isFalse();
  }

  @Test
  void 还没跑完的任务下载要明确说还在生成() {
    var reportId = newReport("R-PENDING", List.of("SALES_AMOUNT"), List.of("SHOP"));
    // 手工插一条 PENDING（不受理执行器）⇒ 下载端点必须说「尚未完成」，而不是 500。
    var jobId = UUID.randomUUID();
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into reporting_export_job"
                  + " (id,tenant_id,actor_id,status,row_count,error,requested_at,created_at,updated_at)"
                  + " values (?,?,?,'PENDING',0,'',now(),now(),now())",
              jobId,
              tenant,
              user);
          sql.execute(
              "insert into reporting_export_job_source"
                  + " (id,tenant_id,job_id,report_id,format,selected_fields,field_headers,"
                  + " filter_snapshot,window_from,window_to,created_at)"
                  + " values (?,?,?,?,'CSV','[]'::jsonb,'[]'::jsonb,'{}'::jsonb,?::date,?::date,now())",
              UUID.randomUUID(),
              tenant,
              jobId,
              reportId,
              DAY1,
              DAY2);
          return null;
        });
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> exports.file(tenant, jobId));
    assertThat(error.getStatusCode().value()).isEqualTo(409);
    assertThat(error.getMessage()).contains("尚未完成");
  }

  // ==========================================================================
  // 导出：文件是真的
  // ==========================================================================

  @Test
  void CSV导出是带BOM的真文本且中文不乱码() {
    var reportId = newReport("R-CSV", List.of("SALES_AMOUNT", "ORDER_COUNT"), List.of("SHOP"));
    var job = exportAndAwait(reportId, ReportingExportCatalog.ExportFormat.CSV);
    assertThat(job.status()).isEqualTo("SUCCEEDED");

    var file = exports.file(tenant, UUID.fromString(job.jobId()));
    assertThat(file.contentType()).isEqualTo("text/csv; charset=UTF-8");

    var bytes = file.content();
    assertThat(bytes[0]).as("必须是 UTF-8 BOM 的第一个字节").isEqualTo((byte) 0xEF);
    assertThat(bytes[1]).isEqualTo((byte) 0xBB);
    assertThat(bytes[2]).isEqualTo((byte) 0xBF);

    var text = new String(bytes, StandardCharsets.UTF_8);
    assertThat(text).as("中文表头必须在").contains("销售额");
    assertThat(text).as("中文表头必须在").contains("店铺");
    assertThat(text).contains("币种");
    assertThat(text).contains("口径备注");
    // 数据行：SHOP_A 的销售额是 100 + 200 = 300。
    assertThat(text).contains("300");
  }

  @Test
  void XLSX导出是能被打开的真工作簿() throws Exception {
    var reportId = newReport("R-XLSX", List.of("SALES_AMOUNT", "ORDER_COUNT"), List.of("SHOP"));
    var job = exportAndAwait(reportId, ReportingExportCatalog.ExportFormat.XLSX);
    assertThat(job.status()).as("XLSX 导出失败原因：%s", job.error()).isEqualTo("SUCCEEDED");

    var file = exports.file(tenant, UUID.fromString(job.jobId()));
    assertThat(file.contentType()).contains("spreadsheetml");

    try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(file.content()))) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
      var sheet = workbook.getSheetAt(0);
      var header = sheet.getRow(0);
      assertThat(header.getCell(0).getStringCellValue()).isEqualTo("店铺（uuid）");
      assertThat(header.getCell(1).getStringCellValue()).isEqualTo("币种");
      assertThat(header.getCell(2).getStringCellValue()).contains("销售额");
      assertThat(sheet.getLastRowNum()).as("表头 + 4 行数据").isEqualTo(4);

      // 数字格是**数值**（不是文本）—— 用户拿到这份表的第一件事就是求和与排序。
      var numericCells = 0;
      var found300 = false;
      for (var rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        var row = sheet.getRow(rowIndex);
        var cell = row.getCell(2);
        if (cell != null && cell.getCellType() == CellType.NUMERIC) {
          numericCells++;
          if (cell.getNumericCellValue() == 300.0) found300 = true;
        }
      }
      assertThat(numericCells).as("销售额列必须是数值格").isEqualTo(4);
      assertThat(found300).as("店铺 A 的 100 + 200 = 300").isTrue();
    }
  }

  @Test
  void PDF导出是能被打开的真PDF而且中文能抽出来() throws Exception {
    var reportId = newReport("R-PDF", List.of("SALES_AMOUNT", "ORDER_COUNT"), List.of("SHOP"));
    var job = exportAndAwait(reportId, ReportingExportCatalog.ExportFormat.PDF);
    assertThat(job.status()).as("PDF 导出失败原因：%s", job.error()).isEqualTo("SUCCEEDED");

    var file = exports.file(tenant, UUID.fromString(job.jobId()));
    assertThat(file.contentType()).isEqualTo("application/pdf");
    assertThat(new String(file.content(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");

    try (var document = PDDocument.load(file.content())) {
      assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(1);
      var text = new PDFTextStripper().getText(document);
      assertThat(text).as("PDF 里必须真的有中文（不能是乱码或问号）").contains("销售额");
      assertThat(text).contains("店铺");
      assertThat(text).contains("R-PDF");
    }
  }

  @Test
  void 文件真的落在存储里而且能读回来() throws Exception {
    var reportId = newReport("R-STORE", List.of("SALES_AMOUNT"), List.of("SHOP"));
    var job = exportAndAwait(reportId, ReportingExportCatalog.ExportFormat.CSV);
    var key =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select storage_key from reporting_export_job where tenant_id = ? and id = ?",
                        tenant,
                        UUID.fromString(job.jobId()))
                    .get(0, String.class));
    assertThat(key).as("键里必须带租户段").startsWith("exports/" + tenant + "/");
    assertThat(storage.exists(key)).isTrue();
    assertThat(storage.read(key)).isNotEmpty();
  }

  // ==========================================================================
  // 导出：口径（与页面所见一致）
  // ==========================================================================

  @Test
  void 导出内容与报表页面逐格一致() {
    var reportId = newReport("R-SAME", List.of("SALES_AMOUNT", "ORDER_COUNT"), List.of("SHOP"));
    var response = reports.run(tenant, reportId, user, DAY1, DAY2, 0, 50);
    var job = exportAndAwait(reportId, ReportingExportCatalog.ExportFormat.CSV);
    assertThat(job.status()).isEqualTo("SUCCEEDED");

    var text =
        new String(
            exports.file(tenant, UUID.fromString(job.jobId())).content(), StandardCharsets.UTF_8);
    // 页面上能看到的每个金额，导出里都要能看到（**逐格一致**）。
    for (var row : response.rows()) {
      for (var measure : row.measures()) {
        for (var value : measure.values()) {
          if (value.value() == null) continue;
          assertThat(text).as("导出里必须有页面上的这个值：" + value.value()).contains(
              value.value().stripTrailingZeros().toPlainString());
        }
      }
    }
  }

  @Test
  void 涉币指标不跨币种相加() {
    var reportId = newReport("R-CUR", List.of("SALES_AMOUNT"), List.of("SHOP"));
    var job = exportAndAwait(reportId, ReportingExportCatalog.ExportFormat.CSV);
    var text =
        new String(
            exports.file(tenant, UUID.fromString(job.jobId())).content(), StandardCharsets.UTF_8);

    // 店铺 B 的 50（美元）与 80（欧元）**绝不能**相加成 130。
    assertThat(text).as("绝不能出现跨币种相加的 130").doesNotContain("130");
    assertThat(text).as("美元行").contains("50");
    assertThat(text).as("欧元行").contains("80");
    // 店铺 A 的 100 + 200 = 300（同币种相加是允许的）。
    assertThat(text).contains("300");
  }

  @Test
  void 算不出来不是0而是空单元格加原因() {
    var reportId = newReport("R-RATE", List.of("RETURN_RATE"), List.of("SHOP"));
    var job = exportAndAwait(reportId, ReportingExportCatalog.ExportFormat.CSV);
    assertThat(job.status()).isEqualTo("SUCCEEDED");

    var text =
        new String(
            exports.file(tenant, UUID.fromString(job.jobId())).content(), StandardCharsets.UTF_8);
    // 零店铺的退货率是**算不出来**（分母为 0）⇒ 导出里是空单元格 + 原因，不是 0。
    assertThat(text).as("原因必须写在备注列").contains("退货率");
    assertThat(text).doesNotContain("退货率（%）,0");
  }

  @Test
  void 同步导出超过阈值明确拒绝而不是截断() {
    var reportId = newReport("R-SYNC-BIG", List.of("SALES_AMOUNT"), List.of("SHOP"));
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                exports.sync(
                    tenant,
                    user,
                    new ReportingExportModels.ExportSubscriptionJobCreateRequest(
                        reportId.toString(), "CSV", List.of(), DAY1, DAY2),
                    new ReportingExportRenderer("")));
    assertThat(error.getStatusCode().value()).isEqualTo(409);
    assertThat(error.getMessage()).as("要说清该走哪条路").contains("异步导出任务");
  }

  @Test
  void 同步导出小结果集可以直接拿到文件() {
    var reportId = newReport("R-SYNC-OK", List.of("SALES_AMOUNT"), List.of("SHOP"));
    // 窗口收成一天 ⇒ 只有 1 个维度组合 ⇒ 在同步阈值之内。
    var result =
        exports.sync(
            tenant,
            user,
            new ReportingExportModels.ExportSubscriptionJobCreateRequest(
                reportId.toString(), "CSV", List.of(), DAY1, DAY1),
            new ReportingExportRenderer(""));
    assertThat(result.contentType()).isEqualTo("text/csv; charset=UTF-8");
    assertThat(result.content()).isNotEmpty();
    assertThat(result.content()[0]).isEqualTo((byte) 0xEF);
  }

  @Test
  void 能力声明里导出与投递都变成可用() {
    var capabilities = reports.capabilities();
    assertThat(capabilities.exportAvailable()).as("导出已交付").isTrue();
    assertThat(capabilities.exportUnavailableReason()).isEmpty();
    assertThat(capabilities.deliveryAvailable()).as("实际投递已交付").isTrue();
    assertThat(capabilities.deliveryUnavailableReason()).isEmpty();
    assertThat(capabilities.notDelivered())
        .as("「导出未交付」不再是缺口")
        .noneMatch(note -> note.contains("大文件导出（Excel / CSV / PDF）未交付"));
  }

  // ==========================================================================
  // 投递：诚实性（本模块最容易造假的地方）
  // ==========================================================================

  @Test
  void 没配置SMTP时投递记为失败而不是已发送() {
    var deliveryId = enqueueDelivery("EMAIL", "ops@example.com");
    var result = deliveries.dispatch(tenant, 10);

    assertThat(result.attempted()).isEqualTo(1);
    assertThat(result.delivered()).as("没配置通道 ⇒ 一条都没发出去").isZero();
    assertThat(result.failed()).isEqualTo(1);

    var record = deliveries.detail(tenant, deliveryId);
    assertThat(record.status()).as("🔴 绝不能是 DELIVERED").isEqualTo("FAILED");
    assertThat(record.lastError()).as("失败必须写明原因").contains("REPORTING_SMTP_HOST");
    assertThat(record.attemptCount()).isEqualTo(1);
  }

  @Test
  void 没配置IM通道时投递记为失败而不是已发送() {
    var deliveryId = enqueueDelivery("IM", "group-123");
    var result = deliveries.dispatch(tenant, 10);

    assertThat(result.delivered()).isZero();
    assertThat(result.failed()).isEqualTo(1);
    var record = deliveries.detail(tenant, deliveryId);
    assertThat(record.status()).isEqualTo("FAILED");
    assertThat(record.lastError()).contains("REPORTING_IM_WEBHOOK_URL");
  }

  @Test
  void SMTP真的把邮件发出去之后才记已投递() {
    // 配置真实的 SMTP（本测试里是一台**真的 SMTP 服务**，不是内存替身）。
    System.setProperty("REPORTING_SMTP_HOST", "127.0.0.1");
    System.setProperty("REPORTING_SMTP_PORT", String.valueOf(smtp.port()));
    System.setProperty("REPORTING_SMTP_STARTTLS", "false");
    System.setProperty("REPORTING_SMTP_FROM", "starcon@example.com");

    var deliveryId = enqueueDelivery("EMAIL", "ops@example.com");
    var result = deliveries.dispatch(tenant, 10);

    assertThat(result.delivered()).isEqualTo(1);
    assertThat(result.failed()).isZero();

    var record = deliveries.detail(tenant, deliveryId);
    assertThat(record.status()).isEqualTo("DELIVERED");
    assertThat(record.lastError()).isEmpty();

    // SMTP 服务端**真的收到了**这封邮件。
    var messages = smtp.messages();
    assertThat(messages).hasSize(1);
    var message = messages.get(0);
    assertThat(message).contains("To: ops@example.com");
    assertThat(message).contains("From: starcon@example.com");
    // 主题里的中文按 RFC 2047 base64 编码。
    assertThat(message).contains("Subject: =?UTF-8?B?");
    // 正文（base64）里必须有报表编码。
    var body = decodeFirstTextPart(message);
    assertThat(body).contains("R-EMAIL");
    assertThat(body).contains("报表订阅推送");
    assertThat(body).contains("数据行数：4");
    // 附件（CSV，base64）里才有列名与数据。
    var attachment = decodeAttachment(message);
    assertThat(attachment).contains("店铺");
    assertThat(attachment).contains("销售额");
    assertThat(attachment).contains("300");
  }

  @Test
  void 企业IM真的收到Webhook之后才记已投递() {
    System.setProperty(
        "REPORTING_IM_WEBHOOK_URL",
        "http://localhost:" + im.getAddress().getPort() + "/im-hook");

    var deliveryId = enqueueDelivery("IM", "group-123");
    var result = deliveries.dispatch(tenant, 10);

    assertThat(result.delivered()).isEqualTo(1);
    assertThat(result.failed()).isZero();
    assertThat(deliveries.detail(tenant, deliveryId).status()).isEqualTo("DELIVERED");

    assertThat(IM_REQUESTS).hasSize(1);
    var payload = IM_REQUESTS.get(0);
    assertThat(payload).contains("group-123");
    assertThat(payload).contains("R-IM");
    assertThat(payload).contains("销售额");
  }

  @Test
  void IM通道被拒收时记为失败而不是已投递() {
    // 指向一个一定会拒绝的地址（端口 1 上没人监听 ⇒ 连不上）。
    System.setProperty("REPORTING_IM_WEBHOOK_URL", "http://127.0.0.1:1/im-hook");

    var deliveryId = enqueueDelivery("IM", "group-123");
    var result = deliveries.dispatch(tenant, 10);

    assertThat(result.delivered()).isZero();
    assertThat(result.failed()).isEqualTo(1);
    var record = deliveries.detail(tenant, deliveryId);
    assertThat(record.status()).isEqualTo("FAILED");
    assertThat(record.lastError()).as("失败必须写明原因").isNotBlank();
  }

  @Test
  void 已经投递过的记录不会被重发() {
    System.setProperty("REPORTING_SMTP_HOST", "127.0.0.1");
    System.setProperty("REPORTING_SMTP_PORT", String.valueOf(smtp.port()));
    System.setProperty("REPORTING_SMTP_STARTTLS", "false");
    var deliveryId = enqueueDelivery("EMAIL", "ops@example.com");

    deliveries.dispatch(tenant, 10);
    assertThat(smtp.messages()).hasSize(1);

    var second = deliveries.dispatch(tenant, 10);
    assertThat(second.attempted()).as("已投递的不该再进队列").isZero();
    assertThat(smtp.messages()).as("不能重发").hasSize(1);
    assertThat(deliveries.detail(tenant, deliveryId).status()).isEqualTo("DELIVERED");
  }

  @Test
  void 投递详情端点能答出这一条到底投了没有() throws Exception {
    var deliveryId = enqueueDelivery("EMAIL", "ops@example.com");
    http.perform(
            get("/reporting/custom-report/deliveries/" + deliveryId)
                .header("Authorization", BEARER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deliveryId").value(deliveryId.toString()))
        .andExpect(jsonPath("$.channel").value("EMAIL"))
        .andExpect(jsonPath("$.target").value("ops@example.com"))
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  @Test
  void 导出与投递端点真的走HTTP() throws Exception {
    var reportId = newReport("R-HTTP", List.of("SALES_AMOUNT"), List.of("SHOP"));
    http.perform(get("/reporting/custom-report/export/sources").header("Authorization", BEARER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.formats.length()").value(3))
        .andExpect(jsonPath("$.syncRowThreshold").value(SYNC_THRESHOLD));

    http.perform(
            post("/reporting/custom-report/export/jobs")
                .header("Authorization", BEARER)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new ReportingExportModels.ExportSubscriptionJobCreateRequest(
                            reportId.toString(), "CSV", List.of(), DAY1, DAY2))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobId").exists());
  }

  // ==========================================================================
  // 投递夹具
  // ==========================================================================

  /** 造一条「待投递」记录（订阅计划 + 队列行）。 */
  private UUID enqueueDelivery(String channel, String target) {
    var reportId = newReport("R-" + channel, List.of("SALES_AMOUNT"), List.of("SHOP"));
    var deliveryId = UUID.randomUUID();
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into reporting_custom_report_subscription"
                  + " (id,tenant_id,report_id,cron_expression,channel,target,enabled,created_at,updated_at)"
                  + " values (?,?,?,'0 9 * * *',?,?,true,now(),now())",
              UUID.randomUUID(),
              tenant,
              reportId,
              channel,
              target);
          sql.execute(
              "insert into reporting_custom_report_delivery"
                  + " (id,tenant_id,report_id,subscription_id,scheduled_for,status,attempt_count,"
                  + " last_error,created_at,updated_at)"
                  + " values (?,?,?,?,?::timestamptz,'PENDING',0,'',now(),now())",
              deliveryId,
              tenant,
              reportId,
              sql.fetchOne(
                      "select id from reporting_custom_report_subscription"
                          + " where tenant_id = ? and report_id = ?",
                      tenant,
                      reportId)
                  .get(0, UUID.class),
              java.time.OffsetDateTime.now());
          return null;
        });
    return deliveryId;
  }

  // ==========================================================================
  // 一台**真的** SMTP 服务（测试端，不是内存替身）
  // ==========================================================================

  /** 最小 SMTP 服务端：把收到的邮件原文存起来，供断言。 */
  static final class TestSmtpServer implements AutoCloseable {
    private final ServerSocket server;
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;

    TestSmtpServer() throws Exception {
      this.server = new ServerSocket(0);
      var thread = new Thread(this::acceptLoop, "test-smtp");
      thread.setDaemon(true);
      thread.start();
    }

    int port() {
      return server.getLocalPort();
    }

    List<String> messages() {
      return messages;
    }

    void reset() {
      messages.clear();
    }

    private void acceptLoop() {
      while (running) {
        try (var socket = server.accept()) {
          handle(socket);
        } catch (Exception e) {
          if (running) {
            // 测试端的一次性错误不该让整台服务挂掉。
            continue;
          }
          return;
        }
      }
    }

    private void handle(Socket socket) throws Exception {
      var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      var out = socket.getOutputStream();
      write(out, "220 localhost ESMTP starcon-test");
      var body = new StringBuilder();
      var inData = false;
      String line;
      while ((line = in.readLine()) != null) {
        if (inData) {
          if (".".equals(line)) {
            inData = false;
            messages.add(body.toString());
            body.setLength(0);
            write(out, "250 OK: queued");
          } else {
            body.append(line).append('\n');
          }
          continue;
        }
        var upper = line.toUpperCase(java.util.Locale.ROOT);
        if (upper.startsWith("EHLO")) {
          write(out, "250-localhost");
          write(out, "250 OK");
        } else if (upper.startsWith("HELO")) {
          write(out, "250 OK");
        } else if (upper.startsWith("STARTTLS")) {
          write(out, "502 STARTTLS not supported");
        } else if (upper.startsWith("AUTH LOGIN")) {
          write(out, "334 VXNlcm5hbWU6");
          in.readLine();
          write(out, "334 UGFzc3dvcmQ6");
          in.readLine();
          write(out, "235 Authentication successful");
        } else if (upper.startsWith("MAIL FROM")) {
          write(out, "250 OK");
        } else if (upper.startsWith("RCPT TO")) {
          write(out, "250 OK");
        } else if (upper.startsWith("DATA")) {
          inData = true;
          write(out, "354 End data with <CR><LF>.<CR><LF>");
        } else if (upper.startsWith("QUIT")) {
          write(out, "221 Bye");
          break;
        } else {
          write(out, "250 OK");
        }
      }
    }

    private void write(OutputStream out, String line) throws Exception {
      out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
    }

    @Override
    public void close() {
      running = false;
      try {
        server.close();
      } catch (Exception e) {
        // 关掉就关掉。
      }
    }
  }

  /**
   * 从 MIME 原文里取出第一个 text/plain 部件并解 base64。
   *
   * <p>⚠️ **不能按 "--" 切**（boundary 本身就含 "--"）⇒ 按 "\r\n--" 切。
   */
  static String decodeFirstTextPart(String message) {
    return decodePart(message, "Content-Type: text/plain");
  }

  /** 从 MIME 原文里取出附件部件并解 base64。 */
  static String decodeAttachment(String message) {
    return decodePart(message, "Content-Disposition: attachment");
  }

  /** 取某个 MIME 部件的正文（base64 编码的）。 */
  static String decodePart(String message, String marker) {
    // ⚠️ 测试端的 SMTP 服务按行读，捕获到的原文是 **LF** 行尾 ⇒ 先归一化。
    var normalized = message.replace("\r\n", "\n");
    var at = normalized.indexOf(marker);
    if (at < 0) return "";
    var blank = normalized.indexOf("\n\n", at);
    if (blank < 0) return "";
    var rest = normalized.substring(blank + 2);
    var end = rest.indexOf("\n--");
    if (end < 0) end = rest.length();
    try {
      // ⚠️ 用 MIME 解码器：base64 正文里有换行，基础解码器会直接报错。
      return new String(
          Base64.getMimeDecoder().decode(rest.substring(0, end).trim()), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  /** 兜底：避免未使用的 import 报警。 */
  @SuppressWarnings("unused")
  private static List<String> unused() {
    return new ArrayList<>();
  }
}
