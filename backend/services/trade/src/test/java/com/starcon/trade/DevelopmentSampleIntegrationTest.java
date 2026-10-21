package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
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
 * 6.6.1.2 打样与样品管理的集成测试。
 *
 * <p><b>本测试是 V261 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，逐条在真实
 * PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不是直连数据库，以便同时验证「服务层 + DB 层」
 * 两层都没有把闸门漏掉。
 *
 * <p><b>catalog 起本地 stub 服务</b>：供应商（6.1.1.x）与媒体库（3.4.1.1）都在 catalog 服务，
 * 本模块跨服务一律走端口，禁跨库 SQL / FK / 视图。stub 只负责按上游契约形状返回数。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DevelopmentSampleIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static final UUID readOnlyUser = UUID.randomUUID();
  static HttpServer identity, catalog;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      List.of(
          "trade.development-sample.read",
          "trade.development-sample.update",
          // 上游 6.6.1.1 开发立项的权限：测试要**真实**建一个立项当上游事实
          // （打样单必须挂在立项上，否则「挂不上立项」会先于本模块的逻辑生效）。
          "trade.development-project.read",
          "trade.development-project.update");

  /** 供应商角色 id → 供应商（catalog 侧事实，stub 用）。 */
  static final Map<UUID, Map<String, Object>> suppliersByRole = new ConcurrentHashMap<>();

  /** 媒体 id → 媒体元数据（catalog 侧事实，stub 用）。 */
  static final Map<UUID, Map<String, Object>> mediaById = new ConcurrentHashMap<>();

  static AccessService.Access actor(UUID uid, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static void write(HttpServer server, com.sun.net.httpserver.HttpExchange e, int code, Object body)
      throws java.io.IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
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
      actors.put("Bearer owner", actor(user, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer reader",
          actor(readOnlyUser, List.of(grant("ALL", List.of("trade.development-sample.read")))));
      identity.start();

      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      // 供应商：6.1.1.1 的形状（GET /catalog/supply/vendors/{roleId}）。
      catalog.createContext(
          "/catalog/supply/vendors",
          e -> {
            String path = e.getRequestURI().getPath();
            String roleId = path.substring(path.lastIndexOf('/') + 1);
            Map<String, Object> row = suppliersByRole.get(UUID.fromString(roleId));
            if (row == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            write(catalog, e, 200, Map.of("summary", row));
          });
      // 媒体库：3.4.1.1 的形状（GET /catalog/media 与 GET /catalog/media/{id}）。
      catalog.createContext(
          "/catalog/media",
          e -> {
            String path = e.getRequestURI().getPath();
            if (path.length() > "/catalog/media".length()) {
              String id = path.substring(path.lastIndexOf('/') + 1);
              Map<String, Object> row = mediaById.get(UUID.fromString(id));
              if (row == null) {
                e.sendResponseHeaders(404, -1);
                e.close();
                return;
              }
              write(catalog, e, 200, Map.of("asset", row));
              return;
            }
            List<Map<String, Object>> items = List.copyOf(mediaById.values());
            write(catalog, e, 200, Map.of("items", items, "total", items.size(), "page", 0, "size", 50));
          });
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    catalog.stop(0);
    pg.stop();
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @BeforeEach
  void seed() throws Exception {
    suppliersByRole.clear();
    mediaById.clear();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate development_sample_cost_line, development_sample_cost,"
              + " development_sample_media, development_sample_shipment,"
              + " development_sample_confirmation, development_sample_transition,"
              + " development_sample, development_sample_order_transition,"
              + " development_sample_order cascade");
      s.execute(
          "truncate development_project_handoff, development_project_market_observation,"
              + " development_project_spec_candidate, development_project_transition,"
              + " development_project_milestone, development_project_market,"
              + " development_project cascade");
      s.execute("truncate supplier_source_stall cascade");
    }
  }

  // ================================================================ 工具

  /** 上游 6.6.1.1 开发立项（真实走它的端点建单，本模块只读引用它）。 */
  String createProject(String projectNo) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("projectNo", projectNo);
    body.put("name", "折叠水杯");
    body.put("category", "家居");
    body.put("ownerId", user.toString());
    body.put("ownerName", "张三");
    body.put("targetPrice", new BigDecimal("100.00"));
    body.put("currency", "USD");
    body.put("targetCost", new BigDecimal("60.00"));
    String response =
        http.perform(
                post("/trade/development-projects")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response).get("project").get("id").asText();
  }

  /** 上游 6.1.2.1 档口（直接写它的表 —— 本模块只读引用它）。 */
  String createStall(String code) throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "insert into supplier_source_stall(id,tenant_id,code,platform,platform_stall_id,name,"
              + " collect_method) values ('"
              + id
              + "'::uuid,'"
              + tenant
              + "'::uuid,'"
              + code
              + "','ALIBABA_1688','SHOP-"
              + code
              + "','档口-"
              + code
              + "','URL_SYNC')");
    }
    return id.toString();
  }

  /** catalog 侧的供应商（stub）。 */
  UUID registerSupplier() {
    UUID roleId = UUID.randomUUID();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("roleId", roleId.toString());
    row.put("partyId", UUID.randomUUID().toString());
    row.put("partyCode", "SUP-001");
    row.put("partyName", "义乌一号工厂");
    row.put("partyStatus", "ACTIVE");
    suppliersByRole.put(roleId, row);
    return roleId;
  }

  /** catalog 侧的媒体（stub）。 */
  UUID registerMedia(String name) {
    UUID id = UUID.randomUUID();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", id.toString());
    row.put("name", name);
    row.put("mediaType", "IMAGE");
    row.put("fileFormat", "png");
    row.put("mimeType", "image/png");
    row.put("byteSize", new BigDecimal("20480"));
    row.put("widthPx", new BigDecimal("800"));
    row.put("heightPx", new BigDecimal("600"));
    row.put("contentSha256", "abc123");
    row.put("storageKey", "media/2026/10/" + id);
    mediaById.put(id, row);
    return id;
  }

  Map<String, Object> orderBody(
      String orderNo, String projectId, String sourceType, UUID supplierRefId, UUID stallId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("orderNo", orderNo);
    body.put("developmentProjectId", projectId);
    body.put("sourceType", sourceType);
    body.put("usageType", "DEVELOPMENT");
    if (supplierRefId != null) {
      body.put("supplierRefId", supplierRefId.toString());
    }
    if (stallId != null) {
      body.put("stallId", stallId.toString());
    }
    body.put("currency", "USD");
    body.put("requesterId", user.toString());
    body.put("requesterName", "张三");
    body.put("requestedAt", "2026-10-21T09:00:00Z");
    body.put("expectedDeliveryAt", "2026-10-28T09:00:00Z");
    body.put("purposeNote", "验证杯盖密封性");
    return body;
  }

  String createOrder(String orderNo, String projectId, String sourceType, UUID supplierRefId, UUID stallId)
      throws Exception {
    String response =
        http.perform(
                post("/trade/development-samples")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(orderBody(orderNo, projectId, sourceType, supplierRefId, stallId))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response).get("order").get("id").asText();
  }

  String createSample(String orderId, String sampleNo) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sampleNo", sampleNo);
    body.put("specName", "颜色");
    body.put("specValue", "蓝");
    body.put("quantity", new BigDecimal("2.000000"));
    String response =
        http.perform(
                post("/trade/development-samples/" + orderId + "/samples")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response).get("sample").get("id").asText();
  }

  /** 把样品推进到「已到样」（走真实端点，逐条留痕）。 */
  void advanceToReceived(String orderId, String sampleId) throws Exception {
    advance(orderId, sampleId, "MAKING", "SHIPPED", "RECEIVED");
  }

  /** 依次推进样品状态（每次一步，每步都留一条不可改写的事件）。 */
  void advance(String orderId, String sampleId, String... targets) throws Exception {
    for (String to : targets) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("toStatus", to);
      body.put("reason", "推进到 " + to);
      var result =
          http.perform(
                  post(
                          "/trade/development-samples/"
                              + orderId
                              + "/samples/"
                              + sampleId
                              + "/transitions")
                      .header("Authorization", "Bearer owner")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(json.writeValueAsString(body)))
              .andReturn();
      assertThat(result.getResponse().getStatus())
          .as("推进到 %s 失败：%s", to, result.getResponse().getContentAsString())
          .isEqualTo(200);
    }
  }

  void confirm(String orderId, String sampleId, String result, String reason, String issueNote)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", result);
    body.put("reason", reason);
    if (issueNote != null) {
      body.put("issueNote", issueNote);
    }
    http.perform(
            post(
                    "/trade/development-samples/"
                        + orderId
                        + "/samples/"
                        + sampleId
                        + "/confirmations")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  // ================================================================ 快乐路径

  @Test
  void 建打样单登记样品推进确认全链路() throws Exception {
    String projectId = createProject("DP-1");
    UUID supplier = registerSupplier();
    String orderId = createOrder("SO-1", projectId, "SUPPLIER", supplier, null);

    String detail =
        http.perform(get("/trade/development-samples/" + orderId).header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var order = json.readTree(detail).get("order");
    assertThat(order.get("status").asText()).isEqualTo("REQUESTED");
    assertThat(order.get("statusLabel").asText()).isEqualTo("已申请");
    assertThat(order.get("sourceType").asText()).isEqualTo("SUPPLIER");
    assertThat(order.get("sourceTypeLabel").asText()).isEqualTo("供应商打样");
    assertThat(order.get("usageType").asText()).isEqualTo("DEVELOPMENT");
    // 立项快照：只读引用 + 展示快照。
    assertThat(order.get("developmentProjectId").asText()).isEqualTo(projectId);
    assertThat(order.get("projectNo").asText()).isEqualTo("DP-1");
    assertThat(order.get("projectName").asText()).isEqualTo("折叠水杯");
    // 供应商快照取自 catalog（不采信客户端自报的名字）。
    assertThat(order.get("supplierName").asText()).isEqualTo("义乌一号工厂");

    String sampleId = createSample(orderId, "SP-1");
    advanceToReceived(orderId, sampleId);
    confirm(orderId, sampleId, "PASSED", "与图纸一致", null);

    String sampleDetail =
        http.perform(
                get("/trade/development-samples/" + orderId + "/samples/" + sampleId)
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var sample = json.readTree(sampleDetail).get("sample");
    assertThat(sample.get("status").asText()).isEqualTo("CONFIRMED");
    assertThat(sample.get("statusLabel").asText()).isEqualTo("已确认");
    assertThat(sample.get("quantity").asDouble()).isEqualTo(2.0d);
    var confirmations = json.readTree(sampleDetail).get("confirmations");
    assertThat(confirmations).hasSize(1);
    assertThat(confirmations.get(0).get("result").asText()).isEqualTo("PASSED");
    assertThat(confirmations.get(0).get("resultLabel").asText()).isEqualTo("通过");
    assertThat(confirmations.get(0).get("confirmSeq").asInt()).isEqualTo(1);
  }

  @Test
  void 打样单与样品是零到一关系一台账一件() throws Exception {
    String projectId = createProject("DP-CARD");
    String orderId = createOrder("SO-CARD", projectId, "OWN_FACTORY", null, null);
    createSample(orderId, "SP-C1");
    createSample(orderId, "SP-C2");

    String detail =
        http.perform(get("/trade/development-samples/" + orderId).header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var node = json.readTree(detail);
    assertThat(node.get("order").get("sampleCount").asInt()).isEqualTo(2);
    assertThat(node.get("samples")).hasSize(2);
  }

  @Test
  void 样品台账跨打样单可查() throws Exception {
    String projectId = createProject("DP-LEDGER");
    String order1 = createOrder("SO-L1", projectId, "OWN_FACTORY", null, null);
    String order2 = createOrder("SO-L2", projectId, "OWN_FACTORY", null, null);
    createSample(order1, "SP-L1");
    createSample(order2, "SP-L2");

    String response =
        http.perform(get("/trade/development-samples/samples").header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(response).get("total").asLong()).isEqualTo(2);
  }

  // ================================================================ 上游只读引用

  @Test
  void 打样单挂不上不存在的立项() throws Exception {
    http.perform(
            post("/trade/development-samples")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        orderBody("SO-NOPROJ", UUID.randomUUID().toString(), "OWN_FACTORY", null, null))))
        .andExpect(status().isNotFound());
  }

  @Test
  void 已终止的立项不接受新打样单() throws Exception {
    String projectId = createProject("DP-TERM");
    // 把立项推进到终止（走 6.6.1.1 的真实端点）。
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", "TERMINATED");
    body.put("reason", "产品线砍掉");
    http.perform(
            post("/trade/development-projects/" + projectId + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());

    http.perform(
            post("/trade/development-samples")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(orderBody("SO-TERM", projectId, "OWN_FACTORY", null, null))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 档口打样只引用不改动档口() throws Exception {
    String projectId = createProject("DP-STALL");
    String stallId = createStall("ST-1");
    String orderId = createOrder("SO-STALL", projectId, "STALL", null, UUID.fromString(stallId));

    String detail =
        http.perform(get("/trade/development-samples/" + orderId).header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var order = json.readTree(detail).get("order");
    assertThat(order.get("stallId").asText()).isEqualTo(stallId);
    assertThat(order.get("stallCode").asText()).isEqualTo("ST-1");
    assertThat(order.get("stallName").asText()).isEqualTo("档口-ST-1");
    // 档口的事实仍归 6.1.2.1：本模块一个字都没改。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery("select code, name from supplier_source_stall where id = '" + stallId + "'::uuid")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("code")).isEqualTo("ST-1");
      assertThat(rs.getString("name")).isEqualTo("档口-ST-1");
    }
  }

  @Test
  void 供应商打样必须挂供应商() throws Exception {
    String projectId = createProject("DP-NOSUP");
    http.perform(
            post("/trade/development-samples")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(orderBody("SO-NOSUP", projectId, "SUPPLIER", null, null))))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 打样 vs 达人寄样

  @Test
  void 非开发打样用途被服务层拒绝() throws Exception {
    String projectId = createProject("DP-USE");
    Map<String, Object> body = orderBody("SO-USE", projectId, "OWN_FACTORY", null, null);
    body.put("usageType", "MARKETING");
    http.perform(
            post("/trade/development-samples")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 达人寄样用途被数据库层拒绝() throws Exception {
    String projectId = createProject("DP-USE-DB");
    String orderId = createOrder("SO-USE-DB", projectId, "OWN_FACTORY", null, null);
    UUID sampleId = UUID.randomUUID();
    // 🔴 直连 SQL 绕开服务层：数据库层必须照样挡住营销寄样。
    //    （样品表上有 BEFORE INSERT 的「来源 / 用途 / 币种必须与打样单一致」守卫，
    //     它先于列 CHECK 触发 —— 两者都拒绝营销寄样，防线是两道。）
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "insert into development_sample(id,tenant_id,sample_no,order_id,"
                          + " development_project_id,project_no,project_name,source_type,usage_type,"
                          + " status,quantity,currency,created_by)"
                          + " values ('"
                          + sampleId
                          + "'::uuid,'"
                          + tenant
                          + "'::uuid,'SP-MKT','"
                          + orderId
                          + "'::uuid,'"
                          + projectId
                          + "'::uuid,'DP-USE-DB','折叠水杯','OWN_FACTORY','MARKETING','REQUESTED',"
                          + " 1,'USD','"
                          + user
                          + "'::uuid)"))
          .hasMessageContaining("用途");
    }
  }

  @Test
  void 打样单本身的用途也被列约束挡住() throws Exception {
    String projectId = createProject("DP-USE-DB2");
    UUID orderId = UUID.randomUUID();
    // 🔴 打样单表上没有守卫触发器 ⇒ 走的是**列 CHECK**（usage_type = 'DEVELOPMENT'）。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "insert into development_sample_order(id,tenant_id,order_no,"
                          + " development_project_id,project_no,project_name,source_type,usage_type,"
                          + " status,currency,requester_id,requested_at,created_by)"
                          + " values ('"
                          + orderId
                          + "'::uuid,'"
                          + tenant
                          + "'::uuid,'SO-MKT','"
                          + projectId
                          + "'::uuid,'DP-USE-DB2','折叠水杯','OWN_FACTORY','MARKETING','REQUESTED',"
                          + " 'USD','"
                          + user
                          + "'::uuid,'2026-10-21T09:00:00Z','"
                          + user
                          + "'::uuid)"))
          .hasMessageContaining("development_sample_order_usage_type_check");
    }
  }

  // ================================================================ 状态机（DB 层）

  @Test
  void 没有迁移事件直接改打样单状态被数据库拒绝() throws Exception {
    String projectId = createProject("DP-GUARD1");
    String orderId = createOrder("SO-GUARD1", projectId, "OWN_FACTORY", null, null);
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update development_sample_order set status = 'IN_PROGRESS'"
                          + " where id = '"
                          + orderId
                          + "'::uuid"))
          .hasMessageContaining("缺少对应迁移事件");
    }
  }

  @Test
  void 没有确认记录直接改样品状态被数据库拒绝() throws Exception {
    String projectId = createProject("DP-GUARD2");
    String orderId = createOrder("SO-GUARD2", projectId, "OWN_FACTORY", null, null);
    String sampleId = createSample(orderId, "SP-GUARD2");
    // 先推进到「已到样」—— 从「已到样」到「已确认」是**合法边**，
    // 这时守卫才会走到「有没有对应的确认记录」那一格。
    advanceToReceived(orderId, sampleId);
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update development_sample set status = 'CONFIRMED' where id = '"
                          + sampleId
                          + "'::uuid"))
          .hasMessageContaining("缺少对应的确认记录");
    }
  }

  @Test
  void 制作进度端点不接受确认类状态() throws Exception {
    String projectId = createProject("DP-EDGE");
    String orderId = createOrder("SO-EDGE", projectId, "OWN_FACTORY", null, null);
    String sampleId = createSample(orderId, "SP-EDGE");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", "CONFIRMED");
    body.put("reason", "直接确认");
    http.perform(
            post("/trade/development-samples/" + orderId + "/samples/" + sampleId + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 样品非法状态边被服务层拒绝() throws Exception {
    String projectId = createProject("DP-EDGE2");
    String orderId = createOrder("SO-EDGE2", projectId, "OWN_FACTORY", null, null);
    String sampleId = createSample(orderId, "SP-EDGE2");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", "SHIPPED");
    body.put("reason", "跳过打样中");
    http.perform(
            post("/trade/development-samples/" + orderId + "/samples/" + sampleId + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 修改后可以重新打样并改完直接通过() throws Exception {
    String projectId = createProject("DP-MODIFY");
    String orderId = createOrder("SO-MODIFY", projectId, "OWN_FACTORY", null, null);
    String sampleId = createSample(orderId, "SP-MODIFY");
    advanceToReceived(orderId, sampleId);
    confirm(orderId, sampleId, "MODIFY", "颜色不对", "改成墨绿色");
    assertThat(statusOf(orderId, sampleId)).isEqualTo("REVISING");

    // 修改中 → 重新打样（轮次 +1）。
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", "MAKING");
    body.put("reason", "按修改意见重新打样");
    http.perform(
            post("/trade/development-samples/" + orderId + "/samples/" + sampleId + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
    assertThat(statusOf(orderId, sampleId)).isEqualTo("MAKING");
    advance(orderId, sampleId, "SHIPPED", "RECEIVED");
    confirm(orderId, sampleId, "PASSED", "改后确认通过", null);
    assertThat(statusOf(orderId, sampleId)).isEqualTo("CONFIRMED");

    String confirmations =
        http.perform(
                get("/trade/development-samples/" + orderId + "/samples/" + sampleId + "/confirmations")
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var rows = json.readTree(confirmations);
    assertThat(rows).hasSize(2);
    // 第几次确认 ≠ 第几轮打样：同一样品在「修改中 → 直接通过」这条路上会在同一轮打样里确认两次。
    assertThat(rows.get(0).get("confirmSeq").asInt()).isEqualTo(2);
    assertThat(rows.get(0).get("result").asText()).isEqualTo("PASSED");
    assertThat(rows.get(1).get("confirmSeq").asInt()).isEqualTo(1);
    assertThat(rows.get(1).get("result").asText()).isEqualTo("MODIFY");
    assertThat(rows.get(1).get("issueNote").asText()).isEqualTo("改成墨绿色");
  }

  @Test
  void 修改结果必须写明修改意见() throws Exception {
    String projectId = createProject("DP-ISSUE");
    String orderId = createOrder("SO-ISSUE", projectId, "OWN_FACTORY", null, null);
    String sampleId = createSample(orderId, "SP-ISSUE");
    advanceToReceived(orderId, sampleId);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", "MODIFY");
    body.put("reason", "要改");
    http.perform(
            post("/trade/development-samples/" + orderId + "/samples/" + sampleId + "/confirmations")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 打样单标记完成要求样品全部终态() throws Exception {
    String projectId = createProject("DP-DONE");
    String orderId = createOrder("SO-DONE", projectId, "OWN_FACTORY", null, null);
    String sampleId = createSample(orderId, "SP-DONE");

    // 样品还在「已申请」⇒ 不许把单子标记为已完成（DB 守卫）。
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", "IN_PROGRESS");
    body.put("reason", "开工");
    http.perform(
            post("/trade/development-samples/" + orderId + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
    Map<String, Object> done = new LinkedHashMap<>();
    done.put("toStatus", "COMPLETED");
    done.put("reason", "打完");
    // 🔴 DB 层拒绝 ⇒ 409（本仓约定：DB 层的 23514 落到 409，服务层的拒绝才是 400）。
    http.perform(
            post("/trade/development-samples/" + orderId + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(done)))
        .andExpect(status().isConflict());

    // 样品落到终态后再试 ⇒ 成功。
    advanceToReceived(orderId, sampleId);
    confirm(orderId, sampleId, "PASSED", "与图纸一致", null);
    http.perform(
            post("/trade/development-samples/" + orderId + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(done)))
        .andExpect(status().isOk());
  }

  // ================================================================ 留痕不可改写

  @Test
  void 确认记录不可改写也不可删() throws Exception {
    String projectId = createProject("DP-IMM");
    String orderId = createOrder("SO-IMM", projectId, "OWN_FACTORY", null, null);
    String sampleId = createSample(orderId, "SP-IMM");
    advanceToReceived(orderId, sampleId);
    confirm(orderId, sampleId, "PASSED", "与图纸一致", null);

    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update development_sample_confirmation set reason = '改个理由'"
                          + " where sample_id = '"
                          + sampleId
                          + "'::uuid"))
          .hasMessageContaining("不可改写");
      assertThatThrownBy(
              () ->
                  s.execute(
                      "delete from development_sample_confirmation where sample_id = '"
                          + sampleId
                          + "'::uuid"))
          .hasMessageContaining("不可删除");
    }
  }

  @Test
  void 打样单状态迁移事件不可改写() throws Exception {
    String projectId = createProject("DP-IMM2");
    String orderId = createOrder("SO-IMM2", projectId, "OWN_FACTORY", null, null);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", "IN_PROGRESS");
    body.put("reason", "开工");
    http.perform(
            post("/trade/development-samples/" + orderId + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());

    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update development_sample_order_transition set reason = '改个理由'"
                          + " where order_id = '"
                          + orderId
                          + "'::uuid"))
          .hasMessageContaining("不可改写");
    }
  }

  // ================================================================ 物流快照

  @Test
  void 物流快照登记并如实标轨迹不可用() throws Exception {
    String projectId = createProject("DP-SHIP");
    String orderId = createOrder("SO-SHIP", projectId, "OWN_FACTORY", null, null);
    String sampleId = createSample(orderId, "SP-SHIP");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trackingNo", "SF1234567890");
    body.put("carrierCode", "SF");
    body.put("carrierName", "顺丰");
    body.put("logisticsStatus", "SHIPPED");
    // 🔴 带 +08:00 偏移的时刻：写回值必须仍是同一个瞬间（不偏 −28800s）。
    body.put("shippedAt", "2026-10-21T08:30:00+08:00");
    body.put("expectedArrivalAt", "2026-10-23T08:30:00+08:00");
    String response =
        http.perform(
                post("/trade/development-samples/" + orderId + "/samples/" + sampleId + "/shipments")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var row = json.readTree(response).get(0);
    assertThat(row.get("trackingNo").asText()).isEqualTo("SF1234567890");
    assertThat(row.get("logisticsStatusLabel").asText()).isEqualTo("已发出");
    // 真实物流轨迹取不到（10 物流未接线）⇒ 如实标。
    assertThat(row.get("trackingAvailable").asBoolean()).isFalse();
    assertThat(row.get("trackingOwnerModule").asText()).isEqualTo("10 物流");
    // 🔴 写回值断言：2026-10-21T08:30+08:00 == 2026-10-21T00:30Z。
    var shippedAt = OffsetDateTime.parse(row.get("shippedAt").asText());
    assertThat(shippedAt.toInstant())
        .as("带偏移的时刻必须原样落库，不许偏 −28800s")
        .isEqualTo(OffsetDateTime.parse("2026-10-21T08:30:00+08:00").toInstant());
    assertThat(shippedAt.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime())
        .isEqualTo(java.time.LocalDateTime.of(2026, 10, 21, 0, 30));
  }

  @Test
  void 签收状态必须填签收时刻() throws Exception {
    String projectId = createProject("DP-SHIP2");
    String orderId = createOrder("SO-SHIP2", projectId, "OWN_FACTORY", null, null);
    String sampleId = createSample(orderId, "SP-SHIP2");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trackingNo", "YT9988");
    body.put("logisticsStatus", "DELIVERED");
    body.put("shippedAt", "2026-10-21T08:30:00+08:00");
    http.perform(
            post("/trade/development-samples/" + orderId + "/samples/" + sampleId + "/shipments")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 样品图片

  @Test
  void 样品图片引用catalog媒体并落元数据快照() throws Exception {
    String projectId = createProject("DP-MEDIA");
    String orderId = createOrder("SO-MEDIA", projectId, "OWN_FACTORY", null, null);
    String sampleId = createSample(orderId, "SP-MEDIA");
    UUID mediaId = registerMedia("杯盖特写");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("mediaRefId", mediaId.toString());
    String response =
        http.perform(
                post("/trade/development-samples/" + orderId + "/samples/" + sampleId + "/media")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var row = json.readTree(response).get(0);
    assertThat(row.get("mediaRefId").asText()).isEqualTo(mediaId.toString());
    assertThat(row.get("mediaName").asText()).isEqualTo("杯盖特写");
    assertThat(row.get("contentSha256").asText()).isEqualTo("abc123");
    assertThat(row.get("byteSize").asDouble()).isEqualTo(20480.0d);
  }

  @Test
  void 挂不存在的媒体被拒() throws Exception {
    String projectId = createProject("DP-MEDIA2");
    String orderId = createOrder("SO-MEDIA2", projectId, "OWN_FACTORY", null, null);
    String sampleId = createSample(orderId, "SP-MEDIA2");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("mediaRefId", UUID.randomUUID().toString());
    http.perform(
            post("/trade/development-samples/" + orderId + "/samples/" + sampleId + "/media")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isNotFound());
  }

  // ================================================================ 样品成本

  @Test
  void 成本合计由明细自动维护且币种一致() throws Exception {
    String projectId = createProject("DP-COST");
    String orderId = createOrder("SO-COST", projectId, "OWN_FACTORY", null, null);

    addCostLine(orderId, "SAMPLE_FEE", "120.00", "打样费");
    addCostLine(orderId, "FREIGHT", "30.50", "往返运费");

    String cost =
        http.perform(get("/trade/development-samples/" + orderId + "/cost").header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var view = json.readTree(cost);
    assertThat(view.get("totalAmount").asDouble()).isEqualTo(150.5d);
    assertThat(view.get("currency").asText()).isEqualTo("USD");
    assertThat(view.get("lines")).hasSize(2);
    // 🔴 进费用归集做不到（12 财务未交付）⇒ 如实标，不假装已入账。
    assertThat(view.get("expensePosted").asBoolean()).isFalse();
    assertThat(view.get("expenseOwnerModule").asText()).isEqualTo("12 费用");
  }

  @Test
  void 成本明细的币种必须与成本头一致被数据库拒绝() throws Exception {
    String projectId = createProject("DP-COST-FX");
    String orderId = createOrder("SO-COST-FX", projectId, "OWN_FACTORY", null, null);
    addCostLine(orderId, "SAMPLE_FEE", "120.00", "打样费");

    // 🔴 用**直连**查成本头 id：trade_app 走 RLS，事务外没有租户上下文 ⇒ 一行都看不见。
    UUID costId;
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select id from development_sample_cost where order_id = '"
                    + orderId
                    + "'::uuid")) {
      assertThat(rs.next()).isTrue();
      costId = rs.getObject("id", UUID.class);
    }
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "insert into development_sample_cost_line(id,tenant_id,cost_id,order_id,cost_type,"
                          + " amount,currency,incurred_at,created_by) values (gen_random_uuid(),'"
                          + tenant
                          + "'::uuid,'"
                          + costId
                          + "'::uuid,'"
                          + orderId
                          + "'::uuid,'FREIGHT',10.00,'CNY','2026-10-21T00:00:00Z','"
                          + user
                          + "'::uuid)"))
          .hasMessageContaining("币种");
    }
  }

  @Test
  void 成本不得声称已经入账被数据库拒绝() throws Exception {
    String projectId = createProject("DP-COST-POST");
    String orderId = createOrder("SO-COST-POST", projectId, "OWN_FACTORY", null, null);
    addCostLine(orderId, "SAMPLE_FEE", "120.00", "打样费");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update development_sample_cost set expense_posted = true where order_id = '"
                          + orderId
                          + "'::uuid"))
          .hasMessageContaining("development_sample_cost_not_posted");
    }
  }

  @Test
  void 删成本明细后合计自动重算() throws Exception {
    String projectId = createProject("DP-COST-DEL");
    String orderId = createOrder("SO-COST-DEL", projectId, "OWN_FACTORY", null, null);
    addCostLine(orderId, "SAMPLE_FEE", "120.00", "打样费");
    addCostLine(orderId, "FREIGHT", "30.50", "运费");

    String cost =
        http.perform(get("/trade/development-samples/" + orderId + "/cost").header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // 🔴 按**成本类型**定位要删的那一行：两行 occurred_at 相同，排序只靠随机 id，
    //    按下标删会删错行，测的就不是「删掉后合计重算」了。
    String lineId =
        java.util.stream.StreamSupport.stream(json.readTree(cost).get("lines").spliterator(), false)
            .filter(n -> n.get("costType").asText().equals("SAMPLE_FEE"))
            .findFirst()
            .orElseThrow()
            .get("id")
            .asText();
    http.perform(
            delete("/trade/development-samples/" + orderId + "/cost/lines/" + lineId)
                .header("Authorization", "Bearer owner"))
        .andExpect(status().isOk());
    String after =
        http.perform(get("/trade/development-samples/" + orderId + "/cost").header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(after).get("totalAmount").asDouble()).isEqualTo(30.5d);
  }

  void addCostLine(String orderId, String costType, String amount, String description) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("costType", costType);
    body.put("amount", new BigDecimal(amount));
    body.put("description", description);
    body.put("incurredAt", "2026-10-21T09:00:00Z");
    http.perform(
            post("/trade/development-samples/" + orderId + "/cost/lines")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  // ================================================================ 权限与分页

  @Test
  void 只有读权限时写端点被拒() throws Exception {
    String projectId = createProject("DP-RO");
    Map<String, Object> body = orderBody("SO-RO", projectId, "OWN_FACTORY", null, null);
    http.perform(
            post("/trade/development-samples")
                .header("Authorization", "Bearer reader")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isForbidden());
  }

  @Test
  void 清单真分页() throws Exception {
    String projectId = createProject("DP-PAGE");
    for (int i = 1; i <= 3; i++) {
      createOrder("SO-PAGE-" + i, projectId, "OWN_FACTORY", null, null);
    }
    String response =
        http.perform(
                get("/trade/development-samples?page=0&size=2").header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var page = json.readTree(response);
    assertThat(page.get("total").asLong()).isEqualTo(3);
    assertThat(page.get("rows")).hasSize(2);
    assertThat(page.get("size").asInt()).isEqualTo(2);
  }

  @Test
  void 能力声明如实标出三件做不到的事() throws Exception {
    String response =
        http.perform(
                get("/trade/development-samples/capabilities").header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var caps = json.readTree(response);
    assertThat(caps.get("expenseAggregationAvailable").asBoolean()).isFalse();
    assertThat(caps.get("expenseAggregationOwnerModule").asText()).isEqualTo("12 费用");
    assertThat(caps.get("logisticsTrackingAvailable").asBoolean()).isFalse();
    assertThat(caps.get("logisticsTrackingOwnerModule").asText()).isEqualTo("10 物流");
    assertThat(caps.get("pilotOrderAvailable").asBoolean()).isFalse();
    assertThat(caps.get("pilotOrderOwnerModule").asText()).isEqualTo("6.6.2.1 试产单");
    // 达人寄样不归本模块管。
    assertThat(caps.get("marketingSampleDelivered").asBoolean()).isFalse();
    assertThat(caps.get("marketingSampleOwnerModule").asText()).isEqualTo("14.5.1.2 达人寄样");
    // 已接通的三个上游。
    assertThat(caps.get("projectReferenceAvailable").asBoolean()).isTrue();
    assertThat(caps.get("stallReferenceAvailable").asBoolean()).isTrue();
    assertThat(caps.get("supplierDirectoryAvailable").asBoolean()).isTrue();
    assertThat(caps.get("mediaLibraryAvailable").asBoolean()).isTrue();
  }

  @Test
  void 下拉给出状态机的合法去向() throws Exception {
    String response =
        http.perform(get("/trade/development-samples/options").header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var options = json.readTree(response);
    var sampleStatuses = options.get("sampleStatuses");
    assertThat(sampleStatuses).hasSize(7);
    var received =
        java.util.stream.StreamSupport.stream(sampleStatuses.spliterator(), false)
            .filter(n -> n.get("value").asText().equals("RECEIVED"))
            .findFirst()
            .orElseThrow();
    assertThat(received.get("allowedTargets").toString()).contains("CONFIRMED").contains("REVISING");
    // 用途词表只有一个值。
    assertThat(options.get("usageTypes")).hasSize(1);
    assertThat(options.get("usageTypes").get(0).get("value").asText()).isEqualTo("DEVELOPMENT");
  }

  // ================================================================ 工具

  String statusOf(String orderId, String sampleId) throws Exception {
    String response =
        http.perform(
                get("/trade/development-samples/" + orderId + "/samples/" + sampleId)
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response).get("sample").get("status").asText();
  }
}
