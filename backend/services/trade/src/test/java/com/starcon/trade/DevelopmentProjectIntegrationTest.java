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
 * 6.6.1.1 开发立项的集成测试。
 *
 * <p><b>本测试是 V257 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，逐条在真实
 * PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不是直连数据库，以便同时验证「服务层 + DB 层」
 * 两层都没有把闸门漏掉。
 *
 * <p><b>catalog 起本地 stub 服务</b>（而不是在测试里 new 一个假对象）：目标成本的唯一系统来源是
 * 5.3.1.1 利润测算器（catalog 服务），本模块跨服务一律走端口，禁跨库 SQL / FK / 视图。
 * stub 只负责按上游契约形状返回数，数字本身是按上游算式真算出来的，不是写死的数组。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DevelopmentProjectIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static final UUID readOnlyUser = UUID.randomUUID();
  static HttpServer identity, catalog;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      List.of("trade.development-project.read", "trade.development-project.update");

  /** 参考 SKU → 采购成本行（stub 用）。没有登记的 SKU ⇒ 上游说「取不到」。 */
  static final Map<UUID, Map<String, Object>> purchaseBySku = new ConcurrentHashMap<>();

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
      actors.put(
          "Bearer owner",
          actor(user, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer reader",
          actor(readOnlyUser, List.of(grant("ALL", List.of("trade.development-project.read")))));
      identity.start();

      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/profit-calculator/calculate",
          e -> {
            var body = json.readValue(e.getRequestBody(), Map.class);
            UUID skuId = UUID.fromString(String.valueOf(body.get("skuId")));
            var line = purchaseBySku.get(skuId);
            // 🔴 用 LinkedHashMap 而不是 Map.of：Map.of 不接受 null 值，
            //    而瀑布图里有 null 字段（unavailableReason / basis）—— 用 Map.of 会 NPE。
            Map<String, Object> waterfallLine = new LinkedHashMap<>();
            waterfallLine.put("code", "PURCHASE");
            waterfallLine.put("label", "采购成本");
            waterfallLine.put("available", line != null);
            waterfallLine.put("partial", false);
            waterfallLine.put("amount", line == null ? null : line.get("amount"));
            waterfallLine.put("totalAmount", line == null ? null : line.get("amount"));
            waterfallLine.put(
                "currency",
                line == null ? body.get("currency") : line.getOrDefault("currency", body.get("currency")));
            waterfallLine.put(
                "unavailableReason",
                line == null ? "该 SKU 在成本窗口内没有到货事实，采购成本取不到" : null);
            waterfallLine.put(
                "basis",
                line == null ? null : line.getOrDefault("basis", "按移动加权法取成本窗口内的到货金额"));
            write(
                catalog,
                e,
                200,
                Map.of(
                    "skuId", skuId,
                    "currency", body.get("currency"),
                    "decisionBasis", "COMPLETE_FROM_RULES",
                    "costEffectiveFrom", "2026-01-01T00:00:00Z",
                    "waterfall", List.of(waterfallLine)));
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
    purchaseBySku.clear();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate development_project_handoff, development_project_market_observation,"
              + " development_project_spec_candidate, development_project_transition,"
              + " development_project_milestone, development_project_market,"
              + " development_project cascade");
    }
  }

  // ================================================================ 工具

  String createProject(String projectNo, String price, String cost) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("projectNo", projectNo);
    body.put("name", "折叠水杯");
    body.put("category", "家居");
    body.put("ownerId", user.toString());
    body.put("ownerName", "张三");
    body.put("targetPrice", new BigDecimal(price));
    body.put("currency", "USD");
    if (cost != null) {
      body.put("targetCost", new BigDecimal(cost));
      body.put("costBasis", "供应商报价 + BOM 估算");
      body.put("costEstimatedAt", "2026-01-05T10:00:00Z");
    }
    body.put("decisionNote", "评审通过");
    body.put("launchTargetAt", "2026-06-01T00:00:00Z");
    body.put("note", "首批 500 件");
    var response =
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

  void transition(String id, String to, String reason) throws Exception {
    transition(id, to, reason, null, null);
  }

  void transition(String id, String to, String reason, String exemptionCode, String exemptionNote)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", to);
    body.put("reason", reason);
    if (exemptionCode != null) {
      body.put("exemptionCode", exemptionCode);
    }
    if (exemptionNote != null) {
      body.put("exemptionNote", exemptionNote);
    }
    http.perform(
            post("/trade/development-projects/" + id + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  String projectJson(String id) throws Exception {
    return http.perform(
            get("/trade/development-projects/" + id).header("Authorization", "Bearer owner"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明把上下游可用性说清楚() throws Exception {
    http.perform(
            get("/trade/development-projects/capabilities").header("Authorization", "Bearer owner"))
        .andExpect(status().isOk())
        // 5.3.1.1 利润测算器已交付 ⇒ 目标成本有系统来源。
        .andExpect(jsonPath("$.costEstimateAvailable").value(true))
        .andExpect(jsonPath("$.costEstimateSource").value(org.hamcrest.Matchers.containsString("5.3.1.1")))
        // 14 广告的 reporting 服务不在本仓 ⇒ 系统侧市场数据取不到，**如实标**。
        .andExpect(jsonPath("$.marketDataAvailable").value(false))
        .andExpect(jsonPath("$.marketDataOwnerModule").value(org.hamcrest.Matchers.containsString("14")))
        // 6.6.1.2 / 6.6.2.1 未交付 ⇒ 本模块只产交接单。
        .andExpect(jsonPath("$.samplingModuleDelivered").value(false))
        .andExpect(jsonPath("$.pilotModuleDelivered").value(false))
        // 3.1.1.1 建档在 catalog（已交付）⇒ 本模块只产候选参数。
        .andExpect(jsonPath("$.skuOnboardingDelivered").value(true))
        .andExpect(jsonPath("$.stateMachineNote").value(org.hamcrest.Matchers.containsString("终态")))
        .andExpect(jsonPath("$.marginRoundingNote").value(org.hamcrest.Matchers.containsString("HALF_UP")));
  }

  // ================================================================ 立项

  @Test
  void 立项初始状态是评估且成本取不到时毛利为空而不是零() throws Exception {
    String id = createProject("DP-20260101-A1", "100.00", null);
    var project = json.readTree(projectJson(id)).get("project");
    assertThat(project.get("status").asText()).isEqualTo("EVALUATION");
    assertThat(project.get("statusLabel").asText()).isEqualTo("评估");
    // 🔴 成本取不到 ⇒ 毛利率是 null（**不是 0**，更不是 100%）。
    assertThat(project.get("targetMarginRate").isNull()).isTrue();
    assertThat(project.get("targetCost").isNull()).isTrue();
    assertThat(project.get("costAvailable").asBoolean()).isFalse();
    // 点名归谁管。
    assertThat(project.get("costSource").asText()).isEqualTo("UNAVAILABLE");
    assertThat(project.get("marginBasis").asText()).contains("不输出目标毛利率");
  }

  @Test
  void 有手工成本时目标毛利由服务端算出而不是调用方报() throws Exception {
    String id = createProject("DP-20260101-A2", "100.00", "60.00");
    var project = json.readTree(projectJson(id)).get("project");
    assertThat(project.get("targetMarginRate").asDouble()).isEqualTo(0.4d);
    assertThat(project.get("costAvailable").asBoolean()).isTrue();
    assertThat(project.get("costSource").asText()).isEqualTo("MANUAL_ESTIMATE");
    assertThat(project.get("marginBasis").asText()).contains("(目标售价 − 目标成本) ÷ 目标售价");
  }

  @Test
  void 输入模型里没有毛利率字段所以调用方报不上来() throws Exception {
    // 🔴 请求体里塞一个 targetMarginRate ⇒ fail-on-unknown-properties ⇒ 400。
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("projectNo", "DP-20260101-A3");
    body.put("name", "折叠水杯");
    body.put("category", "家居");
    body.put("ownerId", user.toString());
    body.put("targetPrice", new BigDecimal("100.00"));
    body.put("currency", "USD");
    body.put("targetMarginRate", new BigDecimal("0.99"));
    http.perform(
            post("/trade/development-projects")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void 立项编号在租户内唯一() throws Exception {
    createProject("DP-DUP", "100.00", "60.00");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("projectNo", "DP-DUP");
    body.put("name", "另一个");
    body.put("category", "家居");
    body.put("ownerId", user.toString());
    body.put("targetPrice", new BigDecimal("100.00"));
    body.put("currency", "USD");
    http.perform(
            post("/trade/development-projects")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isConflict());
  }

  // ================================================================ 状态机

  @Test
  void 状态机只认存在的边() throws Exception {
    String id = createProject("DP-20260101-B1", "100.00", "60.00");
    transition(id, "APPROVED", "评审通过");
    assertThat(json.readTree(projectJson(id)).get("project").get("status").asText())
        .isEqualTo("APPROVED");

    // 不允许跳级：立项 → 量产。
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", "MASS_PRODUCTION");
    body.put("reason", "直接量产");
    http.perform(
            post("/trade/development-projects/" + id + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
    // 状态没被改。
    assertThat(json.readTree(projectJson(id)).get("project").get("status").asText())
        .isEqualTo("APPROVED");
  }

  @Test
  void 跳过打样与试产必须给显式豁免记录() throws Exception {
    String id = createProject("DP-20260101-B2", "100.00", "60.00");
    transition(id, "APPROVED", "评审通过");
    transition(id, "IN_DEVELOPMENT", "排期确定");

    // 没有豁免 ⇒ 400。
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", "MASS_PRODUCTION");
    body.put("reason", "客户要求直供成品");
    http.perform(
            post("/trade/development-projects/" + id + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());

    // 有豁免 ⇒ 放行，且豁免留在事件里。
    transition(
        id,
        "MASS_PRODUCTION",
        "客户要求直供成品",
        "SKIP-SAMPLING-PILOT",
        "客户要求直供成品，本次不做打样与试产");
    var transitions = json.readTree(projectJson(id)).get("transitions");
    assertThat(transitions).hasSize(3);
    var last = transitions.get(0);
    assertThat(last.get("fromStatus").asText()).isEqualTo("IN_DEVELOPMENT");
    assertThat(last.get("toStatus").asText()).isEqualTo("MASS_PRODUCTION");
    assertThat(last.get("exemptionCode").asText()).isEqualTo("SKIP-SAMPLING-PILOT");
    assertThat(last.get("exemptionNote").asText()).contains("不做打样与试产");
  }

  @Test
  void 终止是终态不提供恢复() throws Exception {
    String id = createProject("DP-20260101-B3", "100.00", "60.00");
    transition(id, "APPROVED", "评审通过");
    transition(id, "TERMINATED", "客户放弃这个款式");
    assertThat(json.readTree(projectJson(id)).get("project").get("status").asText())
        .isEqualTo("TERMINATED");

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", "APPROVED");
    body.put("reason", "恢复");
    http.perform(
            post("/trade/development-projects/" + id + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 每次状态迁移都落一条不可改写的事件() throws Exception {
    String id = createProject("DP-20260101-B4", "100.00", "60.00");
    transition(id, "APPROVED", "评审通过");
    var transitions = json.readTree(projectJson(id)).get("transitions");
    assertThat(transitions).hasSize(1);
    var event = transitions.get(0);
    // 谁 / 何时 / 从哪到哪 / 为什么 —— 四要素齐全。
    assertThat(event.get("fromStatus").asText()).isEqualTo("EVALUATION");
    assertThat(event.get("toStatus").asText()).isEqualTo("APPROVED");
    assertThat(event.get("reason").asText()).isEqualTo("评审通过");
    assertThat(event.get("actorId").asText()).isEqualTo(user.toString());
    assertThat(event.has("occurredAt")).isTrue();
    assertThat(event.get("appliedAt").asText()).isNotBlank();

    // 不可改写：直接改事件内容被数据库拒绝。
    assertThatThrownBy(
            () ->
                access.run(
                    actors.get("Bearer owner"),
                    "trade.development-project.read",
                    () ->
                        sql.execute(
                            "update development_project_transition set reason = '改一改'"
                                + " where tenant_id = ?::uuid",
                            tenant)))
        .hasMessageContaining("不可改写");
  }

  @Test
  void 迁移必须写原因() throws Exception {
    String id = createProject("DP-20260101-B5", "100.00", "60.00");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", "APPROVED");
    body.put("reason", "  ");
    http.perform(
            post("/trade/development-projects/" + id + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void 数据库层挡住绕过服务的非法状态变更() throws Exception {
    String id = createProject("DP-20260101-B6", "100.00", "60.00");
    // 直接用 SQL 把状态从「评估」改到「量产」—— 没有迁移事件 ⇒ 数据库守卫必须拒绝。
    assertThatThrownBy(
            () ->
                access.run(
                    actors.get("Bearer owner"),
                    "trade.development-project.read",
                    () ->
                        sql.execute(
                            "update development_project set status = 'MASS_PRODUCTION'"
                                + " where tenant_id = ?::uuid and id = ?::uuid",
                            tenant,
                            UUID.fromString(id))))
        .hasMessageContaining("开发立项");
  }

  // ================================================================ 里程碑 / 目标市场 / 规格候选

  @Test
  void 里程碑计划时间写回一致不偏时区() throws Exception {
    String id = createProject("DP-20260101-C1", "100.00", "60.00");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("milestoneType", "SAMPLING");
    // 带 +08:00 偏移的时刻：写进 timestamptz 后读回来是**同一瞬间**（UTC 2026-01-31T16:00Z）。
    body.put("plannedAt", "2026-02-01T00:00:00+08:00");
    body.put("note", "第一轮打样");
    http.perform(
            post("/trade/development-projects/" + id + "/milestones")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
    var milestone = json.readTree(projectJson(id)).get("milestones").get(0);
    OffsetDateTime planned = OffsetDateTime.parse(milestone.get("plannedAt").asText());
    // 🔴 断言**写回的值**（同一个瞬间），不是「没抛异常」。
    assertThat(planned.toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-02-01T00:00:00+08:00").toInstant());
    assertThat(planned.toInstant()).isEqualTo(OffsetDateTime.parse("2026-01-31T16:00:00Z").toInstant());
    assertThat(milestone.get("milestoneTypeLabel").asText()).isEqualTo("打样");
  }

  @Test
  void 里程碑标记已达成时必须给实际时刻() throws Exception {
    String id = createProject("DP-20260101-C2", "100.00", "60.00");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("milestoneType", "SAMPLING");
    body.put("plannedAt", "2026-02-01T00:00:00Z");
    body.put("status", "REACHED");
    http.perform(
            post("/trade/development-projects/" + id + "/milestones")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 目标市场与平台和规格候选都能登记并读回() throws Exception {
    String id = createProject("DP-20260101-C3", "100.00", "60.00");
    http.perform(
            post("/trade/development-projects/" + id + "/markets")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "marketCode", "US",
                            "platformCode", "AMAZON",
                            "targetPrice", new BigDecimal("39.99"),
                            "currency", "USD",
                            "priority", 1))))
        .andExpect(status().isOk());
    http.perform(
            post("/trade/development-projects/" + id + "/spec-candidates")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("specName", "颜色", "specValue", "蓝", "sortRank", 0))))
        .andExpect(status().isOk());

    var detail = json.readTree(projectJson(id));
    assertThat(detail.get("markets")).hasSize(1);
    assertThat(detail.get("markets").get(0).get("marketCode").asText()).isEqualTo("US");
    assertThat(detail.get("markets").get(0).get("platformCode").asText()).isEqualTo("AMAZON");
    assertThat(detail.get("specCandidates")).hasSize(1);
    assertThat(detail.get("specCandidates").get(0).get("specName").asText()).isEqualTo("颜色");
    // 清单行上的计数也跟着更新。
    assertThat(detail.get("project").get("marketCount").asInt()).isEqualTo(1);
    assertThat(detail.get("project").get("milestoneCount").asInt()).isEqualTo(0);
  }

  // ================================================================ 市场观察

  @Test
  void 手工录入市场数据必须带来源与采集时间() throws Exception {
    String id = createProject("DP-20260101-D1", "100.00", "60.00");
    // 缺来源 ⇒ 400。
    http.perform(
            post("/trade/development-projects/" + id + "/market-observations")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "marketCode", "US",
                            "metricCode", "CATEGORY_SIZE",
                            "metricValue", new BigDecimal("120000"),
                            "collectedAt", "2026-10-01T00:00:00Z"))))
        .andExpect(status().is4xxClientError());
    // 缺采集时间 ⇒ 400。
    http.perform(
            post("/trade/development-projects/" + id + "/market-observations")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "marketCode", "US",
                            "metricCode", "CATEGORY_SIZE",
                            "metricValue", new BigDecimal("120000"),
                            "source", "亚马逊美国站类目页 2026-10"))))
        .andExpect(status().is4xxClientError());
    // 齐全 ⇒ 落库并带回来。
    http.perform(
            post("/trade/development-projects/" + id + "/market-observations")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "marketCode", "US",
                            "metricCode", "CATEGORY_SIZE",
                            "metricValue", new BigDecimal("120000"),
                            "valueUnit", "件",
                            "source", "亚马逊美国站类目页 2026-10",
                            "collectedAt", "2026-10-01T00:00:00Z"))))
        .andExpect(status().isOk());
    var observations = json.readTree(projectJson(id)).get("marketObservations");
    assertThat(observations).hasSize(1);
    assertThat(observations.get(0).get("source").asText()).isEqualTo("亚马逊美国站类目页 2026-10");
    assertThat(observations.get(0).has("collectedAt")).isTrue();
  }

  // ================================================================ 目标成本估算

  @Test
  void 目标成本能从利润测算器取到并由服务端重算毛利() throws Exception {
    String id = createProject("DP-20260101-E1", "100.00", null);
    UUID skuId = UUID.randomUUID();
    purchaseBySku.put(skuId, Map.of("amount", new BigDecimal("62.50"), "currency", "USD"));

    http.perform(
            post("/trade/development-projects/" + id + "/cost-estimate")
                .header("Authorization", "Bearer owner")
                .param("referenceSkuId", skuId.toString())
                .param("referenceSkuCode", "REF-SKU-1"))
        .andExpect(status().isOk());

    var project = json.readTree(projectJson(id)).get("project");
    assertThat(project.get("targetCost").asDouble()).isEqualTo(62.5d);
    assertThat(project.get("costAvailable").asBoolean()).isTrue();
    assertThat(project.get("costSource").asText()).isEqualTo("CATALOG_ESTIMATE");
    // 毛利由服务端重算：(100 − 62.5) ÷ 100 = 0.375
    assertThat(project.get("targetMarginRate").asDouble()).isEqualTo(0.375d);
  }

  @Test
  void 目标成本取不到时明确失败且不静默清空已有成本() throws Exception {
    String id = createProject("DP-20260101-E2", "100.00", "60.00");
    UUID skuId = UUID.randomUUID();
    // 不登记 ⇒ 上游说取不到。
    http.perform(
            post("/trade/development-projects/" + id + "/cost-estimate")
                .header("Authorization", "Bearer owner")
                .param("referenceSkuId", skuId.toString()))
        .andExpect(status().isServiceUnavailable());
    // 原有成本没被动。
    var project = json.readTree(projectJson(id)).get("project");
    assertThat(project.get("targetCost").asDouble()).isEqualTo(60.0d);
    assertThat(project.get("costAvailable").asBoolean()).isTrue();
  }

  @Test
  void 跨币种的目标成本判为取不到而不是按一比一折算() throws Exception {
    String id = createProject("DP-20260101-E3", "100.00", null);
    UUID skuId = UUID.randomUUID();
    purchaseBySku.put(skuId, Map.of("amount", new BigDecimal("62.50"), "currency", "EUR"));
    http.perform(
            get("/trade/development-projects/" + id + "/cost-estimate")
                .header("Authorization", "Bearer owner")
                .param("referenceSkuId", skuId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.available").value(false))
        // 🔴 取不到时 unitCost 是 null（**不是 0**）。
        .andExpect(jsonPath("$.unitCost").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.ownerModule").value(org.hamcrest.Matchers.containsString("汇率")));
  }

  // ================================================================ SKU 候选 / 交接单

  @Test
  void 未通过立项评审时不能产交接单() throws Exception {
    String id = createProject("DP-20260101-F1", "100.00", "60.00");
    http.perform(
            post("/trade/development-projects/" + id + "/handoffs")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("handoffType", "SAMPLING_ORDER"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 立项通过后能产交接单且只产候选参数() throws Exception {
    String id = createProject("DP-20260101-F2", "100.00", "60.00");
    transition(id, "APPROVED", "评审通过");
    http.perform(
            post("/trade/development-projects/" + id + "/markets")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "marketCode", "US",
                            "platformCode", "AMAZON",
                            "targetPrice", new BigDecimal("39.99"),
                            "currency", "USD"))))
        .andExpect(status().isOk());
    http.perform(
            post("/trade/development-projects/" + id + "/handoffs")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("handoffType", "SAMPLING_ORDER", "note", "打样需求"))))
        .andExpect(status().isOk());

    var detail = json.readTree(projectJson(id));
    assertThat(detail.get("handoffs")).hasSize(1);
    var handoff = detail.get("handoffs").get(0);
    // 交接给 6.6.1.2（打样），**本模块不代它建单**。
    assertThat(handoff.get("targetModule").asText()).isEqualTo("6.6.1.2");
    assertThat(handoff.get("status").asText()).isEqualTo("PENDING");
    var payload = json.readTree(handoff.get("payload").asText());
    assertThat(payload.get("projectNo").asText()).isEqualTo("DP-20260101-F2");
    assertThat(payload.get("targetMarkets")).hasSize(1);

    // SKU 候选参数（只产候选，不建 SKU）。
    http.perform(
            get("/trade/development-projects/" + id + "/sku-candidate")
                .header("Authorization", "Bearer owner"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetModule").value("3.1.1.1"))
        .andExpect(jsonPath("$.handoffReady").value(true))
        .andExpect(jsonPath("$.targetPriceMin").value(39.99d))
        .andExpect(jsonPath("$.targetPriceMax").value(39.99d));
  }

  // ================================================================ 清单 / 权限

  @Test
  void 清单真分页与筛选() throws Exception {
    createProject("DP-PAGE-1", "100.00", "60.00");
    createProject("DP-PAGE-2", "100.00", "60.00");
    http.perform(
            get("/trade/development-projects")
                .header("Authorization", "Bearer owner")
                .param("keyword", "DP-PAGE-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].projectNo").value("DP-PAGE-1"));

    http.perform(
            get("/trade/development-projects")
                .header("Authorization", "Bearer owner")
                .param("page", "0")
                .param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows").isArray())
        .andExpect(jsonPath("$.total").value(2));
  }

  @Test
  void 只有读权限时写端点403() throws Exception {
    // 请求体必须**合法**：否则 400（参数校验）会先于 403（权限）发生，测的就不是权限了。
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("projectNo", "DP-403");
    body.put("name", "折叠水杯");
    body.put("category", "家居");
    body.put("ownerId", user.toString());
    body.put("targetPrice", new BigDecimal("100.00"));
    body.put("currency", "USD");
    http.perform(
            post("/trade/development-projects")
                .header("Authorization", "Bearer reader")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isForbidden());
  }

  // ================================================================ 迁移确实落地了，且 RLS 是**强制**的

  @Test
  void 本模块的表强制rls而不是只启用() {
    for (String table :
        List.of(
            "development_project",
            "development_project_market",
            "development_project_milestone",
            "development_project_transition",
            "development_project_spec_candidate",
            "development_project_market_observation",
            "development_project_handoff")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V257 必须真的建出 %s 这张表", table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as("%s 的 RLS 未启用", table).isTrue();
      assertThat(row.get(1, Boolean.class)).as("%s 只有 FORCE 才挡得住「运行角色被误授 owner」", table)
          .isTrue();
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as("trade_app 必须真的能读 %s", table)
          .isTrue();
    }
  }
}
