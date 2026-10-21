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
 * 6.3.1.1 采购单列表的<b>共享底座</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身</b>。跨服务一律走**真的本地 HTTP 服务**：
 *
 * <ul>
 *   <li><b>identity</b>：{@code GET /internal/access} —— 只回答「你是谁、在哪个租户、有哪些权限与范围」，
 *       租户隔离仍由 trade 侧的 RLS 在数据库层强制；</li>
 *   <li><b>catalog</b>：5.2.2.1 汇率查询 / 5.2.2.2 汇率台账（{@code GET /catalog/exchange-rates} 与
 *       {@code GET /catalog/exchange-rates/functional-currencies}）—— 只按上游契约形状返回数，
 *       <b>绝不</b>替业务逻辑做决定（汇率值由替身给出，但「取哪天的那条」由本模块决定）。</li>
 * </ul>
 *
 * <p><b>上游事实怎么来的</b>：6.2.2.1 的采购申请与转单事实、6.3.2.2 的到货跟踪**直接种进它们自己的表**
 * —— 本模块是它们的下游消费者，读它们的事实表正是本模块要证明的事；种数据用迁移用户（超级用户），
 * 生产运行角色 {@code trade_app} 对那些表只有上游授予的权限。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class PurchaseOrderListFixture {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID(), userC = UUID.randomUUID();
  static final UUID departmentA = UUID.randomUUID();
  static final UUID warehouseId = UUID.randomUUID();
  static final UUID legalEntityId = UUID.randomUUID();

  /** 本位币（法人档案的事实，catalog 拥有）。 */
  static final String FUNCTIONAL_CURRENCY = "CNY";

  /** 替身给出的汇率：1 USD = 7.2 CNY。 */
  static final BigDecimal FX_RATE = new BigDecimal("7.20000000");

  static HttpServer identity, catalog;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 置位后 catalog 一律回 503（模拟「汇率服务不可达」）。 */
  static final AtomicBoolean catalogDown = new AtomicBoolean();

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

      // ---- catalog：汇率台账 + 法人本位币 ----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/exchange-rates",
          e -> {
            if (catalogDown.get()) {
              write(catalog, e, 503, Map.of("message", "商品主数据服务暂时不可达"));
              return;
            }
            String path = e.getRequestURI().getPath();
            if (path.equals("/catalog/exchange-rates/functional-currencies")) {
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
            String query = e.getRequestURI().getRawQuery();
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
          });
      catalog.start();

      actors.put("Bearer tenant-a", actor(userA, tenantA, departmentA, "ALL"));
      actors.put("Bearer tenant-b", actor(userB, tenantB, null, "ALL"));
      // 同一个租户里的**另一个**用户：数据范围 SELF 时只看得到自己负责的单。
      actors.put("Bearer self-a", actor(userC, tenantA, departmentA, "SELF"));
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

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
          "truncate purchase_order_event, purchase_order_line, purchase_order,"
              + " purchase_arrival_event, purchase_arrival_disposition,"
              + " purchase_arrival_payable_confirmation, purchase_arrival_receipt,"
              + " purchase_arrival_tracking,"
              + " purchase_request_conversion, purchase_request_adjustment, purchase_request_line,"
              + " purchase_request cascade");
    }
  }

  /** 一条 6.2.2.1 的转单事实（连同它的申请单与申请行）。 */
  record SeededConversion(UUID conversionId, UUID requestId, String requestNo, UUID supplierId) {}

  /**
   * 种一张**已转单**的采购申请 + 申请行 + 转单事实（6.2.2.1 的事实）。
   *
   * @param unitPrice 申请行的预估单价（null = 这一行没有预估单价 ⇒ 转单时必须被拒）
   */
  static SeededConversion seedConversion(
      UUID tenant, String tag, String currency, String unitPrice, LocalDate arrival, UUID supplier)
      throws Exception {
    UUID requestId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID conversionId = UUID.randomUUID();
    String requestNo = "PR-TEST-" + tag;
    BigDecimal quantity = new BigDecimal("100");
    BigDecimal price = unitPrice == null ? null : new BigDecimal(unitPrice);
    BigDecimal amount = price == null ? null : quantity.multiply(price);

    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_request(id,tenant_id,request_no,source_type,target_warehouse_id,"
                    + " target_warehouse_code,target_warehouse_name,legal_entity_id,legal_entity_code,"
                    + " legal_entity_name,currency_code,estimated_amount,status,approval_required,"
                    + " created_by,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::varchar,'PLAN',?::uuid,'WH-LA-01','洛杉矶仓',"
                    + " ?::uuid,'US-LLC','美国子公司',?::varchar,?::numeric,'DRAFT',false,"
                    + " ?::uuid,now(),now())")) {
      ps.setObject(1, requestId);
      ps.setObject(2, tenant);
      ps.setString(3, requestNo);
      ps.setObject(4, warehouseId);
      ps.setObject(5, legalEntityId);
      ps.setString(6, currency);
      if (amount == null) {
        ps.setNull(7, java.sql.Types.NUMERIC);
      } else {
        ps.setBigDecimal(7, amount);
      }
      ps.setObject(8, userA);
      ps.executeUpdate();
    }

    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_request_line(id,tenant_id,request_id,sku_id,sku_code,sku_name,"
                    + " requested_quantity,unit_price,line_amount,supplier_id,supplier_code,supplier_name,"
                    + " requested_arrival_date,priority,sort_rank,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,"
                    + " ?::numeric,?::numeric,?::numeric,?::uuid,?::varchar,?::varchar,"
                    + " ?::date,'NORMAL',0,now(),now())")) {
      ps.setObject(1, lineId);
      ps.setObject(2, tenant);
      ps.setObject(3, requestId);
      ps.setObject(4, UUID.nameUUIDFromBytes(("SKU-" + tag).getBytes()));
      ps.setString(5, "SKU-" + tag);
      ps.setString(6, "SKU-" + tag + " 名称");
      ps.setBigDecimal(7, quantity);
      if (price == null) {
        ps.setNull(8, java.sql.Types.NUMERIC);
        ps.setNull(9, java.sql.Types.NUMERIC);
      } else {
        ps.setBigDecimal(8, price);
        ps.setBigDecimal(9, amount);
      }
      ps.setObject(10, supplier);
      ps.setString(11, "SUP-" + tag);
      ps.setString(12, "供应商-" + tag);
      ps.setDate(13, java.sql.Date.valueOf(arrival));
      ps.executeUpdate();
    }

    // 申请单走 DRAFT → APPROVED → CONVERTED（6.2.2.1 的状态机，
    // 且**提交后申请行即冻结** ⇒ 行必须在 DRAFT 状态下插进去）。
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_request set status='APPROVED' where id=?::uuid")) {
      ps.setObject(1, requestId);
      ps.executeUpdate();
    }
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "update purchase_request set status='CONVERTED', converted_at=now(), converted_by=?::uuid"
                    + " where id=?::uuid")) {
      ps.setObject(1, userA);
      ps.setObject(2, requestId);
      ps.executeUpdate();
    }

    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_request_conversion(id,tenant_id,request_id,request_line_id,"
                    + " supplier_id,supplier_code,supplier_name,converted_quantity,converted_arrival_date,"
                    + " target_warehouse_id,target_warehouse_code,target_warehouse_name,"
                    + " converted_by,converted_at,created_at)"
                    + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,"
                    + " ?::numeric,?::date,?::uuid,?::varchar,?::varchar,?::uuid,now(),now())")) {
      ps.setObject(1, conversionId);
      ps.setObject(2, tenant);
      ps.setObject(3, requestId);
      ps.setObject(4, lineId);
      ps.setObject(5, supplier);
      ps.setString(6, "SUP-" + tag);
      ps.setString(7, "供应商-" + tag);
      ps.setBigDecimal(8, quantity);
      ps.setDate(9, java.sql.Date.valueOf(arrival));
      ps.setObject(10, warehouseId);
      ps.setString(11, "WH-LA-01");
      ps.setString(12, "洛杉矶仓");
      ps.setObject(13, userA);
      ps.executeUpdate();
    }
    return new SeededConversion(conversionId, requestId, requestNo, supplier);
  }

  /**
   * 种一张 6.3.2.2 的到货跟踪（上游事实）。
   *
   * <p>⚠️ **已收数量只能通过「到货流水」进去**：6.3.2.2 的 BEFORE 触发器
   * {@code guard_purchase_arrival_tracking_head} 在 INSERT 时把 {@code received_qty} 强制改成 0，
   * AFTER 触发器 {@code apply_purchase_arrival_receipt} 才按流水把它算出来
   * （并把状态落成 PARTIALLY_RECEIVED / FULLY_RECEIVED）。
   * ⇒ 直接往头上写「已收 40」是写不进去的 —— 这正是 6.3.2.2「已收数量由 DB 从流水重算」的落点。
   *
   * @param orderNo 6.3.1.1 的采购单号（两个模块按<b>单号</b>串起来）
   * @param receivable 可收数量
   * @param received 本次登记的到货量（0 = 还没到货）
   */
  static UUID seedArrivalTracking(
      UUID tenant, String orderNo, String receivable, String received) throws Exception {
    UUID id = UUID.randomUUID();
    BigDecimal rec = new BigDecimal(receivable);
    BigDecimal got = new BigDecimal(received);
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_arrival_tracking(id,tenant_id,tracking_no,purchase_order_no,"
                    + " source_doc_no,source_doc_type,supplier_ref_id,supplier_code,supplier_name,"
                    + " receivable_qty,unit_price,currency,promised_arrival_date,arrival_status,"
                    + " created_by,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,'PURCHASE_ORDER',"
                    + " ?::uuid,'SUP-T','供应商-T',?::numeric,?::numeric,'USD',?::date,"
                    + " 'PENDING_SHIPMENT',?::uuid,now(),now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, "PA-TEST-" + orderNo);
      ps.setString(4, orderNo);
      ps.setString(5, orderNo);
      ps.setObject(6, UUID.randomUUID());
      ps.setBigDecimal(7, rec);
      ps.setBigDecimal(8, new BigDecimal("2.5"));
      ps.setDate(9, java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC)));
      ps.setObject(10, userA);
      ps.executeUpdate();
    }

    if (got.signum() > 0) {
      // 到货流水：序号与类型由 DB 排（守卫②）；warehouse 收货单号只作**来源快照**。
      try (var c = admin();
          var ps =
              c.prepareStatement(
                  "insert into purchase_arrival_receipt(id,tenant_id,tracking_id,arrived_qty,"
                      + " arrived_at,recorded_at,warehouse_receipt_code,warehouse_receipt_received_qty,"
                      + " warehouse_receipt_synced_at,note,recorded_by)"
                      + " values (?::uuid,?::uuid,?::uuid,?::numeric,now(),now(),?::varchar,?::numeric,"
                      + " now(),'上游事实',?::uuid)")) {
        ps.setObject(1, UUID.randomUUID());
        ps.setObject(2, tenant);
        ps.setObject(3, id);
        ps.setBigDecimal(4, got);
        ps.setString(5, "GR-TEST-" + orderNo);
        ps.setBigDecimal(6, got);
        ps.setObject(7, userA);
        ps.executeUpdate();
      }
    }
    return id;
  }

  /** 种一张采购单（头 + 一行；金额与行数由 DB 触发器算）。 */
  static UUID seedOrder(
      UUID tenant,
      UUID owner,
      UUID dept,
      String orderNo,
      String currency,
      String quantity,
      String unitPrice,
      LocalDate promised,
      String supplierCode)
      throws Exception {
    return seedOrder(tenant, owner, dept, orderNo, currency, quantity, unitPrice, promised,
        supplierCode, FX_RATE);
  }

  /**
   * 种一张采购单（头 + 一行；金额与行数由 DB 触发器算）。
   *
   * @param fxRate 汇率快照（null = 建单时**没有**冻结到汇率 ⇒ 本位币合计不参与它）
   */
  static UUID seedOrder(
      UUID tenant,
      UUID owner,
      UUID dept,
      String orderNo,
      String currency,
      String quantity,
      String unitPrice,
      LocalDate promised,
      String supplierCode,
      BigDecimal fxRate)
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
                    + " values (?::uuid,?::uuid,?::varchar,'PURCHASE_REQUEST_CONVERSION','PR-TEST',"
                    + " ?::uuid,?::varchar,?::varchar,?::uuid,'US-LLC','美国子公司',?::varchar,'DRAFT',"
                    + " ?::date,?::uuid,'WH-LA-01','洛杉矶仓',?::uuid,?::uuid,?::varchar,now(),"
                    + " ?::uuid,now(),now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, orderNo);
      ps.setObject(4, UUID.randomUUID());
      ps.setString(5, supplierCode);
      ps.setString(6, "供应商-" + supplierCode);
      ps.setObject(7, legalEntityId);
      ps.setString(8, currency);
      ps.setDate(9, java.sql.Date.valueOf(promised));
      ps.setObject(10, warehouseId);
      ps.setObject(11, owner);
      ps.setObject(12, dept);
      ps.setString(13, fxRate == null ? null : FUNCTIONAL_CURRENCY);
      ps.setObject(14, userA);
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
      if (fxRate == null) {
        ps.setNull(9, java.sql.Types.NUMERIC);
        ps.setNull(10, java.sql.Types.VARCHAR);
        ps.setNull(11, java.sql.Types.DATE);
      } else {
        ps.setBigDecimal(9, fxRate);
        ps.setString(10, currency);
        ps.setDate(11, java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC)));
      }
      ps.executeUpdate();
    }
    return id;
  }

  /** 以某个身份跑一段工作（真实绑租户 ⇒ 真实 RLS）。 */
  <T> T as(String bearer, java.util.function.Supplier<T> work) {
    return access.run(actors.get(bearer), "trade.purchase.read", work);
  }

  /** 以某个身份跑一段工作（写权限码，真实绑租户）。 */
  <T> T asWrite(String bearer, java.util.function.Supplier<T> work) {
    return access.run(actors.get(bearer), "trade.purchase.update", work);
  }
}
