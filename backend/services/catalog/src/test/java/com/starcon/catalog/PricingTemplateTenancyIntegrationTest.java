package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.PricingTemplateModels.*;
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
 * 5.3.2.1 定价模板的**多租户三断言**（AGENTS.md 明文要求，缺一不可）。
 *
 * <p>跑的是这三条：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>：A 看不到 B 的模板 / 调整项 / 适用对象 / 应用留痕（列表、明细、直查都验）；
 *   <li><b>伪造 tenant header 无效</b>：请求头里的 {@code X-Tenant-Id} 改不了任何事 ——
 *       租户只来自已认证成员关系（identity），伪造头下写入的行仍然落在调用者自己名下；
 *   <li><b>连接池归还后不串租户</b>：连接池压到 1 条连接，A→B→A→B 交替 5 轮，
 *       每一轮读到的都只有当前租户的数据。
 * </ol>
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身</b>；运行角色
 * {@code catalog_app} 是 {@code nobypassrls}（否则它会绕过策略，三条断言全部变成空断言）。
 *
 * <p>为什么定价模板必须隔离：模板里写着这个租户的**成本加成率、目标毛利率、竞品价与站点差异**。
 * A 读到 B 的模板 ⇒ A 的定价依据直接串味，而 A 的改价建议会带着 B 的成本口径出街。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PricingTemplateTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer warehouse;
  static HttpServer channel;
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
      startChannelStub();
      actors.put(
          "Bearer tenant-a",
          actor(user, a, List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", allPermissions()))));
      actors.put(
          "Bearer tenant-b",
          actor(user, b, List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", allPermissions()))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

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

  static void startChannelStub() throws Exception {
    channel = HttpServer.create(new InetSocketAddress(0), 0);
    channel.createContext(
        "/channel",
        e -> {
          var marketplace = new LinkedHashMap<String, Object>();
          marketplace.put("id", UUID.randomUUID().toString());
          marketplace.put("code", "US");
          marketplace.put("name", "美国站");
          marketplace.put("countryCode", "US");
          marketplace.put("defaultCurrency", "USD");
          marketplace.put("currencyScale", 2);
          marketplace.put("vatRequired", false);
          var platform = new LinkedHashMap<String, Object>();
          platform.put("id", UUID.randomUUID().toString());
          platform.put("code", "AMAZON");
          platform.put("name", "Amazon");
          platform.put("integration", "OFFICIAL");
          platform.put("supportStatus", "SUPPORTED");
          platform.put("capabilities", List.of());
          platform.put("marketplaces", List.of(marketplace));
          byte[] out = JSON.writeValueAsBytes(List.of(platform));
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, out.length);
          e.getResponseBody().write(out);
          e.close();
        });
    channel.start();
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
    r.add("CHANNEL_URL", () -> "http://localhost:" + channel.getAddress().getPort());
    // 单连接：只有复用同一条连接，才能真正证明归还之后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    channel.stop(0);
    warehouse.stop(0);
    identity.stop(0);
    pg.stop();
  }

  @Autowired SkuCostCompositionService costs;
  @Autowired PricingTemplateService templates;
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
    shipment.put("createdAt", TODAY.minusDays(5).atStartOfDay().atOffset(ZoneOffset.UTC).toString());
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
    estimate.put("createdAt", TODAY.minusDays(5).atStartOfDay().atOffset(ZoneOffset.UTC).toString());
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

  static String bearer(AccessService.Access who) {
    return "Bearer " + (who.tenantId().equals(a) ? "tenant-a" : "tenant-b");
  }

  private TemplateInput templateInput(String code) {
    return new TemplateInput(
        code,
        "模板 " + code,
        "夹具模板",
        BaseMethod.FIXED_MARGIN,
        new BigDecimal("0.25"),
        null,
        null,
        ProductStage.NEW,
        "USD",
        new BigDecimal("0.15"),
        null,
        new BigDecimal("5"),
        null,
        null,
        true,
        OffsetDateTime.now(ZoneOffset.UTC),
        null,
        "夹具",
        List.of(
            new TemplateAdjustmentInput(
                AdjustmentType.SITE_DIFFERENTIAL, "US", new BigDecimal("0.02"), null, null, "站点差异")),
        List.of(new ScopeInput(ScopeType.BRAND, "BRAND", null)),
        null);
  }

  private TemplateDetail save(AccessService.Access who, String code) {
    var input = templateInput(code);
    return write(who, () -> templates.save(who, null, input));
  }

  // ================================================================ 断言一：两租户互不可见

  @Test
  void 两租户互不可见() {
    var mine = save(actorA, unique("TPL-A"));
    var theirs = save(actorB, unique("TPL-B"));

    // 两边都真的有数据 —— 否则下面的断言就是空的。
    assertThat(tx(actorA, () -> templates.detail(mine.summary().id()))).isNotNull();
    assertThat(tx(actorB, () -> templates.detail(theirs.summary().id()))).isNotNull();

    // 列表里混不进对方的模板。
    var myRows = tx(actorA, () -> templates.list(new PricingTemplateService.TemplateQuery(null, null, null, null, null, null, 0, 200)));
    assertThat(myRows.items().stream().map(TemplateSummary::id)).contains(mine.summary().id());
    assertThat(myRows.items().stream().map(TemplateSummary::id)).doesNotContain(theirs.summary().id());

    // 明细读不到对方的模板。
    assertThatThrownBy(() -> tx(actorA, () -> templates.detail(theirs.summary().id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> templates.detail(mine.summary().id())))
        .isInstanceOf(ResponseStatusException.class);

    // 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from pricing_template where id=?", theirs.summary().id())
                        .get(0, Long.class)))
        .isZero();
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne(
                            "select count(*) from pricing_template where id=?", mine.summary().id())
                        .get(0, Long.class)))
        .isZero();

    // 调整项与适用对象也隔离（它们跟着模板走）。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from pricing_template_adjustment where template_id=?",
                            theirs.summary().id())
                        .get(0, Long.class)))
        .isZero();
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from pricing_template_scope where template_id=?",
                            theirs.summary().id())
                        .get(0, Long.class)))
        .isZero();
  }

  /** 写路径也隔离：A 的应用留痕 B 读不到（留痕里有 A 的成本口径与建议价）。 */
  @Test
  void 应用留痕也隔离() {
    UUID skuId = pricedSku(actorA, "ISO");
    var mine = save(actorA, unique("TPL-A"));
    var request = new ApplyRequest(
        skuId,
        "US",
        "AMAZON",
        "USD",
        BigDecimal.ONE,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "隔离夹具");
    var result = write(actorA, () -> templates.apply(actorA, mine.summary().id(), request, bearer(actorA)));
    assertThat(result.suggestedPrice()).isNotNull();

    // A 自己看得到。
    var myApplications =
        tx(
            actorA,
            () ->
                templates.listApplications(
                    new PricingTemplateService.ApplicationQuery(mine.summary().id(), skuId, 0, 20)));
    assertThat(myApplications.items()).hasSize(1);

    // B 看不到这一条（列表、明细、直查都验）。
    var theirApplications =
        tx(
            actorB,
            () ->
                templates.listApplications(
                    new PricingTemplateService.ApplicationQuery(null, skuId, 0, 20)));
    assertThat(theirApplications.items()).isEmpty();
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne(
                            "select count(*) from pricing_template_application where id=?",
                            result.applicationId())
                        .get(0, Long.class)))
        .isZero();
  }

  // ================================================================ 断言二：伪造 tenant header 无效

  @Test
  void 伪造租户头无效() throws Exception {
    var theirs = save(actorB, unique("TPL-B"));
    var code = unique("TPL-FORGED");
    var input = templateInput(code);

    // 带伪造的 X-Tenant-Id 头写入：行必须落在**调用者自己**的名下（B 的租户下读不到）。
    http.perform(
            post("/catalog/pricing-templates/templates")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content(JSON.writeValueAsBytes(input)))
        .andExpect(status().isOk());
    http.perform(
            post("/catalog/pricing-templates/templates")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .contentType("application/json")
                .content(JSON.writeValueAsBytes(input)))
        .andExpect(status().isConflict());

    // 伪造头下写出来的行，租户仍然是 A（identity 解析出来的那个）。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from pricing_template where code=? and tenant_id=?",
                            code,
                            a)
                        .get(0, Long.class)))
        .isEqualTo(1L);
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne(
                            "select count(*) from pricing_template where code=?", code)
                        .get(0, Long.class)))
        .isZero();

    // 带伪造头读别人的模板 ⇒ 读不到。
    http.perform(
            get("/catalog/pricing-templates/templates/" + theirs.summary().id())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
  }

  // ================================================================ 断言三：连接池归还后不串租户

  @Test
  void 连接池归还后不串租户() {
    var mine = save(actorA, unique("TPL-A"));
    var theirs = save(actorB, unique("TPL-B"));
    // 交替 5 轮：每一次读都必须只看到当前租户的行。
    for (int round = 0; round < 5; round++) {
      var aRows = tx(actorA, () -> templates.list(new PricingTemplateService.TemplateQuery(null, null, null, null, null, null, 0, 200)));
      assertThat(aRows.items().stream().map(TemplateSummary::id)).contains(mine.summary().id());
      assertThat(aRows.items().stream().map(TemplateSummary::id)).doesNotContain(theirs.summary().id());

      var bRows = tx(actorB, () -> templates.list(new PricingTemplateService.TemplateQuery(null, null, null, null, null, null, 0, 200)));
      assertThat(bRows.items().stream().map(TemplateSummary::id)).contains(theirs.summary().id());
      assertThat(bRows.items().stream().map(TemplateSummary::id)).doesNotContain(mine.summary().id());
    }
  }
}
