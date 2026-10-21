package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.PricingRuleModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 5.3.2.2 价格规则的**多租户三断言**（AGENTS.md 明文要求，缺一不可）。
 *
 * <p>跑的是这三条：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>：A 看不到 B 的规则 / 作用范围 / 命中记录 / 建议调价
 *       （列表、明细、直查都验）；
 *   <li><b>伪造 tenant header 无效</b>：请求头里的 {@code X-Tenant-Id} 改不了任何事 ——
 *       租户只来自已认证成员关系（identity），伪造头下写入的行仍然落在调用者自己名下；
 *   <li><b>连接池归还后不串租户</b>：连接池压到 1 条连接，A→B→A→B 交替 5 轮，
 *       每一轮读到的都只有当前租户的数据。
 * </ol>
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身</b>；运行角色
 * {@code catalog_app} 是 {@code nobypassrls}（否则它会绕过策略，三条断言全部变成空断言）。
 *
 * <p>为什么价格规则必须隔离：规则里写着这个租户的<b>降价幅度、毛利下限与自动放行阈值</b>。
 * A 读到 B 的规则 ⇒ A 的自动调价会按 B 的成本口径出街；而 A 的建议调价列表里会出现
 * B 的 SKU 与 B 的成本 —— 那是一条直接把两个租户的经营数据串起来的路。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PricingRuleTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer warehouse;
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();

  static final LocalDate DAY_NEW = LocalDate.now().minusDays(10);
  static final LocalDate TODAY = LocalDate.now();

  static final Map<UUID, List<Map<String, Object>>> SHIPMENTS =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final Map<UUID, List<Map<String, Object>>> DUTIES =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final Map<UUID, BigDecimal> AVAILABLE = new java.util.concurrent.ConcurrentHashMap<>();

  static AccessService.Access actor(
      UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "attribute", "research", "compliance", "assets")
        .flatMap(
            p ->
                java.util.stream.Stream.of("read", "create", "update", "delete")
                    .map(op -> "catalog." + p + "." + op))
        .toList();
  }

  static {
    pg.start();
    try (var conn =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = conn.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
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
      startWarehouseStub();
      actors.put(
          "Bearer tenant-a",
          actor(
              user,
              a,
              List.of(
                  new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", allPermissions()))));
      actors.put(
          "Bearer tenant-b",
          actor(
              user,
              b,
              List.of(
                  new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", allPermissions()))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** warehouse 替身：成本构成的头程 / 关税 + 库存可用量。 */
  static void startWarehouseStub() throws Exception {
    warehouse = HttpServer.create(new InetSocketAddress(0), 0);
    warehouse.createContext(
        "/warehouse",
        e -> {
          var path = e.getRequestURI().getPath();
          UUID tenant =
              "Bearer tenant-b".equals(e.getRequestHeaders().getFirst("Authorization")) ? b : a;
          Object body;
          if (path.equals("/warehouse/first-mile-shipments")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var shipment : SHIPMENTS.getOrDefault(tenant, List.of())) {
              var row = new LinkedHashMap<String, Object>();
              row.put("id", shipment.get("id"));
              row.put("shipmentCode", shipment.get("shipmentCode"));
              row.put("currency", shipment.get("currency"));
              row.put("freightCost", shipment.get("freightCost"));
              row.put("insuranceCost", shipment.get("insuranceCost"));
              row.put("handlingCost", shipment.get("handlingCost"));
              row.put("status", shipment.get("status"));
              row.put("createdAt", shipment.get("createdAt"));
              rows.add(row);
            }
            body = Map.of("rows", rows, "total", rows.size(), "page", 0, "size", rows.size());
          } else if (path.startsWith("/warehouse/first-mile-shipments/")) {
            String id = path.substring("/warehouse/first-mile-shipments/".length());
            Map<String, Object> shipment = null;
            for (var candidate : SHIPMENTS.getOrDefault(tenant, List.of())) {
              if (id.equals(candidate.get("id"))) shipment = candidate;
            }
            if (shipment == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("id", shipment.get("id"));
            row.put("shipmentCode", shipment.get("shipmentCode"));
            row.put("currency", shipment.get("currency"));
            row.put("freightCost", shipment.get("freightCost"));
            row.put("insuranceCost", shipment.get("insuranceCost"));
            row.put("handlingCost", shipment.get("handlingCost"));
            row.put("status", shipment.get("status"));
            row.put("createdAt", shipment.get("createdAt"));
            body = Map.of("shipment", row, "lines", shipment.get("lines"));
          } else if (path.equals("/warehouse/duty-taxes")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var estimate : DUTIES.getOrDefault(tenant, List.of())) {
              var row = new LinkedHashMap<String, Object>();
              row.put("id", estimate.get("id"));
              row.put("estimateCode", estimate.get("estimateCode"));
              row.put("estimateCurrency", estimate.get("estimateCurrency"));
              row.put("status", estimate.get("status"));
              row.put("createdAt", estimate.get("createdAt"));
              rows.add(row);
            }
            body = Map.of("rows", rows, "page", 0, "size", rows.size(), "totalPages", 1);
          } else if (path.startsWith("/warehouse/duty-taxes/") && path.endsWith("/lines")) {
            String id =
                path.substring("/warehouse/duty-taxes/".length(), path.length() - "/lines".length());
            Map<String, Object> estimate = null;
            for (var candidate : DUTIES.getOrDefault(tenant, List.of())) {
              if (id.equals(candidate.get("id"))) estimate = candidate;
            }
            if (estimate == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            body = estimate.get("lines");
          } else if (path.equals("/warehouse/inventory/availability")) {
            var request = JSON.readValue(e.getRequestBody(), Map.class);
            @SuppressWarnings("unchecked")
            var skuIds = (List<String>) request.get("skuIds");
            List<Map<String, Object>> items = new ArrayList<>();
            for (var raw : skuIds) {
              var skuId = UUID.fromString(raw);
              var row = new LinkedHashMap<String, Object>();
              row.put("skuId", skuId.toString());
              row.put("skuCode", "WH-" + raw.substring(0, 8));
              row.put("available", AVAILABLE.getOrDefault(skuId, BigDecimal.ZERO));
              row.put("inTransit", BigDecimal.ZERO);
              items.add(row);
            }
            body =
                Map.of(
                    "warehouseId",
                    "00000000-0000-0000-0000-000000000001",
                    "requestedCount",
                    items.size(),
                    "items",
                    items,
                    "note",
                    "替身数据");
          } else {
            e.sendResponseHeaders(404, -1);
            e.close();
            return;
          }
          byte[] out = JSON.writeValueAsBytes(body);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, out.length);
          e.getResponseBody().write(out);
          e.close();
        });
    warehouse.start();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "catalog_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    // 单连接：只有复用同一条连接，才能真正证明归还之后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    warehouse.stop(0);
    identity.stop(0);
    pg.stop();
  }

  @Autowired SkuCostCompositionService costs;
  @Autowired PricingRuleService rules;
  @Autowired CatalogService catalog;
  @Autowired PartyService parties;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actorA;
  AccessService.Access actorB;

  @BeforeEach
  void setUp() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    unit(actorA);
    unit(actorB);
    // 每个用例从干净的 A 侧规则台账开始（评估跑的是「当前生效的全部规则」）。
    tx(
        actorA,
        () -> {
          sql.execute("delete from pricing_rule_scope where tenant_id=?", actorA.tenantId());
          sql.execute("delete from pricing_rule where tenant_id=?", actorA.tenantId());
          return null;
        });
  }

  // ================================================================ 夹具

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  void unit(AccessService.Access who) {
    tx(
        who,
        () -> {
          sql.execute(
              "insert into unit_of_measure(id,tenant_id,code,name,dimension,scale,allow_purchase,"
                  + "allow_sale,allow_storage,enabled) values(?::uuid,?::uuid,?,?, 'COUNT',0,true,true,true,true)"
                  + " on conflict (tenant_id,code) do nothing",
              UUID.randomUUID(),
              who.tenantId(),
              "PCS",
              "PCS");
          return null;
        });
  }

  UUID sku(AccessService.Access who, String prefix) {
    String code = unique(prefix);
    UUID spuId =
        write(
            who,
            () ->
                catalog.saveSpu(
                    who,
                    null,
                    new SpuInput(
                        code + "-SPU", "SPU " + code, "", "Brand", "Category", "DRAFT", 0)));
    return write(
        who,
        () ->
            catalog.saveSku(
                who,
                spuId,
                null,
                new SkuInput(code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), 0)));
  }

  UUID supplierRole(AccessService.Access who, String prefix) {
    String code = unique(prefix);
    UUID partyId =
        write(
                who,
                () ->
                    parties.save(
                            who,
                            null,
                            new PartyInput(
                                code,
                                "供应商 " + code,
                                "ORGANIZATION",
                                "CN",
                                "mail@" + code.toLowerCase() + ".example",
                                null,
                                null,
                                "ACTIVE",
                                List.of(new PartyRoleInput("SUPPLIER", "ACTIVE", "")),
                                0)))
            .id();
    return tx(
        who,
        () ->
            sql.fetchOne(
                    "select id from party_role where tenant_id=? and party_id=? and role_type='SUPPLIER'",
                    who.tenantId(),
                    partyId)
                .get(0, UUID.class));
  }

  void delivery(AccessService.Access who, UUID roleId, UUID skuId, String ref) {
    write(
        who,
        () -> {
          sql.execute(
              "insert into vendor_delivery_record(id,tenant_id,role_id,delivery_ref,sku_id,purchase_unit_code,"
                  + "quantity,amount,currency,ordered_at,due_date,received_date,note)"
                  + " values(?::uuid,?::uuid,?::uuid,?,?::uuid,?,?,?,?,?::timestamptz,?,?,?)",
              UUID.randomUUID(),
              who.tenantId(),
              roleId,
              ref,
              skuId,
              "PCS",
              new BigDecimal("100"),
              new BigDecimal("1000"),
              "USD",
              DAY_NEW.minusDays(3).atStartOfDay().atOffset(ZoneOffset.UTC),
              DAY_NEW,
              DAY_NEW,
              "");
          return null;
        });
  }

  void shipment(UUID tenant, UUID skuId) {
    var line = new LinkedHashMap<String, Object>();
    line.put("skuId", skuId.toString());
    line.put("plannedQuantity", new BigDecimal("100"));
    line.put("shippedQuantity", new BigDecimal("100"));
    var shipment = new LinkedHashMap<String, Object>();
    shipment.put("id", UUID.randomUUID().toString());
    shipment.put("shipmentCode", "FM-" + UUID.randomUUID().toString().substring(0, 8));
    shipment.put("currency", "USD");
    shipment.put("freightCost", new BigDecimal("200"));
    shipment.put("insuranceCost", BigDecimal.ZERO);
    shipment.put("handlingCost", BigDecimal.ZERO);
    shipment.put("status", "DISPATCHED");
    shipment.put("createdAt", DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC).toString());
    shipment.put("lines", List.of(line));
    SHIPMENTS.computeIfAbsent(tenant, k -> new ArrayList<>()).add(shipment);
  }

  void dutyEstimate(UUID tenant, UUID skuId) {
    var line = new LinkedHashMap<String, Object>();
    line.put("skuId", skuId.toString());
    line.put("uom", "PCS");
    line.put("quantity", new BigDecimal("100"));
    line.put("estimatedDutyAmount", new BigDecimal("300"));
    line.put("estimatedVatAmount", new BigDecimal("0"));
    line.put("estimatedLineTotalTax", new BigDecimal("300"));
    var estimate = new LinkedHashMap<String, Object>();
    estimate.put("id", UUID.randomUUID().toString());
    estimate.put("estimateCode", "DT-" + UUID.randomUUID().toString().substring(0, 8));
    estimate.put("estimateCurrency", "USD");
    estimate.put("status", "LOCKED");
    estimate.put("createdAt", DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC).toString());
    estimate.put("lines", List.of(line));
    DUTIES.computeIfAbsent(tenant, k -> new ArrayList<>()).add(estimate);
  }

  UUID pricedSku(AccessService.Access who, String prefix) {
    UUID roleId = supplierRole(who, prefix + "-SUP");
    UUID skuId = sku(who, prefix + "-SKU");
    delivery(who, roleId, skuId, unique("PO"));
    shipment(who.tenantId(), skuId);
    dutyEstimate(who.tenantId(), skuId);
    var effective = DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC);
    write(
        who,
        () ->
            costs.save(
                who,
                skuId,
                new SkuCostCompositionModels.ProfileInput(
                    "MOVING_AVERAGE", "USD", effective, new BigDecimal("1.00"), "夹具成本构成", 0),
                bearer(who)));
    return skuId;
  }

  void priceObservation(AccessService.Access who, UUID skuId) {
    write(
        who,
        () -> {
          sql.execute(
              "insert into price_anomaly_observation(id,tenant_id,sku_id,platform_code,site_code,"
                  + "currency,internal_price,platform_price,price_change_source,source,observed_at,"
                  + "created_by) values(?::uuid,?::uuid,?::uuid,'AMAZON','US','USD',100,100,'UNKNOWN',"
                  + "'MANUAL_ATTESTATION',?::timestamptz,?::uuid)",
              UUID.randomUUID(),
              who.tenantId(),
              skuId,
              OffsetDateTime.now(ZoneOffset.UTC),
              who.userId());
          return null;
        });
  }

  static String bearer(AccessService.Access who) {
    return "Bearer " + (who.tenantId().equals(a) ? "tenant-a" : "tenant-b");
  }

  private PricingRuleInput ruleInput(String code) {
    return new PricingRuleInput(
        code,
        "规则 " + code,
        "隔离夹具",
        TriggerType.INVENTORY_BELOW_THRESHOLD,
        new BigDecimal("10"),
        null,
        null,
        null,
        ActionType.LOWER_PRICE,
        new BigDecimal("0.05"),
        10,
        "USD",
        new BigDecimal("0.10"),
        true,
        OffsetDateTime.now(ZoneOffset.UTC),
        null,
        "夹具",
        List.of(new PricingRuleScopeInput(ScopeType.BRAND, "BRAND", null)),
        null);
  }

  private PricingRuleDetail save(AccessService.Access who, String code) {
    var input = ruleInput(code);
    return write(who, () -> rules.save(who, null, input));
  }

  // ================================================================ 断言一：两租户互不可见

  @Test
  void 两租户互不可见() {
    var mine = save(actorA, unique("RULE-A"));
    var theirs = save(actorB, unique("RULE-B"));

    // 两边都真的有数据 —— 否则下面的断言就是空的。
    assertThat(tx(actorA, () -> rules.detail(mine.summary().id()))).isNotNull();
    assertThat(tx(actorB, () -> rules.detail(theirs.summary().id()))).isNotNull();

    // 列表里混不进对方的规则。
    var myRows = tx(actorA, () -> rules.list(new PricingRuleService.RuleQuery(null, null, null, null, null, null, 0, 200)));
    assertThat(myRows.items().stream().map(PricingRuleSummary::id)).contains(mine.summary().id());
    assertThat(myRows.items().stream().map(PricingRuleSummary::id)).doesNotContain(theirs.summary().id());

    // 明细读不到对方的规则。
    assertThatThrownBy(() -> tx(actorA, () -> rules.detail(theirs.summary().id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> rules.detail(mine.summary().id())))
        .isInstanceOf(ResponseStatusException.class);

    // 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from pricing_rule where id=?", theirs.summary().id())
                        .get(0, Long.class)))
        .isZero();
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne(
                            "select count(*) from pricing_rule where id=?", mine.summary().id())
                        .get(0, Long.class)))
        .isZero();

    // 作用范围也隔离（它跟着规则走）。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from pricing_rule_scope where rule_id=?",
                            theirs.summary().id())
                        .get(0, Long.class)))
        .isZero();
  }

  /** 命中记录与建议调价也隔离：它们带着 SKU 与价格，A 读到 B 的就等于把两个租户的经营数据串起来。 */
  @Test
  void 命中记录与建议调价也隔离() {
    UUID skuId = pricedSku(actorA, "ISO");
    AVAILABLE.put(skuId, new BigDecimal("3"));
    priceObservation(actorA, skuId);
    var mine = save(actorA, unique("RULE-A"));
    var result =
        write(
            actorA,
            () ->
                rules.evaluate(
                    actorA,
                    new EvaluateRequest(
                        List.of(skuId),
                        null,
                        "US",
                        "AMAZON",
                        "USD",
                        null,
                        BigDecimal.ONE,
                        null,
                        new BigDecimal("0.15"),
                        null,
                        new BigDecimal("5"),
                        "隔离夹具"),
                    bearer(actorA)));
    assertThat(result.suggestionCount()).isEqualTo(1);
    var suggestionId = result.suggestions().get(0).id();

    // A 自己看得到。
    var myHits =
        tx(
            actorA,
            () -> rules.listHits(new PricingRuleService.HitQuery(null, skuId, null, 0, 20)));
    assertThat(myHits.items()).isNotEmpty();
    var mySuggestions =
        tx(
            actorA,
            () ->
                rules.listSuggestions(
                    new PricingRuleService.SuggestionQuery(null, skuId, null, null, 0, 20)));
    assertThat(mySuggestions.items()).hasSize(1);

    // B 看不到（列表、明细、直查都验）。
    var theirHits =
        tx(
            actorB,
            () -> rules.listHits(new PricingRuleService.HitQuery(null, skuId, null, 0, 20)));
    assertThat(theirHits.items()).isEmpty();
    var theirSuggestions =
        tx(
            actorB,
            () ->
                rules.listSuggestions(
                    new PricingRuleService.SuggestionQuery(null, skuId, null, null, 0, 20)));
    assertThat(theirSuggestions.items()).isEmpty();
    assertThatThrownBy(() -> tx(actorB, () -> rules.suggestion(suggestionId)))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne(
                            "select count(*) from pricing_rule_suggestion where id=?", suggestionId)
                        .get(0, Long.class)))
        .isZero();
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne(
                            "select count(*) from pricing_rule_hit where rule_id=?",
                            mine.summary().id())
                        .get(0, Long.class)))
        .isZero();
  }

  // ================================================================ 断言二：伪造 tenant header 无效

  @Test
  void 伪造租户头无效() throws Exception {
    var theirs = save(actorB, unique("RULE-B"));
    var code = unique("RULE-FORGED");
    var input = ruleInput(code);

    // 带伪造的 X-Tenant-Id 头写入：行必须落在**调用者自己**的名下（B 的租户下读不到）。
    http.perform(
            post("/catalog/pricing-rules/rules")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content(JSON.writeValueAsBytes(input)))
        .andExpect(status().isOk());

    // 伪造头下写出来的行，租户仍然是 A（identity 解析出来的那个）。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from pricing_rule where code=? and tenant_id=?",
                            code,
                            a)
                        .get(0, Long.class)))
        .isEqualTo(1L);
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne(
                            "select count(*) from pricing_rule where code=?", code)
                        .get(0, Long.class)))
        .isZero();

    // 带伪造头读别人的规则 ⇒ 读不到。
    http.perform(
            get("/catalog/pricing-rules/rules/" + theirs.summary().id())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
  }

  // ================================================================ 断言三：连接池归还后不串租户

  @Test
  void 连接池归还后不串租户() {
    var mine = save(actorA, unique("RULE-A"));
    var theirs = save(actorB, unique("RULE-B"));
    // 交替 5 轮：每一次读都必须只看到当前租户的行（连接池只有 1 条连接 ⇒ 必然复用）。
    for (int round = 0; round < 5; round++) {
      var aRows =
          tx(
              actorA,
              () ->
                  rules.list(
                      new PricingRuleService.RuleQuery(null, null, null, null, null, null, 0, 200)));
      assertThat(aRows.items().stream().map(PricingRuleSummary::id)).contains(mine.summary().id());
      assertThat(aRows.items().stream().map(PricingRuleSummary::id)).doesNotContain(theirs.summary().id());

      var bRows =
          tx(
              actorB,
              () ->
                  rules.list(
                      new PricingRuleService.RuleQuery(null, null, null, null, null, null, 0, 200)));
      assertThat(bRows.items().stream().map(PricingRuleSummary::id)).contains(theirs.summary().id());
      assertThat(bRows.items().stream().map(PricingRuleSummary::id)).doesNotContain(mine.summary().id());
    }
  }
}
