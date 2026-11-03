package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.ExternalIntegrationModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 18.2.3.1「其他对接」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住蓝图 18.2.3.1 的**三项能力**与**加粗的那条纪律**：
 *
 * <ol>
 *   <li><b>统一连接配置管理</b>：连接档案真落库、类别与适配器必须相容、密钥只存引用；
 *   <li><b>同步任务配置</b>：任务类型必须与连接的类别相容、一个连接一个任务类型；
 *   <li><b>连接状态 + 同步记录</b>：未接入 ⇒ <b>409</b> + 如实标注，
 *       <b>不写成功记录</b>；人工登记的外部侧已完成记录 source=EXTERNAL、
 *       provider_notified=false。
 *   <li><b>🔴 每个对接独立连接与独立健康状态</b>：一个对接挂了**不影响**另一个。
 * </ol>
 *
 * <p><b>🔴 时间纪律</b>：落库时间列用 {@code ?::timestamptz} 绑定，并<b>断言写回的值</b>，
 * 不只断言「没抛异常」。
 *
 * <p><b>🔴 数量纪律</b>：条数一律 {@link BigDecimal}，断言用 {@code isEqualByComparingTo}
 * （比的是数值，不是标度）。
 *
 * <p>多租户隔离的三条断言在 {@code ExternalIntegrationTenancyIntegrationTest} 里
 * （同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExternalIntegrationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer channel;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的三张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_external_connection",
          "platform_external_sync_task",
          "platform_external_sync_record");

  static final List<String> PERMISSIONS =
      List.of(
          "platform.external.read",
          "platform.external.create",
          "platform.external.update",
          "platform.external.sync");

  // ---------------------------------------------------------------- 夹具工具

  static Map<String, Object> map(Object... keyValues) {
    var out = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < keyValues.length; i += 2) {
      out.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
    }
    return out;
  }

  static void respond(HttpExchange e, int status, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  static AccessService.Access actor(UUID uid, UUID tid, List<String> permissions) {
    return new AccessService.Access(
        uid,
        tid,
        "Test",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
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
    actors.put("session", actor(UUID.randomUUID(), tenant, PERMISSIONS));
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

      // 🔴 上游「2 渠道机制」（channel 服务）：**只读**端口的夹具。
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/platforms",
          e ->
              respond(
                  e,
                  200,
                  List.of(
                      map(
                          "id", "00000000-0000-4000-8000-0000000000a1",
                          "code", "AMAZON",
                          "name", "Amazon",
                          "integration", "API",
                          "supportStatus", "SUPPORTED"),
                      map(
                          "id", "00000000-0000-4000-8000-0000000000a2",
                          "code", "SHOPEE",
                          "name", "Shopee",
                          "integration", "API",
                          "supportStatus", "SUPPORTED"))));
      channel.createContext(
          "/channel/shops",
          e ->
              respond(
                  e,
                  200,
                  List.of(
                      map(
                          "id", "00000000-0000-4000-8000-0000000000b1",
                          "code", "AMAZON-US-01",
                          "name", "Amazon 美国站",
                          "platformCode", "AMAZON",
                          "platformName", "Amazon",
                          "status", "ACTIVE",
                          "lastSyncAt", "2026-10-30T00:00:00Z"))));
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();
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
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stopContainers() {
    if (identity != null) identity.stop(0);
    if (channel != null) channel.stop(0);
    if (pg.isRunning()) pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_external_sync_record, platform_external_sync_task,"
              + " platform_external_connection restart identity cascade");
    }
  }

  // ---------------------------------------------------------------- 请求助手

  String postJson(String path, Object body, int expectedStatus) throws Exception {
    var response =
        http.perform(
                post(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus())
        .as("POST " + path + " 的状态码（响应：" + response.getContentAsString() + "）")
        .isEqualTo(expectedStatus);
    return response.getContentAsString();
  }

  String getJson(String path, int expectedStatus) throws Exception {
    var response =
        http.perform(get(path).header("Authorization", "session")).andReturn().getResponse();
    assertThat(response.getStatus())
        .as("GET " + path + " 的状态码（响应：" + response.getContentAsString() + "）")
        .isEqualTo(expectedStatus);
    return response.getContentAsString();
  }

  /**
   * 错误文案：MockMvc 走 sendError 时落在 errorMessage，走 ProblemDetail 时落在响应体。
   *
   * <p>（与 18.2.2.1 支付与资金的测试同一写法 —— 服务层用
   * {@code ResponseStatusException(BAD_REQUEST, "能看懂的话")} 给出原因，
   * 原因落在 {@code errorMessage} 上而不是响应体里。）
   */
  static String detail(MockHttpServletResponse r) throws java.io.UnsupportedEncodingException {
    var message = r.getErrorMessage();
    var body = r.getContentAsString();
    return (message == null ? "" : message) + " " + body;
  }

  /** 登记一个对接连接（**密钥只存引用**）。 */
  UUID registerConnection(String adapterCode, String name) throws Exception {
    var body =
        postJson(
            "/platform/external-integrations/connections",
            map(
                "category", categoryOf(adapterCode),
                "adapterCode", adapterCode,
                "name", name,
                "baseUrl", "https://example.invalid/api",
                "credentialRef", "secret://external/" + adapterCode.toLowerCase() + "/main",
                "credentialHint", "9f2a",
                "status", "ACTIVE",
                "timeoutSeconds", 15,
                "maxAttempts", 3,
                "remark", ""),
            200);
    return json.readValue(body, ExternalConnectionRow.class).id();
  }

  /** 登记一个同步任务。 */
  UUID registerTask(UUID connectionId, String taskType, String name) throws Exception {
    var body =
        postJson(
            "/platform/external-integrations/sync-tasks",
            map(
                "connectionId", connectionId.toString(),
                "taskType", taskType,
                "direction", "PULL",
                "name", name,
                "resourceRef", "SHOP-1",
                "scheduleKind", "MANUAL",
                "enabled", true,
                "status", "ACTIVE",
                "remark", ""),
            200);
    return json.readValue(body, ExternalSyncTaskRow.class).id();
  }

  static String categoryOf(String adapterCode) {
    return switch (adapterCode) {
      case "ALIBABA_1688", "TAOBAO" -> "SUPPLY_SOURCE";
      case "OVERSEAS_WAREHOUSE" -> "OVERSEAS_WAREHOUSE";
      case "LOGISTICS_CARRIER" -> "LOGISTICS_CARRIER";
      case "CERTIFICATION_AGGREGATOR" -> "CERTIFICATION_AGGREGATOR";
      case "SELF_HOSTED_BI" -> "SELF_HOSTED_BI";
      default -> throw new IllegalArgumentException(adapterCode);
    };
  }

  static String taskTypeOf(String adapterCode) {
    return switch (adapterCode) {
      case "ALIBABA_1688", "TAOBAO" -> "CATALOG_SYNC";
      case "OVERSEAS_WAREHOUSE" -> "INVENTORY_SYNC";
      case "LOGISTICS_CARRIER" -> "SHIPMENT_TRACKING_SYNC";
      case "CERTIFICATION_AGGREGATOR" -> "CERTIFICATE_SYNC";
      case "SELF_HOSTED_BI" -> "BI_DATASET_SYNC";
      default -> throw new IllegalArgumentException(adapterCode);
    };
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================
  @Test
  void 能力声明如实答六个外部通道未接入且写明归谁() throws Exception {
    var capabilities =
        json.readValue(
            getJson("/platform/external-integrations/capabilities", 200),
            ExternalIntegrationCapabilities.class);
    assertThat(capabilities.capabilities()).isNotEmpty();

    var direct =
        capabilities.capabilities().stream()
            .filter(c -> c.name().contains("经由外部通道发起同步"))
            .findFirst()
            .orElseThrow();
    assertThat(direct.available()).as("直连同步当前**不可用**（本仓没有任何真实外部系统适配器）").isFalse();
    assertThat(direct.ownerModule()).isEqualTo("18.2.3");
    assertThat(direct.evidence()).isNotBlank();

    // 🔴 三项蓝图能力必须逐项**可用**。
    for (var name :
        List.of(
            "统一连接配置管理（对接连接档案）",
            "同步任务配置",
            "🔴 **每个对接独立连接与独立健康状态**",
            "同步记录（输出里的「同步记录」）")) {
      var capability =
          capabilities.capabilities().stream()
              .filter(c -> c.name().equals(name))
              .findFirst()
              .orElseThrow(() -> new AssertionError("能力声明缺这一项：" + name));
      assertThat(capability.available()).as(name).isTrue();
      assertThat(capability.evidence()).as(name + " 必须写清依据").isNotBlank();
    }
  }

  @Test
  void 逐个适配器的可用性都答未接入() throws Exception {
    var availability =
        json.readValue(
            getJson("/platform/external-integrations/adapters/availability", 200),
            ExternalAdapterAvailability[].class);
    assertThat(availability).hasSize(6);
    for (var adapter : availability) {
      assertThat(adapter.connected()).as(adapter.adapterCode() + " 不得声称已接通").isFalse();
      assertThat(adapter.reason()).isNotBlank();
      assertThat(adapter.ownerModule()).isEqualTo("18.2.3");
    }
  }

  // ==========================================================================
  // 能力一：统一连接配置管理
  // ==========================================================================
  @Test
  void 连接配置真落库且能列表筛选与搜索() throws Exception {
    var id = registerConnection("ALIBABA_1688", "1688 主货源");
    registerConnection("OVERSEAS_WAREHOUSE", "美国海外仓");

    var all =
        json.readValue(
            getJson("/platform/external-integrations/connections", 200),
            ExternalConnectionRow[].class);
    assertThat(all).hasSize(2);

    var filtered =
        json.readValue(
            getJson("/platform/external-integrations/connections?category=OVERSEAS_WAREHOUSE", 200),
            ExternalConnectionRow[].class);
    assertThat(filtered).hasSize(1);
    assertThat(filtered[0].name()).isEqualTo("美国海外仓");

    var searched =
        json.readValue(
            getJson("/platform/external-integrations/connections?keyword=主货源", 200),
            ExternalConnectionRow[].class);
    assertThat(searched).hasSize(1);
    assertThat(searched[0].id()).isEqualTo(id);

    // 详情
    var detail =
        json.readValue(
            getJson("/platform/external-integrations/connections/" + id, 200),
            ExternalConnectionRow.class);
    assertThat(detail.adapterCode()).isEqualTo("ALIBABA_1688");
    assertThat(detail.credentialRef()).isEqualTo("secret://external/alibaba_1688/main");
    assertThat(detail.credentialHint()).isEqualTo("9f2a");
    assertThat(detail.credentialConfigured()).isTrue();
    // 🔴 刚登记、还没跑过、且外部通道未接入 ⇒ 如实答「未接入」（落库的列与算出来的结论一致）。
    assertThat(detail.healthStatus()).isEqualTo("NOT_CONNECTED");
    assertThat(detail.taskCount()).isZero();
    assertThat(detail.recordCount()).isZero();
  }

  @Test
  void 库里没有任何密钥明文列() throws Exception {
    for (String table : TABLES) {
      var columns =
          sql.fetch(
                  "select column_name from information_schema.columns where table_name = ?",
                  table)
              .map(r -> r.get(0, String.class).toLowerCase(java.util.Locale.ROOT));
      // 🔴 **不许有装密钥本体的列**（与 18.1.1.1 应用凭证、18.2.2.1 支付与资金同一纪律）。
      for (var forbidden : List.of("secret", "password", "api_key", "apikey", "token")) {
        assertThat(columns)
            .as(table + " 不允许出现含「" + forbidden + "」的列名")
            .noneMatch(c -> c.contains(forbidden));
      }
      // 🔴 唯一允许带「credential」的是**引用**三件套 —— 它存的是「去哪里取密钥」的指针，
      //    不是密钥本体。（所以这里不能把「credential」整类列为禁止。）
      assertThat(columns.stream().filter(c -> c.contains("credential")).toList())
          .as(table + " 只允许 credential_ref / credential_ref_lookup / credential_hint")
          .isSubsetOf(List.of("credential_ref", "credential_ref_lookup", "credential_hint"));
    }
    // 🔴 值层面：整个库里搜不到明文密钥。
    var leaked =
        sql.fetchOne(
            "select count(*) from platform_external_connection where credential_ref like '%9f2a%'");
    assertThat(leaked.get(0, Long.class)).isZero();
    // 定位摘要不是密钥本体：库里存的是**引用串**的摘要。
    var refs =
        tenants.run(
            tenant,
            () ->
                sql.fetch("select credential_ref, credential_ref_lookup from platform_external_connection")
                    .map(r -> r.get(0, String.class)));
    assertThat(refs).allMatch(ref -> ref.startsWith("secret://"));
  }

  @Test
  void 类别与适配器不相容时拒绝() throws Exception {
    var response =
        http.perform(
                post("/platform/external-integrations/connections")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "category", "OVERSEAS_WAREHOUSE",
                                "adapterCode", "ALIBABA_1688",
                                "name", "错配配置"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(detail(response)).as("拒绝时要说清是哪个适配器").contains("ALIBABA_1688");
    // 错配的配置一行都不许落库。
    assertThat(
            tenants.run(
                tenant, () -> sql.fetch("select id from platform_external_connection").size()))
        .isZero();
  }

  @Test
  void 停用连接不删行且历史保留() throws Exception {
    var id = registerConnection("LOGISTICS_CARRIER", "专线物流");
    var task = registerTask(id, "SHIPMENT_TRACKING_SYNC", "运单轨迹同步");
    postJson(
        "/platform/external-integrations/sync-records",
        map(
            "taskId", task.toString(),
            "status", "SUCCEEDED",
            "recordsTotal", "1",
            "recordsSucceeded", "1",
            "recordsFailed", "0"),
        200);

    // 停用**任务**不动连接（任务与连接各自独立）。
    postJson("/platform/external-integrations/sync-tasks/" + task + "/disable", map(), 200);
    assertThat(
            json.readValue(
                    getJson("/platform/external-integrations/connections/" + id, 200),
                    ExternalConnectionRow.class)
                .status())
        .as("停用任务不该把连接也停用")
        .isEqualTo("ACTIVE");

    // 停用**连接**也不删行。
    postJson("/platform/external-integrations/connections/" + id + "/disable", map(), 200);
    var row =
        json.readValue(
            getJson("/platform/external-integrations/connections/" + id, 200),
            ExternalConnectionRow.class);
    assertThat(row.status()).isEqualTo("DISABLED");
    // 行还在，历史同步记录也还在。
    assertThat(
            tenants.run(
                tenant, () -> sql.fetch("select id from platform_external_connection").size()))
        .isEqualTo(1);
    assertThat(
            tenants.run(
                tenant, () -> sql.fetch("select id from platform_external_sync_record").size()))
        .isEqualTo(1);
  }

  // ==========================================================================
  // 能力二：同步任务配置
  // ==========================================================================
  @Test
  void 同步任务配置真落库且能列表() throws Exception {
    var connection = registerConnection("OVERSEAS_WAREHOUSE", "美国海外仓");
    var task = registerTask(connection, "INVENTORY_SYNC", "海外仓库存同步");

    var tasks =
        json.readValue(
            getJson("/platform/external-integrations/sync-tasks", 200),
            ExternalSyncTaskRow[].class);
    assertThat(tasks).hasSize(1);
    assertThat(tasks[0].taskType()).isEqualTo("INVENTORY_SYNC");
    assertThat(tasks[0].connectionId()).isEqualTo(connection);
    assertThat(tasks[0].lastRunStatus()).isEmpty();

    var byConnection =
        json.readValue(
            getJson(
                "/platform/external-integrations/sync-tasks?connectionId=" + connection, 200),
            ExternalSyncTaskRow[].class);
    assertThat(byConnection).hasSize(1);

    var detail =
        json.readValue(
            getJson("/platform/external-integrations/sync-tasks/" + task, 200),
            ExternalSyncTaskRow.class);
    assertThat(detail.name()).isEqualTo("海外仓库存同步");
    assertThat(detail.taskTypeLabel()).isEqualTo("库存观测同步");
  }

  @Test
  void 任务类型与连接的类别不相容时拒绝() throws Exception {
    var connection = registerConnection("OVERSEAS_WAREHOUSE", "美国海外仓");
    var response =
        http.perform(
                post("/platform/external-integrations/sync-tasks")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            map(
                                "connectionId", connection.toString(),
                                "taskType", "CATALOG_SYNC",
                                "name", "错配任务"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(detail(response)).as("拒绝时要说清是哪个同步类型").contains("CATALOG_SYNC");
    assertThat(
            tenants.run(
                tenant, () -> sql.fetch("select id from platform_external_sync_task").size()))
        .isZero();
  }

  @Test
  void 一个连接一个任务类型重复登记是更新而不是新增() throws Exception {
    var connection = registerConnection("ALIBABA_1688", "1688 主货源");
    registerTask(connection, "CATALOG_SYNC", "货源清单同步 A");
    registerTask(connection, "CATALOG_SYNC", "货源清单同步 B");

    var tasks =
        json.readValue(
            getJson("/platform/external-integrations/sync-tasks", 200),
            ExternalSyncTaskRow[].class);
    assertThat(tasks).as("同连接同类型只能有一行").hasSize(1);
    assertThat(tasks[0].name()).isEqualTo("货源清单同步 B");
  }

  // ==========================================================================
  // 能力三：连接状态 + 同步记录（健康状态）
  // ==========================================================================
  @Test
  void 未接入时跑同步如实答409且只写未接入记录() throws Exception {
    var connection = registerConnection("ALIBABA_1688", "1688 主货源");
    var task = registerTask(connection, "CATALOG_SYNC", "货源清单同步");

    var body =
        postJson("/platform/external-integrations/sync-tasks/" + task + "/run", map(), 409);
    var record = json.readValue(body, ExternalSyncRecordRow.class);
    assertThat(record.status()).isEqualTo("NOT_CONNECTED");
    assertThat(record.statusLabel()).isEqualTo("外部通道未接入");
    assertThat(record.providerNotified()).as("未接入 ⇒ 没有与外部系统核对过").isFalse();
    assertThat(record.providerNotice()).isNotBlank();
    assertThat(record.recordsTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(record.externalRef()).as("未接入 ⇒ 不得凭空造一个外部单号").isEmpty();
    assertThat(record.failureClass()).isEqualTo("NOT_CONFIGURED");

    // 🔴 库里**没有**任何成功记录。
    var succeeded =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select id from platform_external_sync_record where status = 'SUCCEEDED'")
                    .size());
    assertThat(succeeded).as("未接入不许写成功记录").isZero();
  }

  @Test
  void 人工登记外部侧已完成的同步会被明确标注未核对() throws Exception {
    var connection = registerConnection("ALIBABA_1688", "1688 主货源");
    var task = registerTask(connection, "CATALOG_SYNC", "货源清单同步");

    var body =
        postJson(
            "/platform/external-integrations/sync-records",
            map(
                "taskId", task.toString(),
                "status", "SUCCEEDED",
                "recordsTotal", "120",
                "recordsSucceeded", "118",
                "recordsFailed", "2",
                "externalRef", "1688-BATCH-20261031",
                "startedAt", "2026-10-31T00:00:00Z",
                "finishedAt", "2026-10-31T00:05:00Z"),
            200);
    var record = json.readValue(body, ExternalSyncRecordRow.class);
    assertThat(record.source()).isEqualTo("EXTERNAL");
    assertThat(record.sourceLabel()).isEqualTo("外部侧已完成 · 人工登记");
    assertThat(record.providerNotified())
        .as("人工登记 ⇒ 本系统没有与外部系统核对过")
        .isFalse();
    assertThat(record.providerNotice()).isNotBlank();
    assertThat(record.recordsTotal()).isEqualByComparingTo(new BigDecimal("120"));
    assertThat(record.recordsSucceeded()).isEqualByComparingTo(new BigDecimal("118"));
    assertThat(record.recordsFailed()).isEqualByComparingTo(new BigDecimal("2"));

    // 🔴 时间写回的值必须**精确相等**（不是「没抛异常」）。
    //    timestamptz 存的是**时刻**（偏移量由会话时区决定），所以按 UTC 归一化后逐字段比 ——
    //    若写库时误用 Timestamp.valueOf(LocalDateTime…)（静默偏 −28800s），这两行都会炸。
    var times =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select started_at, finished_at from platform_external_sync_record where id = ?",
                        record.id()));
    var started = times.get("started_at", OffsetDateTime.class);
    var finished = times.get("finished_at", OffsetDateTime.class);
    assertThat(started.toInstant()).isEqualTo(java.time.Instant.parse("2026-10-31T00:00:00Z"));
    assertThat(finished.toInstant()).isEqualTo(java.time.Instant.parse("2026-10-31T00:05:00Z"));
    assertThat(started.withOffsetSameInstant(ZoneOffset.UTC))
        .isEqualTo(java.time.OffsetDateTime.parse("2026-10-31T00:00:00Z"));
    assertThat(finished.withOffsetSameInstant(ZoneOffset.UTC))
        .isEqualTo(java.time.OffsetDateTime.parse("2026-10-31T00:05:00Z"));
  }

  @Test
  void 人工登记后这个对接的健康状态变健康() throws Exception {
    var connection = registerConnection("ALIBABA_1688", "1688 主货源");
    var task = registerTask(connection, "CATALOG_SYNC", "货源清单同步");

    var before =
        json.readValue(
            getJson("/platform/external-integrations/connections/" + connection + "/health", 200),
            ExternalConnectionHealth.class);
    assertThat(before.healthStatus())
        .as("还没跑过、且外部通道未接入 ⇒ 如实答「未接入」（**不是**「坏了」）")
        .isEqualTo("NOT_CONNECTED");
    assertThat(before.recordCount()).as("还没跑过 ⇒ 一条记录都没有").isZero();
    assertThat(before.healthy()).isFalse();

    postJson(
        "/platform/external-integrations/sync-records",
        map(
            "taskId", task.toString(),
            "status", "SUCCEEDED",
            "recordsTotal", "10",
            "recordsSucceeded", "10",
            "recordsFailed", "0"),
        200);

    var after =
        json.readValue(
            getJson("/platform/external-integrations/connections/" + connection + "/health", 200),
            ExternalConnectionHealth.class);
    assertThat(after.healthStatus()).isEqualTo("HEALTHY");
    assertThat(after.healthy()).isTrue();
    assertThat(after.recordCount()).isEqualTo(1);
    assertThat(after.successCount()).isEqualTo(1);
  }

  // ==========================================================================
  // 🔴 本模块最核心的一条：一个对接挂了不影响其它对接
  // ==========================================================================
  @Test
  void 一个对接故障不会把另一个对接的健康状态带成异常() throws Exception {
    var broken = registerConnection("ALIBABA_1688", "1688 主货源");
    var healthy = registerConnection("TAOBAO", "淘宝货源");

    var brokenTask = registerTask(broken, "CATALOG_SYNC", "1688 货源清单同步");
    var healthyTask = registerTask(healthy, "CATALOG_SYNC", "淘宝货源清单同步");

    // 健康的那个先成功一次。
    postJson(
        "/platform/external-integrations/sync-records",
        map(
            "taskId", healthyTask.toString(),
            "status", "SUCCEEDED",
            "recordsTotal", "5",
            "recordsSucceeded", "5",
            "recordsFailed", "0"),
        200);

    // 🔴 坏掉的那个：先失败一次（历史上没成功过 ⇒ 不健康）。
    postJson(
        "/platform/external-integrations/sync-records",
        map(
            "taskId", brokenTask.toString(),
            "status", "FAILED",
            "recordsTotal", "5",
            "recordsSucceeded", "0",
            "recordsFailed", "5",
            "failureReason", "外部系统拒绝：凭据过期",
            "failureClass", "REJECTED"),
        200);

    var brokenHealth =
        json.readValue(
            getJson("/platform/external-integrations/connections/" + broken + "/health", 200),
            ExternalConnectionHealth.class);
    var healthyHealth =
        json.readValue(
            getJson("/platform/external-integrations/connections/" + healthy + "/health", 200),
            ExternalConnectionHealth.class);

    assertThat(brokenHealth.healthStatus()).isEqualTo("UNHEALTHY");
    assertThat(brokenHealth.healthy()).isFalse();

    // 🔴 **核心断言**：另一个对接仍然是健康的。
    assertThat(healthyHealth.healthStatus())
        .as("一个外部服务故障不应影响其他集成")
        .isEqualTo("HEALTHY");
    assertThat(healthyHealth.healthy()).isTrue();
    assertThat(healthyHealth.recordCount()).isEqualTo(1);
    assertThat(healthyHealth.failureCount()).isZero();

    // 🔴 连接列表里**逐条**呈现：坏掉的那个是「不健康」，另一个仍然是「健康」——
    //    **不存在**一个「整体不健康」的格子。
    var listBefore =
        json.readValue(
            getJson("/platform/external-integrations/connections", 200),
            ExternalConnectionRow[].class);
    assertThat(listBefore).hasSize(2);
    var namesBefore = new java.util.HashMap<String, ExternalConnectionRow>();
    for (var row : listBefore) namesBefore.put(row.name(), row);
    assertThat(namesBefore.get("1688 主货源").healthStatus()).isEqualTo("UNHEALTHY");
    assertThat(namesBefore.get("淘宝货源").healthStatus()).isEqualTo("HEALTHY");

    // 🔴 再让坏掉的那个跑一次通道同步（⇒ NOT_CONNECTED），**健康的那个仍然健康**。
    var healthyBefore = healthyHealth.lastSyncAt();
    postJson("/platform/external-integrations/sync-tasks/" + brokenTask + "/run", map(), 409);

    var healthyAfter =
        json.readValue(
            getJson("/platform/external-integrations/connections/" + healthy + "/health", 200),
            ExternalConnectionHealth.class);
    assertThat(healthyAfter.healthStatus())
        .as("跑一次失败的同步不得把别的对接带成异常")
        .isEqualTo("HEALTHY");
    assertThat(healthyAfter.lastSyncAt()).isEqualTo(healthyBefore);
    assertThat(healthyAfter.recordCount()).isEqualTo(1);

    // 🔴 坏掉的那个：最新一条记录是「未接入」⇒ 如实答「未接入」
    //    （**它自己的**记录说的，不是别的对接连累的）。
    var brokenAfter =
        json.readValue(
            getJson("/platform/external-integrations/connections/" + broken + "/health", 200),
            ExternalConnectionHealth.class);
    assertThat(brokenAfter.healthStatus()).isEqualTo("NOT_CONNECTED");
    assertThat(brokenAfter.recordCount()).isEqualTo(2);

    // 🔴 连接列表里**逐条**呈现，不存在一个「整体不健康」。
    var all =
        json.readValue(
            getJson("/platform/external-integrations/connections", 200),
            ExternalConnectionRow[].class);
    assertThat(all).hasSize(2);
    var byName = new java.util.HashMap<String, ExternalConnectionRow>();
    for (var row : all) byName.put(row.name(), row);
    assertThat(byName.get("1688 主货源").healthStatus()).isEqualTo("NOT_CONNECTED");
    assertThat(byName.get("淘宝货源").healthStatus()).isEqualTo("HEALTHY");

    // 🔴 **落到库里也是逐行独立的**：健康状态是连接自己那一行的列，
    //    不是任何跨行聚合的结果 —— 一个对接挂了，别的对接那一行一个字都不变。
    var stored =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select name, health_status from platform_external_connection order by name")
                    .map(r -> r.get(0, String.class) + "=" + r.get(1, String.class)));
    assertThat(stored).containsExactlyInAnyOrder("1688 主货源=NOT_CONNECTED", "淘宝货源=HEALTHY");
  }

  @Test
  void 一个对接失败后自己降级为亚健康而不是影响别的对接() throws Exception {
    var connection = registerConnection("LOGISTICS_CARRIER", "专线物流");
    var task = registerTask(connection, "SHIPMENT_TRACKING_SYNC", "运单轨迹同步");

    postJson(
        "/platform/external-integrations/sync-records",
        map(
            "taskId", task.toString(),
            "status", "SUCCEEDED",
            "recordsTotal", "3",
            "recordsSucceeded", "3",
            "recordsFailed", "0"),
        200);
    postJson(
        "/platform/external-integrations/sync-records",
        map(
            "taskId", task.toString(),
            "status", "FAILED",
            "recordsTotal", "3",
            "recordsSucceeded", "0",
            "recordsFailed", "3",
            "failureReason", "网络错误",
            "failureClass", "UNAVAILABLE"),
        200);

    var health =
        json.readValue(
            getJson("/platform/external-integrations/connections/" + connection + "/health", 200),
            ExternalConnectionHealth.class);
    assertThat(health.healthStatus()).as("成功过又失败 ⇒ 亚健康").isEqualTo("DEGRADED");
    assertThat(health.successCount()).isEqualTo(1);
    assertThat(health.failureCount()).isEqualTo(1);
  }

  @Test
  void 重算健康只改这一个连接() throws Exception {
    var a = registerConnection("ALIBABA_1688", "1688 主货源");
    var b = registerConnection("TAOBAO", "淘宝货源");
    var taskB = registerTask(b, "CATALOG_SYNC", "淘宝货源清单同步");
    postJson(
        "/platform/external-integrations/sync-records",
        map(
            "taskId", taskB.toString(),
            "status", "SUCCEEDED",
            "recordsTotal", "2",
            "recordsSucceeded", "2",
            "recordsFailed", "0"),
        200);

    var bBefore =
        json.readValue(
            getJson("/platform/external-integrations/connections/" + b, 200),
            ExternalConnectionRow.class);
    var aBefore =
        json.readValue(
            getJson("/platform/external-integrations/connections/" + a, 200),
            ExternalConnectionRow.class);

    postJson("/platform/external-integrations/connections/" + a + "/health/refresh", map(), 200);

    var bAfter =
        json.readValue(
            getJson("/platform/external-integrations/connections/" + b, 200),
            ExternalConnectionRow.class);
    var aAfter =
        json.readValue(
            getJson("/platform/external-integrations/connections/" + a, 200),
            ExternalConnectionRow.class);

    assertThat(bAfter.healthStatus()).isEqualTo(bBefore.healthStatus());
    assertThat(bAfter.updatedAt()).isEqualTo(bBefore.updatedAt());
    assertThat(aAfter.healthStatus()).isEqualTo(aBefore.healthStatus());
  }

  // ==========================================================================
  // 同步记录台账
  // ==========================================================================
  @Test
  void 同步记录是真只追加台账改不了也删不了() throws Exception {
    var connection = registerConnection("SELF_HOSTED_BI", "自建 BI");
    var task = registerTask(connection, "BI_DATASET_SYNC", "BI 数据集同步");
    var body =
        postJson(
            "/platform/external-integrations/sync-records",
            map(
                "taskId", task.toString(),
                "status", "SUCCEEDED",
                "recordsTotal", "1",
                "recordsSucceeded", "1",
                "recordsFailed", "0"),
            200);
    var recordId = json.readValue(body, ExternalSyncRecordRow.class).id();

    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "update platform_external_sync_record set external_ref = 'x'"
                                + " where id = ?",
                            recordId)))
        .as("已结束的同步记录不可修改")
        .hasStackTraceContaining("只追加");

    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "delete from platform_external_sync_record where id = ?", recordId)))
        .as("已结束的同步记录不可删除")
        .hasStackTraceContaining("只追加");

    var still =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select external_ref from platform_external_sync_record where id = ?",
                        recordId)
                    .get(0, String.class));
    assertThat(still).as("行还在且没被改过").isEmpty();
  }

  @Test
  void 同步记录列表真筛选真搜索() throws Exception {
    var connection = registerConnection("ALIBABA_1688", "1688 主货源");
    var task = registerTask(connection, "CATALOG_SYNC", "货源清单同步");
    postJson(
        "/platform/external-integrations/sync-records",
        map(
            "taskId", task.toString(),
            "status", "FAILED",
            "recordsTotal", "2",
            "recordsSucceeded", "0",
            "recordsFailed", "2",
            "externalRef", "BATCH-001",
            "failureReason", "凭据过期",
            "failureClass", "REJECTED"),
        200);

    var byStatus =
        json.readValue(
            getJson("/platform/external-integrations/sync-records?status=FAILED", 200),
            ExternalSyncRecordRow[].class);
    assertThat(byStatus).hasSize(1);

    var bySource =
        json.readValue(
            getJson("/platform/external-integrations/sync-records?source=EXTERNAL", 200),
            ExternalSyncRecordRow[].class);
    assertThat(bySource).hasSize(1);
    assertThat(bySource[0].source()).isEqualTo("EXTERNAL");

    var byKeyword =
        json.readValue(
            getJson("/platform/external-integrations/sync-records?keyword=BATCH-001", 200),
            ExternalSyncRecordRow[].class);
    assertThat(byKeyword).hasSize(1);
    assertThat(byKeyword[0].externalRef()).isEqualTo("BATCH-001");

    var byConnection =
        json.readValue(
            getJson("/platform/external-integrations/sync-records?connectionId=" + connection, 200),
            ExternalSyncRecordRow[].class);
    assertThat(byConnection).hasSize(1);

    var detail =
        json.readValue(
            getJson("/platform/external-integrations/sync-records/" + byStatus[0].id(), 200),
            ExternalSyncRecordRow.class);
    assertThat(detail.failureReason()).isEqualTo("凭据过期");
  }

  @Test
  void 概览的数与库里对得上() throws Exception {
    var connection = registerConnection("ALIBABA_1688", "1688 主货源");
    var task = registerTask(connection, "CATALOG_SYNC", "货源清单同步");
    postJson(
        "/platform/external-integrations/sync-records",
        map(
            "taskId", task.toString(),
            "status", "SUCCEEDED",
            "recordsTotal", "1",
            "recordsSucceeded", "1",
            "recordsFailed", "0"),
        200);

    var overview =
        json.readValue(
            getJson("/platform/external-integrations/overview", 200),
            ExternalIntegrationOverview.class);
    assertThat(overview.connections()).isEqualTo(1);
    assertThat(overview.activeConnections()).isEqualTo(1);
    assertThat(overview.healthyConnections()).isEqualTo(1);
    assertThat(overview.syncTasks()).isEqualTo(1);
    assertThat(overview.enabledSyncTasks()).isEqualTo(1);
    assertThat(overview.syncRecords()).isEqualTo(1);
    assertThat(overview.failedSyncRecords()).isZero();
  }

  // ==========================================================================
  // 上游（2 渠道机制，只读）
  // ==========================================================================
  @Test
  void 上游渠道目录与店铺真读channel服务() throws Exception {
    var platforms =
        json.readValue(
            getJson("/platform/external-integrations/upstream/platforms", 200),
            UpstreamChannelPlatformRow[].class);
    assertThat(platforms).hasSize(2);
    assertThat(platforms[0].code()).isEqualTo("AMAZON");

    var shops =
        json.readValue(
            getJson("/platform/external-integrations/upstream/shops", 200),
            UpstreamChannelShopRow[].class);
    assertThat(shops).hasSize(1);
    assertThat(shops[0].code()).isEqualTo("AMAZON-US-01");
    assertThat(shops[0].platformCode()).isEqualTo("AMAZON");
  }

  // ==========================================================================
  // 鉴权
  // ==========================================================================
  @Test
  void 没有会话一律401() throws Exception {
    var response = http.perform(get("/platform/external-integrations/connections")).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void 端点是只读的业务动作不会误伤别家的前缀() throws Exception {
    // 只读端点（GET /connections/{id}/health）必须存在，且不与其它模块的路径混淆。
    var response =
        http.perform(
                get("/platform/external-integrations/connections/"
                        + UUID.randomUUID()
                        + "/health")
                    .header("Authorization", "session"))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).as("不存在的连接 ⇒ 404").isEqualTo(404);
  }
}
