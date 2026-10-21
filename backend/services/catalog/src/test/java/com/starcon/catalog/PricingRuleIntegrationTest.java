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
 * 5.3.2.2 价格规则：真实 PostgreSQL 16 + 强制 RLS 下的规则 CRUD、规则评估、
 * **命中记录**与**建议调价列表**，以及「先建议再执行 + 高风险人工确认」这条生死线。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：规则、作用范围、命中记录、建议调价全部真的落库。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>跨服务依赖的处理</b>：库存可用量与库龄来自 warehouse（另一个数据库），
 * 成本构成的头程与关税也来自 warehouse。生产路径是真实的 {@code RestClient} HTTP 调用，
 * 本测试用一个 {@link HttpServer} 起确定性替身来满足它们 —— 替身只存在于测试代码里，
 * 所以它证明的是<b>生产代码真的走 HTTP 且透传了租户上下文</b>。
 *
 * <p><b>本文件最重要的七个断言</b>：
 *
 * <ol>
 *   <li>{@link #规则落库且生效时段原样写回()}：时间写回不偏 8 小时（timestamptz 语义）。
 *   <li>{@link #库存低于阈值命中并产出建议调价()}：建议价 = 当前价 × (1 − 幅度)，落库可查。
 *   <li>{@link #降价幅度超过阈值必须人工确认()}：高风险 ⇒ 不自动放行 ⇒ 人工确认后才放行。
 *   <li>{@link #优先级先命中者优先()}：同一 SKU 上只有优先级最高的规则产出建议调价。
 *   <li>{@link #成本上涨需要两版完整快照()}：只有一版 ⇒ 不判定（不是判定成「没涨」）。
 *   <li>{@link #取不到当前价就不产出建议价()}：取不到 ⇒ 留痕 + 不给价（不是 0）。
 *   <li>{@link #评估不改价()}：产出的是建议，不是生效价。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PricingRuleIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer warehouse;
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();

  static final LocalDate DAY_OLD = LocalDate.now().minusDays(30);
  static final LocalDate DAY_NEW = LocalDate.now().minusDays(10);
  static final LocalDate TODAY = LocalDate.now();

  /** warehouse 替身的头程发运单数据（tenant → 发运单）。 */
  static final Map<UUID, List<Map<String, Object>>> SHIPMENTS =
      new java.util.concurrent.ConcurrentHashMap<>();
  /** warehouse 替身的关税估算单数据（tenant → 估算单）。 */
  static final Map<UUID, List<Map<String, Object>>> DUTIES =
      new java.util.concurrent.ConcurrentHashMap<>();
  /** warehouse 替身的可用量（skuId → 可用量）。 */
  static final Map<UUID, BigDecimal> AVAILABLE = new java.util.concurrent.ConcurrentHashMap<>();
  /** warehouse 替身的库龄（skuId → 最老的一批的库龄天数）。 */
  static final Map<UUID, Integer> AGING_DAYS = new java.util.concurrent.ConcurrentHashMap<>();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
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
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read", "catalog.sku.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * warehouse 替身：形状与 warehouse 的授权端点逐字一致。
   *
   * <p>它同时满足三件事：成本构成的头程 / 关税、库存可用量、库龄呆滞清单。
   */
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
          } else if (path.equals("/warehouse/inventory/aging/slow-moving")) {
            var query = e.getRequestURI().getRawQuery();
            UUID skuId = null;
            if (query != null) {
              for (var part : query.split("&")) {
                if (part.startsWith("skuId=")) skuId = UUID.fromString(part.substring(7));
              }
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            var days = skuId == null ? null : AGING_DAYS.get(skuId);
            if (days != null) {
              var row = new LinkedHashMap<String, Object>();
              row.put("skuId", skuId.toString());
              row.put("skuCode", "WH-" + skuId.toString().substring(0, 8));
              row.put("agingDays", days);
              row.put("available", AVAILABLE.getOrDefault(skuId, BigDecimal.ZERO));
              row.put("slowMovingStatus", days > 90 ? "OBSOLETE" : "SLOW_MOVING");
              rows.add(row);
            }
            body = Map.of("rows", rows, "total", rows.size(), "page", 0, "size", 200);
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

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setUp() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    unit(actor, "PCS");
    unit(actorB, "PCS");
    // 🔴 每个用例从**干净的规则台账**开始：评估跑的是「当前生效的全部规则」，
    // 前一个用例留下的规则会命中后一个用例的 SKU —— 那样断言到的就不是这条规则了。
    tx(
        actor,
        () -> {
          sql.execute("delete from pricing_rule_scope where tenant_id=?", actor.tenantId());
          sql.execute("delete from pricing_rule where tenant_id=?", actor.tenantId());
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

  void unit(AccessService.Access who, String code) {
    tx(
        who,
        () -> {
          sql.execute(
              "insert into unit_of_measure(id,tenant_id,code,name,dimension,scale,allow_purchase,"
                  + "allow_sale,allow_storage,enabled) values(?::uuid,?::uuid,?,?, 'COUNT',0,true,true,true,true)"
                  + " on conflict (tenant_id,code) do nothing",
              UUID.randomUUID(),
              who.tenantId(),
              code,
              code);
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

  void delivery(
      AccessService.Access who,
      UUID roleId,
      UUID skuId,
      String ref,
      LocalDate received,
      String quantity,
      String amount,
      String currency,
      String purchaseUnit) {
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
              purchaseUnit,
              new BigDecimal(quantity),
              new BigDecimal(amount),
              currency,
              received.minusDays(3).atStartOfDay().atOffset(ZoneOffset.UTC),
              received,
              received,
              "");
          return null;
        });
  }

  void shipment(UUID tenant, UUID skuId, String currency, String freight, String qty) {
    var line = new LinkedHashMap<String, Object>();
    line.put("skuId", skuId.toString());
    line.put("plannedQuantity", new BigDecimal(qty));
    line.put("shippedQuantity", new BigDecimal(qty));
    var shipment = new LinkedHashMap<String, Object>();
    shipment.put("id", UUID.randomUUID().toString());
    shipment.put("shipmentCode", "FM-" + UUID.randomUUID().toString().substring(0, 8));
    shipment.put("currency", currency);
    shipment.put("freightCost", new BigDecimal(freight));
    shipment.put("insuranceCost", BigDecimal.ZERO);
    shipment.put("handlingCost", BigDecimal.ZERO);
    shipment.put("status", "DISPATCHED");
    // 🔴 createdAt 必须落在成本快照的口径窗口里（窗口 = 截止生效时间的回溯窗口）：
    //    落在窗口外 ⇒ 这一版快照缺头程/关税 ⇒ snapshot_complete=false。
    shipment.put("createdAt", DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC).toString());
    shipment.put("lines", List.of(line));
    SHIPMENTS.computeIfAbsent(tenant, k -> new ArrayList<>()).add(shipment);
  }

  void dutyEstimate(UUID tenant, UUID skuId, String currency, String uom, String qty, String duty) {
    var line = new LinkedHashMap<String, Object>();
    line.put("skuId", skuId.toString());
    line.put("uom", uom);
    line.put("quantity", new BigDecimal(qty));
    line.put("estimatedDutyAmount", new BigDecimal(duty));
    line.put("estimatedVatAmount", new BigDecimal("0"));
    line.put("estimatedLineTotalTax", new BigDecimal(duty));
    var estimate = new LinkedHashMap<String, Object>();
    estimate.put("id", UUID.randomUUID().toString());
    estimate.put("estimateCode", "DT-" + UUID.randomUUID().toString().substring(0, 8));
    estimate.put("estimateCurrency", currency);
    estimate.put("status", "LOCKED");
    estimate.put("createdAt", DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC).toString());
    estimate.put("lines", List.of(line));
    DUTIES.computeIfAbsent(tenant, k -> new ArrayList<>()).add(estimate);
  }

  /**
   * 成本构成夹具：采购 10.00 / 头程 2.00 / 关税 3.00 / 包装 1.00 / 其他直接成本 0。
   *
   * <p>⇒ 5.3.1.1 的单位成本合计 = 16.00。
   */
  void costFixture(AccessService.Access who, UUID skuId, String prefix) {
    UUID roleId = supplierRole(who, prefix + "-SUP");
    delivery(who, roleId, skuId, unique("PO"), DAY_NEW, "100", "1000", "USD", "PCS");
    shipment(who.tenantId(), skuId, "USD", "200", "100");
    dutyEstimate(who.tenantId(), skuId, "USD", "PCS", "100", "300");
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
  }

  /** 有成本构成的 SKU。 */
  UUID pricedSku(AccessService.Access who, String prefix) {
    UUID skuId = sku(who, prefix + "-SKU");
    costFixture(who, skuId, prefix);
    return skuId;
  }

  /** 价格观测（5.5.2.1 的观测台账，只读）。 */
  void priceObservation(
      AccessService.Access who,
      UUID skuId,
      String platformCode,
      String siteCode,
      String currency,
      String internalPrice,
      String platformPrice,
      OffsetDateTime observedAt) {
    write(
        who,
        () -> {
          sql.execute(
              "insert into price_anomaly_observation(id,tenant_id,sku_id,platform_code,site_code,"
                  + "currency,internal_price,platform_price,price_change_source,source,observed_at,"
                  + "created_by) values(?::uuid,?::uuid,?::uuid,?,?,?,?,?,'UNKNOWN','MANUAL_ATTESTATION',"
                  + "?::timestamptz,?::uuid)",
              UUID.randomUUID(),
              who.tenantId(),
              skuId,
              platformCode,
              siteCode,
              currency,
              internalPrice == null ? null : new BigDecimal(internalPrice),
              platformPrice == null ? null : new BigDecimal(platformPrice),
              observedAt,
              who.userId());
          return null;
        });
  }

  static String bearer(AccessService.Access who) {
    return "Bearer " + (who.tenantId().equals(a) ? "tenant-a" : "tenant-b");
  }

  // ================================================================ 请求构造

  /** 规则输入（夹具）。 */
  private PricingRuleInput ruleInput(
      TriggerType trigger,
      ActionType action,
      BigDecimal thresholdQuantity,
      BigDecimal thresholdRate,
      BigDecimal marginFloor,
      Integer agingDays,
      BigDecimal actionRate,
      int priority,
      BigDecimal maxAutoDrop) {
    return new PricingRuleInput(
        unique("RULE"),
        "规则 " + priority,
        "夹具规则",
        trigger,
        thresholdQuantity,
        thresholdRate,
        marginFloor,
        agingDays,
        action,
        actionRate,
        priority,
        "USD",
        maxAutoDrop,
        true,
        OffsetDateTime.now(ZoneOffset.UTC),
        null,
        "夹具",
        List.of(),
        null);
  }

  /** 默认的评估请求：AMAZON / US / USD / 数量 1，平台费与物流费是 5.3.1.1 的手工假设。 */
  private EvaluateRequest evaluateFor(UUID skuId) {
    return new EvaluateRequest(
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
        "夹具评估");
  }

  private PricingRuleDetail save(AccessService.Access who, PricingRuleInput input) {
    return write(who, () -> rules.save(who, null, input));
  }

  private EvaluationResult evaluate(AccessService.Access who, EvaluateRequest request) {
    return write(who, () -> rules.evaluate(who, request, bearer(who)));
  }

  // ================================================================ 断言

  /** 规则落库：时间原样写回 + 作用范围落库 + 检索 + 乐观锁。 */
  @Test
  void 规则落库且生效时段原样写回() {
    // 🔴 用一个带 +08:00 偏移的时刻：写回时若被当成 character varying 或按本机时区解释，
    //    读回来就会偏 8 小时 —— 那会让「规则从什么时候生效」整个错掉。
    var effectiveFrom = OffsetDateTime.of(2026, 3, 1, 10, 0, 0, 0, ZoneOffset.ofHours(8));
    var input =
        new PricingRuleInput(
            unique("RULE"),
            "规则 时段",
            "夹具规则",
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
            effectiveFrom,
            null,
            "夹具",
            List.of(new PricingRuleScopeInput(ScopeType.CATEGORY, "ELEC", null)),
            null);
    var saved = save(actor, input);

    assertThat(saved.summary().code()).isEqualTo(input.code());
    assertThat(saved.summary().triggerType()).isEqualTo(TriggerType.INVENTORY_BELOW_THRESHOLD);
    assertThat(saved.scopes()).hasSize(1);
    assertThat(saved.scopes().get(0).ref()).isEqualTo("ELEC");
    assertThat(saved.summary().scopeCount()).isEqualTo(1);
    assertThat(saved.summary().priority()).isEqualTo(10);

    // 🔴 时间写回：同一个瞬间，不能偏 8 小时。
    OffsetDateTime written =
        tx(
            actor,
            () ->
                sql.fetchOne(
                        "select effective_from from pricing_rule where tenant_id=? and id=?",
                        actor.tenantId(),
                        saved.summary().id())
                    .get(0, OffsetDateTime.class));
    assertThat(written.toInstant()).isEqualTo(effectiveFrom.toInstant());

    // 按作用范围检索能命中。
    var page =
        tx(
            actor,
            () ->
                rules.list(
                    new PricingRuleService.RuleQuery(
                        null, null, null, null, ScopeType.CATEGORY, "ELEC", 0, 200)));
    assertThat(page.items().stream().map(PricingRuleSummary::id)).contains(saved.summary().id());
    assertThat(page.total()).isGreaterThanOrEqualTo(1);
    assertThat(page.size()).isEqualTo(200);

    // 修改走乐观锁：版本对不上时 409。
    var stale =
        new PricingRuleInput(
            input.code(),
            input.name(),
            input.description(),
            input.triggerType(),
            input.thresholdQuantity(),
            null,
            null,
            null,
            input.actionType(),
            input.actionRate(),
            input.priority(),
            "USD",
            input.maxAutoPriceDropRate(),
            true,
            effectiveFrom,
            null,
            "夹具",
            input.scopes(),
            saved.version() - 1);
    assertThatThrownBy(() -> write(actor, () -> rules.save(actor, saved.summary().id(), stale)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
  }

  /** 库存低于阈值 ⇒ 命中，并产出一条**建议调价**（低风险 ⇒ 已放行）。 */
  @Test
  void 库存低于阈值命中并产出建议调价() {
    UUID skuId = pricedSku(actor, "INV");
    AVAILABLE.put(skuId, new BigDecimal("3"));
    priceObservation(
        actor, skuId, "AMAZON", "US", "USD", "100.00", "100.00", OffsetDateTime.now(ZoneOffset.UTC));
    var rule =
        save(
            actor,
            ruleInput(
                TriggerType.INVENTORY_BELOW_THRESHOLD,
                ActionType.LOWER_PRICE,
                new BigDecimal("10"),
                null,
                null,
                null,
                new BigDecimal("0.05"),
                10,
                new BigDecimal("0.10")));
    var result = evaluate(actor, evaluateFor(skuId));

    assertThat(result.hitCount()).isGreaterThanOrEqualTo(1);
    assertThat(result.suggestionCount()).isEqualTo(1);
    var suggestion = result.suggestions().get(0);
    // 建议价 = 100 × (1 − 5%) = 95
    assertThat(suggestion.currentPrice()).isEqualByComparingTo("100.0000");
    assertThat(suggestion.suggestedPrice()).isEqualByComparingTo("95.0000");
    assertThat(suggestion.priceChangeRate()).isEqualByComparingTo("-0.050000");
    assertThat(suggestion.requiresConfirmation()).isFalse();
    assertThat(suggestion.status()).isEqualTo(SuggestionStatus.READY);
    assertThat(suggestion.decisionReady()).isTrue();
    // 毛利来自 5.3.1.1 对建议价的复核：95 − 14.25（平台费）− 5（物流）− 16（成本）= 59.75
    assertThat(suggestion.grossProfit()).isEqualByComparingTo("59.7500");
    assertThat(suggestion.grossMarginRate()).isEqualByComparingTo("0.628947");

    // 命中记录真的落库了。
    var hits =
        tx(
            actor,
            () ->
                rules.listHits(
                    new PricingRuleService.HitQuery(rule.summary().id(), skuId, null, 0, 20)));
    assertThat(hits.items()).hasSize(1);
    assertThat(hits.items().get(0).matched()).isTrue();
    assertThat(hits.items().get(0).reason()).contains("低于阈值");
    assertThat(hits.items().get(0).evidence()).containsKey("available");

    // 建议调价列表能查到这条。
    var suggestions =
        tx(
            actor,
            () ->
                rules.listSuggestions(
                    new PricingRuleService.SuggestionQuery(rule.summary().id(), null, null, null, 0, 20)));
    assertThat(suggestions.items()).hasSize(1);
    assertThat(suggestions.items().get(0).suggestedPrice()).isEqualByComparingTo("95.0000");
  }

  /** 🔴 降价幅度超过阈值 ⇒ 不自动放行 ⇒ 人工确认 / 驳回。 */
  @Test
  void 降价幅度超过阈值必须人工确认() {
    UUID skuId = pricedSku(actor, "RISK");
    AVAILABLE.put(skuId, new BigDecimal("3"));
    priceObservation(
        actor, skuId, "AMAZON", "US", "USD", "100.00", "100.00", OffsetDateTime.now(ZoneOffset.UTC));
    // 自动放行上限 2% ⇒ 降 5% 属于高风险。
    var rule =
        save(
            actor,
            ruleInput(
                TriggerType.INVENTORY_BELOW_THRESHOLD,
                ActionType.LOWER_PRICE,
                new BigDecimal("10"),
                null,
                null,
                null,
                new BigDecimal("0.05"),
                10,
                new BigDecimal("0.02")));
    var result = evaluate(actor, evaluateFor(skuId));

    assertThat(result.suggestionCount()).isEqualTo(1);
    assertThat(result.pendingConfirmationCount()).isEqualTo(1);
    var suggestion = result.suggestions().get(0);
    assertThat(suggestion.requiresConfirmation()).isTrue();
    assertThat(suggestion.status()).isEqualTo(SuggestionStatus.PENDING_CONFIRMATION);

    // 人工确认 ⇒ 已确认。
    var confirmed =
        write(
            actor,
            () ->
                rules.decide(
                    actor,
                    suggestion.id(),
                    new DecideRequest(Decision.CONFIRM, "确认清仓")));
    assertThat(confirmed.status()).isEqualTo(SuggestionStatus.CONFIRMED);
    assertThat(confirmed.decidedAt()).isNotNull();

    // 已确认的建议不能改判（只追加的事实）。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        rules.decide(
                            actor, suggestion.id(), new DecideRequest(Decision.REJECT, "反悔"))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));

    // 驳回必须写原因。
    UUID other = pricedSku(actor, "RISK2");
    AVAILABLE.put(other, new BigDecimal("3"));
    priceObservation(
        actor, other, "AMAZON", "US", "USD", "100.00", "100.00", OffsetDateTime.now(ZoneOffset.UTC));
    var rule2 =
        save(
            actor,
            ruleInput(
                TriggerType.INVENTORY_BELOW_THRESHOLD,
                ActionType.LOWER_PRICE,
                new BigDecimal("10"),
                null,
                null,
                null,
                new BigDecimal("0.05"),
                10,
                new BigDecimal("0.02")));
    var result2 = evaluate(actor, evaluateFor(other));
    var pending = result2.suggestions().get(0);
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () -> rules.decide(actor, pending.id(), new DecideRequest(Decision.REJECT, "  "))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(400));
    assertThat(rule2.summary().id()).isNotNull();
  }

  /** 🔴 同一 SKU 上只有优先级最高的规则产出建议调价。 */
  @Test
  void 优先级先命中者优先() {
    UUID skuId = pricedSku(actor, "PRIO");
    AVAILABLE.put(skuId, new BigDecimal("3"));
    priceObservation(
        actor, skuId, "AMAZON", "US", "USD", "100.00", "100.00", OffsetDateTime.now(ZoneOffset.UTC));
    var high =
        save(
            actor,
            ruleInput(
                TriggerType.INVENTORY_BELOW_THRESHOLD,
                ActionType.LOWER_PRICE,
                new BigDecimal("10"),
                null,
                null,
                null,
                new BigDecimal("0.02"),
                10,
                new BigDecimal("0.50")));
    var low =
        save(
            actor,
            ruleInput(
                TriggerType.INVENTORY_BELOW_THRESHOLD,
                ActionType.LOWER_PRICE,
                new BigDecimal("10"),
                null,
                null,
                null,
                new BigDecimal("0.03"),
                20,
                new BigDecimal("0.50")));
    var result = evaluate(actor, evaluateFor(skuId));

    // 两条规则都命中 ⇒ 两条命中记录，但只有优先级高的那条产出建议。
    assertThat(result.hitCount()).isEqualTo(2);
    assertThat(result.suggestionCount()).isEqualTo(1);
    assertThat(result.suggestions().get(0).ruleId()).isEqualTo(high.summary().id());
    // 建议价 = 100 × (1 − 2%) = 98（不是低优先级规则的 97）。
    assertThat(result.suggestions().get(0).suggestedPrice()).isEqualByComparingTo("98.0000");

    var hits =
        tx(
            actor,
            () ->
                rules.listHits(
                    new PricingRuleService.HitQuery(null, skuId, null, 0, 20)));
    var superseded = hits.items().stream().filter(h -> h.superseded()).findFirst().orElseThrow();
    assertThat(superseded.ruleId()).isEqualTo(low.summary().id());
    assertThat(superseded.supersededByRuleId()).isEqualTo(high.summary().id());
    assertThat(superseded.reason()).contains("覆盖");
  }

  /** 成本上涨：只有一版成本 ⇒ 不判定；两版完整快照 ⇒ 命中。 */
  @Test
  void 成本上涨需要两版完整快照() {
    UUID skuId = pricedSku(actor, "COST");
    priceObservation(
        actor, skuId, "AMAZON", "US", "USD", "100.00", "100.00", OffsetDateTime.now(ZoneOffset.UTC));
    var rule =
        save(
            actor,
            ruleInput(
                TriggerType.COST_INCREASE,
                ActionType.RAISE_PRICE,
                null,
                new BigDecimal("0.10"),
                null,
                null,
                new BigDecimal("0.05"),
                10,
                new BigDecimal("0.50")));

    // 只有一版 ⇒ 不判定（不是判定成「没涨」）。
    var first = evaluate(actor, evaluateFor(skuId));
    assertThat(first.suggestionCount()).isZero();
    assertThat(first.hits().get(0).matched()).isFalse();
    assertThat(first.hits().get(0).reason()).contains("不判定");

    // 追加一版成本（包装成本 1.00 → 5.00 ⇒ 单位成本 16 → 20，涨幅 25%）⇒ 命中。
    var saved =
        write(
            actor,
            () ->
                costs.save(
                    actor,
                    skuId,
                    new SkuCostCompositionModels.ProfileInput(
                        "MOVING_AVERAGE",
                        "USD",
                        DAY_NEW.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC),
                        new BigDecimal("5.00"),
                        "夹具：包装成本上涨",
                        0),
                    bearer(actor)));
    assertThat(saved.revisionNo()).isEqualTo(2);

    var second = evaluate(actor, evaluateFor(skuId));
    assertThat(second.suggestionCount()).isEqualTo(1);
    assertThat(second.hits().get(0).matched()).isTrue();
    assertThat(second.hits().get(0).reason()).contains("涨到");
    // 涨价建议 = 100 × (1 + 5%) = 105
    assertThat(second.suggestions().get(0).suggestedPrice()).isEqualByComparingTo("105.0000");
    assertThat(rule.summary().id()).isNotNull();
  }

  /** 库龄超期：库龄来自 warehouse 的呆滞清单（跨服务只读）。 */
  @Test
  void 库龄超期命中() {
    UUID skuId = pricedSku(actor, "AGING");
    AGING_DAYS.put(skuId, 120);
    priceObservation(
        actor, skuId, "AMAZON", "US", "USD", "100.00", "100.00", OffsetDateTime.now(ZoneOffset.UTC));
    save(
        actor,
        ruleInput(
            TriggerType.AGING_OVER_THRESHOLD,
            ActionType.LOWER_PRICE,
            null,
            null,
            null,
            90,
            new BigDecimal("0.10"),
            10,
            new BigDecimal("0.50")));
    var result = evaluate(actor, evaluateFor(skuId));
    assertThat(result.suggestionCount()).isEqualTo(1);
    assertThat(result.hits().get(0).matched()).isTrue();
    assertThat(result.hits().get(0).reason()).contains("库龄");
    // 降价 10% ⇒ 90
    assertThat(result.suggestions().get(0).suggestedPrice()).isEqualByComparingTo("90.0000");
  }

  /** 库龄取不到 ⇒ 不判定（不是判定成「没超期」）。 */
  @Test
  void 库龄取不到就不判定() {
    UUID skuId = pricedSku(actor, "AGING2");
    priceObservation(
        actor, skuId, "AMAZON", "US", "USD", "100.00", "100.00", OffsetDateTime.now(ZoneOffset.UTC));
    save(
        actor,
        ruleInput(
            TriggerType.AGING_OVER_THRESHOLD,
            ActionType.LOWER_PRICE,
            null,
            null,
            null,
            90,
            new BigDecimal("0.10"),
            10,
            new BigDecimal("0.50")));
    var result = evaluate(actor, evaluateFor(skuId));
    assertThat(result.suggestionCount()).isZero();
    assertThat(result.hits().get(0).matched()).isFalse();
    assertThat(result.hits().get(0).reason()).contains("不判定");
  }

  /** 毛利低于下限：毛利来自 5.3.1.1 的真实测算。 */
  @Test
  void 毛利低于下限命中() {
    UUID skuId = pricedSku(actor, "MARGIN");
    // 售价 20：平台费 3 + 物流 5 + 成本 16 = 24 ⇒ 毛利 −4（负毛利）⇒ 低于下限。
    priceObservation(
        actor, skuId, "AMAZON", "US", "USD", "20.00", "20.00", OffsetDateTime.now(ZoneOffset.UTC));
    save(
        actor,
        ruleInput(
            TriggerType.MARGIN_BELOW_FLOOR,
            ActionType.LOWER_PRICE,
            null,
            null,
            new BigDecimal("0.20"),
            null,
            new BigDecimal("0.05"),
            10,
            new BigDecimal("0.50")));
    var result = evaluate(actor, evaluateFor(skuId));
    assertThat(result.suggestionCount()).isEqualTo(1);
    assertThat(result.hits().get(0).matched()).isTrue();
    assertThat(result.hits().get(0).reason()).contains("毛利率");
    // 负毛利也要如实落库（它是「这条建议会亏」的警报）。
    assertThat(result.suggestions().get(0).grossMarginRate()).isLessThan(BigDecimal.ZERO);
  }

  /** 🔴 取不到当前价 ⇒ 留痕 + **不产出建议价**（不是用 0 顶）。 */
  @Test
  void 取不到当前价就不产出建议价() {
    UUID skuId = pricedSku(actor, "NOPRICE");
    AVAILABLE.put(skuId, new BigDecimal("3"));
    // 故意不给价格观测。
    save(
        actor,
        ruleInput(
            TriggerType.INVENTORY_BELOW_THRESHOLD,
            ActionType.LOWER_PRICE,
            new BigDecimal("10"),
            null,
            null,
            null,
            new BigDecimal("0.05"),
            10,
            new BigDecimal("0.50")));
    var result = evaluate(actor, evaluateFor(skuId));
    assertThat(result.suggestionCount()).isZero();
    assertThat(result.hitCount()).isEqualTo(1);
    assertThat(result.hits().get(0).matched()).isTrue();
    assertThat(result.hits().get(0).reason()).contains("不产出建议价");
  }

  /** 未命中也要留痕（运营要回答「为什么这条规则没动我的商品」）。 */
  @Test
  void 未命中也留痕() {
    UUID skuId = pricedSku(actor, "MISS");
    AVAILABLE.put(skuId, new BigDecimal("500"));
    priceObservation(
        actor, skuId, "AMAZON", "US", "USD", "100.00", "100.00", OffsetDateTime.now(ZoneOffset.UTC));
    save(
        actor,
        ruleInput(
            TriggerType.INVENTORY_BELOW_THRESHOLD,
            ActionType.LOWER_PRICE,
            new BigDecimal("10"),
            null,
            null,
            null,
            new BigDecimal("0.05"),
            10,
            new BigDecimal("0.50")));
    var result = evaluate(actor, evaluateFor(skuId));
    assertThat(result.suggestionCount()).isZero();
    assertThat(result.hits().get(0).matched()).isFalse();
    assertThat(result.hits().get(0).reason()).contains("未命中");
  }

  /** 🔴 评估**不改价**：价格观测台账一行都不动。 */
  @Test
  void 评估不改价() {
    UUID skuId = pricedSku(actor, "NOPRICEWRITE");
    AVAILABLE.put(skuId, new BigDecimal("3"));
    priceObservation(
        actor, skuId, "AMAZON", "US", "USD", "100.00", "100.00", OffsetDateTime.now(ZoneOffset.UTC));
    save(
        actor,
        ruleInput(
            TriggerType.INVENTORY_BELOW_THRESHOLD,
            ActionType.LOWER_PRICE,
            new BigDecimal("10"),
            null,
            null,
            null,
            new BigDecimal("0.05"),
            10,
            new BigDecimal("0.50")));
    long beforeCount = observationCount(skuId);
    BigDecimal beforeSum = observationSum(skuId);
    var result = evaluate(actor, evaluateFor(skuId));
    assertThat(result.suggestionCount()).isEqualTo(1);
    assertThat(observationCount(skuId)).isEqualTo(beforeCount);
    assertThat(observationSum(skuId)).isEqualByComparingTo(beforeSum);
  }

  /** HTTP 层：端点真的通，且写动作要权限。 */
  @Test
  void 评估端点真的通() throws Exception {
    UUID skuId = pricedSku(actor, "HTTP");
    AVAILABLE.put(skuId, new BigDecimal("3"));
    priceObservation(
        actor, skuId, "AMAZON", "US", "USD", "100.00", "100.00", OffsetDateTime.now(ZoneOffset.UTC));
    save(
        actor,
        ruleInput(
            TriggerType.INVENTORY_BELOW_THRESHOLD,
            ActionType.LOWER_PRICE,
            new BigDecimal("10"),
            null,
            null,
            null,
            new BigDecimal("0.05"),
            10,
            new BigDecimal("0.50")));
    http.perform(
            post("/catalog/pricing-rules/evaluate")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
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
                            "HTTP 夹具"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.suggestionCount").value(1))
        .andExpect(jsonPath("$.suggestions[0].suggestedPrice").value(95.0));

    // 只读角色不能评估（写权限要 catalog.spu.update）。
    http.perform(
            post("/catalog/pricing-rules/evaluate")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
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
                            "只读角色"))))
        .andExpect(status().isForbidden());
  }

  /** 价格观测台账里这个 SKU 的观测条数（评估**不改价**的判据）。 */
  long observationCount(UUID skuId) {
    return tx(
        actor,
        () ->
            sql.fetchOne(
                    "select count(*) from price_anomaly_observation where tenant_id=? and sku_id=?",
                    actor.tenantId(),
                    skuId)
                .get(0, Long.class));
  }

  /** 价格观测台账里这个 SKU 的平台价合计（评估**不改价**的判据）。 */
  BigDecimal observationSum(UUID skuId) {
    return tx(
        actor,
        () ->
            sql.fetchOne(
                    "select coalesce(sum(platform_price),0) from price_anomaly_observation"
                        + " where tenant_id=? and sku_id=?",
                    actor.tenantId(),
                    skuId)
                .get(0, BigDecimal.class));
  }
}
