package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * 6.6.2.1 试产单的集成测试。
 *
 * <p><b>本测试是 V264 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，逐条在真实
 * PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不是直连数据库，以便同时验证「服务层 + DB 层」
 * 两层都没有把闸门漏掉。
 *
 * <p><b>catalog 起本地 stub 服务</b>：BOM 版本（3.5.2.1）在 catalog 独立库，本模块跨服务一律走端口，
 * 禁跨库 SQL / FK / 视图。stub 只负责按上游契约形状返回数。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductionTrialIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static final UUID readOnlyUser = UUID.randomUUID();
  static HttpServer identity, catalog;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      List.of("trade.production-trial.read", "trade.production-trial.update");

  /** catalog 侧的 BOM 版本（stub）。 */
  record StubComponent(
      UUID skuId, String skuCode, BigDecimal unitQuantity, BigDecimal scrapRate) {}

  record StubBomVersion(
      UUID id,
      UUID bomId,
      String bomCode,
      String outputSkuCode,
      String outputSkuName,
      String versionNo,
      String name,
      String status,
      String statusLabel,
      List<StubComponent> components) {}

  static final Map<UUID, StubBomVersion> bomVersions = new ConcurrentHashMap<>();

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
          actor(readOnlyUser, List.of(grant("ALL", List.of("trade.production-trial.read")))));
      identity.start();

      // catalog：3.5.2.1 的三个形状（版本详情 / 版本列表 / R7 单层需求量）。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/bom-versions",
          e -> {
            String path = e.getRequestURI().getPath();
            String query = e.getRequestURI().getQuery();
            if (path.equals("/catalog/bom-versions")) {
              List<Map<String, Object>> items = new ArrayList<>();
              for (StubBomVersion v : bomVersions.values()) {
                items.add(versionMap(v));
              }
              write(
                  catalog,
                  e,
                  200,
                  Map.of(
                      "items", items, "page", 0, "size", 50, "total", items.size(),
                      "totalPages", 1));
              return;
            }
            String rest = path.substring("/catalog/bom-versions/".length());
            if (rest.endsWith("/requirement")) {
              String id = rest.substring(0, rest.length() - "/requirement".length());
              StubBomVersion v = bomVersions.get(UUID.fromString(id));
              if (v == null) {
                e.sendResponseHeaders(404, -1);
                e.close();
                return;
              }
              BigDecimal outputQuantity = new BigDecimal("1");
              if (query != null) {
                for (String pair : query.split("&")) {
                  if (pair.startsWith("outputQuantity=")) {
                    outputQuantity = new BigDecimal(pair.substring("outputQuantity=".length()));
                  }
                }
              }
              write(catalog, e, 200, requirementMap(v, outputQuantity));
              return;
            }
            StubBomVersion v = bomVersions.get(UUID.fromString(rest));
            if (v == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            write(catalog, e, 200, Map.of("version", versionMap(v)));
          });
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** catalog 的版本行（形状逐字对齐 catalog 的 {@code BomVersionRow}）。 */
  static Map<String, Object> versionMap(StubBomVersion v) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", v.id());
    row.put("bomId", v.bomId());
    row.put("bomCode", v.bomCode());
    row.put("outputSkuId", UUID.randomUUID());
    row.put("outputSkuCode", v.outputSkuCode());
    row.put("outputSkuName", v.outputSkuName());
    row.put("versionNo", v.versionNo());
    row.put("name", v.name());
    row.put("status", v.status());
    row.put("statusLabel", v.statusLabel());
    row.put("componentCount", v.components().size());
    row.put("effective", "PUBLISHED".equals(v.status()));
    return row;
  }

  /**
   * catalog 的 R7 单层需求量（形状逐字对齐 catalog 的 {@code BomRequirement}）。
   *
   * <p>🔴 公式逐字沿用 catalog 规则 R7：{@code 单位用量 × 产量 × (1 + 损耗率/100)}，
   * 6 位小数 HALF_UP，**不取整**。
   */
  static Map<String, Object> requirementMap(StubBomVersion v, BigDecimal outputQuantity) {
    List<Map<String, Object>> lines = new ArrayList<>();
    for (StubComponent c : v.components()) {
      BigDecimal required =
          c.unitQuantity()
              .multiply(outputQuantity)
              .multiply(BigDecimal.ONE.add(c.scrapRate().divide(new BigDecimal("100"))))
              .setScale(6, RoundingMode.HALF_UP);
      Map<String, Object> line = new LinkedHashMap<>();
      line.put("skuId", c.skuId());
      line.put("skuCode", c.skuCode());
      line.put("skuName", c.skuCode());
      line.put("unitQuantity", c.unitQuantity());
      line.put("scrapRate", c.scrapRate());
      line.put("requiredQuantity", required);
      lines.add(line);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("bomId", v.bomId());
    body.put("versionId", v.id());
    body.put("versionNo", v.versionNo());
    body.put("outputSkuId", UUID.randomUUID());
    body.put("outputSkuCode", v.outputSkuCode());
    body.put("outputQuantity", outputQuantity);
    body.put("lines", lines);
    body.put("singleLevel", true);
    return body;
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
    bomVersions.clear();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate production_trial_mass_production_recommendation, production_trial_cost_line,"
              + " production_trial_cost, production_trial_result, production_trial_inspection_item,"
              + " production_trial_component_requirement, production_trial_order_transition,"
              + " production_trial_order cascade");
      s.execute("truncate development_sample_order cascade");
    }
  }

  // ================================================================ 工具

  /** 在 catalog 侧登记一个 BOM 版本（stub）。 */
  UUID registerBomVersion(String status, String statusLabel) {
    UUID id = UUID.randomUUID();
    bomVersions.put(
        id,
        new StubBomVersion(
            id,
            UUID.randomUUID(),
            "BOM-001",
            "SKU-CUP-001",
            "折叠水杯",
            "v1",
            "折叠水杯 v1",
            status,
            statusLabel,
            List.of(
                new StubComponent(
                    UUID.randomUUID(), "MAT-001", new BigDecimal("2.000000"), new BigDecimal("5.0000")),
                new StubComponent(
                    UUID.randomUUID(), "MAT-002", new BigDecimal("1.000000"), BigDecimal.ZERO))));
    return id;
  }

  /** 建一张试产单（走真实端点）。 */
  String createTrial(String suffix, UUID bomVersionId, String currency, String massTargetCost)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trialNo", "TP-" + suffix);
    body.put("bomVersionId", bomVersionId.toString());
    body.put("targetQuantity", new BigDecimal("100.000000"));
    body.put("productionLineCode", "LINE-A");
    body.put("productionLineName", "一线");
    body.put("factoryCode", "F-001");
    body.put("factoryName", "东莞一厂");
    body.put("qualityStandardCode", "QA-STD-01");
    body.put("currency", currency);
    body.put("requestedAt", "2026-10-21T09:00:00+08:00");
    body.put("requesterId", user.toString());
    body.put("purposeNote", "验证结构强度");
    if (massTargetCost != null) {
      body.put("massTargetCost", new BigDecimal(massTargetCost));
    }
    String response =
        http.perform(
                post("/trade/trial-productions")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response).get("order").get("id").asText();
  }

  /** 推进状态。 */
  void transition(String trialId, String toStatus) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", toStatus);
    body.put("reason", "推进到 " + toStatus);
    http.perform(
            post("/trade/trial-productions/" + trialId + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  /** 提交全部组件领用需求（草稿 → 已提交领用需求）。 */
  void submitAllRequirements(String trialId) throws Exception {
    String response =
        http.perform(
                get("/trade/trial-productions/" + trialId + "/component-requirements")
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (var node : json.readTree(response)) {
      http.perform(
              post(
                      "/trade/trial-productions/"
                          + trialId
                          + "/component-requirements/"
                          + node.get("id").asText()
                          + "/submit")
                  .header("Authorization", "Bearer owner"))
          .andExpect(status().isOk());
    }
  }

  /** 把一张单推进到「试产中」。 */
  String toInProgress(String trialId) throws Exception {
    transition(trialId, "SCHEDULED");
    submitAllRequirements(trialId);
    transition(trialId, "IN_PROGRESS");
    return trialId;
  }

  // ================================================================ 建单

  @Test
  void 建单后初始状态是已申请且组件领用需求已生成() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("A-1", bomVersionId, "USD", "100.00");

    String response =
        http.perform(
                get("/trade/trial-productions/" + trialId).header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var detail = json.readTree(response);

    assertThat(detail.get("order").get("status").asText()).isEqualTo("REQUESTED");
    assertThat(detail.get("order").get("statusLabel").asText()).isEqualTo("已申请");

    // 组件领用需求：两条（catalog 的两个组件行），初始为「草稿」。
    assertThat(detail.get("componentRequirements")).hasSize(2);
    var first = detail.get("componentRequirements").get(0);
    assertThat(first.get("requirementStatus").asText()).isEqualTo("DRAFT");
    assertThat(first.get("requirementStatusLabel").asText()).isEqualTo("草稿");
    // 🔴 需求**不是库存**：没有任何「已扣减 / 结存」字段，inventoryDeducted 恒为 false。
    assertThat(first.get("inventoryDeducted").asBoolean()).isFalse();
    assertThat(first.get("inventoryOwnerModule").asText()).isEqualTo("8.4.1 组装单");

    // BOM 版本快照（catalog 改名后仍能显示当时用的是哪一版）。
    assertThat(detail.get("order").get("bomVersionNo").asText()).isEqualTo("v1");
    assertThat(detail.get("order").get("bomCode").asText()).isEqualTo("BOM-001");
    assertThat(detail.get("order").get("outputSkuCode").asText()).isEqualTo("SKU-CUP-001");
  }

  @Test
  void 组件需求量原样搬运catalog_不重算() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("A-2", bomVersionId, "USD", null);

    String response =
        http.perform(
                get("/trade/trial-productions/" + trialId + "/component-requirements")
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var rows = json.readTree(response);
    // catalog 的 R7：2 × 100 × (1 + 5/100) = 210.000000
    assertThat(dec(rows.get(0), "requiredQuantity")).isEqualByComparingTo("210.000000");
    assertThat(dec(rows.get(0), "unitQuantity")).isEqualByComparingTo("2.000000");
    assertThat(dec(rows.get(0), "scrapRate")).isEqualByComparingTo("5.0000");
    // 第二个组件 1 × 100 × 1 = 100.000000
    assertThat(dec(rows.get(1), "requiredQuantity")).isEqualByComparingTo("100.000000");
  }

  @Test
  void 草稿BOM版本不许试产() throws Exception {
    UUID bomVersionId = registerBomVersion("DRAFT", "草稿");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trialNo", "TP-DRAFT");
    body.put("bomVersionId", bomVersionId.toString());
    body.put("targetQuantity", new BigDecimal("100.000000"));
    body.put("currency", "USD");
    body.put("requestedAt", "2026-10-21T09:00:00+08:00");
expectStatus(
            post("/trade/trial-productions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)),
            400);
  }

  @Test
  void 单号在租户内唯一() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    createTrial("DUP", bomVersionId, "USD", null);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trialNo", "TP-DUP");
    body.put("bomVersionId", bomVersionId.toString());
    body.put("targetQuantity", new BigDecimal("100.000000"));
    body.put("currency", "USD");
    body.put("requestedAt", "2026-10-21T09:00:00+08:00");
    http.perform(
            post("/trade/trial-productions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isConflict());
  }

  @Test
  void 写时间的值原样落库_不偏时区() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("TZ", bomVersionId, "USD", null);
    String response =
        http.perform(
                get("/trade/trial-productions/" + trialId).header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var requestedAt =
        OffsetDateTime.parse(json.readTree(response).get("order").get("requestedAt").asText());
    assertThat(requestedAt.toInstant())
        .as("09:00+08:00 就是 01:00Z：偏 −28800s 说明用了 Timestamp.valueOf(LocalDateTime)")
        .isEqualTo(OffsetDateTime.parse("2026-10-21T09:00:00+08:00").toInstant());
  }

  // ================================================================ 状态机与守卫

  @Test
  void 状态只能沿状态机走() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("S-1", bomVersionId, "USD", null);
    // 已申请 → 试产中 是跳级，被服务层挡下。
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", "IN_PROGRESS");
    body.put("reason", "直接开产");
expectStatus(
            post("/trade/trial-productions/" + trialId + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)),
            400);
  }

  @Test
  void 绕过服务层直接改状态会被数据库守卫挡下() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("S-2", bomVersionId, "USD", null);
    UUID id = UUID.fromString(trialId);

    // (1) 没有迁移事件 ⇒ DB 守卫拒绝。
    expectDbFailure(
        "update production_trial_order set status = 'SCHEDULED' where id = ?::uuid",
        "缺少对应迁移事件",
        id);

    // (2) 跳级同样被挡下（事件补上了，但 REQUESTED → IN_PROGRESS 不是合法边）。
    insertTransition(id, "REQUESTED", "IN_PROGRESS", "直接开产");
    expectDbFailure(
        "update production_trial_order set status = 'IN_PROGRESS' where id = ?::uuid",
        "不允许从",
        id);

    // (3) 开产前组件领用需求必须已提交：合法边 + 有事件，但需求还是「草稿」⇒ DB 拒绝。
    transition(trialId, "SCHEDULED");
    insertTransition(id, "SCHEDULED", "IN_PROGRESS", "直接开产");
    expectDbFailure(
        "update production_trial_order set status = 'IN_PROGRESS' where id = ?::uuid",
        "未提交",
        id);
  }

  @Test
  void 领用需求未提交不许开产() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("S-3", bomVersionId, "USD", null);
    transition(trialId, "SCHEDULED");
    // 需求还是「草稿」⇒ DB 守卫挡下「试产中」。
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", "IN_PROGRESS");
    body.put("reason", "开产");
    http.perform(
            post("/trade/trial-productions/" + trialId + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
    // 提交需求后再开产即可。
    submitAllRequirements(trialId);
    transition(trialId, "IN_PROGRESS");
  }

  @Test
  void 没有试产结果不许标记为已完成() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("S-4", bomVersionId, "USD", null);
    toInProgress(trialId);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", "COMPLETED");
    body.put("reason", "做完了");
expectStatus(
            post("/trade/trial-productions/" + trialId + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)),
            400);
  }

  @Test
  void 迁移事件不可改写不可删() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("S-5", bomVersionId, "USD", null);
    transition(trialId, "SCHEDULED");
    UUID eventId =
        fetchUuid(
            "select id from production_trial_order_transition where trial_order_id = ?::uuid",
            UUID.fromString(trialId));
    expectDbFailure(
        "update production_trial_order_transition set reason = '改个理由' where id = ?::uuid",
        "不可改写",
        eventId);
    expectDbFailure(
        "delete from production_trial_order_transition where id = ?::uuid", "不可删除", eventId);
  }

  // ================================================================ 组件领用需求

  @Test
  void 领用需求提交后是已提交领用需求_并且不扣库存() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("R-1", bomVersionId, "USD", null);
    submitAllRequirements(trialId);
    String response =
        http.perform(
                get("/trade/trial-productions/" + trialId + "/component-requirements")
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (var node : json.readTree(response)) {
      assertThat(node.get("requirementStatus").asText()).isEqualTo("SUBMITTED");
      assertThat(node.get("requirementStatusLabel").asText()).isEqualTo("已提交领用需求");
      // 🔴 需求提交了，但库存**没有**被本模块扣掉。
      assertThat(node.get("inventoryDeducted").asBoolean()).isFalse();
      assertThat(node.get("assemblyOrderRefId").isNull()).isTrue();
    }
    // 🔴 **本模块不持库存事实**：组件领用需求这张表里**没有任何库存结存 / 已扣减列** ——
    //    它记的是「要领多少」，不是「还剩多少」。库存事实归 warehouse 独立库。
    var balanceColumns =
        sql.fetch(
            "select column_name from information_schema.columns where table_schema = 'public'"
                + " and table_name = 'production_trial_component_requirement'"
                + " and (column_name like '%on_hand%' or column_name like '%stock%'"
                + "      or column_name like '%balance%' or column_name like '%deducted%'"
                + "      or column_name like '%issued%')");
    assertThat(balanceColumns).as("组件领用需求表里不该出现任何库存结存 / 已扣减列").isEmpty();
  }

  @Test
  void 记录加工单承接时只存逻辑引用与快照() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("R-2", bomVersionId, "USD", null);
    submitAllRequirements(trialId);
    String response =
        http.perform(
                get("/trade/trial-productions/" + trialId + "/component-requirements")
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String requirementId = json.readTree(response).get(0).get("id").asText();
    UUID assemblyOrderId = UUID.randomUUID();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("assemblyOrderRefId", assemblyOrderId.toString());
    body.put("assemblyOrderNo", "AO-20261021-0001");
    String released =
        http.perform(
                post(
                        "/trade/trial-productions/"
                            + trialId
                            + "/component-requirements/"
                            + requirementId
                            + "/release")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var node = json.readTree(released).get(0);
    assertThat(node.get("requirementStatus").asText()).isEqualTo("RELEASED");
    assertThat(node.get("assemblyOrderNo").asText()).isEqualTo("AO-20261021-0001");
    assertThat(node.get("assemblyOrderRefId").asText()).isEqualTo(assemblyOrderId.toString());
  }

  @Test
  void 加工单承接必须给出组装单号快照() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("R-3", bomVersionId, "USD", null);
    submitAllRequirements(trialId);
    String response =
        http.perform(
                get("/trade/trial-productions/" + trialId + "/component-requirements")
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String requirementId = json.readTree(response).get(0).get("id").asText();
    // 直接改库绕过服务层：RELEASED 但没有组装单引用 ⇒ DB CHECK 拒绝。
    expectDbFailure(
        "update production_trial_component_requirement set requirement_status = 'RELEASED'"
            + " where id = ?::uuid",
        "production_trial_component_requirement_release_snapshot",
        UUID.fromString(requirementId));
  }

  // ================================================================ 质检标准

  @Test
  void 质检项判定不可撤回成待判定() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("Q-1", bomVersionId, "USD", null);
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("itemCode", "ITEM-01");
    item.put("itemName", "外观");
    item.put("standardValue", "无划痕");
    item.put("criticalItem", true);
    String items =
        http.perform(
                post("/trade/trial-productions/" + trialId + "/inspection-items")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(item)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String itemId = json.readTree(items).get(0).get("id").asText();

    Map<String, Object> judgment = new LinkedHashMap<>();
    judgment.put("judgment", "PENDING");
expectStatus(
            put("/trade/trial-productions/" + trialId + "/inspection-items/" + itemId)
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(judgment)),
            400);
  }

  @Test
  void 质检项未判定完不许标记为已完成() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("Q-2", bomVersionId, "USD", null);
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("itemCode", "ITEM-01");
    item.put("itemName", "外观");
    http.perform(
            post("/trade/trial-productions/" + trialId + "/inspection-items")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(item)))
        .andExpect(status().isOk());
    toInProgress(trialId);
    recordResult(trialId, "100", "95");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", "COMPLETED");
    body.put("reason", "做完了");
expectStatus(
            post("/trade/trial-productions/" + trialId + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)),
            400);
  }

  // ================================================================ 试产结果与良率

  @Test
  void 良率由数据库推导_是比例不是百分数() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("Y-1", bomVersionId, "USD", null);
    toInProgress(trialId);
    String response = recordResult(trialId, "100", "95");
    var result = json.readTree(response);
    assertThat(dec(result, "yieldRate")).isEqualByComparingTo("0.950000");
    assertThat(dec(result, "outputQuantity")).isEqualByComparingTo("100.000000");
    assertThat(dec(result, "qualifiedQuantity")).isEqualByComparingTo("95.000000");
    assertThat(dec(result, "defectiveQuantity")).isEqualByComparingTo("5.000000");
  }

  @Test
  void 良率不收_报上来也会被覆盖() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("Y-2", bomVersionId, "USD", null);
    toInProgress(trialId);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("outputQuantity", new BigDecimal("100.000000"));
    body.put("qualifiedQuantity", new BigDecimal("95.000000"));
    body.put("yieldRate", new BigDecimal("99.000000"));
    body.put("conclusion", "良率很好");
    body.put("recordedAt", "2026-10-21T10:00:00+08:00");
    // 输入模型里根本没有良率字段 ⇒ 报上来直接 400（fail-on-unknown-properties）。
    http.perform(
            post("/trade/trial-productions/" + trialId + "/result")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 合格数量超过产出被拒绝() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("Y-3", bomVersionId, "USD", null);
    toInProgress(trialId);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("outputQuantity", new BigDecimal("100.000000"));
    body.put("qualifiedQuantity", new BigDecimal("101.000000"));
    body.put("conclusion", "写错了");
    body.put("recordedAt", "2026-10-21T10:00:00+08:00");
    http.perform(
            post("/trade/trial-productions/" + trialId + "/result")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 成本

  @Test
  void 成本合计由明细行自动维护_删行重算() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("C-1", bomVersionId, "USD", null);
    addCostLine(trialId, "MATERIAL", "120.0000", "物料");
    addCostLine(trialId, "PROCESSING", "30.0000", "加工");
    String response =
        http.perform(
                get("/trade/trial-productions/" + trialId + "/cost")
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var cost = json.readTree(response);
    assertThat(dec(cost, "totalAmount")).isEqualByComparingTo("150.0000");
    assertThat(cost.get("currency").asText()).isEqualTo("USD");
    assertThat(cost.get("lines")).hasSize(2);

    // 🔴 **按金额找要删的那一行**，不按行号：两行的 `incurred_at` 相同，
    //    服务端按 `(incurred_at, id)` 排序，而 id 是随机 UUID ⇒ 同刻两行的先后**不稳定**。
    //    断言若依赖「第 0 行是谁」，就会变成一个偶发失败的 flaky 用例。
    String lineId = null;
    String remaining = null;
    for (var line : cost.get("lines")) {
      if (dec(line, "amount").compareTo(new BigDecimal("120.0000")) == 0) {
        lineId = line.get("id").asText();
      } else {
        remaining = line.get("amount").asText();
      }
    }
    assertThat(lineId).as("没找到金额 120 的那一行").isNotNull();
    http.perform(
            delete("/trade/trial-productions/" + trialId + "/cost/lines/" + lineId)
                .header("Authorization", "Bearer owner"))
        .andExpect(status().isOk());
    String afterDelete =
        http.perform(
                get("/trade/trial-productions/" + trialId + "/cost")
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    // 合计 = 剩下的那一行（数据库触发器按明细行自动重算）。
    assertThat(dec(json.readTree(afterDelete), "totalAmount"))
        .isEqualByComparingTo(new BigDecimal(remaining));
    assertThat(dec(json.readTree(afterDelete), "totalAmount")).isEqualByComparingTo("30.0000");
  }

  @Test
  void 明细行币种与成本头不一致被数据库触发器拒绝() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("C-2", bomVersionId, "USD", null);
    UUID costId =
        fetchUuid(
            "select id from production_trial_cost where trial_order_id = ?::uuid",
            UUID.fromString(trialId));
    expectDbFailure(
        "insert into production_trial_cost_line(id,tenant_id,cost_id,trial_order_id,"
            + " cost_type,amount,currency,description,incurred_at,created_by,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::uuid,'MATERIAL',10.0000,'CNY','错的币种',"
            + " now(),?::uuid,now())",
        "币种",
        UUID.randomUUID(),
        tenant,
        costId,
        UUID.fromString(trialId),
        user);
  }

  @Test
  void 量产目标成本取不到时成本对比不可比_不是零() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("C-3", bomVersionId, "USD", null);
    addCostLine(trialId, "MATERIAL", "120.0000", "物料");
    String response =
        http.perform(
                get("/trade/trial-productions/" + trialId + "/cost-comparison")
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var comparison = json.readTree(response);
    assertThat(comparison.get("comparable").asBoolean()).isFalse();
    assertThat(comparison.get("massTargetCost").isNull()).isTrue();
    assertThat(comparison.get("varianceRate").isNull()).isTrue();
    assertThat(comparison.get("note").asText()).contains("固定汇率");
  }

  @Test
  void 同一币种内成本可比_偏差率算对() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("C-4", bomVersionId, "USD", "100.0000");
    addCostLine(trialId, "MATERIAL", "120.0000", "物料");
    String response =
        http.perform(
                get("/trade/trial-productions/" + trialId + "/cost-comparison")
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var comparison = json.readTree(response);
    assertThat(comparison.get("comparable").asBoolean()).isTrue();
    assertThat(dec(comparison, "massTargetCost")).isEqualByComparingTo("100.0000");
    assertThat(dec(comparison, "varianceAmount")).isEqualByComparingTo("20.0000");
    assertThat(dec(comparison, "varianceRate")).isEqualByComparingTo("0.200000");
  }

  @Test
  void 概览里成本按币种分组不跨币种相加() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    createTrial("C-5-USD", bomVersionId, "USD", "100.0000");
    createTrial("C-5-CNY", bomVersionId, "CNY", null);
    addCostLineByTrialNo("TP-C-5-USD", "MATERIAL", "120.0000", "物料");
    addCostLineByTrialNo("TP-C-5-CNY", "MATERIAL", "80.0000", "物料");
    String response =
        http.perform(
                get("/trade/trial-productions/summary").header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var summary = json.readTree(response);
    var currencies = summary.get("costByCurrency");
    assertThat(currencies).hasSize(2);
    // 两个币种各有自己的合计，**没有**一个把 USD 与 CNY 加起来的总数。
    assertThat(currencies.toString()).doesNotContain("200.0000");
    var comparison = summary.get("costComparison");
    assertThat(comparison).hasSize(2);
    for (var node : comparison) {
      if (node.get("currency").asText().equals("USD")) {
        assertThat(node.get("comparable").asBoolean()).isTrue();
      } else {
        assertThat(node.get("comparable").asBoolean()).isFalse();
        assertThat(node.get("massTargetCost").isNull()).isTrue();
      }
    }
  }

  // ================================================================ 转量产建议

  @Test
  void 建议转量产但良率不达标被拒() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("M-1", bomVersionId, "USD", "100.0000");
    toInProgress(trialId);
    recordResult(trialId, "100", "90");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("decision", "GO");
    body.put("rationale", "良率不错");
    body.put("conditionNote", "良率 ≥ 0.95 且成本偏差 ≤ 0.10");
    body.put("minYieldThreshold", new BigDecimal("0.95"));
    body.put("maxCostVarianceRate", new BigDecimal("0.10"));
expectStatus(
            post("/trade/trial-productions/" + trialId + "/mass-production-recommendation")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)),
            400);
  }

  @Test
  void 条件达标时建议转量产成功并留下实测快照() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("M-2", bomVersionId, "USD", "100.0000");
    toInProgress(trialId);
    recordResult(trialId, "100", "98");
    addCostLine(trialId, "MATERIAL", "103.0000", "物料");
    // 🔴 建议建立在**已完成**的试产之上 —— 先把单子推完，再出建议。
    transition(trialId, "COMPLETED");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("decision", "GO");
    body.put("rationale", "良率与成本都达标");
    body.put("conditionNote", "良率 ≥ 0.95 且成本偏差 ≤ 0.10");
    body.put("minYieldThreshold", new BigDecimal("0.95"));
    body.put("maxCostVarianceRate", new BigDecimal("0.10"));
    String response =
        http.perform(
                post("/trade/trial-productions/" + trialId + "/mass-production-recommendation")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var rec = json.readTree(response);
    assertThat(rec.get("decision").asText()).isEqualTo("GO");
    assertThat(rec.get("decisionLabel").asText()).isEqualTo("建议转量产");
    assertThat(dec(rec, "observedYieldRate")).isEqualByComparingTo("0.980000");
    assertThat(dec(rec, "observedTrialCost")).isEqualByComparingTo("103.0000");
    assertThat(dec(rec, "observedMassTargetCost")).isEqualByComparingTo("100.0000");
    assertThat(dec(rec, "observedCostVarianceRate")).isEqualByComparingTo("0.030000");
    assertThat(rec.get("observedCurrency").asText()).isEqualTo("USD");
  }

  @Test
  void 一张单只能有一条转量产建议_且建议不可改写() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("M-3", bomVersionId, "USD", "100.0000");
    toInProgress(trialId);
    recordResult(trialId, "100", "98");
    transition(trialId, "COMPLETED");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("decision", "NO_GO");
    body.put("rationale", "关键质检项不合格");
    body.put("conditionNote", "关键项全部合格");
    body.put("minYieldThreshold", new BigDecimal("0.95"));
    http.perform(
            post("/trade/trial-productions/" + trialId + "/mass-production-recommendation")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
    // 再来一条 ⇒ 409（建议是不可改写的留痕）。
    http.perform(
            post("/trade/trial-productions/" + trialId + "/mass-production-recommendation")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isConflict());
    // 直接改库 ⇒ DB 挡下。
    expectDbFailure(
        "update production_trial_mass_production_recommendation set decision = 'GO'"
            + " where trial_order_id = ?::uuid",
        "不可改写",
        UUID.fromString(trialId));
  }

  @Test
  void 没做完的试产不许出转量产建议() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    String trialId = createTrial("M-4", bomVersionId, "USD", "100.0000");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("decision", "NO_GO");
    body.put("rationale", "还没做完");
    body.put("conditionNote", "门槛");
    body.put("minYieldThreshold", new BigDecimal("0.95"));
expectStatus(
            post("/trade/trial-productions/" + trialId + "/mass-production-recommendation")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)),
            400);
  }

  // ================================================================ 能力声明与权限

  @Test
  void 能力声明如实标出做不到的三件事() throws Exception {
    String response =
        http.perform(
                get("/trade/trial-productions/capabilities").header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var caps = json.readTree(response);
    // 🔴 真实库存扣减做不到 —— 归 8.4.1 组装单。
    assertThat(caps.get("inventoryDeductionAvailable").asBoolean()).isFalse();
    assertThat(caps.get("inventoryDeductionOwnerModule").asText()).isEqualTo("8.4.1 组装单");
    assertThat(caps.get("inventoryDeductionNote").asText()).contains("只产出组件领用需求");
    // 🔴 转量产建档未交付。
    assertThat(caps.get("massProductionHandoffAvailable").asBoolean()).isFalse();
    assertThat(caps.get("massProductionHandoffOwnerModule").asText())
        .isEqualTo("6.6.2.2 转量产与建档");
    // 🔴 汇率未交付 ⇒ 不做固定汇率折算。
    assertThat(caps.get("exchangeRateAvailable").asBoolean()).isFalse();
    assertThat(caps.get("exchangeRateNote").asText()).contains("不做固定汇率折算");
    // BOM 版本可用。
    assertThat(caps.get("bomVersionAvailable").asBoolean()).isTrue();
  }

  @Test
  void 只有读权限时写端点403() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trialNo", "TP-RO");
    body.put("bomVersionId", bomVersionId.toString());
    body.put("targetQuantity", new BigDecimal("100.000000"));
    body.put("currency", "USD");
    body.put("requestedAt", "2026-10-21T09:00:00+08:00");
    http.perform(
            post("/trade/trial-productions")
                .header("Authorization", "Bearer reader")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isForbidden());
  }

  @Test
  void 清单是真分页() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    for (int i = 1; i <= 5; i++) {
      createTrial("P-" + i, bomVersionId, "USD", null);
    }
    String response =
        http.perform(
                get("/trade/trial-productions?page=0&size=2")
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var page = json.readTree(response);
    assertThat(page.get("total").asLong()).isEqualTo(5);
    assertThat(page.get("size").asInt()).isEqualTo(2);
    assertThat(page.get("rows")).hasSize(2);

    String page2 =
        http.perform(
                get("/trade/trial-productions?page=1&size=2")
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readTree(page2).get("rows")).hasSize(2);
    assertThat(
            json.readTree(page2)
                .get("rows")
                .get(0)
                .get("trialNo")
                .asText())
        .isNotEqualTo(json.readTree(response).get("rows").get(0).get("trialNo").asText());
  }

  @Test
  void 关键词筛选是真筛选() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    createTrial("K-1", bomVersionId, "USD", null);
    createTrial("K-2", bomVersionId, "USD", null);
    String response =
        http.perform(
                get("/trade/trial-productions?keyword=TP-K-1")
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var page = json.readTree(response);
    assertThat(page.get("total").asLong()).isEqualTo(1);
    assertThat(page.get("rows").get(0).get("trialNo").asText()).isEqualTo("TP-K-1");
  }

  // ================================================================ 工具

  /**
   * 执行一次请求并断言状态码。
   *
   * <p>🔴 本服务的<b>错误响应体是空的</b>（Spring 的 {@code ResponseStatusExceptionResolver}
   * 走 {@code sendError}，MockMvc 下不会渲染错误页）⇒ 集成测试只断言<b>状态码</b>与
   * <b>事后的数据状态</b>；「错误理由是否说清楚了」由
   * {@link ProductionTrialRulesTest} 逐条断言 {@code getReason()} 的原文来守。
   */
  /**
   * 从 JSON 节点取一个<b>精确</b>的十进制数。
   *
   * <p>🔴 <b>不经过 double</b>：金额与数量的断言不该有浮点误差；
   * JSON 里的尾随零（{@code 210.000000} 与 {@code 210.0}）只是显示格式，不是第二个值。
   */
  static BigDecimal dec(com.fasterxml.jackson.databind.JsonNode node, String field) {
    return new BigDecimal(node.get(field).asText());
  }

  void expectStatus(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
      int expectedStatus)
      throws Exception {
    var result = http.perform(builder).andReturn();
    assertThat(result.getResponse().getStatus())
        .withFailMessage(
            "期望 HTTP %s，实际 %s", expectedStatus, result.getResponse().getStatus())
        .isEqualTo(expectedStatus);
  }

  /** 记录试产结果。 */
  String recordResult(String trialId, String output, String qualified) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("outputQuantity", new BigDecimal(output));
    body.put("qualifiedQuantity", new BigDecimal(qualified));
    body.put("conclusion", "试产结论");
    body.put("recordedAt", "2026-10-21T10:00:00+08:00");
    return http.perform(
            post("/trade/trial-productions/" + trialId + "/result")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** 追加一条成本明细行。 */
  void addCostLine(String trialId, String costType, String amount, String description)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("costType", costType);
    body.put("amount", new BigDecimal(amount));
    body.put("description", description);
    body.put("incurredAt", "2026-10-21T10:00:00+08:00");
    http.perform(
            post("/trade/trial-productions/" + trialId + "/cost/lines")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  /** 追加一条成本明细行（按试产单号找单）。 */
  void addCostLineByTrialNo(String trialNo, String costType, String amount, String description)
      throws Exception {
    addCostLine(findTrialId(trialNo), costType, amount, description);
  }

  /** 按试产单号找 id（超管直连：RLS 之外，避免把「看不见」误当成「找不到」）。 */
  String findTrialId(String trialNo) throws Exception {
    return fetchUuid(
            "select id from production_trial_order where tenant_id = ?::uuid and trial_no = ?::varchar",
            tenant,
            trialNo)
        .toString();
  }

  // ================================================================ DB 直连工具

  /**
   * 超管直连（<b>故意绕过 RLS</b>）。
   *
   * <p>🔴 用来证明「<b>DB 守卫自己会挡</b>」：这类断言**必须**在 RLS 之外做 ——
   * 在 RLS 之内做，验到的是「看不见」，不是「挡得住」。业务查询一律走 HTTP 端点。
   */
  Connection superuser() throws Exception {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  /** 在超管连接上执行一条 SQL，断言它被数据库拒绝（并说明拒绝理由的关键词）。 */
  void expectDbFailure(String sqlText, String expectedMessagePart, Object... binds) {
    assertThatThrownBy(
            () -> {
              try (Connection c = superuser();
                  PreparedStatement ps = c.prepareStatement(sqlText)) {
                for (int i = 0; i < binds.length; i++) {
                  ps.setObject(i + 1, binds[i]);
                }
                ps.execute();
              }
            })
        .hasMessageContaining(expectedMessagePart);
  }

  /** 在超管连接上插一条迁移事件（用来构造「事件有了但状态仍不合法」的场景）。 */
  void insertTransition(UUID trialOrderId, String fromStatus, String toStatus, String reason)
      throws Exception {
    try (Connection c = superuser();
        PreparedStatement ps =
            c.prepareStatement(
                "insert into production_trial_order_transition(id,tenant_id,trial_order_id,"
                    + " from_status,to_status,reason,actor_id,actor_name,occurred_at,created_at)"
                    + " values (?::uuid,?::uuid,?::uuid,?,?,?,?::uuid,'',now(),now())")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, tenant);
      ps.setObject(3, trialOrderId);
      ps.setString(4, fromStatus);
      ps.setString(5, toStatus);
      ps.setString(6, reason);
      ps.setObject(7, user);
      ps.execute();
    }
  }

  /** 超管直连读一个 uuid（RLS 之外）。 */
  UUID fetchUuid(String sqlText, Object... binds) throws Exception {
    try (Connection c = superuser();
        PreparedStatement ps = c.prepareStatement(sqlText)) {
      for (int i = 0; i < binds.length; i++) {
        ps.setObject(i + 1, binds[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getObject(1, UUID.class) : null;
      }
    }
  }
}
