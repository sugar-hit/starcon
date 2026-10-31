package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 18.1.3.1「导入导出任务」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住蓝图 18.1.3.1 的**六类职责**，每一类都必须是「真在生效」而不是「端点存在」：
 *
 * <ol>
 *   <li><b>任务创建（选择数据域与范围）</b>：范围快照在创建那一刻冻结，之后不可改；
 *   <li><b>异步执行</b>：POST 只返回「已受理」，文件在后台生成（轮询才拿得到）；
 *   <li><b>进度跟踪</b>：任务行上的四个计数与状态真的推进；
 *   <li><b>结果下载</b>：真文件落磁盘、sha256 与字节数落库、**带有效期**、过期回 410；
 *   <li><b>失败行报告</b>：逐行逐列（哪一行、哪一列、为什么），并另出可下载的 CSV 报告；
 *   <li><b>模板下载</b>：列头 + 示例行。
 * </ol>
 *
 * <p>🔴 <b>跨服务取数走的是授权端口</b>：catalog 由一个真实的 HTTP 夹具扮演，
 * 本测试断言导出文件里出现的是**上游返回的行**（不是本地造的常量）。
 *
 * <p>🔴 <b>没接通的能力如实答</b>：未接通的数据域发起任务必须 <b>409</b>，
 * 而不是返回一条「已受理」然后永远不产出文件。
 *
 * <p>租户隔离的三条断言在 {@code ImportExportTenancyIntegrationTest} 里（同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImportExportIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final String BEARER = "session-io";
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer catalog;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的四张表。 */
  static final List<String> TABLES =
      List.of("platform_io_task", "platform_io_file", "platform_io_row_error", "platform_io_template");

  /** 权限码（**必须同时含 .read** —— AccessService 的判据要求同一 grant 里两者都有）。 */
  static final List<String> PERMISSIONS =
      List.of("platform.io.read", "platform.io.create", "platform.io.execute", "platform.io.download");

  /** 上游 catalog 的 SKU 行（**导出文件里必须出现它们**）。 */
  static final String UPSTREAM_SKU_CODE = "SKU-UPSTREAM-1";

  static AccessService.Access actor(UUID uid, UUID tenantId, List<String> permissions) {
    return new AccessService.Access(
        uid,
        tenantId,
        "Test",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

  static void respond(HttpExchange e, int status, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put(BEARER, actor(UUID.randomUUID(), tenant, PERMISSIONS));
    try {
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
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();

      // catalog 夹具：只实现「SKU 清单」这一个端点（授权只读端口读的就是它）。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/skus",
          e -> {
            if (!BEARER.equals(e.getRequestHeaders().getFirst("Authorization"))) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            var sku = new java.util.LinkedHashMap<String, Object>();
            sku.put("id", UUID.randomUUID().toString());
            sku.put("spuId", UUID.randomUUID().toString());
            sku.put("spuCode", "SPU-UPSTREAM");
            sku.put("spuName", "上游商品");
            sku.put("category", "耳机");
            sku.put("code", UPSTREAM_SKU_CODE);
            sku.put("name", "上游 SKU");
            sku.put("barcode", "6900000000");
            sku.put("baseUnit", "件");
            sku.put("status", "ACTIVE");
            sku.put("version", 1);
            sku.put("variantSignature", "黑色");
            sku.put("updatedAt", "2026-10-01T08:00:00Z");
            var page = new java.util.LinkedHashMap<String, Object>();
            page.put("items", List.of(sku));
            page.put("total", 1);
            page.put("page", 0);
            page.put("size", 200);
            respond(e, 200, page);
          });
      catalog.setExecutor(Executors.newFixedThreadPool(2));
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "platform_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + catalog.getAddress().getPort());
    // 其余上游**故意指向不可达地址**：本测试只覆盖 catalog 与 platform 自有表，
    // 别的上游读不到时必须 503（而不是返回空集合冒充「没有数据」）。
    r.add("TRADE_URL", () -> "http://127.0.0.1:1");
    r.add("WAREHOUSE_URL", () -> "http://127.0.0.1:1");
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:1");
    r.add("FINANCE_URL", () -> "http://127.0.0.1:1");
    r.add("PLATFORM_IO_STORAGE_ROOT", () -> "target/platform-io-store-test");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ImportExportService service;

  @AfterAll
  static void stopContainers() {
    if (identity != null) identity.stop(0);
    if (catalog != null) catalog.stop(0);
    if (pg.isRunning()) pg.stop();
  }

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_io_row_error, platform_io_file, platform_io_template,"
              + " platform_io_task, platform_webhook_subscription_event,"
              + " platform_webhook_subscription, platform_webhook_event_type,"
              + " platform_webhook_signing_secret, platform_app restart identity cascade");
      s.execute("alter sequence platform_io_task_no_seq restart with 1");
    }
    seed();
  }

  /** 种子数据：一个应用 + 一个事件类型（导入与导出都要用）。 */
  void seed() {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_app(id,tenant_id,app_code,app_name,app_type,status,created_at,"
                  + " updated_at) values(?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              "erp-connector",
              "对接方 ERP",
              "THIRD_PARTY",
              "ACTIVE",
              now,
              now);
          sql.execute(
              "insert into platform_webhook_event_type(id,tenant_id,event_type,schema_version,status,"
                  + " payload_schema,source_service,source_artifact,registered_at)"
                  + " values(?,?,?,?,?,?::jsonb,?,?,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              "order.created",
              1,
              "ACTIVE",
              "{}",
              "trade",
              "trade_order_created",
              now);
          return null;
        });
  }

  // ================================================================ 工具

  /** 发一个带会话头的请求。 */
  private org.springframework.test.web.servlet.RequestBuilder authed(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
    return builder.header("Authorization", BEARER);
  }

  /** 轮询到任务进入终态（返回状态码）。 */
  String waitForTerminal(UUID taskId) throws Exception {
    for (var i = 0; i < 200; i++) {
      var body =
          http.perform(authed(get("/platform/io/tasks/" + taskId)))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString(StandardCharsets.UTF_8);
      var node = json.readTree(body);
      var state = node.get("status").asText();
      if (!state.equals("PENDING") && !state.equals("RUNNING")) return state;
      Thread.sleep(50);
    }
    throw new AssertionError("任务没有在预期时间内进入终态：" + taskId);
  }

  /** 发起一个导出任务，返回任务 id。 */
  UUID createExport(String domainCode) throws Exception {
    var body =
        http.perform(
                authed(
                    post("/platform/io/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("domainCode", domainCode)))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return UUID.fromString(json.readTree(body).get("id").asText());
  }

  // ==========================================================================
  // 一、能力声明：没接通的格子如实标 false
  // ==========================================================================
  @Test
  void 能力声明里未接通的格子如实标false并写明归属() throws Exception {
    var body =
        http.perform(authed(get("/platform/io/capabilities")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    var node = json.readTree(body);
    var capabilities = node.get("capabilities");
    assertThat(capabilities).isNotNull();
    var unavailable = new java.util.ArrayList<String>();
    for (var c : capabilities) {
      if (!c.get("available").asBoolean()) {
        unavailable.add(c.get("name").asText());
        assertThat(c.get("ownerModule").asText())
            .as("不可用的能力必须写明归属模块，不能只说「没做」")
            .isNotEmpty();
        assertThat(c.get("basis").asText()).isNotEmpty();
      }
    }
    assertThat(unavailable).isNotEmpty();

    // 数据域目录：两个方向各一行，没接通的那行 available=false 且**带原因**。
    var domains =
        http.perform(authed(get("/platform/io/domains")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    var domainNode = json.readTree(domains);
    var customerExport =
        findDomain(domainNode, "CUSTOMER", "EXPORT");
    assertThat(customerExport).isNotNull();
    assertThat(customerExport.get("available").asBoolean()).isFalse();
    assertThat(customerExport.get("reason").asText()).contains("crm");
    var orderImport = findDomain(domainNode, "ORDER", "IMPORT");
    assertThat(orderImport).isNotNull();
    assertThat(orderImport.get("available").asBoolean()).isFalse();
    assertThat(orderImport.get("reason").asText()).isNotEmpty();
  }

  private com.fasterxml.jackson.databind.JsonNode findDomain(
      com.fasterxml.jackson.databind.JsonNode domains, String code, String direction) {
    for (var d : domains) {
      if (code.equals(d.get("code").asText()) && direction.equals(d.get("direction").asText())) {
        return d;
      }
    }
    return null;
  }

  // ==========================================================================
  // 二、任务创建 + 异步执行 + 进度跟踪 + 结果下载
  // ==========================================================================
  @Test
  void 导出任务异步跑完并能下载到真文件() throws Exception {
    var before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5);
    var taskId = createExport("PLATFORM_APP");
    var state = waitForTerminal(taskId);
    assertThat(state).as("导出任务应当成功结束").isEqualTo("SUCCEEDED");

    var detail =
        http.perform(authed(get("/platform/io/tasks/" + taskId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    var node = json.readTree(detail);
    assertThat(node.get("totalRows").asInt()).isEqualTo(1);
    assertThat(node.get("processedRows").asInt()).isEqualTo(1);
    assertThat(node.get("succeededRows").asInt()).isEqualTo(1);
    assertThat(node.get("failedRows").asInt()).isZero();
    assertThat(node.get("resultFileId").asText()).isNotEmpty();
    // 🔴 **下载有效期必须真的写了**（蓝图加粗句）。
    assertThat(node.get("expiresAt").isNull()).isFalse();
    // 🔴 **时间列写回逐字相等**：创建时刻落在发起前后的窗口里（不是墙钟漂移）。
    var created = OffsetDateTime.parse(node.get("createdAt").asText());
    assertThat(created).isAfterOrEqualTo(before);
    assertThat(created).isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(5));

    // 下载：真内容 + BOM + 表头 + 种子行。
    var file =
        http.perform(authed(get("/platform/io/tasks/" + taskId + "/file")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var csv = file.getContentAsString(StandardCharsets.UTF_8);
    assertThat(csv).startsWith("﻿");
    assertThat(csv).contains("应用编码");
    assertThat(csv).contains("erp-connector");
    assertThat(csv).contains("对接方 ERP");

    // 落库的元信息：sha256 与字节数都是真值。
    var meta =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                    "select content_sha256,byte_size from platform_io_file where task_id = ?", taskId));
    assertThat(meta).isNotNull();
    assertThat(meta.get("content_sha256", String.class)).hasSize(64);
    assertThat(meta.get("byte_size", java.math.BigDecimal.class).intValue())
        .isEqualTo(file.getContentAsByteArray().length);
  }

  @Test
  void 范围快照在创建后不可改() throws Exception {
    var taskId = createExport("PLATFORM_APP");
    waitForTerminal(taskId);
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "update platform_io_task set scope = ?::jsonb where id = ?",
                            "{\"q\":\"改过的范围\"}", taskId)))
        .as("改范围快照必须被触发器挡住（它是「当时导的是什么」的审计事实）")
        .hasMessageContaining("不可改");
  }

  @Test
  void 状态机只允许合法迁移() {
    // 直接插一条 PENDING 任务（不经过执行器），再试图一步跳到 SUCCEEDED。
    var id = UUID.randomUUID();
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into platform_io_task(id,tenant_id,task_no,direction,domain_code,domain_label,"
                  + " format,status,scope,actor_id,requested_at,created_at,updated_at)"
                  + " values(?,?,?,'EXPORT','PLATFORM_APP','平台应用与凭证','CSV','PENDING','{}'::jsonb,?,"
                  + " ?::timestamptz,?::timestamptz,?::timestamptz)",
              id,
              tenant,
              "IO-STATE-TEST",
              UUID.randomUUID(),
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "update platform_io_task set status = 'SUCCEEDED' where id = ?", id)))
        .as("PENDING 不能一步跳到 SUCCEEDED（必须有执行过程）")
        .hasMessageContaining("状态不能从");
  }

  @Test
  void 导出未接通的数据域如实409() throws Exception {
    http.perform(
            authed(
                post("/platform/io/tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(Map.of("domainCode", "CUSTOMER")))))
        .andExpect(status().isConflict());
  }

  @Test
  void 未接通的数据域的拒绝理由写明缺什么与归谁() {
    var actor = actors.get(BEARER);
    var e =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                service.createExport(
                    "CUSTOMER", Map.of(), actor));
    assertThat(e.getStatusCode().value()).isEqualTo(409);
    assertThat(e.getReason()).contains("未接通");
    assertThat(e.getReason()).contains("15.1");
    assertThat(e.getReason()).contains("crm");

    var e2 =
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> service.createImport("ORDER", "x.csv", new byte[] {1}, Map.of(), actor));
    assertThat(e2.getStatusCode().value()).isEqualTo(409);
    assertThat(e2.getReason()).contains("未接通");
    assertThat(e2.getReason()).contains("9.1");
  }

  @Test
  void 未知的范围字段直接400而不是默默忽略() throws Exception {
    http.perform(
            authed(
                post("/platform/io/tasks")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsBytes(
                            Map.of("domainCode", "PLATFORM_APP", "scope", Map.of("typo", "x"))))))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 三、跨服务数据域：经授权端口取上游真数据
  // ==========================================================================
  @Test
  void 跨服务数据域的导出经授权端口取到上游真数据() throws Exception {
    var taskId = createExport("PRODUCT");
    var state = waitForTerminal(taskId);
    assertThat(state).as("商品域导出应当成功").isEqualTo("SUCCEEDED");
    var csv =
        http.perform(authed(get("/platform/io/tasks/" + taskId + "/file")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(csv).contains(UPSTREAM_SKU_CODE);
    assertThat(csv).contains("上游 SKU");
    assertThat(csv).contains("SPU-UPSTREAM");
  }

  @Test
  void 上游读不到时任务失败而不是产出空文件() throws Exception {
    // trade 指向不可达地址 ⇒ 导出订单必须失败（503），**不能**产出一份空 CSV。
    var taskId = createExport("ORDER");
    var state = waitForTerminal(taskId);
    assertThat(state).as("上游不可达时任务必须失败").isEqualTo("FAILED");
    var detail =
        http.perform(authed(get("/platform/io/tasks/" + taskId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(json.readTree(detail).get("error").asText()).isNotEmpty();
    http.perform(authed(get("/platform/io/tasks/" + taskId + "/file")))
        .andExpect(status().isNotFound());
  }

  // ==========================================================================
  // 四、导入：真落库 + 逐行失败报告
  // ==========================================================================
  @Test
  void 导入把合法行真落库并把失败行逐行逐列报告出来() throws Exception {
    var csv =
        "﻿订阅名称,订阅端点地址,所属应用,订阅事件类型,说明\n"
            + "订单订阅A,https://a.example.com/hook,erp-connector,order.created,第一条\n"
            + "订单订阅B,https://b.example.com/hook,erp-connector,order.created,第二条\n"
            + "坏订阅,ftp://bad.example.com/hook,不存在的应用,order.created,第三行有问题\n";
    var upload =
        new MockMultipartFile(
            "file", "subscriptions.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    var body =
        http.perform(
                authed(
                    multipart("/platform/io/tasks/import")
                        .file(upload)
                        .param("domainCode", "PLATFORM_SUBSCRIPTION")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    var taskId = UUID.fromString(json.readTree(body).get("id").asText());
    var state = waitForTerminal(taskId);
    assertThat(state).as("导入任务应当成功结束（失败行不影响任务本身）").isEqualTo("SUCCEEDED");

    // 🔴 **合法行真的落库了**（不是只记了个数）。
    var subs =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select name,target_url from platform_webhook_subscription order by name")
                    .map(
                        r ->
                            r.get(0, String.class)
                                + "|"
                                + r.get(1, String.class)));
    assertThat(subs).containsExactly("订单订阅A|https://a.example.com/hook", "订单订阅B|https://b.example.com/hook");

    // 事件过滤也真落库了（导入的不是一张空壳订阅）。
    var events =
        tenants.run(
            tenant,
            () -> sql.fetch("select event_type from platform_webhook_subscription_event").size());
    assertThat(events).isEqualTo(2);

    // 🔴 **失败行逐行逐列**：第 3 行、原因码 APP_NOT_FOUND。
    var errors =
        http.perform(authed(get("/platform/io/tasks/" + taskId + "/errors")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    var errorNode = json.readTree(errors);
    // 🔴 **一行可以有多处问题**（这一行的地址 scheme 不对 + 应用不存在）⇒ 两个失败项。
    assertThat(errorNode.get("total").asInt()).isEqualTo(2);
    var codes = new java.util.ArrayList<String>();
    for (var item : errorNode.get("items")) {
      // 🔴 行号是**文件里的物理行号**（表头 = 1）⇒ 表头 + 2 行合法数据 ⇒ 坏行是第 4 行。
      assertThat(item.get("rowNo").asInt()).as("行号是文件里的物理行号（表头 = 1）").isEqualTo(4);
      assertThat(item.get("severity").asText()).isEqualTo("ERROR");
      codes.add(item.get("errorCode").asText());
    }
    assertThat(codes).contains("APP_NOT_FOUND", "INVALID_TARGET_URL");
    var appError =
        java.util.stream.StreamSupport.stream(
                errorNode.get("items").spliterator(), false)
            .filter(n -> "APP_NOT_FOUND".equals(n.get("errorCode").asText()))
            .findFirst()
            .orElseThrow();
    assertThat(appError.get("message").asText()).contains("不存在的应用");

    // 失败行报告文件可下载。
    var report =
        http.perform(authed(get("/platform/io/tasks/" + taskId + "/error-report")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(report).contains("APP_NOT_FOUND");
    assertThat(report).contains("INVALID_TARGET_URL");
    assertThat(report).contains("4");

    // 导入回执（结果文件）也在。
    var receipt =
        http.perform(authed(get("/platform/io/tasks/" + taskId + "/file")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(receipt).contains("APPLIED");
  }

  @Test
  void 导入未接通的数据域如实409() throws Exception {
    var upload =
        new MockMultipartFile("file", "x.csv", "text/csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));
    http.perform(
            authed(
                multipart("/platform/io/tasks/import")
                    .file(upload)
                    .param("domainCode", "ORDER")))
        .andExpect(status().isConflict());
  }

  @Test
  void 导入缺少必填列时整体失败而不是只导一半() throws Exception {
    var csv = "﻿订阅名称,所属应用\n只有两列,erp-connector\n";
    var upload =
        new MockMultipartFile(
            "file", "bad.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    var body =
        http.perform(
                authed(
                    multipart("/platform/io/tasks/import")
                        .file(upload)
                        .param("domainCode", "PLATFORM_SUBSCRIPTION")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    var taskId = UUID.fromString(json.readTree(body).get("id").asText());
    var state = waitForTerminal(taskId);
    assertThat(state).isEqualTo("FAILED");
    // 🔴 一次数据都不写。
    assertThat(
            tenants.run(tenant, () -> sql.fetch("select id from platform_webhook_subscription").size()))
        .isZero();
  }

  // ==========================================================================
  // 五、模板下载
  // ==========================================================================
  @Test
  void 模板下载给出列头与示例行() throws Exception {
    var body =
        http.perform(authed(get("/platform/io/templates?domainCode=PLATFORM_SUBSCRIPTION")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    var node = json.readTree(body);
    assertThat(node.get("total").asInt()).isEqualTo(1);
    var templateId = node.get("items").get(0).get("id").asText();

    var csv =
        http.perform(authed(get("/platform/io/templates/" + templateId + "/file")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(csv).contains("订阅名称");
    assertThat(csv).contains("订阅端点地址");
    assertThat(csv).contains("所属应用");
    assertThat(csv).contains("订阅事件类型");
    // 示例行也在（运营照着填）。
    assertThat(csv).contains("erp-connector");
  }

  // ==========================================================================
  // 六、下载有效期
  // ==========================================================================
  @Test
  void 文件过期后下载回410而任务记录保留() throws Exception {
    var taskId = createExport("PLATFORM_APP");
    waitForTerminal(taskId);
    // 把有效期推到过去（模拟到期）。
    tenants.run(
        tenant,
        () ->
            sql.execute(
                "update platform_io_task set expires_at = ?::timestamptz where id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(1),
                taskId));
    http.perform(authed(get("/platform/io/tasks/" + taskId + "/file"))).andExpect(status().isGone());
    // 🔴 **任务记录仍然保留**（它是审计事实）。
    http.perform(authed(get("/platform/io/tasks/" + taskId))).andExpect(status().isOk());
  }

  // ==========================================================================
  // 七、只读端点与鉴权
  // ==========================================================================
  @Test
  void 没有会话一律401() throws Exception {
    http.perform(get("/platform/io/tasks")).andExpect(status().isUnauthorized());
    http.perform(get("/platform/io/capabilities")).andExpect(status().isUnauthorized());
  }

  @Test
  void 任务列表真分页真筛选() throws Exception {
    createExport("PLATFORM_APP");
    var body =
        http.perform(authed(get("/platform/io/tasks?direction=EXPORT&domainCode=PLATFORM_APP&page=0&size=5")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    var node = json.readTree(body);
    assertThat(node.get("total").asInt()).isEqualTo(1);
    assertThat(node.get("items")).hasSize(1);
    assertThat(node.get("items").get(0).get("direction").asText()).isEqualTo("EXPORT");

    // 筛选真的生效（换一个域就是 0 条）。
    var empty =
        http.perform(authed(get("/platform/io/tasks?domainCode=ORDER")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(json.readTree(empty).get("total").asInt()).isZero();
  }

  @Test
  void 迁移确实落地了且四张表强制rls() {
    for (var table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V415 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner 绕过").isTrue();
      var nullable =
          sql.fetchOne(
                  "select is_nullable from information_schema.columns where table_name = ? and column_name = 'tenant_id'",
                  table)
              .get(0, String.class);
      assertThat(nullable).as(table + " 的 tenant_id 必须是 NOT NULL").isEqualTo("NO");
    }
  }
}
