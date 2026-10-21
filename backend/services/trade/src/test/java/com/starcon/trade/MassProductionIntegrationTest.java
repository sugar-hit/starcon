package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
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
 * 6.6.2.2 转量产与建档的集成测试。
 *
 * <p><b>本测试是 V268 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，逐条在真实
 * PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不是直连数据库，以便同时验证「服务层 + DB 层」
 * 两层都没有把闸门漏掉。
 *
 * <p><b>catalog / warehouse 起本地 stub 服务</b>：BOM 版本、SPU、SKU 建档、报关属性都在
 * catalog 独立库，6.2.1.2 的重算要读 warehouse —— 本模块跨服务一律走端口，
 * 禁跨库 SQL / FK / 视图。stub 只负责按上游契约形状返回数。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MassProductionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static final UUID readOnlyUser = UUID.randomUUID();
  static HttpServer identity, catalog, warehouse;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      List.of(
          "trade.mass-production.read",
          "trade.mass-production.update",
          "trade.production-trial.read",
          "trade.production-trial.update");

  // ---------------------------------------------------------------------
  // catalog stub 的可编程行为
  // ---------------------------------------------------------------------

  /** 建 SKU 的下一次结果：OK / REJECT / FAIL / TIMEOUT。 */
  static final AtomicReference<String> createOutcome = new AtomicReference<>("OK");

  /** 写报关属性的下一次结果：OK / REJECT。 */
  static final AtomicReference<String> customsOutcome = new AtomicReference<>("OK");

  /** warehouse stub 的模式：OK 正常 / FAIL 报错（用来验证「交接失败也留痕」）。 */
  static final AtomicReference<String> warehouseMode = new AtomicReference<>("OK");

  /** 已建出来的 SKU id（建档确认后能读回来）。 */
  static final Map<String, UUID> createdSkus = new ConcurrentHashMap<>();

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

  /** catalog 侧的 SPU（建档目标键）。 */
  static final UUID spuId = UUID.randomUUID();

  /** catalog 侧的商品属性（建档载荷的「属性」候选）。 */
  static final UUID attributeId = UUID.randomUUID();

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
          actor(readOnlyUser, List.of(grant("ALL", List.of("trade.mass-production.read")))));
      identity.start();

      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/",
          e -> {
            String path = e.getRequestURI().getPath();
            String method = e.getRequestMethod();
            try {
              routeCatalog(e, path, method);
            } catch (Exception ex) {
              e.sendResponseHeaders(500, -1);
              e.close();
            }
          });
      catalog.start();

      // warehouse：6.2.1.2 的重算要读可用量与在途腿（本模块只触发，不写它的表）。
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/inventory/sellable/skus",
          e -> {
            if ("FAIL".equals(warehouseMode.get())) {
              write(warehouse, e, 500, Map.of("message", "warehouse down"));
              return;
            }
            write(warehouse, e, 200, Map.of("rows", List.of(), "total", 0));
          });
      warehouse.createContext(
          "/warehouse/inventory/in-transit",
          e -> {
            if ("FAIL".equals(warehouseMode.get())) {
              write(warehouse, e, 500, Map.of("message", "warehouse down"));
              return;
            }
            write(warehouse, e, 200, Map.of("rows", List.of(), "total", 0));
          });
      warehouse.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** catalog stub 的路由。 */
  static void routeCatalog(com.sun.net.httpserver.HttpExchange e, String path, String method)
      throws java.io.IOException {
    // ---- 3.5.2.1 BOM 版本 ----
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
              "items", items, "page", 0, "size", 50, "total", items.size(), "totalPages", 1));
      return;
    }
    if (path.startsWith("/catalog/bom-versions/")) {
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
        String query = e.getRequestURI().getQuery();
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
      return;
    }

    // ---- 3.1.2.1 商品档案 / SKU 建档 ----
    if (path.equals("/catalog/products") && "GET".equals(method)) {
      List<Map<String, Object>> items = new ArrayList<>();
      Map<String, Object> spu = new LinkedHashMap<>();
      spu.put("id", spuId);
      spu.put("code", "SPU-CUP-001");
      spu.put("name", "折叠水杯");
      spu.put("description", "折叠水杯 SPU");
      spu.put("brand", "STARCON");
      spu.put("category", "家居");
      spu.put("status", "ON_SALE");
      spu.put("version", 1);
      spu.put("skuCount", 0);
      items.add(spu);
      write(catalog, e, 200, items);
      return;
    }
    if (path.startsWith("/catalog/products/") && path.endsWith("/skus") && "POST".equals(method)) {
      String outcome = createOutcome.get();
      if ("TIMEOUT".equals(outcome)) {
        // 睡到客户端读超时 ⇒ 客户端**无法判断**建没建出来（结果未知）。
        try {
          Thread.sleep(3000);
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
        }
        e.sendResponseHeaders(200, -1);
        e.close();
        return;
      }
      if ("REJECT".equals(outcome)) {
        write(catalog, e, 400, Map.of("message", "sku code already exists"));
        return;
      }
      if ("FAIL".equals(outcome)) {
        write(catalog, e, 500, Map.of("message", "boom"));
        return;
      }
      byte[] requestBody = e.getRequestBody().readAllBytes();
      var parsed = json.readTree(new String(requestBody, java.nio.charset.StandardCharsets.UTF_8));
      UUID skuId = UUID.randomUUID();
      createdSkus.put(parsed.get("code").asText(), skuId);
      write(catalog, e, 200, skuId.toString());
      return;
    }
    if (path.startsWith("/catalog/products/") && "GET".equals(method)) {
      String id = path.substring("/catalog/products/".length());
      if (!id.equals(spuId.toString())) {
        e.sendResponseHeaders(404, -1);
        e.close();
        return;
      }
      Map<String, Object> spu = new LinkedHashMap<>();
      spu.put("id", spuId);
      spu.put("code", "SPU-CUP-001");
      spu.put("name", "折叠水杯");
      spu.put("description", "折叠水杯 SPU");
      spu.put("brand", "STARCON");
      spu.put("category", "家居");
      spu.put("status", "ON_SALE");
      spu.put("version", 1);
      spu.put("skuCount", 0);
      Map<String, Object> attribute = new LinkedHashMap<>();
      attribute.put("id", attributeId);
      attribute.put("code", "COLOR");
      attribute.put("name", "颜色");
      attribute.put("dataType", "TEXT");
      attribute.put("valueMode", "SKU_ONLY");
      attribute.put("multiple", true);
      attribute.put("required", false);
      attribute.put("variantAxis", true);
      attribute.put("unit", "个");
      attribute.put("groupName", "外观");
      attribute.put("options", List.of("蓝色", "红色"));
      attribute.put("defaultValues", List.of());
      write(
          catalog,
          e,
          200,
          Map.of("product", spu, "attributes", List.of(attribute), "skus", List.of()));
      return;
    }

    // ---- 3.7.1.1 报关属性 ----
    if (path.startsWith("/catalog/customs-attributes/skus/") && "PUT".equals(method)) {
      if ("REJECT".equals(customsOutcome.get())) {
        write(catalog, e, 400, Map.of("message", "hs code invalid"));
        return;
      }
      write(catalog, e, 200, Map.of("profile", Map.of("id", UUID.randomUUID())));
      return;
    }

    // ---- 6.2.1.2 重算要读的形状（无数据时不会走到这里，兜底返回空） ----
    if (path.equals("/catalog/supplier-quotes/effective")) {
      write(catalog, e, 200, List.of());
      return;
    }
    if (path.startsWith("/catalog/skus/")) {
      e.sendResponseHeaders(404, -1);
      e.close();
      return;
    }

    e.sendResponseHeaders(404, -1);
    e.close();
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
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 短读超时：让「结果未知」那条路在测试里可复现（不真的等 8 秒）。
    r.add("CATALOG_TIMEOUT_SECONDS", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    catalog.stop(0);
    warehouse.stop(0);
    pg.stop();
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @BeforeEach
  void seed() throws Exception {
    bomVersions.clear();
    createdSkus.clear();
    createOutcome.set("OK");
    customsOutcome.set("OK");
    warehouseMode.set("OK");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate mass_production_downstream_handoff, mass_production_customs_element,"
              + " mass_production_sku_filing_attribute_value, mass_production_sku_filing_attribute,"
              + " mass_production_sku_filing, mass_production_bom_line,"
              + " mass_production_plan_transition, mass_production_plan cascade");
      s.execute(
          "truncate production_trial_mass_production_recommendation, production_trial_cost_line,"
              + " production_trial_cost, production_trial_result, production_trial_inspection_item,"
              + " production_trial_component_requirement, production_trial_order_transition,"
              + " production_trial_order cascade");
      s.execute("truncate replenishment_suggestion_decision, replenishment_suggestion_step,"
          + " replenishment_suggestion, replenishment_suggestion_batch cascade");
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
                    UUID.randomUUID(), "MAT-001", new BigDecimal("2.000000"), new BigDecimal("5.0000")))));
    return id;
  }

  /** 建一条**已完成**且**有「建议转量产」结论的试产单（走 6.6.2.1 的真实端点）。 */
  String createCompletedTrial(String suffix) throws Exception {
    return createCompletedTrial(suffix, "GO");
  }

  /** 建一条**已完成**的试产单，并按 decision 出上游转量产建议（GO / NO_GO）。 */
  String createCompletedTrial(String suffix, String decision) throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trialNo", "TP-" + suffix);
    body.put("bomVersionId", bomVersionId.toString());
    body.put("targetQuantity", new BigDecimal("100.000000"));
    body.put("currency", "USD");
    body.put("requestedAt", "2026-10-21T09:00:00+08:00");
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
    String id = json.readTree(response).get("order").get("id").asText();

    // 领用需求全部提交（开产前的前置）。
    String listJson =
        http.perform(
                get("/trade/trial-productions/" + id + "/component-requirements")
                    .header("Authorization", "Bearer owner"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (var node : json.readTree(listJson)) {
      http.perform(
              post(
                      "/trade/trial-productions/"
                          + id
                          + "/component-requirements/"
                          + node.get("id").asText()
                          + "/submit")
                  .header("Authorization", "Bearer owner"))
          .andExpect(status().isOk());
    }

    // REQUESTED → SCHEDULED → IN_PROGRESS
    transitionTrial(id, "SCHEDULED");
    transitionTrial(id, "IN_PROGRESS");

    // 记录试产结果（良率 0.95）。
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("outputQuantity", new BigDecimal("100.000000"));
    result.put("qualifiedQuantity", new BigDecimal("95.000000"));
    result.put("conclusion", "良率达标");
    result.put("recordedAt", "2026-10-21T10:00:00+08:00");
    http.perform(
            post("/trade/trial-productions/" + id + "/result")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(result)))
        .andExpect(status().isOk());

    transitionTrial(id, "COMPLETED");

    // 出「建议转量产」结论。
    Map<String, Object> recommendation = new LinkedHashMap<>();
    recommendation.put("decision", decision);
    recommendation.put("rationale", "良率 0.95 达标");
    recommendation.put("conditionNote", "良率门槛 0.90，本次不设成本门槛");
    recommendation.put("minYieldThreshold", new BigDecimal("0.900000"));
    recommendation.put("maxCostVarianceRate", null);
    http.perform(
            post("/trade/trial-productions/" + id + "/mass-production-recommendation")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(recommendation)))
        .andExpect(status().isOk());
    return id;
  }

  void transitionTrial(String id, String toStatus) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("toStatus", toStatus);
    body.put("reason", "测试推进");
    http.perform(
            post("/trade/trial-productions/" + id + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  /** 建一条量产计划（默认 DRAFT）。 */
  String createPlan(String trialOrderId, String planNo) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("planNo", planNo);
    body.put("trialOrderId", trialOrderId);
    body.put("bomVersionId", bomVersions.keySet().iterator().next().toString());
    body.put("planQuantity", new BigDecimal("1000.000000"));
    body.put("targetCost", new BigDecimal("12.5000"));
    body.put("currency", "USD");
    body.put("factoryCode", "F-001");
    body.put("factoryName", "宁波工厂");
    body.put("plannedStartAt", "2026-11-01T09:00:00+08:00");
    body.put("note", "首批量产");
    String response =
        http.perform(
                post("/trade/mass-productions")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response).get("plan").get("id").asText();
  }

  /** 推进量产计划状态。 */
  void transitionPlan(String id, String targetStatus, Map<String, Object> extra) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetStatus", targetStatus);
    body.put("reason", "测试推进");
    if (extra != null) {
      body.putAll(extra);
    }
    http.perform(
            post("/trade/mass-productions/" + id + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  /** 走完「已决策 → BOM 已定版」。 */
  String planDecidedAndFrozen(String trialOrderId, String planNo) throws Exception {
    String id = createPlan(trialOrderId, planNo);
    transitionPlan(
        id,
        "DECIDED",
        Map.of(
            "decision",
            "GO",
            "decisionSource",
            "TRIAL_RECOMMENDATION",
            "decisionRationale",
            "上游建议转量产，良率达标"));
    transitionPlan(id, "BOM_FROZEN", null);
    return id;
  }

  /** 建一条 SKU 建档请求。 */
  String createFiling(String planId, boolean withCustoms) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetSpuId", spuId.toString());
    body.put("skuCode", "SKU-CUP-001-" + planNoSuffix(planId));
    body.put("skuName", "折叠水杯 蓝色");
    body.put("barcode", "690123456789" + Math.abs(planId.hashCode() % 1000));
    body.put("baseUnit", "个");
    body.put("skuStatus", "DRAFT");
    List<Map<String, Object>> attributes = new ArrayList<>();
    Map<String, Object> attribute = new LinkedHashMap<>();
    attribute.put("attributeId", attributeId.toString());
    attribute.put("attributeCode", "COLOR");
    attribute.put("attributeName", "颜色");
    attribute.put("valueState", "SET");
    attribute.put("values", List.of("蓝色"));
    attributes.add(attribute);
    body.put("attributes", attributes);
    List<Map<String, Object>> elements = new ArrayList<>();
    if (withCustoms) {
      body.put("customsHsCode", "961700");
      body.put("customsOriginCountryCode", "CN");
      body.put("customsDeclaredName", "折叠水杯");
      body.put("customsStatutoryUnitCode", "PCS");
      Map<String, Object> element = new LinkedHashMap<>();
      element.put("ordinal", 1);
      element.put("elementCode", "MAT");
      element.put("elementName", "材质");
      element.put("elementValue", "PP");
      element.put("required", true);
      elements.add(element);
    }
    body.put("customsElements", elements);
    String response =
        http.perform(
                post("/trade/mass-productions/" + planId + "/sku-filing")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response).get("id").asText();
  }

  static String planNoSuffix(String planId) {
    return planId.substring(0, 8);
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明如实标出做不到的事() throws Exception {
    var body =
        json.readTree(
            http.perform(get("/trade/mass-productions/capabilities").header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(body.get("skuFilingAvailable").asBoolean())
        .as("catalog 确实有建档入口 ⇒ 可用")
        .isTrue();
    assertThat(body.get("skuFilingOwner").asText()).contains("3.1.2.1");
    assertThat(body.get("purchaseDemandAvailable").asBoolean()).isTrue();
    assertThat(body.get("pricingAvailable").asBoolean())
        .as("5.3.1.1 未交付 ⇒ 如实标不可用")
        .isFalse();
    assertThat(body.get("pricingOwner").asText()).contains("5.3.1.1");
    assertThat(body.get("assemblyOrderAvailable").asBoolean())
        .as("8.4.1 组装单未衔接 ⇒ 如实标不可用")
        .isFalse();
  }

  // ================================================================ 建计划

  @Test
  void 建计划要求试产已完成() throws Exception {
    UUID bomVersionId = registerBomVersion("PUBLISHED", "已发布");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("planNo", "MP-EARLY");
    body.put("trialOrderId", UUID.randomUUID().toString());
    body.put("bomVersionId", bomVersionId.toString());
    body.put("planQuantity", new BigDecimal("1000.000000"));
    body.put("currency", "USD");
    http.perform(
            post("/trade/mass-productions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isNotFound());
  }

  @Test
  void 建计划要求BOM版本已发布() throws Exception {
    String trialId = createCompletedTrial("PUB");
    UUID draftBom = registerBomVersion("DRAFT", "草稿");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("planNo", "MP-DRAFTBOM");
    body.put("trialOrderId", trialId);
    body.put("bomVersionId", draftBom.toString());
    body.put("planQuantity", new BigDecimal("1000.000000"));
    body.put("currency", "USD");
    http.perform(
            post("/trade/mass-productions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 计划号在租户内唯一() throws Exception {
    String trialId = createCompletedTrial("UNIQ");
    createPlan(trialId, "MP-DUP");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("planNo", "MP-DUP");
    body.put("trialOrderId", trialId);
    body.put("bomVersionId", bomVersions.keySet().iterator().next().toString());
    body.put("planQuantity", new BigDecimal("1000.000000"));
    body.put("currency", "USD");
    http.perform(
            post("/trade/mass-productions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isConflict());
  }

  // ================================================================ 量产决策的举证

  @Test
  void 决策缺理由被拒() throws Exception {
    String trialId = createCompletedTrial("NOREASON");
    String planId = createPlan(trialId, "MP-NOREASON");
    expectTransitionStatus(
        planId,
        "DECIDED",
        Map.of(
            "decision",
            "GO",
            "decisionSource",
            "TRIAL_RECOMMENDATION",
            "decisionRationale",
            "   "),
        400);
  }

  /** 断言一次状态迁移拿到的是预期的 HTTP 状态。 */
  void expectTransitionStatus(
      String id, String targetStatus, Map<String, Object> extra, int expectedStatus)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetStatus", targetStatus);
    body.put("reason", "测试推进");
    if (extra != null) {
      body.putAll(extra);
    }
    http.perform(
            post("/trade/mass-productions/" + id + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().is(expectedStatus));
  }

  @Test
  void 覆盖上游建议必须写理由() throws Exception {
    // 上游建议本来就是「暂不建议转量产」⇒ 本计划要「转量产」就必须写覆盖理由。
    String trialId = createCompletedTrial("OVERRIDE", "NO_GO");
    String planId = createPlan(trialId, "MP-OVERRIDE");
    Map<String, Object> noReason =
        Map.of(
            "targetStatus",
            "DECIDED",
            "decision",
            "GO",
            "decisionSource",
            "MANUAL_OVERRIDE",
            "decisionRationale",
            "客户坚持");
    var rejected =
        http.perform(
                post("/trade/mass-productions/" + planId + "/transitions")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(without(noReason, "overrideReason"))))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse();
    assertThat(rejected.getErrorMessage())
        .as("缺覆盖理由要说清为什么")
        .contains("覆盖理由");

    Map<String, Object> withReason = new LinkedHashMap<>(noReason);
    withReason.put("overrideReason", "客户已确认先做 500 件试水");
    http.perform(
            post("/trade/mass-productions/" + planId + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(withReason)))
        .andExpect(status().isOk());
  }

  static Map<String, Object> without(Map<String, Object> source, String key) {
    Map<String, Object> copy = new LinkedHashMap<>(source);
    copy.remove(key);
    return copy;
  }

  @Test
  void 决策落库后不可改写() throws Exception {
    String trialId = createCompletedTrial("IMMUT");
    String planId = createPlan(trialId, "MP-IMMUT");
    transitionPlan(
        planId,
        "DECIDED",
        Map.of(
            "decision",
            "GO",
            "decisionSource",
            "TRIAL_RECOMMENDATION",
            "decisionRationale",
            "良率达标"));
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      var e =
          assertThatThrownBy(
                  () ->
                      s.execute(
                          "update mass_production_plan set decision_rationale = '改一改' where id = '"
                              + planId
                              + "'"))
              .isInstanceOf(java.sql.SQLException.class)
              .hasMessageContaining("不可改写");
      assertThat(e).isNotNull();
    }
  }

  // ================================================================ BOM 定版

  @Test
  void 定版把catalog的R7需求量原样搬过来() throws Exception {
    String trialId = createCompletedTrial("FREEZE");
    String planId = planDecidedAndFrozen(trialId, "MP-FREEZE");
    String body =
        http.perform(get("/trade/mass-productions/" + planId + "/bom-lines")
                .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var lines = json.readTree(body);
    assertThat(lines.size()).isEqualTo(1);
    var line = lines.get(0);
    // 计划批量 1000 × 单位用量 2 × (1 + 5%) = 2100.000000（catalog 的 R6/R7，本服务不重算）
    assertThat(new BigDecimal(line.get("requiredQuantity").asText()))
        .isEqualByComparingTo(new BigDecimal("2100.000000"));
    assertThat(new BigDecimal(line.get("unitQuantity").asText()))
        .isEqualByComparingTo(new BigDecimal("2.000000"));
    assertThat(line.get("skuCode").asText()).isEqualTo("MAT-001");
  }

  @Test
  void 没有组件行不许标成BOM已定版() throws Exception {
    String trialId = createCompletedTrial("NOFREEZE");
    String planId = createPlan(trialId, "MP-NOFREEZE");
    transitionPlan(
        planId,
        "DECIDED",
        Map.of(
            "decision",
            "GO",
            "decisionSource",
            "TRIAL_RECOMMENDATION",
            "decisionRationale",
            "良率达标"));
    // 直接改库跳过定版 ⇒ DB 守卫必须挡。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "insert into mass_production_plan_transition(id,tenant_id,plan_id,from_status,"
                          + " to_status,reason,actor_id,occurred_at) values (gen_random_uuid(),'"
                          + tenant
                          + "','"
                          + planId
                          + "','DECIDED','BOM_FROZEN','绕过服务',gen_random_uuid(),now());"
                          + " update mass_production_plan set status = 'BOM_FROZEN' where id = '"
                          + planId
                          + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("还没有定版");
    }
  }

  // ================================================================ SKU 建档

  @Test
  void 建档请求落的是待办事实不是SKU() throws Exception {
    String trialId = createCompletedTrial("FILING");
    String planId = planDecidedAndFrozen(trialId, "MP-FILING");
    String filingId = createFiling(planId, false);
    var body = json.readTree(filingJson(planId));
    assertThat(body.get("status").asText()).isEqualTo("PENDING");
    assertThat(body.get("targetSystem").asText()).isEqualTo("catalog");
    assertThat(body.get("targetSpuId").asText()).isEqualTo(spuId.toString());
    assertThat(body.get("idempotencyKey").asText()).isNotBlank();
    assertThat(body.get("targetSkuId").isNull())
        .as("还没提交 ⇒ 没有 SKU id")
        .isTrue();

    // 🔴 本服务**不建 SKU 表**：库里根本没有 sku / barcode 主数据表。
    List<String> tables = new ArrayList<>();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs =
            s.executeQuery(
                "select table_name from information_schema.tables where table_schema = 'public'"
                    + " and table_name in ('sku','spu','mass_production_sku')")) {
      while (rs.next()) {
        tables.add(rs.getString(1));
      }
    }
    assertThat(tables).as("正式 SKU 的表归 catalog，本服务不建").isEmpty();
    assertThat(filingId).isNotBlank();
  }

  @Test
  void 一张计划只允许一条建档请求() throws Exception {
    String trialId = createCompletedTrial("ONEFILE");
    String planId = planDecidedAndFrozen(trialId, "MP-ONEFILE");
    createFiling(planId, false);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("targetSpuId", spuId.toString());
    body.put("skuCode", "SKU-OTHER");
    body.put("skuName", "另一个 SKU");
    body.put("barcode", "6900000000000");
    body.put("baseUnit", "个");
    body.put("skuStatus", "DRAFT");
    body.put("attributes", List.of());
    body.put("customsElements", List.of());
    http.perform(
            post("/trade/mass-productions/" + planId + "/sku-filing")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isConflict());
  }

  @Test
  void 提交建档真的把SKU建到商品中心() throws Exception {
    String trialId = createCompletedTrial("SUBMIT");
    String planId = planDecidedAndFrozen(trialId, "MP-SUBMIT");
    String filingId = createFiling(planId, false);
    String body =
        http.perform(
                post("/trade/mass-productions/" + planId + "/sku-filing/" + filingId + "/submit")
                    .header("Authorization", "Bearer owner"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var filing = json.readTree(body);
    assertThat(filing.get("status").asText()).isEqualTo("CONFIRMED");
    assertThat(filing.get("targetSkuId").asText())
        .as("SKU id 是从商品中心读回来的，不是本服务编的")
        .isNotBlank();
    assertThat(createdSkus).containsKey(filing.get("skuCode").asText());
  }

  @Test
  void 已建成的建档请求不许再提交() throws Exception {
    String trialId = createCompletedTrial("RESUBMIT");
    String planId = planDecidedAndFrozen(trialId, "MP-RESUBMIT");
    String filingId = createFiling(planId, false);
    http.perform(
            post("/trade/mass-productions/" + planId + "/sku-filing/" + filingId + "/submit")
                .header("Authorization", "Bearer owner"))
        .andExpect(status().isOk());
    http.perform(
            post("/trade/mass-productions/" + planId + "/sku-filing/" + filingId + "/submit")
                .header("Authorization", "Bearer owner"))
        .andExpect(status().isConflict());
  }

  @Test
  void 超时导致结果未知时不许盲目重试() throws Exception {
    String trialId = createCompletedTrial("UNKNOWN");
    String planId = planDecidedAndFrozen(trialId, "MP-UNKNOWN");
    String filingId = createFiling(planId, false);
    createOutcome.set("TIMEOUT");
    http.perform(
            post("/trade/mass-productions/" + planId + "/sku-filing/" + filingId + "/submit")
                .header("Authorization", "Bearer owner"))
        .andExpect(status().isServiceUnavailable());
    var filing = json.readTree(filingJson(planId));
    assertThat(filing.get("status").asText())
        .as("结果未知是一种独立状态，不是普通的失败")
        .isEqualTo("UNKNOWN");
    // 🔴 再提交必须被拒：商品中心的建档端点不接受幂等键，重试可能建出重复 SKU。
    http.perform(
            post("/trade/mass-productions/" + planId + "/sku-filing/" + filingId + "/submit")
                .header("Authorization", "Bearer owner"))
        .andExpect(status().isConflict());
  }

  @Test
  void 商品中心拒单时建档失败且可重试() throws Exception {
    String trialId = createCompletedTrial("REJECT");
    String planId = planDecidedAndFrozen(trialId, "MP-REJECT");
    String filingId = createFiling(planId, false);
    createOutcome.set("REJECT");
    http.perform(
            post("/trade/mass-productions/" + planId + "/sku-filing/" + filingId + "/submit")
                .header("Authorization", "Bearer owner"))
        .andExpect(status().isBadRequest());
    var filing = json.readTree(filingJson(planId));
    assertThat(filing.get("status").asText()).isEqualTo("FAILED");
    assertThat(filing.get("errorMessage").asText()).isNotBlank();
    assertThat(filing.get("targetSkuId").isNull())
        .as("被拒 ⇒ SKU 根本没建出来")
        .isTrue();
    // 失败后可以重试。
    createOutcome.set("OK");
    http.perform(
            post("/trade/mass-productions/" + planId + "/sku-filing/" + filingId + "/submit")
                .header("Authorization", "Bearer owner"))
        .andExpect(status().isOk());
  }

  @Test
  void 报关属性写不进去时是部分完成且SKU已建成() throws Exception {
    String trialId = createCompletedTrial("PARTIAL");
    String planId = planDecidedAndFrozen(trialId, "MP-PARTIAL");
    String filingId = createFiling(planId, true);
    customsOutcome.set("REJECT");
    http.perform(
            post("/trade/mass-productions/" + planId + "/sku-filing/" + filingId + "/submit")
                .header("Authorization", "Bearer owner"))
        .andExpect(status().isBadRequest());
    var filing = json.readTree(filingJson(planId));
    assertThat(filing.get("status").asText()).isEqualTo("PARTIAL");
    assertThat(filing.get("targetSkuId").asText())
        .as("SKU 真的建成了 ⇒ id 必须记下来")
        .isNotBlank();
    // 再提交只补报关属性，不会建第二个 SKU。
    customsOutcome.set("OK");
    http.perform(
            post("/trade/mass-productions/" + planId + "/sku-filing/" + filingId + "/submit")
                .header("Authorization", "Bearer owner"))
        .andExpect(status().isOk());
    assertThat(createdSkus).hasSize(1);
  }

  @Test
  void 没有已确认的建档就不许标成SKU已建档() throws Exception {
    String trialId = createCompletedTrial("NOSKU");
    String planId = planDecidedAndFrozen(trialId, "MP-NOSKU");
    http.perform(
            post("/trade/mass-productions/" + planId + "/transitions")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("targetStatus", "SKU_FILED", "reason", "x"))))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 下游交接

  @Test
  void 下达时如实记下两个下游的交接结果() throws Exception {
    String trialId = createCompletedTrial("HANDOFF");
    String planId = planDecidedAndFrozen(trialId, "MP-HANDOFF");
    String filingId = createFiling(planId, false);
    http.perform(
            post("/trade/mass-productions/" + planId + "/sku-filing/" + filingId + "/submit")
                .header("Authorization", "Bearer owner"))
        .andExpect(status().isOk());
    transitionPlan(planId, "SKU_FILED", null);
    transitionPlan(planId, "RELEASED", null);

    var handoffs = json.readTree(handoffJson(planId));
    assertThat(handoffs.size()).isEqualTo(2);
    Map<String, com.fasterxml.jackson.databind.JsonNode> byModule = new LinkedHashMap<>();
    for (var handoff : handoffs) {
      byModule.put(handoff.get("targetModule").asText(), handoff);
    }
    var replenishment = byModule.get("6.2.1.2");
    assertThat(replenishment).isNotNull();
    assertThat(replenishment.get("status").asText())
        .as("交接失败原因：%s", replenishment.get("errorMessage").asText())
        .isEqualTo("SUCCEEDED");
    assertThat(replenishment.get("resultRef").asText())
        .as("重算触发成功 ⇒ 批次号要留下来")
        .isNotBlank();
    assertThat(replenishment.get("massProductionInputApplied").asBoolean())
        .as("🔴 量产参数没有进到 6.2.1.2 的计算里，必须如实标 false")
        .isFalse();
    assertThat(replenishment.get("impact").asText()).contains("未");

    var pricing = byModule.get("5.3.1.1");
    assertThat(pricing).isNotNull();
    assertThat(pricing.get("status").asText()).isEqualTo("SKIPPED");
    assertThat(pricing.get("available").asBoolean())
        .as("5.3.1.1 未交付 ⇒ 如实标不可用")
        .isFalse();
    assertThat(pricing.get("massProductionInputApplied").asBoolean()).isFalse();
  }

  @Test
  void 交接失败也留痕而且不回滚下达() throws Exception {
    String trialId = createCompletedTrial("HFAIL");
    String planId = planDecidedAndFrozen(trialId, "MP-HFAIL");
    String filingId = createFiling(planId, false);
    http.perform(
            post("/trade/mass-productions/" + planId + "/sku-filing/" + filingId + "/submit")
                .header("Authorization", "Bearer owner"))
        .andExpect(status().isOk());
    transitionPlan(planId, "SKU_FILED", null);
    // 让补货重算失败：warehouse 报错（用模式开关，**不**停服务器 ——
    // 停服务器会让 WAREHOUSE_URL 指到死端口，把后面所有用例一起带崩）。
    warehouseMode.set("FAIL");
    try {
      transitionPlan(planId, "RELEASED", null);
    } finally {
      warehouseMode.set("OK");
    }
    var plan = json.readTree(detailJson(planId)).get("plan");
    assertThat(plan.get("status").asText())
        .as("下达是硬事实，交接是派生动作：交接失败不回滚下达")
        .isEqualTo("RELEASED");
    var handoffs = json.readTree(handoffJson(planId));
    boolean sawFailed = false;
    for (var handoff : handoffs) {
      if ("6.2.1.2".equals(handoff.get("targetModule").asText())
          && "FAILED".equals(handoff.get("status").asText())) {
        sawFailed = true;
        assertThat(handoff.get("errorMessage").asText()).isNotBlank();
      }
    }
    assertThat(sawFailed).as("失败也必须留痕").isTrue();
  }

  @Test
  void 手动重触发补货重算也标着参数没进去() throws Exception {
    String trialId = createCompletedTrial("RETRIG");
    String planId = planDecidedAndFrozen(trialId, "MP-RETRIG");
    http.perform(
            post("/trade/mass-productions/" + planId + "/handoffs/replenishment")
                .header("Authorization", "Bearer owner"))
        .andExpect(status().isOk());
    var handoffs = json.readTree(handoffJson(planId));
    assertThat(handoffs.size()).isEqualTo(2);
    for (var handoff : handoffs) {
      assertThat(handoff.get("massProductionInputApplied").asBoolean())
          .as("🔴 量产参数没有进到下游计算里，必须如实标 false")
          .isFalse();
    }
    boolean sawReplenishment = false;
    boolean sawPricing = false;
    for (var handoff : handoffs) {
      if ("6.2.1.2".equals(handoff.get("targetModule").asText())) {
        sawReplenishment = true;
        assertThat(handoff.get("status").asText()).isEqualTo("SUCCEEDED");
      }
      if ("5.3.1.1".equals(handoff.get("targetModule").asText())) {
        sawPricing = true;
        assertThat(handoff.get("available").asBoolean())
            .as("5.3.1.1 未交付 ⇒ 如实标不可用")
            .isFalse();
      }
    }
    assertThat(sawReplenishment).isTrue();
    assertThat(sawPricing).isTrue();
  }

  // ================================================================ 成本对比

  @Test
  void 目标成本取不到时标不可比而不是零() throws Exception {
    String trialId = createCompletedTrial("NOCOST");
    UUID bomVersionId = bomVersions.keySet().iterator().next();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("planNo", "MP-NOCOST");
    body.put("trialOrderId", trialId);
    body.put("bomVersionId", bomVersionId.toString());
    body.put("planQuantity", new BigDecimal("1000.000000"));
    body.put("targetCost", null);
    body.put("currency", "USD");
    String response =
        http.perform(
                post("/trade/mass-productions")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String planId = json.readTree(response).get("plan").get("id").asText();
    var comparison =
        json.readTree(
            http.perform(get("/trade/mass-productions/" + planId + "/cost-comparison")
                    .header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(comparison.get("comparable").asBoolean()).isFalse();
    assertThat(comparison.get("targetCost").isNull())
        .as("取不到就是取不到，不是 0")
        .isTrue();
    assertThat(comparison.get("reason").asText()).contains("不可比");
  }

  @Test
  void 跨币种不可比且不折算() throws Exception {
    String trialId = createCompletedTrial("FX");
    UUID bomVersionId = bomVersions.keySet().iterator().next();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("planNo", "MP-FX");
    body.put("trialOrderId", trialId);
    body.put("bomVersionId", bomVersionId.toString());
    body.put("planQuantity", new BigDecimal("1000.000000"));
    body.put("targetCost", new BigDecimal("12.5000"));
    body.put("currency", "CNY");
    String response =
        http.perform(
                post("/trade/mass-productions")
                    .header("Authorization", "Bearer owner")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String planId = json.readTree(response).get("plan").get("id").asText();
    var comparison =
        json.readTree(
            http.perform(get("/trade/mass-productions/" + planId + "/cost-comparison")
                    .header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(comparison.get("comparable").asBoolean()).isFalse();
    assertThat(comparison.get("varianceAmount").isNull())
        .as("跨币种不做固定汇率折算 ⇒ 差额算不出来")
        .isTrue();
    assertThat(comparison.get("reason").asText()).contains("汇率");
  }

  @Test
  void 同币种可比时算得出偏差率() throws Exception {
    String trialId = createCompletedTrial("SAMEFX");
    // 给试产单补一条成本明细（12 元/个 × 100 = 1200）。
    addTrialCostLine(trialId, new BigDecimal("12.0000"));
    String planId = createPlan(trialId, "MP-SAMEFX");
    var comparison =
        json.readTree(
            http.perform(get("/trade/mass-productions/" + planId + "/cost-comparison")
                    .header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(comparison.get("comparable").asBoolean()).isTrue();
    assertThat(new BigDecimal(comparison.get("trialCost").asText()))
        .isEqualByComparingTo("12.0000");
    assertThat(new BigDecimal(comparison.get("varianceAmount").asText()))
        .isEqualByComparingTo("-0.5000");
    assertThat(new BigDecimal(comparison.get("varianceRate").asText()))
        .isEqualByComparingTo("-0.040000");
  }

  void addTrialCostLine(String trialId, BigDecimal amount) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("costType", "MATERIAL");
    body.put("amount", amount);
    body.put("description", "材料成本");
    body.put("incurredAt", "2026-10-21T10:30:00+08:00");
    http.perform(
            post("/trade/trial-productions/" + trialId + "/cost/lines")
                .header("Authorization", "Bearer owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  // ================================================================ 清单与权限

  @Test
  void 清单真分页与真筛选() throws Exception {
    String trialId = createCompletedTrial("LIST");
    createPlan(trialId, "MP-LIST-1");
    createPlan(trialId, "MP-LIST-2");
    var page =
        json.readTree(
            http.perform(
                    get("/trade/mass-productions?page=0&size=1")
                        .header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(page.get("total").asLong()).isEqualTo(2);
    assertThat(page.get("rows").size()).isEqualTo(1);
    assertThat(page.get("totalPages").asInt()).isEqualTo(2);

    var filtered =
        json.readTree(
            http.perform(
                    get("/trade/mass-productions?keyword=MP-LIST-2")
                        .header("Authorization", "Bearer owner"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(filtered.get("total").asLong()).isEqualTo(1);
    assertThat(filtered.get("rows").get(0).get("planNo").asText()).isEqualTo("MP-LIST-2");
  }

  @Test
  void 只有读权限时写端点403() throws Exception {
    String trialId = createCompletedTrial("READONLY");
    String planId = createPlan(trialId, "MP-READONLY");
    http.perform(
            post("/trade/mass-productions/" + planId + "/transitions")
                .header("Authorization", "Bearer reader")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("targetStatus", "CANCELLED", "reason", "x"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void 跳级迁移被DB守卫挡住() throws Exception {
    String trialId = createCompletedTrial("SKIP");
    String planId = createPlan(trialId, "MP-SKIP");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "insert into mass_production_plan_transition(id,tenant_id,plan_id,from_status,"
                          + " to_status,reason,actor_id,occurred_at) values (gen_random_uuid(),'"
                          + tenant
                          + "','"
                          + planId
                          + "','DRAFT','RELEASED','绕过服务',gen_random_uuid(),now());"
                          + " update mass_production_plan set status = 'RELEASED' where id = '"
                          + planId
                          + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("状态不允许");
    }
  }

  @Test
  void 迁移事件不可改写不可删() throws Exception {
    String trialId = createCompletedTrial("TRAIL");
    String planId = createPlan(trialId, "MP-TRAIL");
    transitionPlan(
        planId,
        "DECIDED",
        Map.of(
            "decision",
            "GO",
            "decisionSource",
            "TRIAL_RECOMMENDATION",
            "decisionRationale",
            "良率达标"));
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(() -> s.execute("delete from mass_production_plan_transition"))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("不可删除");
      assertThatThrownBy(
              () -> s.execute("update mass_production_plan_transition set reason = '改一改'"))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("不可改写");
    }
  }

  // ================================================================ 工具

  String detailJson(String planId) throws Exception {
    return http.perform(get("/trade/mass-productions/" + planId).header("Authorization", "Bearer owner"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  String filingJson(String planId) throws Exception {
    return http.perform(
            get("/trade/mass-productions/" + planId + "/sku-filing").header("Authorization", "Bearer owner"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  String handoffJson(String planId) throws Exception {
    return http.perform(
            get("/trade/mass-productions/" + planId + "/handoffs").header("Authorization", "Bearer owner"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
