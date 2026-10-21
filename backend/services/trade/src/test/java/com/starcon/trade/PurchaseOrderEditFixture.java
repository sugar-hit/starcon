package com.starcon.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 6.3.1.2 采购单创建与编辑的<b>共享底座</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身</b>。跨服务一律走**真的本地 HTTP 服务**：
 *
 * <ul>
 *   <li><b>identity</b>：{@code GET /internal/access} —— 只回答「你是谁、在哪个租户、有哪些权限与范围」，
 *       租户隔离仍由 trade 侧的 RLS 在数据库层强制；</li>
 *   <li><b>catalog</b>：SKU 与单位换算（3.1.2.4）/ 有效报价（3.6.2.1）/ 汇率台账与法人本位币
 *       （5.2.2.2）/ 供应商（6.1.1.x）/ 媒体库（3.4.1.1）；</li>
 *   <li><b>warehouse</b>：仓库与法人参照（7.x）/ 分 SKU 可用量（7.1.1.3）。</li>
 * </ul>
 *
 * <p>替身只回答「上游那边是什么」，<b>绝不替业务逻辑做决定</b>：
 * 「取哪天的那条汇率」「歧义时选哪一条」「箱规是几」都由本模块决定。
 *
 * <p><b>上游事实怎么来的</b>：6.2.2.2 的合并草稿（6.2.2.1 的申请行）**直接种进它们自己的表** ——
 * 本模块是它们的下游消费者，读它们的事实表正是本模块要证明的事；种数据用迁移用户（超级用户），
 * 生产运行角色 {@code trade_app} 对那些表只有上游授予的权限。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class PurchaseOrderEditFixture {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID(), userC = UUID.randomUUID();
  static final UUID departmentA = UUID.randomUUID();

  /** 目标仓 / 法人（warehouse 的事实）。 */
  static final UUID warehouseId = UUID.randomUUID();
  static final UUID legalEntityId = UUID.randomUUID();

  /** 供应商：trade 全线用 **SUPPLIER 角色 id** 寻址；报价台账用 **主体 id** 寻址。 */
  static final UUID supplierRoleId = UUID.randomUUID();
  static final UUID supplierPartyId = UUID.randomUUID();

  /** 本位币（法人档案的事实，catalog 拥有）。 */
  static final String FUNCTIONAL_CURRENCY = "CNY";

  /** 替身给出的汇率：1 USD = 7.2 CNY。 */
  static final BigDecimal FX_RATE = new BigDecimal("7.20000000");

  /** 采购单位箱规：1 箱 = 12 件。 */
  static final BigDecimal QUANTITY_PER_BASE = new BigDecimal("12");

  static HttpServer identity, catalog, warehouse;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 置位后 catalog 一律回 503（模拟「商品主数据服务不可达」）。 */
  static final AtomicBoolean catalogDown = new AtomicBoolean();

  /** 置位后 warehouse 一律回 503。 */
  static final AtomicBoolean warehouseDown = new AtomicBoolean();

  /** 置位后报价**并列**（歧义）：同一优先级两条有效报价。 */
  static final AtomicBoolean ambiguousQuotes = new AtomicBoolean();

  /** 置位后报价**取不到**（严格取价 404）。 */
  static final AtomicBoolean noQuote = new AtomicBoolean();

  /** 置位后汇率台账**取不到**（提交时汇率快照必须变成 UNAVAILABLE，**不是 1:1**）。 */
  static final AtomicBoolean noRate = new AtomicBoolean();

  /** 替身收到的最近一次汇率查询参数（断言「取哪天的那条」是本模块决定的）。 */
  static final AtomicReference<String> lastRateQuery = new AtomicReference<>();

  static final List<String> perms = List.of("trade.purchase.read", "trade.purchase.update");

  static AccessService.Access actor(UUID uid, UUID tenant, UUID dept, String scope) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", scope, perms));
    return new AccessService.Access(uid, tenant, "Test", UUID.randomUUID(), dept, perms, grants);
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

      // ---- identity：只回答「你是谁 / 哪个租户 / 什么权限与范围」 ----
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
      identity.start();

      // ---- catalog：SKU / 报价 / 汇率 / 供应商 / 媒体库 ----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext("/catalog", PurchaseOrderEditFixture::catalogHandler);
      catalog.start();

      // ---- warehouse：仓 / 法人 / 可用量 ----
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext("/warehouse", PurchaseOrderEditFixture::warehouseHandler);
      warehouse.start();

      actors.put("Bearer tenant-a", actor(userA, tenantA, departmentA, "ALL"));
      actors.put("Bearer tenant-b", actor(userB, tenantB, null, "ALL"));
      // 同一个租户里的**另一个**用户：数据范围 SELF 时只看得到自己负责的单。
      actors.put("Bearer self-a", actor(userC, tenantA, departmentA, "SELF"));
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  // ================================================================ catalog 替身

  private static void catalogHandler(HttpExchange e) throws IOException {
    if (catalogDown.get()) {
      write(catalog, e, 503, Map.of("message", "商品主数据服务暂时不可达"));
      return;
    }
    String path = e.getRequestURI().getPath();
    String query = e.getRequestURI().getRawQuery();

    // ---- 汇率台账 + 法人本位币 ----
    if (path.startsWith("/catalog/exchange-rates")) {
      if (path.endsWith("/functional-currencies")) {
        write(
            catalog,
            e,
            200,
            Map.of(
                "rows",
                List.of(
                    Map.of(
                        "legalEntityId", legalEntityId.toString(),
                        "legalEntityCode", "US-LLC",
                        "legalEntityName", "美国子公司",
                        "baseCurrency", FUNCTIONAL_CURRENCY)),
                "note", "本位币来自法人档案"));
        return;
      }
      if (noRate.get()) {
        write(catalog, e, 200, Map.of("items", List.of(), "total", 0, "page", 0, "size", 500));
        return;
      }
      lastRateQuery.set(query);
      String base = param(query, "baseCurrency");
      String quote = param(query, "quoteCurrency");
      // 汇率生效日 = 昨天（落在「发生日当天或之前最新的一条」的窗口内）。
      String effective = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
      var body = new LinkedHashMap<String, Object>();
      body.put(
          "items",
          List.of(
              Map.of(
                  "id", UUID.randomUUID().toString(),
                  "baseCurrency", base,
                  "quoteCurrency", quote,
                  "rate", FX_RATE,
                  "effectiveDate", effective,
                  "sourceCode", "TEST",
                  "sourceKind", "MANUAL",
                  "display", base + "/" + quote + " " + FX_RATE)));
      body.put("total", 1);
      body.put("page", 0);
      body.put("size", 500);
      body.put("note", "汇率台账");
      write(catalog, e, 200, body);
      return;
    }

    // ---- SKU 与单位换算（3.1.2.4）----
    if (path.startsWith("/catalog/skus/")) {
      UUID skuId = UUID.fromString(path.substring("/catalog/skus/".length()));
      write(
          catalog,
          e,
          200,
          Map.of(
              "id", skuId.toString(),
              "code", "SKU-" + skuId.toString().substring(0, 8),
              "name", "测试商品",
              "category", "CAT-1",
              "baseUnit", "件",
              "unitConversions",
              List.of(
                  Map.of(
                      "unitCode", "件",
                      "role", "BASE",
                      "quantityPerBase", BigDecimal.ONE,
                      "current", true),
                  Map.of(
                      "unitCode", "箱",
                      "role", "PURCHASE",
                      "quantityPerBase", QUANTITY_PER_BASE,
                      "current", true))));
      return;
    }

    // ---- 有效报价（3.6.2.1）：/effective 与 /effective/resolve ----
    if (path.startsWith("/catalog/supplier-quotes/effective")) {
      if (noQuote.get()) {
        write(catalog, e, 404, Map.of("message", "该 SKU 该供应商没有有效报价"));
        return;
      }
      boolean strict = path.endsWith("/resolve");
      UUID skuId = UUID.fromString(param(query, "skuId"));
      if (ambiguousQuotes.get()) {
        if (strict) {
          // 严格取价：歧义 ⇒ 409（**不替用户挑**）。
          write(catalog, e, 409, Map.of("message", "同一优先级存在多条有效报价，必须由人指定用哪一条"));
          return;
        }
        var tie = new LinkedHashMap<String, Object>();
        tie.put("id", UUID.randomUUID().toString());
        tie.put("supplierId", supplierPartyId.toString());
        tie.put("supplierName", "测试供应商");
        tie.put("skuCode", "SKU-1");
        tie.put("skuName", "测试商品");
        tie.put("currency", "USD");
        tie.put("purchaseUnitCode", "箱");
        tie.put("moqQuantity", new BigDecimal("1"));
        tie.put("leadTimeDays", 7);
        tie.put("priority", 1);
        tie.put("validFrom", LocalDate.now(ZoneOffset.UTC).toString());
        tie.put("validTo", null);
        tie.put("tiers", List.of(Map.of("minQuantity", new BigDecimal("1"), "unitPrice", new BigDecimal("2.5"))));
        tie.put("selectionState", "AMBIGUOUS");
        var tie2 = new LinkedHashMap<String, Object>(tie);
        tie2.put("id", UUID.randomUUID().toString());
        var tieBody = new LinkedHashMap<String, Object>();
        tieBody.put("skuId", skuId.toString());
        tieBody.put("skuCode", "SKU-1");
        tieBody.put("skuName", "测试商品");
        tieBody.put("supplierId", supplierPartyId.toString());
        tieBody.put("supplierName", "测试供应商");
        tieBody.put("at", LocalDate.now(ZoneOffset.UTC).toString());
        tieBody.put("selected", null);
        tieBody.put("selectedTier", null);
        tieBody.put("candidates", List.of(tie, tie2));
        tieBody.put("ambiguous", true);
        tieBody.put("ambiguityProblem", "同一优先级存在多条有效报价，必须由人指定用哪一条");
        tieBody.put("warnings", List.of("存在优先级并列的有效报价"));
        tieBody.put("note", "候选并列");
        write(catalog, e, 200, tieBody);
        return;
      }
      var quote = new LinkedHashMap<String, Object>();
      quote.put("id", quoteId.toString());
      quote.put("supplierId", supplierPartyId.toString());
      quote.put("supplierName", "测试供应商");
      quote.put("skuCode", "SKU-1");
      quote.put("skuName", "测试商品");
      quote.put("currency", "USD");
      quote.put("purchaseUnitCode", "箱");
      quote.put("moqQuantity", new BigDecimal("1"));
      quote.put("leadTimeDays", 7);
      quote.put("priority", 1);
      quote.put("validFrom", LocalDate.now(ZoneOffset.UTC).toString());
      quote.put("validTo", null);
      quote.put("tiers", List.of(Map.of("minQuantity", new BigDecimal("1"), "unitPrice", new BigDecimal("2.5"))));
      quote.put("selectionState", "SELECTED");
      var body = new LinkedHashMap<String, Object>();
      body.put("skuId", skuId.toString());
      body.put("skuCode", "SKU-1");
      body.put("skuName", "测试商品");
      body.put("supplierId", supplierPartyId.toString());
      body.put("supplierName", "测试供应商");
      body.put("at", LocalDate.now(ZoneOffset.UTC).toString());
      body.put("selected", quote);
      body.put("selectedTier", Map.of("minQuantity", new BigDecimal("1"), "unitPrice", new BigDecimal("2.5")));
      body.put("candidates", List.of(quote));
      body.put("ambiguous", false);
      body.put("ambiguityProblem", null);
      body.put("warnings", List.of());
      body.put("note", "唯一有效报价");
      write(catalog, e, 200, body);
      return;
    }

    // ---- 供应商（6.1.1.x）----
    if (path.startsWith("/catalog/supply/vendors")) {
      if (path.equals("/catalog/supply/vendors")) {
        write(
            catalog,
            e,
            200,
            Map.of(
                "items",
                List.of(vendorRow()),
                "total",
                1,
                "page",
                0,
                "size",
                200));
        return;
      }
      UUID roleId = UUID.fromString(path.substring("/catalog/supply/vendors/".length()));
      if (!supplierRoleId.equals(roleId)) {
        write(catalog, e, 404, Map.of("message", "供应商不存在"));
        return;
      }
      write(catalog, e, 200, Map.of("summary", vendorRow(), "deliveries", List.of(), "inspections", List.of()));
      return;
    }

    // ---- 媒体库（3.4.1.1）----
    if (path.startsWith("/catalog/media/")) {
      UUID mediaId = UUID.fromString(path.substring("/catalog/media/".length()));
      var asset = new LinkedHashMap<String, Object>();
      asset.put("id", mediaId.toString());
      asset.put("name", "合同扫描件.pdf");
      asset.put("mediaType", "DOCUMENT");
      asset.put("mimeType", "application/pdf");
      asset.put("byteSize", new BigDecimal("20480"));
      asset.put("storageKey", "media/" + mediaId + ".pdf");
      asset.put("originalFilename", "合同扫描件.pdf");
      asset.put("enabled", true);
      var detail = new LinkedHashMap<String, Object>();
      detail.put("asset", asset);
      detail.put("versions", List.of());
      detail.put("tags", Map.of());
      detail.put("references", List.of());
      detail.put("integrityChecks", List.of());
      detail.put("deletable", true);
      detail.put("note", "素材详情");
      write(catalog, e, 200, detail);
      return;
    }

    write(catalog, e, 404, Map.of("message", "catalog 替身没有这个路径：" + path));
  }

  private static Map<String, Object> vendorRow() {
    return Map.of(
        "roleId", supplierRoleId.toString(),
        "partyId", supplierPartyId.toString(),
        "partyCode", "SUP-TEST",
        "partyName", "测试供应商",
        "partyStatus", "ACTIVE");
  }

  // ================================================================ warehouse 替身

  private static void warehouseHandler(HttpExchange e) throws IOException {
    if (warehouseDown.get()) {
      write(warehouse, e, 503, Map.of("message", "仓库服务暂时不可达"));
      return;
    }
    String path = e.getRequestURI().getPath();
    String query = e.getRequestURI().getRawQuery();
    if (path.equals("/warehouse/warehouses")) {
      write(
          warehouse,
          e,
          200,
          List.of(
              Map.of(
                  "id", warehouseId.toString(),
                  "code", "WH-LA-01",
                  "name", "洛杉矶仓",
                  "countryCode", "US",
                  "status", "ACTIVE")));
      return;
    }
    if (path.equals("/warehouse/legal-entities")) {
      write(
          warehouse,
          e,
          200,
          List.of(
              Map.of(
                  "id", legalEntityId.toString(),
                  "code", "US-LLC",
                  "name", "美国子公司",
                  "status", "ACTIVE")));
      return;
    }
    if (path.startsWith("/warehouse/inventory/sellable/skus")) {
      String sku = param(query, "skuId");
      if (sku == null) {
        write(warehouse, e, 200, Map.of("rows", List.of(), "total", 0));
        return;
      }
      write(
          warehouse,
          e,
          200,
          Map.of(
              "rows",
              List.of(
                  Map.of(
                      "skuId", sku,
                      "skuCode", "SKU-1",
                      "skuName", "测试商品",
                      "onHand", new BigDecimal("50"),
                      "reserved", new BigDecimal("10"),
                      "available", new BigDecimal("40"),
                      "sellableBeforeBuffer", new BigDecimal("40"),
                      "safetyBuffer", new BigDecimal("0"),
                      "sellable", new BigDecimal("40"))),
              "total",
              1));
      return;
    }
    write(warehouse, e, 404, Map.of("message", "warehouse 替身没有这个路径：" + path));
  }

  // ================================================================ 工具

  static String param(String query, String key) {
    if (query == null) {
      return null;
    }
    for (String pair : query.split("&")) {
      int i = pair.indexOf('=');
      if (i > 0 && pair.substring(0, i).equals(key)) {
        return URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  static void write(HttpServer server, HttpExchange e, int code, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
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
    // ⚠️ **连接池固定为 1**：池大于 1 时每次可能拿到不同连接，
    // 「归还后不串租户」可能只是碰巧换了条干净连接。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @AfterAll
  static void noteSharedResources() {
    // 🔴 **刻意不在这里停容器与替身服务**：它们被两个测试类共享，
    // 先结束的测试类把共享资源停掉会让后面的类全部失败。
    // 改由 JVM 退出时由 Testcontainers 的 Ryuk 统一回收。
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 数据准备

  /** 用迁移用户（超级用户）连接：绕开 RLS，只用来清表与种上游事实。 */
  static Connection admin() throws Exception {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  /** 清掉本模块的表与上游事实表。 */
  static void truncateAll() throws Exception {
    try (var c = admin();
        var s = c.createStatement()) {
      s.execute(
          "truncate purchase_order_draft_source, purchase_order_attachment,"
              + " purchase_order_ship_address, purchase_order_submit_snapshot,"
              + " purchase_order_line_snapshot,"
              + " purchase_order_event, purchase_order_line, purchase_order,"
              + " purchase_demand_merge_line_source, purchase_demand_merge_line,"
              + " purchase_demand_merge_unmerged, purchase_demand_batch_adjustment,"
              + " purchase_demand_merge_draft, purchase_demand_merge_batch cascade");
    }
  }

  /** 报价 id（替身返回的那一条）。 */
  static final UUID quoteId = UUID.randomUUID();

  /** 以某个身份跑一段工作（真实绑租户 ⇒ 真实 RLS）。 */
  <T> T as(String bearer, java.util.function.Supplier<T> work) {
    return access.run(actors.get(bearer), "trade.purchase.read", work);
  }

  /** 以某个身份跑一段工作（写权限码，真实绑租户）。 */
  <T> T asWrite(String bearer, java.util.function.Supplier<T> work) {
    return access.run(actors.get(bearer), "trade.purchase.update", work);
  }

  /**
   * 种一张采购单（头 + 一行；金额与行数由 DB 触发器算）。
   *
   * @param sourceType MANUAL（手工建单）/ PURCHASE_REQUEST_CONVERSION
   */
  static UUID seedOrder(
      UUID tenant, UUID owner, UUID dept, String orderNo, String currency, String quantity,
      String unitPrice, LocalDate promised, String supplierCode, String sourceType)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_order(id,tenant_id,order_no,source_type,source_request_no,"
                    + " supplier_ref_id,supplier_code,supplier_name,legal_entity_id,legal_entity_code,"
                    + " legal_entity_name,currency,status,promised_arrival_date,target_warehouse_id,"
                    + " target_warehouse_code,target_warehouse_name,owner_user_id,owner_department_id,"
                    + " functional_currency,fx_snapshot_at,created_by,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::varchar,?::varchar,'PR-TEST',"
                    + " ?::uuid,?::varchar,?::varchar,?::uuid,'US-LLC','美国子公司',?::varchar,'DRAFT',"
                    + " ?::date,?::uuid,'WH-LA-01','洛杉矶仓',?::uuid,?::uuid,?::varchar,now(),"
                    + " ?::uuid,now(),now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, orderNo);
      ps.setString(4, sourceType);
      ps.setObject(5, supplierRoleId);
      ps.setString(6, supplierCode);
      ps.setString(7, "供应商-" + supplierCode);
      ps.setObject(8, legalEntityId);
      ps.setString(9, currency);
      ps.setDate(10, java.sql.Date.valueOf(promised));
      ps.setObject(11, warehouseId);
      ps.setObject(12, owner);
      ps.setObject(13, dept);
      ps.setString(14, FUNCTIONAL_CURRENCY);
      ps.setObject(15, userA);
      ps.executeUpdate();
    }
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                    + " quantity,unit_code,unit_price,fx_rate_to_functional,fx_rate_base_currency,"
                    + " fx_rate_effective_date,price_source,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::uuid,1,?::uuid,?::varchar,?::varchar,?::numeric,'',"
                    + " ?::numeric,?::numeric,?::varchar,?::date,'REQUEST_ESTIMATE',now(),now())")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, tenant);
      ps.setObject(3, id);
      ps.setObject(4, UUID.nameUUIDFromBytes(("SKU-" + orderNo).getBytes()));
      ps.setString(5, "SKU-" + orderNo);
      ps.setString(6, "SKU-" + orderNo + " 名称");
      ps.setBigDecimal(7, new BigDecimal(quantity));
      ps.setBigDecimal(8, new BigDecimal(unitPrice));
      ps.setBigDecimal(9, FX_RATE);
      ps.setString(10, currency);
      ps.setDate(11, java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC)));
      ps.executeUpdate();
    }
    return id;
  }

  /**
   * 种一张 6.2.2.2 的合并草稿（批次 + 草稿 + 行）。
   *
   * @param unitPrice 草稿行的单价（null = 缺单价 ⇒ 转单必须被拒）
   * @param arrival 草稿行的期望到货日（null = 没有 ⇒ 转单必须被拒）
   * @param finalized 批次是否已定稿（定稿后草稿内容才冻结）
   */
  static UUID seedMergeDraft(
      UUID tenant, String tag, String currency, String unitPrice, LocalDate arrival, boolean finalized)
      throws Exception {
    UUID batchId = UUID.randomUUID();
    UUID draftId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    String batchNo = "DM-TEST-" + tag;
    String draftNo = "PD-TEST-" + tag;

    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_demand_merge_batch(id,tenant_id,batch_no,status,target_warehouse_id,"
                    + " target_warehouse_code,target_warehouse_name,legal_entity_id,legal_entity_code,"
                    + " legal_entity_name,currency_code,draft_count,line_count,unmerged_count,total_quantity,"
                    + " estimated_amount,source_request_ids,note,created_by,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::varchar,'OPEN',?::uuid,'WH-LA-01','洛杉矶仓',"
                    + " ?::uuid,'US-LLC','美国子公司',?::varchar,1,1,0,?::numeric,?::numeric,'{}',"
                    + " '测试',?::uuid,now(),now())")) {
      ps.setObject(1, batchId);
      ps.setObject(2, tenant);
      ps.setString(3, batchNo);
      ps.setObject(4, warehouseId);
      ps.setObject(5, legalEntityId);
      ps.setString(6, currency);
      ps.setBigDecimal(7, new BigDecimal("100"));
      ps.setBigDecimal(8, unitPrice == null ? null : new BigDecimal(unitPrice).multiply(new BigDecimal("100")));
      ps.setObject(9, userA);
      ps.executeUpdate();
    }

    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_demand_merge_draft(id,tenant_id,batch_id,draft_no,supplier_id,"
                    + " supplier_code,supplier_name,target_warehouse_id,target_warehouse_code,"
                    + " target_warehouse_name,legal_entity_id,legal_entity_code,legal_entity_name,"
                    + " currency_code,line_count,total_quantity,estimated_amount,status,note,created_by,"
                    + " created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::uuid,?::varchar,?::uuid,'SUP-TEST','测试供应商',"
                    + " ?::uuid,'WH-LA-01','洛杉矶仓',?::uuid,'US-LLC','美国子公司',?::varchar,"
                    + " 1,?::numeric,?::numeric,'DRAFT','测试',?::uuid,now(),now())")) {
      ps.setObject(1, draftId);
      ps.setObject(2, tenant);
      ps.setObject(3, batchId);
      ps.setString(4, draftNo);
      ps.setObject(5, supplierRoleId);
      ps.setObject(6, warehouseId);
      ps.setObject(7, legalEntityId);
      ps.setString(8, currency);
      ps.setBigDecimal(9, new BigDecimal("100"));
      ps.setBigDecimal(10, unitPrice == null ? null : new BigDecimal(unitPrice).multiply(new BigDecimal("100")));
      ps.setObject(11, userA);
      ps.executeUpdate();
    }

    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_demand_merge_line(id,tenant_id,draft_id,sku_id,sku_code,sku_name,"
                    + " supplier_id,merged_quantity,source_quantity,unit_price,line_amount,arrival_date,"
                    + " source_request_count,sort_rank,note,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::uuid,?::uuid,'SKU-1','测试商品',?::uuid,"
                    + " ?::numeric,?::numeric,?::numeric,?::numeric,?::date,1,0,'测试',now(),now())")) {
      ps.setObject(1, lineId);
      ps.setObject(2, tenant);
      ps.setObject(3, draftId);
      ps.setObject(4, UUID.nameUUIDFromBytes(("SKU-" + tag).getBytes()));
      ps.setObject(5, supplierRoleId);
      ps.setBigDecimal(6, new BigDecimal("100"));
      ps.setBigDecimal(7, new BigDecimal("100"));
      if (unitPrice == null) {
        ps.setNull(8, java.sql.Types.NUMERIC);
        ps.setNull(9, java.sql.Types.NUMERIC);
      } else {
        ps.setBigDecimal(8, new BigDecimal(unitPrice));
        ps.setBigDecimal(9, new BigDecimal(unitPrice).multiply(new BigDecimal("100")));
      }
      if (arrival == null) {
        ps.setNull(10, java.sql.Types.DATE);
      } else {
        ps.setDate(10, java.sql.Date.valueOf(arrival));
      }
      ps.executeUpdate();
    }

    if (finalized) {
      try (var c = admin();
          var ps =
              c.prepareStatement(
                  "update purchase_demand_merge_batch set status='FINALIZED',finalized_at=now(),"
                      + " finalized_by=?::uuid where id=?::uuid")) {
        ps.setObject(1, userA);
        ps.setObject(2, batchId);
        ps.executeUpdate();
      }
    }
    return draftId;
  }
}
