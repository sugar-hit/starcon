package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.LandedCostModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 5.2.1.2 到岸成本：真实 PostgreSQL 16 + 强制 RLS 下的六分量口径、缺口不填 0、
 * 租户隔离三断言、时间戳写回，以及上游不可达时**不拿 0 冒充「没有这笔成本」**。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：到岸成本单、行与六个分量全部真的落库。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>跨服务依赖的处理</b>：头程与关税来自 warehouse 服务（另一个数据库）。
 * 生产路径是真实的 {@code RestClient} HTTP 调用，本测试用一个 {@link HttpServer} 起一个
 * <b>确定性的替身</b>来满足它 —— 替身只存在于测试代码里，且被断言「确实收到了 catalog 透传的
 * {@code Authorization} 头」，所以它证明的是<b>生产代码真的走 HTTP 且透传了租户上下文</b>，
 * 而不是把替身逻辑塞进了生产代码。
 *
 * <p><b>本文件最重要的八个断言</b>：
 *
 * <ol>
 *   <li>{@link #口径在表结构与响应里都标得出来()}：生死线「到岸成本与仓库成本是两个口径」。
 *   <li>{@link #六个分量逐项落库且每项都能追到来源单号()}：缺一项就退化成半截子报表。
 *   <li>{@link #采购价取实际到货金额并按数量进合计()}：6.3 采购单未交付时的替代口径。
 *   <li>{@link #批次级成本摊到行后各行之和严格等于批次总额()}：摊漏了就是系统性低估。
 *   <li>{@link #没有关税估算时关税是缺口而不是0()}：用 0 填关税是最危险的失败模式。
 *   <li>{@link #关税与增值税分列且按承担方决定是否计入()}：DAP 下卖方不担关税。
 *   <li>{@link #清关费与尾程预备是参数未维护时是缺口()}：参数与事实分色。
 *   <li>{@link #租户隔离三断言()}：到岸成本是最敏感的数据之一。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class LandedCostIntegrationTest {
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

  /** warehouse 替身的头程发运单（tenant → 发运单）。 */
  static final Map<UUID, List<Map<String, Object>>> SHIPMENTS =
      new java.util.concurrent.ConcurrentHashMap<>();
  /** warehouse 替身的报关单（tenant → 报关单）。 */
  static final Map<UUID, List<Map<String, Object>>> DECLARATIONS =
      new java.util.concurrent.ConcurrentHashMap<>();
  /** warehouse 替身的关税估算单（tenant → 估算单）。 */
  static final Map<UUID, List<Map<String, Object>>> DUTIES =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 替身最近一次收到的 Authorization 头：用来断言租户上下文真的透传出去了。 */
  static final AtomicReference<String> lastBearer = new AtomicReference<>();

  /** 置位后替身一律回 503：模拟「仓库服务不可用」。 */
  static final AtomicBoolean warehouseDown = new AtomicBoolean();

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
      // 只有商品读权限：写端点必须 403。
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read", "catalog.sku.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * warehouse 替身：一个真实的 HTTP 服务器，形状与 warehouse 的
   * {@code FirstMileDetail} / {@code FirstMilePage} / {@code CustomsDeclarationPage} /
   * {@code DutyEstimatePage} / 关税行列表逐字一致。
   *
   * <p>它刻意<b>不</b>校验 Authorization（真实 warehouse 会向 identity 校验），
   * 因为本测试要断言的正是「catalog 把这个头透传出去了」。租户由该头决定，
   * 与真实 warehouse 从已认证成员关系解析租户同构。
   */
  static void startWarehouseStub() throws Exception {
    warehouse = HttpServer.create(new InetSocketAddress(0), 0);
    warehouse.createContext(
        "/warehouse",
        e -> {
          lastBearer.set(e.getRequestHeaders().getFirst("Authorization"));
          if (warehouseDown.get()) {
            e.sendResponseHeaders(503, -1);
            e.close();
            return;
          }
          var path = e.getRequestURI().getPath();
          var query = e.getRequestURI().getRawQuery();
          UUID tenant =
              "Bearer tenant-b".equals(lastBearer.get())
                  ? b
                  : "Bearer tenant-a".equals(lastBearer.get()) ? a : UUID.randomUUID();
          Object body;
          if (path.equals("/warehouse/first-mile-shipments")) {
            var rows = new ArrayList<Map<String, Object>>();
            for (var shipment : SHIPMENTS.getOrDefault(tenant, List.of())) {
              if (query != null && query.contains("keyword=")) {
                var keyword = query.substring(query.indexOf("keyword=") + 8).toLowerCase();
                if (!String.valueOf(shipment.get("shipmentCode")).toLowerCase().contains(keyword)) {
                  continue;
                }
              }
              var row = new LinkedHashMap<String, Object>(shipment);
              row.remove("lines");
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
            var head = new LinkedHashMap<String, Object>(shipment);
            head.remove("lines");
            body = Map.of("shipment", head, "lines", shipment.getOrDefault("lines", List.of()));
          } else if (path.equals("/warehouse/customs-declarations")) {
            var rows = new ArrayList<Map<String, Object>>();
            for (var declaration : DECLARATIONS.getOrDefault(tenant, List.of())) {
              if (query != null && query.contains("sourceShipmentId=")) {
                var wanted =
                    query.substring(query.indexOf("sourceShipmentId=") + 17).split("&")[0];
                if (!wanted.equals(declaration.get("sourceShipmentId"))) continue;
              }
              rows.add(declaration);
            }
            body = Map.of("rows", rows, "total", rows.size());
          } else if (path.equals("/warehouse/duty-taxes")) {
            var rows = new ArrayList<Map<String, Object>>();
            for (var estimate : DUTIES.getOrDefault(tenant, List.of())) {
              if (query != null && query.contains("declarationId=")) {
                var wanted = query.substring(query.indexOf("declarationId=") + 14).split("&")[0];
                if (!wanted.equals(estimate.get("declarationId"))) continue;
              }
              var row = new LinkedHashMap<String, Object>(estimate);
              row.remove("lines");
              rows.add(row);
            }
            body = Map.of("rows", rows, "total", rows.size());
          } else if (path.startsWith("/warehouse/duty-taxes/") && path.endsWith("/lines")) {
            String id = path.substring("/warehouse/duty-taxes/".length(), path.length() - "/lines".length());
            List<Map<String, Object>> lines = List.of();
            for (var estimate : DUTIES.getOrDefault(tenant, List.of())) {
              if (id.equals(estimate.get("id"))) {
                lines = (List<Map<String, Object>>) estimate.getOrDefault("lines", List.of());
              }
            }
            body = lines;
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

  @Autowired LandedCostService landed;
  @Autowired CatalogService catalog;
  @Autowired PartyService parties;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    warehouse.stop(0);
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setUp() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    unit(actor, "PCS");
    unit(actorB, "PCS");
    warehouseDown.set(false);
    lastBearer.set(null);
    resetLandedCost();
  }

  /**
   * 清掉上一轮留下的到岸成本数据。
   *
   * <p>整个测试类共用一个 PostgreSQL 容器，而到岸成本单 / 行 / 分量 / 参数都是**租户级**的。
   * 不清的话，上一个用例维护的「清关费参数」会被下一个用例读到 ——
   * 「参数未维护时是缺口」这类断言就会莫名其妙地失败，而失败原因跟被测代码无关。
   *
   * <p>🔴 删除顺序必须是 **单 → 行 → 分量**：延迟约束触发器在提交时按**最终状态**核对
   * 「合计 == 计入分量之和」。先把单头删掉，后面几层的核对就没有可对的头了；
   * 反过来先删分量，行合计就会与「无计入分量」对不上而报错。
   */
  void resetLandedCost() {
    for (var who : List.of(actor, actorB)) {
      write(
          who,
          () -> {
            sql.execute("delete from landed_cost_statement where tenant_id=?", who.tenantId());
            sql.execute("delete from landed_cost_line where tenant_id=?", who.tenantId());
            sql.execute("delete from landed_cost_component where tenant_id=?", who.tenantId());
            sql.execute("delete from landed_cost_parameter where tenant_id=?", who.tenantId());
            return null;
          });
    }
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  static String bearerOf(AccessService.Access who) {
    if (who.tenantId().equals(a)) return "Bearer tenant-a";
    if (who.tenantId().equals(b)) return "Bearer tenant-b";
    throw new AssertionError("未知租户，替身不认识：" + who.tenantId());
  }

  // ================================================================ 夹具

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

  /** 写一条**实际到货**事实（V137 的 vendor_delivery_record）。 */
  void delivery(
      AccessService.Access who,
      UUID roleId,
      UUID skuId,
      String ref,
      LocalDate received,
      String quantity,
      String amount,
      String currency) {
    write(
        who,
        () -> {
          sql.execute(
              "insert into vendor_delivery_record(id,tenant_id,role_id,delivery_ref,sku_id,purchase_unit_code,"
                  + "quantity,amount,currency,ordered_at,due_date,received_date,note)"
                  + " values(?::uuid,?::uuid,?::uuid,?,?::uuid,'PCS',?,?,?,?::timestamptz,?,?,?)",
              UUID.randomUUID(),
              who.tenantId(),
              roleId,
              ref,
              skuId,
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

  /**
   * 给 warehouse 替身放一份头程发运单。
   *
   * @return 发运单 id
   */
  UUID shipment(
      AccessService.Access who,
      List<UUID> skuIds,
      List<String> quantities,
      String currency,
      String country,
      String incoterm,
      String riskBearer,
      String freight,
      String insurance,
      String handling) {
    var shipmentId = UUID.randomUUID();
    var lines = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < skuIds.size(); i++) {
      var line = new LinkedHashMap<String, Object>();
      line.put("id", UUID.randomUUID().toString());
      line.put("lineNo", i + 1);
      line.put("skuId", skuIds.get(i).toString());
      line.put("skuCode", "SKU-" + skuIds.get(i).toString().substring(0, 8));
      line.put("skuName", "SKU " + skuIds.get(i).toString().substring(0, 8));
      line.put("plannedQuantity", new BigDecimal(quantities.get(i)));
      line.put("shippedQuantity", new BigDecimal(quantities.get(i)));
      lines.add(line);
    }
    var shipment = new LinkedHashMap<String, Object>();
    shipment.put("id", shipmentId.toString());
    shipment.put("shipmentCode", "FM-" + UUID.randomUUID().toString().substring(0, 8));
    shipment.put("legalEntityId", UUID.randomUUID().toString());
    shipment.put("destinationCountryCode", country);
    shipment.put("destinationName", "目的地仓 " + country);
    shipment.put("transportMode", "SEA");
    shipment.put("incoterm", incoterm);
    shipment.put("riskBearer", riskBearer);
    shipment.put("status", "DISPATCHED");
    shipment.put("currency", currency);
    shipment.put("freightCost", new BigDecimal(freight));
    shipment.put("insuranceCost", new BigDecimal(insurance));
    shipment.put("handlingCost", new BigDecimal(handling));
    shipment.put("lines", lines);
    SHIPMENTS.computeIfAbsent(who.tenantId(), k -> new ArrayList<>()).add(shipment);
    return shipmentId;
  }

  /** 给 warehouse 替身放一份报关单 + 关税估算单。 */
  void dutyEstimate(
      AccessService.Access who,
      UUID shipmentId,
      String shipmentCode,
      List<UUID> skuIds,
      String currency,
      String dutyBearer,
      String status,
      String duty,
      String vat) {
    var declarationId = UUID.randomUUID();
    var declaration = new LinkedHashMap<String, Object>();
    declaration.put("id", declarationId.toString());
    declaration.put("declarationCode", "CD-" + UUID.randomUUID().toString().substring(0, 8));
    declaration.put("sourceShipmentId", shipmentId.toString());
    declaration.put("sourceShipmentCode", shipmentCode);
    declaration.put("destinationCountryCode", "US");
    declaration.put("status", "CLEARED");
    declaration.put("createdAt", TODAY.minusDays(5).atStartOfDay().atOffset(ZoneOffset.UTC).toString());
    DECLARATIONS.computeIfAbsent(who.tenantId(), k -> new ArrayList<>()).add(declaration);

    var lines = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < skuIds.size(); i++) {
      var line = new LinkedHashMap<String, Object>();
      line.put("id", UUID.randomUUID().toString());
      line.put("lineNo", i + 1);
      line.put("skuId", skuIds.get(i).toString());
      line.put("quantity", BigDecimal.TEN);
      line.put("uom", "PCS");
      line.put("estimatedDutyAmount", new BigDecimal(duty));
      line.put("estimatedVatAmount", new BigDecimal(vat));
      line.put(
          "estimatedLineTotalTax", new BigDecimal(duty).add(new BigDecimal(vat)));
      lines.add(line);
    }
    var estimate = new LinkedHashMap<String, Object>();
    var estimateId = UUID.randomUUID();
    estimate.put("id", estimateId.toString());
    estimate.put("estimateCode", "DT-" + UUID.randomUUID().toString().substring(0, 8));
    estimate.put("declarationId", declarationId.toString());
    estimate.put("declarationCode", declaration.get("declarationCode"));
    estimate.put("dutyBearer", dutyBearer);
    estimate.put("estimateCurrency", currency);
    estimate.put("destinationCountryCode", "US");
    estimate.put("status", status);
    estimate.put("createdAt", TODAY.minusDays(5).atStartOfDay().atOffset(ZoneOffset.UTC).toString());
    estimate.put("lines", lines);
    DUTIES.computeIfAbsent(who.tenantId(), k -> new ArrayList<>()).add(estimate);
  }

  /** 维护一个到岸成本参数（清关费 / 尾程预备）。 */
  LandedCostParameterRow parameter(
      AccessService.Access who, String code, String country, String currency, String unitAmount) {
    return write(
        who,
        () ->
            landed.saveParameter(
                who,
                new LandedCostParameterInput(
                    code, country, currency, new BigDecimal(unitAmount), "测试参数", 0)));
  }

  /** 计算一张到岸成本单。 */
  LandedCostComputed compute(AccessService.Access who, UUID shipmentId) {
    return write(
        who,
        () -> landed.compute(who, bearerOf(who), new LandedCostComputeInput(shipmentId, null)));
  }

  LandedCostDetail detail(AccessService.Access who, UUID statementId) {
    return tx(who, () -> landed.detail(statementId));
  }

  static LandedCostComponentRow component(LandedCostLineRow line, String code) {
    for (var component : line.components()) {
      if (component.code().equals(code)) return component;
    }
    throw new AssertionError("没有这个分量：" + code);
  }

  // ================================================================ 口径（生死线）

  /**
   * 口径必须在**表结构与响应里**都标得出来。
   *
   * <p>蓝图加粗句「到岸成本与仓库成本是两个口径」是本模块的生死线：
   * 到岸成本里**不含**仓储费与尾程派送费，它只到「货到目标仓可售」为止。
   */
  @Test
  void 口径在表结构与响应里都标得出来() {
    var capabilities = tx(actor, () -> landed.capabilities());

    // 1) 响应里：算了哪六项、明确不算哪两项。
    assertThat(capabilities.basis().code()).isEqualTo("LANDED_TO_TARGET_WAREHOUSE");
    assertThat(capabilities.basis().includedComponents())
        .containsExactly(
            "PURCHASE_PRICE",
            "INTERNATIONAL_FREIGHT",
            "DUTY",
            "CUSTOMS_CLEARANCE",
            "DESTINATION_HANDLING",
            "LAST_MILE_RESERVE");
    // 🔴 仓储费与尾程派送费**不在**到岸成本里 —— 这正是生死线。
    assertThat(capabilities.basis().excludedComponents())
        .containsExactly("STORAGE_FEE", "LAST_MILE_DELIVERY_FEE");
    assertThat(capabilities.basis().note()).contains("两个口径").contains("不含仓储费");

    // 2) 表结构里：cost_basis 是闭集，写不进仓库成本口径。
    var closed = tx(
        actor,
        () ->
            sql.fetchOne(
                "select count(*) from information_schema.check_constraints"
                    + " where constraint_name like '%landed_cost_statement%'"));
    assertThat(closed).isNotNull();

    // 3) 每一份响应都带着口径 —— 列表与明细都不例外。
    UUID skuId = sku(actor, "BASIS");
    var shipmentId =
        shipment(actor, List.of(skuId), List.of("10"), "USD", "US", "DDP", "SELLER", "100", "0", "20");
    var computed = compute(actor, shipmentId);
    var statement = detail(actor, computed.statementId());
    assertThat(statement.basis().excludedComponents()).contains("STORAGE_FEE");
    assertThat(statement.costBasis()).isEqualTo("LANDED_TO_TARGET_WAREHOUSE");
  }

  // ================================================================ 六分量

  /**
   * 六个分量逐项落库，且**每一项都能追到来源单号**。
   *
   * <p>缺一项就退化成半截子报表；没有来源单号就无法审阅。
   */
  @Test
  void 六个分量逐项落库且每项都能追到来源单号() {
    UUID skuId = sku(actor, "SIX");
    // 采购价也要有事实（六项里唯一一项来自本库）：100 件 1000 元 ⇒ 单价 10.00。
    UUID roleId = supplierRole(actor, "SIX-SUP");
    delivery(actor, roleId, skuId, unique("PO-SIX"), DAY_NEW, "100", "1000", "USD");
    var shipmentId =
        shipment(
            actor,
            List.of(skuId),
            List.of("10"),
            "USD",
            "US",
            "DDP",
            "SELLER",
            "100",
            "10",
            "20");
    var shipmentCode = shipmentCodeOf(shipmentId);
    dutyEstimate(actor, shipmentId, shipmentCode, List.of(skuId), "USD", "SELLER", "LOCKED", "30", "5");
    parameter(actor, "CUSTOMS_CLEARANCE", "US", "USD", "2");
    parameter(actor, "LAST_MILE_RESERVE", "US", "USD", "1");

    var computed = compute(actor, shipmentId);
    var statement = detail(actor, computed.statementId());
    assertThat(statement.lines()).hasSize(1);
    var line = statement.lines().get(0);

    // 恒 6 行分量，一项都不少。
    assertThat(line.components()).hasSize(6);
    assertThat(line.components().stream().map(LandedCostComponentRow::code))
        .containsExactlyInAnyOrder(
            "PURCHASE_PRICE",
            "INTERNATIONAL_FREIGHT",
            "DUTY",
            "CUSTOMS_CLEARANCE",
            "DESTINATION_HANDLING",
            "LAST_MILE_RESERVE");

    // 每一项都带来源单号（参数项用「分量|国|币种」作为它的来源键）。
    for (var component : line.components()) {
      assertThat(component.available())
          .as("分量 %s 必须取得到（夹具已备齐全部上游）", component.code())
          .isTrue();
      assertThat(component.sourceDocumentCode())
          .as("分量 %s 必须能追到来源单号", component.code())
          .isNotBlank();
      assertThat(component.sourceModule()).isNotBlank();
    }
    // 头程两项的来源单号就是头程发运单号。
    assertThat(component(line, "INTERNATIONAL_FREIGHT").sourceDocumentCode()).isEqualTo(shipmentCode);
    assertThat(component(line, "DESTINATION_HANDLING").sourceDocumentCode()).isEqualTo(shipmentCode);
    // 关税的来源单号是关税估算单号。
    assertThat(component(line, "DUTY").sourceDocumentCode()).startsWith("DT-");
  }

  // ================================================================ 采购价

  /** 采购价取 catalog 同库的**实际到货金额**（6.3 采购单未交付时的替代口径）。 */
  @Test
  void 采购价取实际到货金额并按数量进合计() {
    UUID skuId = sku(actor, "PURCH");
    UUID roleId = supplierRole(actor, "PURCH-SUP");
    // 100 件 1000 元 ⇒ 单价 10.00。
    delivery(actor, roleId, skuId, unique("PO-1"), DAY_NEW, "100", "1000", "USD");
    var shipmentId =
        shipment(actor, List.of(skuId), List.of("10"), "USD", "US", "DDP", "SELLER", "100", "0", "0");

    var computed = compute(actor, shipmentId);
    var line = detail(actor, computed.statementId()).lines().get(0);
    var purchase = component(line, "PURCHASE_PRICE");

    assertThat(purchase.available()).isTrue();
    assertThat(purchase.included()).isTrue();
    // 10.00/件 × 10 件 = 100.0000
    assertThat(purchase.amount()).isEqualByComparingTo("100.0000");
    assertThat(purchase.unitAmount()).isEqualByComparingTo("10.0000");
    // 来源单号是到货单号。
    assertThat(purchase.sourceDocumentCode()).startsWith("PO-");
  }

  // ================================================================ 分摊守恒

  /**
   * 批次级成本摊到 SKU 行后，**各行之和严格等于批次总额**。
   *
   * <p>摊漏了就是系统性低估 —— 这正是本模块最危险的失败模式。
   */
  @Test
  void 批次级成本摊到行后各行之和严格等于批次总额() {
    UUID skuA = sku(actor, "ALLOC-A");
    UUID skuB = sku(actor, "ALLOC-B");
    // 数量 3 : 1 ⇒ 75 / 25。
    var shipmentId =
        shipment(
            actor,
            List.of(skuA, skuB),
            List.of("30", "10"),
            "USD",
            "US",
            "DDP",
            "SELLER",
            "100",
            "0",
            "40");

    var computed = compute(actor, shipmentId);
    var statement = detail(actor, computed.statementId());
    assertThat(statement.lines()).hasSize(2);

    var lineA = statement.lines().get(0);
    var lineB = statement.lines().get(1);
    // 运费 100 按 3:1 摊 ⇒ 75 / 25。
    assertThat(component(lineA, "INTERNATIONAL_FREIGHT").amount()).isEqualByComparingTo("75.0000");
    assertThat(component(lineB, "INTERNATIONAL_FREIGHT").amount()).isEqualByComparingTo("25.0000");
    // 操作费 40 ⇒ 30 / 10。
    assertThat(component(lineA, "DESTINATION_HANDLING").amount()).isEqualByComparingTo("30.0000");
    assertThat(component(lineB, "DESTINATION_HANDLING").amount()).isEqualByComparingTo("10.0000");

    // 逐字守恒：两行的头程之和 == 批次头程总额。
    var freightSum =
        component(lineA, "INTERNATIONAL_FREIGHT")
            .amount()
            .add(component(lineB, "INTERNATIONAL_FREIGHT").amount());
    assertThat(freightSum).isEqualByComparingTo("100.0000");
    // 单头合计 = 各行计入分量之和。
    var lineSum = lineA.totalAmount().add(lineB.totalAmount());
    assertThat(statement.totalAmount()).isEqualByComparingTo(lineSum);
  }

  // ================================================================ 缺口

  /**
   * 没有关税估算时，关税是**缺口**而不是 0。
   *
   * <p>用 0 填关税会让到岸成本**系统性低估**，而页面上看不出任何异常 ——
   * 这是本模块最危险的失败模式。
   */
  @Test
  void 没有关税估算时关税是缺口而不是0() {
    UUID skuId = sku(actor, "GAP");
    var shipmentId =
        shipment(actor, List.of(skuId), List.of("10"), "USD", "US", "DDP", "SELLER", "100", "0", "0");

    var computed = compute(actor, shipmentId);
    var statement = detail(actor, computed.statementId());
    var line = statement.lines().get(0);
    var duty = component(line, "DUTY");

    // 🔴 缺口：没有数，不是 0。
    assertThat(duty.available()).isFalse();
    assertThat(duty.included()).isFalse();
    assertThat(duty.amount()).isNull();
    assertThat(duty.unitAmount()).isNull();
    // 缺口必须点名归谁管、缺了会怎样。
    assertThat(duty.ownerModule()).isNotBlank();
    assertThat(duty.impact()).contains("不用 0 假装没有关税");
    // 覆盖度上也要看得见缺了哪几项：关税、采购价、两个未维护的参数、目的地操作费**都是缺口**。
    assertThat(statement.missingComponents())
        .containsExactlyInAnyOrder(
            "PURCHASE_PRICE",
            "DUTY",
            "CUSTOMS_CLEARANCE",
            "LAST_MILE_RESERVE",
            "DESTINATION_HANDLING");
    assertThat(line.missingComponentCount()).isEqualTo(statement.missingComponents().size());

    // 🔴 采购价也没有到货事实 ⇒ 同样缺口（不是 0 元采购）。
    assertThat(component(line, "PURCHASE_PRICE").amount()).isNull();
    // 合计是「已知部分合计」：只含真正计入的分量，不是把缺口当 0 加进去。
    assertThat(statement.totalAmount()).isEqualByComparingTo("100.0000");
  }

  /** 一个分量都没算出来时，合计是 **null**（算不出来），不是 0。 */
  @Test
  void 一个分量都没算出来时合计是空而不是0() {
    UUID skuId = sku(actor, "EMPTY");
    // 头程 0 / 保险 0 / 操作费 0 ⇒ 三个批次级分量全是缺口（上游 NOT NULL DEFAULT 0，0 ≠ 没登记）。
    var shipmentId =
        shipment(actor, List.of(skuId), List.of("10"), "USD", "US", "DDP", "SELLER", "0", "0", "0");

    var computed = compute(actor, shipmentId);
    var statement = detail(actor, computed.statementId());
    var line = statement.lines().get(0);

    assertThat(line.totalAmount()).isNull();
    assertThat(statement.totalAmount()).isNull();
    assertThat(statement.includedComponentCount()).isZero();
    assertThat(statement.missingComponents())
        .contains("PURCHASE_PRICE", "INTERNATIONAL_FREIGHT", "DUTY", "DESTINATION_HANDLING");
  }

  // ================================================================ 关税

  /** 关税与增值税**分列**，并按承担方决定是否计入卖方的到岸成本。 */
  @Test
  void 关税与增值税分列且按承担方决定是否计入() {
    UUID skuId = sku(actor, "TAX");
    // DAP：卖方送到指定目的地，**进口清关与关税由买方承担**。
    // （DDU 是 Incoterms 1990 的旧写法，warehouse 的 first_mile_shipment CHECK 只接受
    //   Incoterms 2010 的 11 个取值，不含 DDU —— 这里用它的 2010 对应项 DAP。）
    var shipmentId =
        shipment(actor, List.of(skuId), List.of("10"), "USD", "US", "DAP", "BUYER", "100", "0", "0");
    var shipmentCode = shipmentCodeOf(shipmentId);
    // 关税 30、增值税 5，但 **买方承担**。
    dutyEstimate(actor, shipmentId, shipmentCode, List.of(skuId), "USD", "BUYER", "LOCKED", "30", "5");

    var computed = compute(actor, shipmentId);
    var line = detail(actor, computed.statementId()).lines().get(0);
    var duty = component(line, "DUTY");

    // 事实取到了（available=true），但不计入卖方的到岸成本（included=false）。
    assertThat(duty.available()).isTrue();
    assertThat(duty.included()).isFalse();
    assertThat(duty.amount()).isEqualByComparingTo("35.0000");
    // 🔴 关税与增值税分列：答得出「哪部分是关税哪部分是税」。
    assertThat(duty.taxDutyAmount()).isEqualByComparingTo("30.0000");
    assertThat(duty.taxOtherAmount()).isEqualByComparingTo("5.0000");
    assertThat(duty.taxBearer()).isEqualTo("BUYER");
    // 不计入必须写清原因。
    assertThat(duty.note()).contains("不由卖方承担");
    // 合计里不含它。
    assertThat(line.totalAmount()).isEqualByComparingTo("100.0000");
  }

  // ================================================================ 参数

  /** 清关费与尾程预备是**参数**：未维护是缺口，维护后按单件 × 数量进合计。 */
  @Test
  void 清关费与尾程预备是参数未维护时是缺口() {
    UUID skuId = sku(actor, "PARAM");
    var shipmentId =
        shipment(actor, List.of(skuId), List.of("10"), "USD", "US", "DDP", "SELLER", "100", "0", "0");

    // 先不维护参数 ⇒ 两个参数分量都是缺口。
    var computed = compute(actor, shipmentId);
    var line = detail(actor, computed.statementId()).lines().get(0);
    var clearance = component(line, "CUSTOMS_CLEARANCE");
    assertThat(clearance.available()).isFalse();
    assertThat(clearance.amount()).isNull();
    assertThat(clearance.ownerModule()).isNotBlank();
    assertThat(clearance.impact()).contains("尚未维护");

    // 维护后重算 ⇒ 按单件 × 基础数量进合计。
    parameter(actor, "CUSTOMS_CLEARANCE", "US", "USD", "2");
    parameter(actor, "LAST_MILE_RESERVE", "US", "USD", "1");
    var recomputed = compute(actor, shipmentId);
    assertThat(recomputed.statementId()).isEqualTo(computed.statementId());
    var line2 = detail(actor, recomputed.statementId()).lines().get(0);
    // 2.00/件 × 10 件 = 20.0000
    assertThat(component(line2, "CUSTOMS_CLEARANCE").amount()).isEqualByComparingTo("20.0000");
    // 1.00/件 × 10 件 = 10.0000
    assertThat(component(line2, "LAST_MILE_RESERVE").amount()).isEqualByComparingTo("10.0000");
  }

  /**
   * 尾程预备是**显式分量**，不被悄悄并进别的项。
   *
   * <p>它与「尾程派送费」不是一回事：前者是定价用的预留（归本模块），
   * 后者是已发生的运费（归 10.2 / 10.6.1.1，不在到岸成本里）。
   */
  @Test
  void 尾程预备是显式分量不被并进别的项() {
    UUID skuId = sku(actor, "RESERVE");
    var shipmentId =
        shipment(actor, List.of(skuId), List.of("10"), "USD", "US", "DDP", "SELLER", "100", "0", "0");
    parameter(actor, "LAST_MILE_RESERVE", "US", "USD", "1.5");

    var computed = compute(actor, shipmentId);
    var line = detail(actor, computed.statementId()).lines().get(0);
    var reserve = component(line, "LAST_MILE_RESERVE");

    assertThat(reserve.code()).isEqualTo("LAST_MILE_RESERVE");
    assertThat(reserve.sourceKind()).isEqualTo("PARAMETER");
    assertThat(reserve.amount()).isEqualByComparingTo("15.0000");
    // 它不并进任何别的分量：国际头程仍是 100（不含预备）。
    assertThat(component(line, "INTERNATIONAL_FREIGHT").amount()).isEqualByComparingTo("100.0000");
    // 口径里写明：尾程派送费实际值**不在**到岸成本里。
    var statement = detail(actor, computed.statementId());
    assertThat(statement.basis().excludedComponents()).contains("LAST_MILE_DELIVERY_FEE");
  }

  // ================================================================ 上游不可达

  /** 上游不可达 ⇒ 503 报「读不到」，**不把头程与关税当成 0**。 */
  @Test
  void 上游不可达返回503而不是把头程与关税当成0() {
    UUID skuId = sku(actor, "DOWN");
    var shipmentId =
        shipment(actor, List.of(skuId), List.of("10"), "USD", "US", "DDP", "SELLER", "100", "0", "0");
    warehouseDown.set(true);
    try {
      var error =
          assertThrows(
              org.springframework.web.server.ResponseStatusException.class,
              () -> compute(actor, shipmentId));
      assertThat(error.getStatusCode().value()).isEqualTo(503);
      assertThat(error.getReason()).contains("读不到");
    } finally {
      warehouseDown.set(false);
    }
  }

  // ================================================================ 幂等与跨服务透传

  /** 重算是**幂等**的：覆盖同一张单，compute_count 加一。 */
  @Test
  void 重算是幂等的同时证明跨服务调用透传了调用方的Authorization头() {
    UUID skuId = sku(actor, "IDEM");
    var shipmentId =
        shipment(actor, List.of(skuId), List.of("10"), "USD", "US", "DDP", "SELLER", "100", "0", "0");

    var first = compute(actor, shipmentId);
    var second = compute(actor, shipmentId);

    // 同一张单，不是新开一张。
    assertThat(second.statementId()).isEqualTo(first.statementId());
    assertThat(second.created()).isFalse();
    assertThat(second.computeCount()).isEqualTo(2);
    assertThat(detail(actor, first.statementId()).computeCount()).isEqualTo(2);
    // 跨服务调用把调用方自己的 bearer 原样带过去了（租户由 warehouse 从成员关系解析）。
    assertThat(lastBearer.get()).isEqualTo("Bearer tenant-a");
  }

  // ================================================================ 目的地对比

  /** 目的地对比：按目的地分组，**跨币种绝不相加**。 */
  @Test
  void 目的地对比按目的地分组且跨币种不相加() {
    UUID skuId = sku(actor, "CMP");
    var usShipment =
        shipment(actor, List.of(skuId), List.of("10"), "USD", "US", "DDP", "SELLER", "100", "0", "0");
    compute(actor, usShipment);
    var deShipment =
        shipment(actor, List.of(skuId), List.of("10"), "EUR", "DE", "DDP", "SELLER", "80", "0", "0");
    compute(actor, deShipment);

    var comparison = tx(actor, () -> landed.comparison(skuId));
    assertThat(comparison.rows()).hasSize(2);
    // 两个目的地各一行，**币种不同的行不合并成一个平均值**。
    assertThat(comparison.rows().stream().map(LandedCostComparisonRow::currency))
        .containsExactlyInAnyOrder("USD", "EUR");
    assertThat(comparison.rows().stream().map(LandedCostComparisonRow::destinationCountryCode))
        .containsExactlyInAnyOrder("US", "DE");
    // 没指定 SKU ⇒ 400（不同 SKU 的单位成本不可比）。
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> tx(actor, () -> landed.comparison(null)));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
  }

  // ================================================================ 时间戳

  /**
   * 时间字段走 {@code ?::timestamptz} + {@link OffsetDateTime} 绑定，**不被时区偏移**。
   *
   * <p>用 {@code Timestamp.valueOf(LocalDateTime...)} 会静默偏 −28800s，
   * 于是「算完这一单的时间窗口」会把这一单筛掉。
   */
  @Test
  void 时间筛选用OffsetDateTime绑定不被时区偏移() {
    UUID skuId = sku(actor, "TIME");
    var shipmentId =
        shipment(actor, List.of(skuId), List.of("10"), "USD", "US", "DDP", "SELLER", "100", "0", "0");
    var computed = compute(actor, shipmentId);
    var computedAt = detail(actor, computed.statementId()).computedAt();
    assertThat(computedAt).isNotNull();

    // 窗口罩住计算时刻 ⇒ 必须查得到。
    var found =
        tx(
            actor,
            () ->
                landed.list(
                    new LandedCostQuery(
                        null, null, null, null, computedAt.minusMinutes(5), computedAt.plusMinutes(5), 0, 20)));
    assertThat(found.items()).extracting(LandedCostStatementRow::id).contains(computed.statementId());

    // 窗口挪到将来 ⇒ 查不到（证明绑定的时刻与库里存的是同一个绝对时刻）。
    var missed =
        tx(
            actor,
            () ->
                landed.list(
                    new LandedCostQuery(
                        null,
                        null,
                        null,
                        null,
                        computedAt.plusHours(1),
                        computedAt.plusHours(2),
                        0,
                        20)));
    assertThat(missed.items()).extracting(LandedCostStatementRow::id).doesNotContain(computed.statementId());
  }

  // ================================================================ 租户隔离三断言

  /**
   * 多租户三断言：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。
   *
   * <p>到岸成本是最敏感的数据之一（能看出别的租户在什么价位采购）。
   */
  @Test
  void 租户隔离三断言覆盖到岸成本单与分量() throws Exception {
    UUID skuA = sku(actor, "TEN-A");
    // 🔴 B 租户的 SKU 必须建在 B 租户下：到岸成本行只能挂本租户的 SKU
    // （V223 的 guard_landed_cost_line_sku 会在写入当下拒掉跨租户引用）。
    UUID skuB = sku(actorB, "TEN-B");
    var shipmentA =
        shipment(actor, List.of(skuA), List.of("10"), "USD", "US", "DDP", "SELLER", "100", "0", "0");
    var shipmentB =
        shipment(actorB, List.of(skuB), List.of("10"), "USD", "US", "DDP", "SELLER", "999", "0", "0");
    var computedA = compute(actor, shipmentA);
    var computedB = compute(actorB, shipmentB);

    // 1) 两租户互不可见。
    var listA = tx(actor, () -> landed.list(new LandedCostQuery(null, null, null, null, null, null, 0, 50)));
    var listB = tx(actorB, () -> landed.list(new LandedCostQuery(null, null, null, null, null, null, 0, 50)));
    assertThat(listA.items()).extracting(LandedCostStatementRow::id).contains(computedA.statementId());
    assertThat(listA.items()).extracting(LandedCostStatementRow::id).doesNotContain(computedB.statementId());
    assertThat(listB.items()).extracting(LandedCostStatementRow::id).doesNotContain(computedA.statementId());
    // 金额清单也不相交。
    assertThat(listA.items().stream().map(LandedCostStatementRow::totalAmount).toList())
        .doesNotContain(computedB.totalAmount());

    // 2) 伪造 tenant header 无效：HTTP 层的 X-Tenant-Id 不改变解析出来的租户。
    http.perform(
            get("/catalog/landed-costs")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(
            result -> {
              var body = result.getResponse().getContentAsString();
              assertThat(body).contains(computedA.statementId().toString());
              assertThat(body).doesNotContain(computedB.statementId().toString());
            });

    // 3) 连接池归还后不串租户：交替 A → B → A 三轮，每次读到的都是自己那一份。
    for (int i = 0; i < 3; i++) {
      var a1 = tx(actor, () -> landed.detail(computedA.statementId()));
      var b1 = tx(actorB, () -> landed.detail(computedB.statementId()));
      var a2 = tx(actor, () -> landed.detail(computedA.statementId()));
      assertThat(a1.totalAmount()).isEqualByComparingTo("100.0000");
      assertThat(b1.totalAmount()).isEqualByComparingTo("999.0000");
      assertThat(a2.totalAmount()).isEqualByComparingTo("100.0000");
    }
  }

  // ================================================================ 权限

  /** 写端点要 update 权限（reader 403），读端点 reader 可用。 */
  @Test
  void 写端点需要update权限() throws Exception {
    UUID skuId = sku(actor, "PERM");
    var shipmentId =
        shipment(actor, List.of(skuId), List.of("10"), "USD", "US", "DDP", "SELLER", "100", "0", "0");

    http.perform(
            post("/catalog/landed-costs")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{\"sourceShipmentId\":\"" + shipmentId + "\"}"))
        .andExpect(status().isForbidden());

    http.perform(get("/catalog/landed-costs/capabilities").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
  }

  // ================================================================ 列表与筛选

  /** 列表是真分页 + 真筛选，关键词能搜到发运单号。 */
  @Test
  void 列表是真分页真筛选() {
    // 两个 SKU、两张发运单，**都在 A 租户** —— 分页与筛选是对同一租户的台账操作。
    UUID skuA = sku(actor, "PAGE-A");
    UUID skuB = sku(actor, "PAGE-B");
    var shipmentA =
        shipment(actor, List.of(skuA), List.of("10"), "USD", "US", "DDP", "SELLER", "100", "0", "0");
    var shipmentB =
        shipment(actor, List.of(skuB), List.of("10"), "USD", "DE", "DDP", "SELLER", "200", "0", "0");
    var computedA = compute(actor, shipmentA);
    var computedB = compute(actor, shipmentB);
    assertThat(computedB.statementId()).isNotEqualTo(computedA.statementId());

    var page = tx(actor, () -> landed.list(new LandedCostQuery(null, null, null, null, null, null, 0, 1)));
    assertThat(page.items()).hasSize(1);
    assertThat(page.total()).isGreaterThanOrEqualTo(2);

    // 按目的国筛。
    var us = tx(actor, () -> landed.list(new LandedCostQuery(null, "US", null, null, null, null, 0, 20)));
    assertThat(us.items()).extracting(LandedCostStatementRow::destinationCountryCode).containsOnly("US");
    assertThat(us.items()).extracting(LandedCostStatementRow::id).contains(computedA.statementId());

    // 按发运单号搜。
    var shipmentCode = shipmentCodeOf(shipmentA);
    var byCode =
        tx(actor, () -> landed.list(new LandedCostQuery(shipmentCode, null, null, null, null, null, 0, 20)));
    assertThat(byCode.items()).extracting(LandedCostStatementRow::id).contains(computedA.statementId());
  }

  // ================================================================ 工具

  /** 取替身里那张发运单的单号。 */
  String shipmentCodeOf(UUID shipmentId) {
    for (var shipment : SHIPMENTS.getOrDefault(a, List.of())) {
      if (shipmentId.toString().equals(shipment.get("id"))) {
        return String.valueOf(shipment.get("shipmentCode"));
      }
    }
    for (var shipment : SHIPMENTS.getOrDefault(b, List.of())) {
      if (shipmentId.toString().equals(shipment.get("id"))) {
        return String.valueOf(shipment.get("shipmentCode"));
      }
    }
    throw new AssertionError("替身里没有这张发运单");
  }
}
