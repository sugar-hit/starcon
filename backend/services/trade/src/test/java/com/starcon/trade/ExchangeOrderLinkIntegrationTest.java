package com.starcon.trade;

import static com.starcon.trade.ExchangeOrderLinkModels.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.3.2.2 换货新单的集成测试（Testcontainers 真实 PostgreSQL 16）。
 *
 * <p><b>本测试要守住的分界线</b>：换货新单必须是<b>真的销售单</b>，闭环状态必须是<b>读到的真事实</b>。
 * 最容易做成假的写法是记一行 new_order_no + status='SHIPPED'，界面上「换货已发货」——
 * 而 sales_order 里没有这张单、仓库也没收到任何需求。所以这里逐条断言：
 *
 * <ul>
 *   <li>建单后 sales_order 里真的多了一张单（行、地址快照、客户快照齐全，V99 的延迟触发器也会验收）；</li>
 *   <li>原订单<b>逐字节未变</b>（蓝图关系句：不修改原出库事实）；</li>
 *   <li>没有真实发货记录时，sync 之后状态<b>必须</b>还是「已建立」；</li>
 *   <li>绕过服务层直接写状态 / 直接插数量不对的明细行，<b>数据库本身</b>要拒。</li>
 * </ul>
 *
 * <p>租户隔离三条断言在 {@code ExchangeOrderLinkTenancyIntegrationTest} 里。
 */
@SpringBootTest
class ExchangeOrderLinkIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenantA = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();
  static final UUID membershipA = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer warehouse;
  static HttpServer catalog;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms = List.of("trade.aftersales.read", "trade.aftersales.write");

  /** 一条**写死的时刻**，用来钉「timestamptz 写回没有偏 −28800s」。 */
  static final OffsetDateTime SHIPPED_AT = OffsetDateTime.parse("2026-04-01T08:00:00+08:00");

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var found = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (found == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(found);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put(
          "Bearer tenant-a",
          new AccessService.Access(
              userA, tenantA, "Test", membershipA, null, perms,
              List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms))));
      identity.start();

      // 11.3.2.1 的授权入口要真打 warehouse：换货授权是真预占的，所以这里按契约形状应答。
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/inventory/sellable/skus",
          e -> {
            String sku = null;
            for (String kv : String.valueOf(e.getRequestURI().getRawQuery()).split("&")) {
              if (kv.startsWith("skuId=")) sku = kv.substring(6);
            }
            var row = new java.util.LinkedHashMap<String, Object>();
            row.put("skuId", sku);
            row.put("skuCode", "NEW-SKU");
            row.put("skuName", "替换商品");
            row.put("onHand", new BigDecimal("100"));
            row.put("reserved", BigDecimal.ZERO);
            row.put("available", new BigDecimal("100"));
            row.put("sellable", new BigDecimal("100"));
            send(e, Map.of("rows", List.of(row), "total", 1, "page", 0, "size", 1));
          });
      warehouse.createContext(
          "/warehouse/inventory/reservations",
          e -> {
            if ("POST".equals(e.getRequestMethod())) {
              send(
                  e,
                  Map.of(
                      "id", UUID.randomUUID().toString(),
                      "reservationNo", "RSV-" + UUID.randomUUID().toString().substring(0, 6),
                      "status", "HELD",
                      "statusLabel", "预占中"));
              return;
            }
            e.sendResponseHeaders(404, -1);
            e.close();
          });
      warehouse.createContext(
          "/warehouse/inventory/reservations/",
          e -> {
            e.getRequestBody().readAllBytes();
            String path = e.getRequestURI().getPath();
            int last = path.lastIndexOf('/');
            int prev = path.lastIndexOf('/', last - 1);
            send(
                e,
                Map.of(
                    "reservationId", path.substring(prev + 1, last),
                    "reservationNo", "RSV-T",
                    "terminal", true,
                    "statusBefore", "HELD",
                    "statusAfter", "RELEASED",
                    "restoredQuantity", "0.000000",
                    "replayed", false,
                    "message", "已释放"));
          });
      warehouse.start();

      // catalog：替换商品事实的唯一来源（11.3.2.1 授权时要校验在售）。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/skus/",
          e -> {
            String id = e.getRequestURI().getPath().substring("/catalog/skus/".length());
            send(e, Map.of("id", id, "code", "NEW-SKU", "name", "替换商品", "status", "ACTIVE"));
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
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    warehouse.stop(0);
    catalog.stop(0);
    pg.stop();
  }

  static void send(com.sun.net.httpserver.HttpExchange e, Object payload) throws java.io.IOException {
    byte[] body = json.writeValueAsBytes(payload);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, body.length);
    e.getResponseBody().write(body);
    e.close();
  }

  @Autowired ExchangeOrderLinkService links;
  @Autowired ExchangeAuthorizationService exchanges;
  @Autowired AccessService access;
  @Autowired DSLContext sql;

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate exchange_order_link_event, exchange_order_link_line, exchange_order_link,"
              + " exchange_return_requirement, exchange_outbound_demand, exchange_reservation,"
              + " exchange_authorization_line, exchange_authorization,"
              + " aftersales_case_line, aftersales_case, shipment_record, sales_order cascade");
    }
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.aftersales.write", work);
  }

  Record one(String query, Object... binds) {
    var r = sql.fetch(query, binds);
    return r.isEmpty() ? null : r.get(0);
  }

  ExchangeOrderQuery query(int page, int size) {
    return new ExchangeOrderQuery(null, null, null, null, null, null, page, size);
  }

  // ================================================================ 素材

  record Seed(UUID orderId, UUID caseId, UUID caseLineId, UUID originalSkuId) {}

  /** 一张完整的原订单：单 + 行 + 收货地址快照 + 客户快照（换货新单要照抄后两者）。 */
  Seed seedOrder(String suffix) {
    UUID orderId = UUID.randomUUID();
    UUID skuId = UUID.randomUUID();
    var t = OffsetDateTime.parse("2026-03-01T09:00:00+08:00");
    asA(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,"
                  + "external_order_no,currency,payment_method,payment_terms_days,status,"
                  + "submitted_at,subtotal_amount,discount_amount,tax_amount,shipping_amount,"
                  + "other_amount,grand_total_amount,note,version,created_at,updated_at)"
                  + " values(?,?::uuid,?,'AMAZON','',?,'USD','PREPAID',0,'SUBMITTED',?::timestamptz,"
                  + "120.0000,0,0,0,0,120.0000,'',0,?::timestamptz,?::timestamptz)",
              orderId, tenantA, "SO-" + suffix, "EXT-" + suffix, t, t, t);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,1,?::uuid,?,?,2,60.0000,0,0,120.0000,'',"
                  + "?::timestamptz)",
              UUID.randomUUID(), tenantA, orderId, skuId, "ORIG-" + suffix, "原商品", t);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), tenantA, orderId, "买家" + suffix, "buyer@example.com", t);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,'INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), tenantA, orderId, UUID.randomUUID(), "C-" + suffix,
              "买家" + suffix, t);
          return null;
        });
    return new Seed(orderId, null, null, skuId);
  }

  /** 一张换货售后单 + 售后商品行。 */
  Seed seedCase(Seed order, String suffix) {
    UUID caseId = UUID.randomUUID();
    UUID caseLineId = UUID.randomUUID();
    var t = OffsetDateTime.parse("2026-03-02T09:00:00+08:00");
    asA(
        () -> {
          sql.execute(
              "insert into aftersales_case(id,tenant_id,case_no,order_id,case_type,status,"
                  + "reason_code,requested_amount,currency,requested_quantity,buyer_request,"
                  + "reply_due_at,received_at,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?,?::uuid,'EXCHANGE','RECEIVED','SIZE_MISMATCH',"
                  + "120.0000,'USD',2.000000,?,?::timestamptz,?::timestamptz,?::timestamptz,"
                  + "?::timestamptz)",
              caseId, tenantA, "AS-" + suffix, order.orderId(), "尺码不对", t, t, t, t);
          sql.execute(
              "insert into aftersales_case_line(id,tenant_id,case_id,order_line_id,quantity,"
                  + "requested_amount,currency,buyer_note,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,(select id from sales_order_line"
                  + " where tenant_id=?::uuid and order_id=?::uuid limit 1),2.000000,null,null,"
                  + "'尺码不对',?::timestamptz)",
              caseLineId, tenantA, caseId, tenantA, order.orderId(), t);
          return null;
        });
    return new Seed(order.orderId(), caseId, caseLineId, order.originalSkuId());
  }

  /** 走 11.3.2.1 **自己的**授权入口造出一张换货授权单（不在测试里直接 INSERT 授权表）。 */
  ExchangeAuthorizationModels.ExchangeDetail authorized(String suffix) {
    Seed seed = seedCase(seedOrder(suffix), suffix);
    return asA(
        () ->
            exchanges.authorize(
                new ExchangeAuthorizationModels.ExchangeAuthorizeRequest(
                    seed.caseId(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    List.of(
                        new ExchangeAuthorizationModels.ExchangeLineRequest(
                            seed.caseLineId(), UUID.randomUUID(), new BigDecimal("2"), "换大一码")),
                    false,
                    "集成用例授权"),
                userA,
                "Bearer tenant-a"));
  }

  ExchangeOrderDetail createLink(String suffix) {
    var auth = authorized(suffix);
    return asA(
        () -> links.create(new ExchangeOrderCreateRequest(auth.authorization().id(), "换货新单"), userA,
            membershipA));
  }

  /** 落一条**真实**发货记录（9.4.1.1 拥有的事实；本服务只读它）。 */
  void shipmentFor(UUID newOrderId, String trackingStatus) {
    asA(
        () -> {
          sql.execute(
              "insert into shipment_record(id,tenant_id,record_no,source_handover_id,"
                  + "source_packing_order_id,source_outbound_order_id,warehouse_id,carrier_code,"
                  + "carrier_name,master_waybill_no,shipped_at,parcel_count,parcel_item_count,"
                  + "total_quantity,sales_order_id,status,tracking_status,synced_at)"
                  + " values(?::uuid,?::uuid,?,?::uuid,?::uuid,?::uuid,?::uuid,'FDX','FedEx',"
                  + "?,?::timestamptz,1,1,2,?::uuid,?,?,?::timestamptz)",
              UUID.randomUUID(), tenantA, "SR-" + UUID.randomUUID().toString().substring(0, 8),
              UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
              "WB-" + UUID.randomUUID().toString().substring(0, 8), SHIPPED_AT,
              newOrderId, "SHIPPED", trackingStatus, SHIPPED_AT);
          return null;
        });
  }

  // ================================================================ 建单：必须是真单

  @Test
  void 建单真的在SalesOrder里建出一张新单() {
    var detail = createLink("REAL");
    var link = detail.link();

    // 正对照：授权行确实存在，数量是 2 —— 后面所有断言都以此为基准。
    var authLine =
        asA(
            () ->
                one(
                    "select authorized_quantity from exchange_authorization_line where id = ?::uuid",
                    detail.lines().get(0).authorizationLineId()));
    assertThat(authLine.get(0, BigDecimal.class)).isEqualByComparingTo("2.000000");

    var order =
        asA(
            () ->
                one(
                    "select order_no,order_source,manual_kind,currency,payment_method,status,"
                        + "grand_total_amount,subtotal_amount,payment_terms_days"
                        + " from sales_order where id = ?::uuid",
                    link.newOrderId()));

    assertThat(order).as("换货新单必须真的在 sales_order 里有一行").isNotNull();
    assertThat(order.get("order_no", String.class)).startsWith("EXO-");
    assertThat(order.get("order_source", String.class)).isEqualTo("MANUAL");
    // ⚠️ EXCHANGE 这个值是 V167 第六节加进去的：塞 SAMPLE 会让订单列表把换货单显示成「样机单」。
    assertThat(order.get("manual_kind", String.class)).isEqualTo("EXCHANGE");
    assertThat(order.get("currency", String.class)).isEqualTo("USD");
    assertThat(order.get("status", String.class)).as("建单时是草稿，提交才进审单队列").isEqualTo("DRAFT");
    // 换货不二次收费：金额恒 0，且这是 CHECK 钉死的，不是服务层自觉。
    assertThat(order.get("grand_total_amount", BigDecimal.class)).isEqualByComparingTo("0");
    assertThat(order.get("payment_terms_days", Integer.class)).isZero();

    // 商品行真的建出来了，数量恒等于授权量（触发器钉死）。
    var line =
        asA(
            () ->
                one(
                    "select quantity,unit_price,line_amount from sales_order_line where id = ?::uuid",
                    detail.lines().get(0).newOrderLineId()));
    assertThat(line.get("quantity", BigDecimal.class))
        .as("新单行的量必须恒等于授权量：少发是只发不收，多发是凭空多发货")
        .isEqualByComparingTo("2.000000");
    assertThat(line.get("unit_price", BigDecimal.class)).isEqualByComparingTo("0");

    // 数量在 DB 上就是 numeric(19,6)：断言真实写回值，不只看字符串。
    var scale =
        asA(
            () ->
                one(
                    "select total_quantity from exchange_order_link where id = ?::uuid",
                    link.id()));
    assertThat(scale.get(0, BigDecimal.class)).isEqualByComparingTo("2.000000");
    assertThat(link.totalQuantity()).isEqualTo("2.000000");
    assertThat(link.newOrderAmount()).isEqualTo("0.0000");
    assertThat(link.lineCount()).isEqualTo(1);
  }

  @Test
  void 新单快照照抄原单且原单逐字节未变() {
    Seed order = seedOrder("ORIGIN");
    var before =
        asA(
            () ->
                one(
                    "select order_no,order_source,manual_kind,currency,payment_method,status,"
                        + "subtotal_amount,grand_total_amount,version from sales_order where id = ?::uuid",
                    order.orderId()));

    Seed seededCase = seedCase(order, "SNAP");
    var auth =
        asA(
            () ->
                exchanges.authorize(
                    new ExchangeAuthorizationModels.ExchangeAuthorizeRequest(
                        seededCase.caseId(), UUID.randomUUID(), UUID.randomUUID(),
                        List.of(
                            new ExchangeAuthorizationModels.ExchangeLineRequest(
                                seededCase.caseLineId(), UUID.randomUUID(), new BigDecimal("2"), "换码")),
                        false, "快照用例"),
                    userA, "Bearer tenant-a"));
    var detail = asA(() -> links.create(new ExchangeOrderCreateRequest(auth.authorization().id(), ""),
        userA, membershipA));

    // 收货地址快照与客户快照逐列照抄原单 —— 换货发给同一个买家。
    var copied =
        asA(
            () ->
                one(
                    "select recipient_name,recipient_phone,country_code,postcode,address_line1"
                        + " from order_address_snapshot"
                        + " where order_id = ?::uuid and address_role = 'SHIPPING'",
                    detail.link().newOrderId()));
    var origin =
        asA(
            () ->
                one(
                    "select recipient_name,recipient_phone,country_code,postcode,address_line1"
                        + " from order_address_snapshot"
                        + " where order_id = ?::uuid and address_role = 'SHIPPING'",
                    order.orderId()));
    assertThat(copied.get(0, String.class)).isEqualTo(origin.get(0, String.class));
    assertThat(copied.get(4, String.class)).isEqualTo(origin.get(4, String.class));

    var copiedParty =
        asA(
            () ->
                one(
                    "select customer_code,customer_name from sales_order_party_snapshot"
                        + " where order_id = ?::uuid",
                    detail.link().newOrderId()));
    assertThat(copiedParty.get(1, String.class)).isEqualTo("买家ORIGIN");

    // 蓝图关系句：「换货新单不修改原出库事实，原单保持完整可追溯」—— 逐列断言原单未变。
    var after =
        asA(
            () ->
                one(
                    "select order_no,order_source,manual_kind,currency,payment_method,status,"
                        + "subtotal_amount,grand_total_amount,version from sales_order where id = ?::uuid",
                    order.orderId()));
    assertThat(after.get(0, String.class)).isEqualTo(before.get(0, String.class));
    assertThat(after.get(1, String.class)).isEqualTo(before.get(1, String.class));
    assertThat(after.get(2, String.class)).isEqualTo(before.get(2, String.class));
    assertThat(after.get(3, String.class)).isEqualTo(before.get(3, String.class));
    assertThat(after.get(4, String.class)).isEqualTo(before.get(4, String.class));
    assertThat(after.get(5, String.class)).isEqualTo(before.get(5, String.class));
    assertThat(after.get(6, BigDecimal.class)).isEqualByComparingTo("120.0000");
    assertThat(after.get(7, BigDecimal.class)).isEqualByComparingTo("120.0000");
    assertThat(after.get(8, Integer.class)).isEqualTo(before.get(8, Integer.class));

    // 原单追溯在关联头上仍然查得到。
    assertThat(detail.link().originalOrderId()).isEqualTo(order.orderId());
    assertThat(detail.link().originalOrderNo()).isEqualTo("SO-ORIGIN");
    assertThat(detail.lines().get(0).originalSkuId()).isEqualTo(order.originalSkuId());
  }

  // ================================================================ 防重复换货

  @Test
  void 同一张换货授权只能建一张换货新单() {
    var auth = authorized("DUP");
    asA(() -> links.create(new ExchangeOrderCreateRequest(auth.authorization().id(), "第一次"),
        userA, membershipA));

    assertThatThrownBy(
            () -> asA(() -> links.create(
                new ExchangeOrderCreateRequest(auth.authorization().id(), "第二次"), userA,
                membershipA)))
        .hasMessageContaining("不能重复换货");

    // 第二次不能留下任何多余的订单 —— 防线在发出建单副作用**之前**。
    assertThat(asA(() -> sql.fetch("select id from sales_order where manual_kind = 'EXCHANGE'").size()))
        .as("重复请求不许建出第二张换货新单")
        .isEqualTo(1);
  }

  @Test
  void 已撤销的换货授权不能建换货新单() {
    var auth = authorized("CANCELLED-AUTH");
    asA(
        () ->
            exchanges.cancel(
                auth.authorization().id(),
                new ExchangeAuthorizationModels.ExchangeCancelRequest(auth.authorization().version(),
                    "撤销授权"),
                userA,
                "Bearer tenant-a"));

    assertThatThrownBy(
            () -> asA(() -> links.create(
                new ExchangeOrderCreateRequest(auth.authorization().id(), ""), userA, membershipA)))
        .hasMessageContaining("已撤销");
  }

  @Test
  void 原单没有收货地址快照时建单被拒而不是编一个地址() {
    UUID orderId = UUID.randomUUID();
    var t = OffsetDateTime.parse("2026-03-01T09:00:00+08:00");
    asA(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,"
                  + "external_order_no,currency,payment_method,payment_terms_days,status,"
                  + "created_at,updated_at)"
                  + " values(?::uuid,?::uuid,'SO-NOADDR','AMAZON','','E1','USD','PREPAID',0,"
                  + "'DRAFT',?::timestamptz,?::timestamptz)",
              orderId, tenantA, t, t);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,1,?::uuid,'S1','商品',1,10.0000,0,0,10.0000,"
                  + "'',?::timestamptz)",
              UUID.randomUUID(), tenantA, orderId, UUID.randomUUID(), t);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?::uuid,'C1','买家','INDIVIDUAL','',"
                  + "?::timestamptz)",
              UUID.randomUUID(), tenantA, orderId, UUID.randomUUID(), t);
          return null;
        });
    // 售后单仍要能建出来，好让用例只命中「没有地址」这一个原因。
    UUID caseId = UUID.randomUUID();
    UUID caseLineId = UUID.randomUUID();
    asA(
        () -> {
          sql.execute(
              "insert into aftersales_case(id,tenant_id,case_no,order_id,case_type,status,"
                  + "reason_code,requested_amount,currency,requested_quantity,buyer_request,"
                  + "reply_due_at,received_at,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,'AS-NOADDR',?::uuid,'EXCHANGE','RECEIVED',"
                  + "'SIZE_MISMATCH',10.0000,'USD',1.000000,'换码',?::timestamptz,?::timestamptz,"
                  + "?::timestamptz,?::timestamptz)",
              caseId, tenantA, orderId, t, t, t, t);
          sql.execute(
              "insert into aftersales_case_line(id,tenant_id,case_id,order_line_id,quantity,"
                  + "requested_amount,currency,buyer_note,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,(select id from sales_order_line"
                  + " where tenant_id=?::uuid and order_id=?::uuid limit 1),1.000000,null,null,"
                  + "'',?::timestamptz)",
              caseLineId, tenantA, caseId, tenantA, orderId, t);
          return null;
        });
    var auth =
        asA(
            () ->
                exchanges.authorize(
                    new ExchangeAuthorizationModels.ExchangeAuthorizeRequest(
                        caseId, UUID.randomUUID(), UUID.randomUUID(),
                        List.of(
                            new ExchangeAuthorizationModels.ExchangeLineRequest(
                                caseLineId, UUID.randomUUID(), new BigDecimal("1"), "换码")),
                        false, "无地址用例"),
                    userA, "Bearer tenant-a"));

    assertThatThrownBy(
            () -> asA(() -> links.create(
                new ExchangeOrderCreateRequest(auth.authorization().id(), ""), userA, membershipA)))
        .hasMessageContaining("收货地址快照");
  }

  // ================================================================ 提交

  @Test
  void 提交把新单真的推进到审单队列且版本冲突被拒() {
    var detail = createLink("SUBMIT");
    int version = detail.link().version();

    var submitted = asA(() -> links.submit(detail.link().id(),
        new ExchangeOrderSubmitRequest(version, "提交"), membershipA));

    assertThat(submitted.link().newOrderStatus()).isEqualTo("SUBMITTED");
    assertThat(submitted.link().newOrderStatusLabel()).isEqualTo("已提交待审");
    assertThat(submitted.link().version()).isEqualTo(version + 1);

    // 正对照：sales_order 上真的推进了（V99 的 SUBMITTED 必须带提交时刻）。
    var order =
        asA(
            () ->
                one("select status,submitted_at from sales_order where id = ?::uuid",
                    detail.link().newOrderId()));
    assertThat(order.get(0, String.class)).isEqualTo("SUBMITTED");
    assertThat(order.get("submitted_at", OffsetDateTime.class)).isNotNull();

    // 关联头上的 submitted_at 也要与新单同一时刻量级（timestamptz 绑定没偏 8 小时）。
    assertThat(submitted.link().submittedAt()).isNotNull();
    assertThat(submitted.link().submittedAt().toInstant())
        .isCloseTo(java.time.Instant.now(), org.assertj.core.api.Assertions.within(5, java.time.temporal.ChronoUnit.MINUTES));

    // 拿着旧版本号再来一次必须被拒。
    assertThatThrownBy(() -> asA(() -> links.submit(detail.link().id(),
        new ExchangeOrderSubmitRequest(version, "重复提交"), membershipA)))
        .hasMessageContaining("已被其他人改动");

    // 缺 expectedVersion 也拒。
    assertThatThrownBy(
            () -> asA(() -> links.submit(detail.link().id(),
                new ExchangeOrderSubmitRequest(null, "无版本"), membershipA)))
        .hasMessageContaining("必须给 expectedVersion");
  }

  // ================================================================ 闭环：只认真实事实

  @Test
  void 没有真实发货记录时同步闭环状态必须还是已建立() {
    var detail = createLink("NOSHIP");
    var submitted = asA(() -> links.submit(detail.link().id(),
        new ExchangeOrderSubmitRequest(detail.link().version(), "提交"), membershipA));

    var synced = asA(() -> links.sync(submitted.link().id(),
        new ExchangeOrderSyncRequest(submitted.link().version(), "同步"), membershipA));

    assertThat(synced.link().closureStatus())
        .as("读不到发货记录就绝不能显示已发出 —— 那正是假交付")
        .isEqualTo("CREATED");
    assertThat(synced.link().shipmentCount()).isZero();
    assertThat(synced.link().firstShippedAt()).isNull();
    assertThat(synced.link().lastTrackingStatus()).isEqualTo("UNSET");

    // 而且必须说清「还差什么」，否则页面只能显示一个灰状态。
    assertThat(synced.closureBlockers()).isNotEmpty();
    assertThat(String.join("；", synced.closureBlockers())).contains("发货记录");
    assertThat(String.join("；", synced.closureBlockers())).contains("旧货");
  }

  @Test
  void 读到真实发货记录才推进为已发出且时间戳没偏八小时() {
    var detail = createLink("SHIP");
    var submitted = asA(() -> links.submit(detail.link().id(),
        new ExchangeOrderSubmitRequest(detail.link().version(), "提交"), membershipA));
    shipmentFor(detail.link().newOrderId(), "IN_TRANSIT");

    var synced = asA(() -> links.sync(submitted.link().id(),
        new ExchangeOrderSyncRequest(submitted.link().version(), "同步"), membershipA));

    assertThat(synced.link().closureStatus()).isEqualTo("DISPATCHED");
    assertThat(synced.link().shipmentCount()).isEqualTo(1);
    assertThat(synced.link().lastTrackingStatus()).isEqualTo("IN_TRANSIT");
    assertThat(synced.link().shipmentRecordNo()).startsWith("SR-");

    // ⚠️ 断言**写回的值**：SHIPPED_AT 是 2026-04-01T08:00+08:00，即 UTC 的 00:00。
    // 若用 Timestamp.valueOf(LocalDateTime) 绑定，这里会偏成 −28800s（变成 3-31 的时刻）。
    // ⚠️ 比的是**时刻**而不是 OffsetDateTime 对象：PG 按会话时区返回，offset 不同但同一瞬间是同一事实。
    assertThat(synced.link().firstShippedAt().toInstant()).isEqualTo(SHIPPED_AT.toInstant());
    var stored =
        asA(
            () ->
                one("select first_shipped_at from exchange_order_link where id = ?::uuid",
                    detail.link().id()));
    assertThat(stored.get(0, OffsetDateTime.class).toInstant())
        .as("timestamptz 必须原样写回，不能偏 8 小时")
        .isEqualTo(SHIPPED_AT.toInstant());
  }

  @Test
  void 旧货还在等回寄时不给闭环免回之后才闭环() {
    var detail = createLink("CLOSE");
    var submitted = asA(() -> links.submit(detail.link().id(),
        new ExchangeOrderSubmitRequest(detail.link().version(), "提交"), membershipA));
    shipmentFor(detail.link().newOrderId(), "DELIVERED");

    var dispatched = asA(() -> links.sync(submitted.link().id(),
        new ExchangeOrderSyncRequest(submitted.link().version(), "同步"), membershipA));
    assertThat(dispatched.link().closureStatus())
        .as("还有旧货在等回寄就不算闭环：只发不收就是白送")
        .isEqualTo("DISPATCHED");
    assertThat(dispatched.link().oldGoodsReturnStatus()).isEqualTo("WAITING");

    // 经批准免回（11.2 的实收状态机未交付，免回是当前唯一可达的「旧货结清」）。
    asA(
        () -> {
          sql.execute(
              "update exchange_return_requirement set status='WAIVED', waived_by=?::uuid,"
                  + " waived_at=?::timestamptz where tenant_id=?::uuid and authorization_id=?::uuid",
              userA, SHIPPED_AT, tenantA, submitted.link().authorizationId());
          return null;
        });

    var closed = asA(() -> links.sync(dispatched.link().id(),
        new ExchangeOrderSyncRequest(dispatched.link().version(), "再同步"), membershipA));
    assertThat(closed.link().closureStatus()).isEqualTo("CLOSED");
    assertThat(closed.link().closedAt()).isNotNull();
    assertThat(closed.link().oldGoodsReturnStatus()).isEqualTo("WAIVED");
    assertThat(closed.closureBlockers()).isEmpty();
  }

  // ================================================================ 撤销

  @Test
  void 撤销只允许草稿且从未发货并同时取消发货需求() {
    var detail = createLink("CANCEL");

    var cancelled = asA(() -> links.cancel(detail.link().id(),
        new ExchangeOrderCancelRequest(detail.link().version(), "建错了"), userA, membershipA));

    assertThat(cancelled.link().closureStatus()).isEqualTo("CANCELLED");
    assertThat(cancelled.link().cancelledAt()).isNotNull();
    assertThat(cancelled.link().cancelReason()).isEqualTo("建错了");

    // 新单都不发了，发货需求必须一起取消，否则 9.3.3.1 会为一件不该发的货建出库单。
    var demand =
        asA(
            () ->
                one(
                    "select status from exchange_outbound_demand where tenant_id=?::uuid"
                        + " and authorization_id=?::uuid",
                    tenantA, detail.link().authorizationId()));
    assertThat(demand.get(0, String.class)).isEqualTo("CANCELLED");

    // 重复撤销被拒。
    assertThatThrownBy(() -> asA(() -> links.cancel(detail.link().id(),
        new ExchangeOrderCancelRequest(cancelled.link().version(), "再撤"), userA, membershipA)))
        .hasMessageContaining("已撤销过");
  }

  @Test
  void 已提交的新单不能在这里撤销() {
    var detail = createLink("CANCEL-SUBMITTED");
    var submitted = asA(() -> links.submit(detail.link().id(),
        new ExchangeOrderSubmitRequest(detail.link().version(), "提交"), membershipA));

    assertThatThrownBy(() -> asA(() -> links.cancel(submitted.link().id(),
        new ExchangeOrderCancelRequest(submitted.link().version(), "撤"), userA, membershipA)))
        .hasMessageContaining("已提交进审单队列");
  }

  @Test
  void 已经发货的新单不能撤销() {
    // 刻意让新单停在草稿态、却已经存在真实发货记录：这在正常流程里不该发生
    // （草稿没进审单队列），但如果它真的发生了，撤销必须被挡住 ——
    // 「撤了单、货已经发出去了」是只发不收的另一个入口。
    var detail = createLink("CANCEL-SHIPPED");
    shipmentFor(detail.link().newOrderId(), "IN_TRANSIT");

    assertThatThrownBy(() -> asA(() -> links.cancel(detail.link().id(),
        new ExchangeOrderCancelRequest(detail.link().version(), "撤"), userA, membershipA)))
        .hasMessageContaining("已经有真实发货记录");
  }

  // ================================================================ DB 层防线

  @Test
  void 绕过服务层直接把状态改成已发出会被数据库拒掉() {
    var detail = createLink("FORGE");
    var submitted = asA(() -> links.submit(detail.link().id(),
        new ExchangeOrderSubmitRequest(detail.link().version(), "提交"), membershipA));

    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update exchange_order_link set closure_status='DISPATCHED',"
                              + " dispatched_at=?::timestamptz where tenant_id=?::uuid and id=?::uuid",
                          SHIPPED_AT, tenantA, detail.link().id());
                      return null;
                    }))
        .hasMessageContaining("不能标记为已发出");
  }

  @Test
  void 绕过服务层把新单行的量改成不等于授权量会被数据库拒掉() {
    var detail = createLink("BADQTY");
    UUID linkId = detail.link().id();
    UUID authLineId = detail.lines().get(0).authorizationLineId();
    UUID newLineId = detail.lines().get(0).newOrderLineId();

    // 真防线在**新单行**上：把它的数量改成 3（授权量是 2），再让明细指向它。
    // 新单还在草稿态，V99 的冻结触发器只拦 SUBMITTED，所以这一步本身合法 ——
    // 正因如此才需要一个独立的数量守恒防线。
    asA(
        () -> {
          sql.execute(
              "update sales_order_line set quantity = 3 where tenant_id=?::uuid and id=?::uuid",
              tenantA, newLineId);
          return null;
        });

    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into exchange_order_link_line(id,tenant_id,link_id,"
                              + "authorization_line_id,new_order_line_id,new_order_line_no,sku_id,"
                              + "sku_code,sku_name,original_sku_id,original_sku_code,quantity,created_at)"
                              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,1,?::uuid,'X','X',"
                              + "?::uuid,'X',3,?::timestamptz)",
                          UUID.randomUUID(), tenantA, linkId, authLineId, newLineId,
                          UUID.randomUUID(), UUID.randomUUID(), SHIPPED_AT);
                      return null;
                    }))
        .hasMessageContaining("新单行数量");
  }

  @Test
  void 明细行上乱填的数量会被纠正成授权量而不是被信任() throws Exception {
    var detail = createLink("CORRECTED");
    UUID linkId = detail.link().id();

    // 明细的 quantity 列本身**不可信**：触发器一律以授权行的授权量覆盖它
    // （与 V162 用售后行覆盖 original_quantity 同一做法）。
    // 这里传 99，落库必须是 2 —— 「填多少收多少」会让换货量与授权量出现第二个答案。
    //
    // ⚠️ 删与插必须在**同一个事务**里：单独提交那次 DELETE 会让
    // trg_exchange_order_link_complete 在提交时发现「登记 1 行、实际 0 行」而报错。
    // 运行角色本来就没有 DELETE 权，所以这半段用超级用户直连做。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      c.setAutoCommit(false);
      try (var st = c.createStatement()) {
        st.execute("select set_config('app.current_tenant','" + tenantA + "',false)");
        st.execute("delete from exchange_order_link_line where link_id = '" + linkId + "'");
        st.execute(
            "insert into exchange_order_link_line(id,tenant_id,link_id,authorization_line_id,"
                + "new_order_line_id,new_order_line_no,sku_id,sku_code,sku_name,original_sku_id,"
                + "original_sku_code,quantity,created_at)"
                + " values('"
                + UUID.randomUUID()
                + "','"
                + tenantA
                + "','"
                + linkId
                + "','"
                + detail.lines().get(0).authorizationLineId()
                + "','"
                + detail.lines().get(0).newOrderLineId()
                + "',9,'"
                + UUID.randomUUID()
                + "','X','X','"
                + UUID.randomUUID()
                + "','X',99,'"
                + SHIPPED_AT
                + "')");
      }
      c.commit();
    }

    var stored =
        asA(
            () ->
                one(
                    "select quantity, new_order_line_no from exchange_order_link_line"
                        + " where tenant_id=?::uuid and link_id=?::uuid",
                    tenantA, linkId));
    assertThat(stored.get(0, BigDecimal.class))
        .as("明细数量必须被纠正成授权量 2，而不是采信调用方填的 99")
        .isEqualByComparingTo("2.000000");
    assertThat(stored.get(1, Integer.class))
        .as("行号同样以新单上的真实行号为准")
        .isEqualTo(1);
  }

  @Test
  void 闭环事件流是appendOnly改不动() {
    var detail = createLink("EVENT");
    assertThat(detail.events()).isNotEmpty();

    // 第一层：运行角色根本没有改写权（V167 的 REVOKE UPDATE,DELETE）。
    // ⚠️ jOOQ 把 42501 包装成「bad SQL grammar」，所以要断言**根因**里的原始消息。
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update exchange_order_link_event set detail = '篡改' where tenant_id=?::uuid",
                          tenantA);
                      return null;
                    }))
        .hasStackTraceContaining("permission denied for table exchange_order_link_event");

    // 第二层：**绕过权限用超级用户直连 SQL 也一样被拒** ——
    // 只有触发器能挡住的那一层才真的挡住了「谁都能改历史」这件事。
    assertThatThrownBy(
            () -> {
              try (var c =
                      DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                try (var st =
                    c.createStatement()) {
                  st.execute("select set_config('app.current_tenant','" + tenantA + "',false)");
                }
                try (var st =
                    c.createStatement()) {
                  st.execute("update exchange_order_link_event set detail = '超级用户篡改'");
                }
              }
            })
        .hasMessageContaining("不可改写的留痕表");
  }

  @Test
  void 迁移确实落地且三张表都强制rls() {
    for (String table :
        List.of("exchange_order_link", "exchange_order_link_line", "exchange_order_link_event")) {
      var row = one("select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V167 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " RLS 未启用").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住 owner").isTrue();
      assertThat(
              one("select has_table_privilege('trade_app',?,'SELECT')", table).get(0, Boolean.class))
          .as("trade_app 必须真的能读 " + table)
          .isTrue();
    }
  }

  // ================================================================ 列表 / 下拉 / 能力声明

  @Test
  void 列表是真分页且汇总与筛选同源() {
    var first = createLink("L1");
    createLink("L2");
    createLink("L3");

    var page0 = asA(() -> links.list(query(0, 2)));
    assertThat(page0.items()).hasSize(2);
    assertThat(page0.total()).isEqualTo(3);
    // ⚠️ 汇总必须带同一个筛选：「第一页 2 条却说有 3 条」之外还要保证汇总是全量口径。
    assertThat(page0.summary().totalCount()).isEqualTo(3);
    assertThat(page0.summary().createdCount()).isEqualTo(3);
    assertThat(page0.summary().pendingDispatchCount()).isEqualTo(3);

    var page1 = asA(() -> links.list(query(1, 2)));
    assertThat(page1.items()).hasSize(1);
    assertThat(page1.total()).isEqualTo(3);

    // 按关联筛。
    var filtered =
        asA(() -> links.list(new ExchangeOrderQuery(first.link().authorizationId().toString(),
            null, null, null, null, null, 0, 20)));
    assertThat(filtered.total()).isEqualTo(1);

    // 按闭环状态筛。
    var closed =
        asA(() -> links.list(new ExchangeOrderQuery(null, null, null, "CLOSED", null, null, 0, 20)));
    assertThat(closed.total()).isZero();
  }

  @Test
  void 关键字能搜到换货新单号新销售单号与替换sku() {
    var detail = createLink("KW");
    String linkNo = detail.link().linkNo();
    String orderNo = detail.link().newOrderNo();

    assertThat(asA(() -> links.list(new ExchangeOrderQuery(null, null, null, null, linkNo, null,
        0, 20))).total()).isEqualTo(1);
    assertThat(asA(() -> links.list(new ExchangeOrderQuery(null, null, null, null, orderNo, null,
        0, 20))).total()).isEqualTo(1);
    assertThat(asA(() -> links.list(new ExchangeOrderQuery(null, null, null, null, "NEW-SKU", null,
        0, 20))).total())
        .as("替换 SKU 编码要能被搜到")
        .isEqualTo(1);
  }

  @Test
  void 下拉只列还能建新单的授权且标出原单有没有地址快照() {
    var auth = authorized("FILTER");
    var options = asA(() -> links.filters());

    assertThat(options.authorizationCount()).isEqualTo(1);
    var option = options.authorizations().get(0);
    assertThat(option.authorizationId()).isEqualTo(auth.authorization().id());
    assertThat(option.hasShippingSnapshot()).isTrue();
    assertThat(option.lines()).isNotEmpty();
    assertThat(option.lines().get(0).quantity()).isEqualTo("2.000000");

    // 建过新单之后就不再出现在下拉里 —— 点了必然被 UNIQUE 拒。
    asA(() -> links.create(new ExchangeOrderCreateRequest(auth.authorization().id(), ""), userA,
        membershipA));
    assertThat(asA(() -> links.filters()).authorizationCount()).isZero();
  }

  @Test
  void 能力声明如实报出下游未交付() {
    var caps = asA(() -> links.capabilities());
    assertThat(caps.exchangeAuthorizationDelivered()).isTrue();
    assertThat(caps.realNewSalesOrder()).isTrue();
    // 9.3.3.1 新出库与 11.2 旧货实收都未交付 —— 必须 false，不能让人以为闭环已经通了。
    assertThat(caps.outboundHandoffDelivered()).isFalse();
    assertThat(caps.oldGoodsReturnReceivedDelivered()).isFalse();
    assertThat(caps.oldGoodsReturnOwnerModule()).isEqualTo("11.2");
    assertThat(caps.closureStatusLabels()).hasSize(caps.closureStatuses().size());
    assertThat(caps.hardRules()).isNotEmpty();
  }

  @Test
  void 详情里的没闭环原因说清还差什么() {
    var detail = createLink("BLOCKERS");
    assertThat(detail.allowedActions()).containsExactly("SUBMIT", "SYNC", "CANCEL");

    var submitted = asA(() -> links.submit(detail.link().id(),
        new ExchangeOrderSubmitRequest(detail.link().version(), "提交"), membershipA));
    var text = String.join("；", submitted.closureBlockers());
    assertThat(text).contains("发货记录");
    assertThat(text).contains("旧货");

    // 撤销后不再报未闭环原因（已撤销不是「还没闭环」）。
    // 另起一张仍停在草稿的：已提交的新单一律撤不掉，作废是 9.2 审单的驳回动作。
    var fresh = createLink("BLOCKERS-FRESH");
    var cancelled = asA(() -> links.cancel(fresh.link().id(),
        new ExchangeOrderCancelRequest(fresh.link().version(), "撤"), userA, membershipA));
    assertThat(cancelled.closureBlockers()).isEmpty();
    assertThat(cancelled.allowedActions()).containsExactly("SYNC");
  }

  @Test
  void 事件时间线记录了每一次状态变化() {
    var detail = createLink("TIMELINE");
    assertThat(detail.events()).hasSize(1);
    assertThat(detail.events().get(0).eventKind()).isEqualTo("LINK_CREATED");
    assertThat(detail.events().get(0).detail()).contains(detail.link().newOrderNo());

    var submitted = asA(() -> links.submit(detail.link().id(),
        new ExchangeOrderSubmitRequest(detail.link().version(), "提交"), membershipA));
    assertThat(submitted.events()).hasSize(2);
    assertThat(submitted.events().get(1).eventKind()).isEqualTo("SUBMITTED");
    // actor 要留痕：换货闭环每一步是谁做的必须查得到。
    assertThat(submitted.events().get(1).actorMembershipId()).isEqualTo(membershipA);

    // 没有真实变化时同步不刷事件（否则真正的状态变化会被淹没）。
    var again = asA(() -> links.sync(submitted.link().id(),
        new ExchangeOrderSyncRequest(submitted.link().version(), "同步"), membershipA));
    assertThat(again.events()).hasSize(2);
  }

  @Test
  void 查不到与不属于自己的单都返回同一条404() {
    assertThatThrownBy(() -> asA(() -> links.detail(UUID.randomUUID())))
        .hasMessageContaining("不存在或不属于当前租户");
  }
}
