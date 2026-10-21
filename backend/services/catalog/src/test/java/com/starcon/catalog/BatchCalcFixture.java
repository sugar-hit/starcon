package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 5.3.1.2 批量测算的**共享底座**：真实 PostgreSQL 16 容器 + 三个跨服务替身 + 业务夹具。
 *
 * <p><b>为什么要有底座</b>：批量测算的集成测试要同时验两件事 ——
 * 「批量测算跑得对」（功能类）与「租户隔得住」（隔离类）。两件事需要的夹具完全一样
 * （两租户、SKU、价格观测、成本构成），拆成两个各自复制一遍只会让它们越走越远。
 *
 * <p><b>容器只启动一次，被所有子类共享</b>；不使用 {@code @AfterAll} 停容器
 * （那会让先结束的测试类把共享容器停掉，后面的类全部失败），
 * 改由 JVM 退出时由 Testcontainers 的 Ryuk 统一回收。
 *
 * <p><b>替身只存在于测试代码里</b>：生产代码路径永远是真实的 HTTP 调用，
 * 替身满足的是 identity（权限）、warehouse（头程与关税）、channel（平台目录）三个跨服务依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class BatchCalcFixture {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();

  static final LocalDate DAY_OLD = LocalDate.now().minusDays(30);
  static final LocalDate DAY_NEW = LocalDate.now().minusDays(10);
  static final LocalDate TODAY = LocalDate.now();

  static HttpServer identity;
  static HttpServer warehouse;
  static HttpServer channel;

  /** warehouse 替身的头程发运单数据（tenant → 发运单）。 */
  static final Map<UUID, List<Map<String, Object>>> SHIPMENTS = new ConcurrentHashMap<>();
  /** warehouse 替身的关税估算单数据（tenant → 估算单）。 */
  static final Map<UUID, List<Map<String, Object>>> DUTIES = new ConcurrentHashMap<>();
  /** 渠道替身最近一次收到的 Authorization 头。 */
  static final AtomicReference<String> lastChannelBearer = new AtomicReference<>();
  /** 置位后渠道替身一律回 503。 */
  static final AtomicBoolean channelDown = new AtomicBoolean();
  /** 置位后仓库替身一律回 503：模拟「仓库服务不可用」。 */
  static final AtomicBoolean warehouseDown = new AtomicBoolean();

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
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      actors.put(
          "Bearer reader",
          actor(
              user,
              a,
              null,
              List.of(grant("ALL", List.of("catalog.spu.read", "catalog.sku.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
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

  // ================================================================ 替身

  /** warehouse 替身：形状与 warehouse 的发运单 / 关税估算单端点逐字一致。 */
  static void startWarehouseStub() throws Exception {
    warehouse = HttpServer.create(new InetSocketAddress(0), 0);
    warehouse.createContext(
        "/warehouse",
        e -> {
          var path = e.getRequestURI().getPath();
          if (warehouseDown.get()) {
            e.sendResponseHeaders(503, -1);
            e.close();
            return;
          }
          UUID tenant =
              "Bearer tenant-b".equals(e.getRequestHeaders().getFirst("Authorization"))
                  ? b
                  : a;
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
            String id = path.substring("/warehouse/duty-taxes/".length(), path.length() - "/lines".length());
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

  /** channel 替身：形状与 channel 的 {@code GET /channel/platforms} 逐字一致。 */
  static void startChannelStub() throws Exception {
    channel = HttpServer.create(new InetSocketAddress(0), 0);
    channel.createContext(
        "/channel",
        e -> {
          lastChannelBearer.set(e.getRequestHeaders().getFirst("Authorization"));
          if (channelDown.get()) {
            e.sendResponseHeaders(503, -1);
            e.close();
            return;
          }
          var marketplace = new LinkedHashMap<String, Object>();
          marketplace.put("id", UUID.randomUUID().toString());
          marketplace.put("code", "US");
          marketplace.put("name", "美国站");
          marketplace.put("countryCode", "US");
          marketplace.put("defaultCurrency", "USD");
          marketplace.put("defaultTimezone", "America/Los_Angeles");
          marketplace.put("defaultLocale", "en-US");
          marketplace.put("currencyScale", 2);
          marketplace.put("vatRequired", false);
          var platform = new LinkedHashMap<String, Object>();
          platform.put("id", UUID.randomUUID().toString());
          platform.put("code", "AMAZON");
          platform.put("name", "Amazon");
          platform.put("integration", "OFFICIAL");
          platform.put("supportStatus", "SUPPORTED");
          platform.put("marketplaces", List.of(marketplace));
          byte[] out = JSON.writeValueAsBytes(List.of(platform));
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, out.length);
          e.getResponseBody().write(out);
          e.close();
        });
    channel.start();
  }

  // ================================================================ 权限夹具

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

  static String bearer(AccessService.Access who) {
    return "Bearer " + (who.tenantId().equals(a) ? "tenant-a" : "tenant-b");
  }

  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  // ================================================================ 注入

  @Autowired SkuCostCompositionService costs;
  @Autowired CatalogService catalog;
  @Autowired PartyService parties;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actor;
  AccessService.Access actorB;

  @org.junit.jupiter.api.BeforeEach
  void resetActors() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    unit(actor, "PCS");
    unit(actorB, "PCS");
    warehouseDown.set(false);
    channelDown.set(false);
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  void writeVoid(AccessService.Access who, Runnable work) {
    access.run(
        who,
        "catalog.spu.update",
        () -> {
          work.run();
          return null;
        });
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

  /** 一个 SPU + SKU（带分类与品牌，供范围筛选用）。 */
  UUID sku(AccessService.Access who, String prefix, String category, String brand) {
    String code = unique(prefix);
    UUID spuId =
        write(
            who,
            () ->
                catalog.saveSpu(
                    who,
                    null,
                    new SpuInput(
                        code + "-SPU",
                        "SPU " + code,
                        "",
                        brand == null ? "" : brand,
                        category == null ? "" : category,
                        "DRAFT",
                        0)));
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
    shipment.put(
        "createdAt", TODAY.minusDays(5).atStartOfDay().atOffset(ZoneOffset.UTC).toString());
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
    estimate.put(
        "createdAt", TODAY.minusDays(5).atStartOfDay().atOffset(ZoneOffset.UTC).toString());
    estimate.put("lines", List.of(line));
    DUTIES.computeIfAbsent(tenant, k -> new ArrayList<>()).add(estimate);
  }

  /**
   * 一个完整夹具：SKU + 成本构成（采购 10.00 / 头程 2.00 / 关税 3.00 / 包装 1.00 / 其他 0）。
   *
   * <p>⇒ 单位已知成本合计 = 16.00（与 5.3.1.1 的夹具口径一致，便于交叉比对）。
   */
  UUID pricedSku(AccessService.Access who, String prefix, String category, String brand) {
    UUID roleId = supplierRole(who, prefix + "-SUP");
    UUID skuId = sku(who, prefix + "-SKU", category, brand);
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
    return skuId;
  }

  /**
   * 登记一条价格观测（5.5.2.1 的价格观测台账 —— 本仓唯一的真实售价来源）。
   *
   * @param internalPrice 我方价（可空）
   * @param platformPrice 平台在售价（可空）
   */
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
}
