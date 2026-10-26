package com.starcon.trade;

import static com.starcon.trade.FulfillmentHealthModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * 1.1.2.3 履约健康。
 *
 * <p>跑的是**真实 PostgreSQL 16 + 强制 RLS**，不用任何内存替身；走 HTTP 端点而不只直连服务层，
 * 以便同时验证「服务层 + DB 层」两层都没把闸门漏掉。
 *
 * <p><b>本测试要钉住的核心是「口径」</b>，不是「有没有返回 200」：
 *
 * <ol>
 *   <li><b>每张比率型卡片的分子与分母都对得上</b>：作废（VOID）不进任何分母，
 *       时限未登记的记录落 UNSET 桶**不进**迟发率分母 —— 把没时限的当准时会让迟发率系统性偏低；</li>
 *   <li><b>红灯项明细与卡片同源</b>：明细的 {@code total} 必须等于对应卡片的分子，
 *       否则「点进去看到的条数」与「卡片上的数字」会打架；</li>
 *   <li><b>分页是真的</b>：翻页后条目不重不漏，total 不随页码变；</li>
 *   <li><b>量不到就是「未接通」而不是 0</b>：平台 ODR 在仓内没有上游，
 *       卡片上必须是 {@code available=false} + {@code value=null} + 灰灯，
 *       **不许**拿 0 把它填满；</li>
 *   <li><b>本命名空间下没有写端点</b>：工作台只读 + 跳转，一切状态变更回到归属系统完成；</li>
 *   <li><b>多租户三断言</b>（两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户）
 *       在 {@code FulfillmentHealthTenancyIntegrationTest} 里实跑。</li>
 * </ol>
 *
 * <p><b>夹具里的时刻 pin 死</b>：发货时刻与发货时限都是被断言的字段，
 * 用墙上时钟构造会让同一个用例只在某个钟点通过。待发货的时限用
 * {@code now()} 相对值（超期 / 今日到期两条桶判定本来就以「此刻」为准）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FulfillmentHealthIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 已落库的时刻（pin 死）。 */
  static final OffsetDateTime SHIP_AT = OffsetDateTime.parse("2026-03-01T10:15:30+08:00");
  static final OffsetDateTime SLA_LATE = OffsetDateTime.parse("2026-02-27T00:00:00+08:00");
  static final OffsetDateTime SLA_OK = OffsetDateTime.parse("2026-03-02T00:00:00+08:00");

  static final List<String> allPermissions =
      java.util.stream.Stream.of("fulfillment")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant, List<String> permissions) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
        s.execute("create role starcon_app nologin");
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
      actors.put("Bearer tenant-a", actor(user, a, allPermissions));
      actors.put("Bearer tenant-b", actor(user, b, allPermissions));
      actors.put(
          "Bearer read-only", actor(user, a, List.of("trade.fulfillment.read")));
      identity.start();
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
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired FulfillmentHealthService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.fulfillment.read", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.fulfillment.read", work);
  }

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate fulfillment_dropship_tracking_exception, fulfillment_dropship_tracking,"
              + " fulfillment_dropship_order, fulfillment_dropship_config,"
              + " shipment_record_tracking_observation, shipment_record_parcel, shipment_record,"
              + " shipment_pending_parcel, shipment_pending_unit, sales_order cascade");
    }
    seedFixtures();
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** A 租户的整套夹具。刻意做成「每一条都让某个口径有话说」的形状。 */
  void seedFixtures() {
    asA(
        () -> {
          // ---- 待发货单元 5 条 ----
          // U1：已过发货时限仍未发出 ⇒ 红灯项 PENDING_SHIPMENT_OVERDUE
          seedPendingUnit(a, "PENDING", OffsetDateTime.now().minusHours(2), null);
          // U2：时限未登记 ⇒ 积压但不是红灯；落 UNSET 桶（不进迟发率分母）
          seedPendingUnit(a, "LABEL_PRINTED", null, null);
          // U3：今日到期（+2h < +24h）⇒ 积压 + 黄灯
          seedPendingUnit(a, "WAYBILL_REQUESTED", OffsetDateTime.now().plusHours(2), null);
          // U4：已交接 ⇒ 不算积压
          seedPendingUnit(a, "HANDED_OVER", OffsetDateTime.now().plusHours(6), null);
          // U5：已作废 ⇒ 不算积压
          seedPendingUnit(a, "CANCELLED", null, null);

          // ---- 发货记录 4 条（含 1 条作废）----
          // R1：迟发（发货晚于时限），且**无任何轨迹观测** ⇒ 不可追踪
          UUID r1 = seedRecord(a, "SHIPPED", SLA_LATE, SHIP_AT, "IN_TRANSIT");
          // R2：准时 + 有轨迹观测 ⇒ 可追踪
          UUID r2 = seedRecord(a, "SHIPPED", SLA_OK, SHIP_AT, "IN_TRANSIT");
          seedObservation(a, r2, "IN_TRANSIT");
          // R3：时限未登记（UNSET 桶）+ 轨迹异常 + 有观测 ⇒ 可追踪但异常
          UUID r3 = seedRecord(a, "SHIPPED", null, SHIP_AT, "EXCEPTION");
          seedObservation(a, r3, "EXCEPTION");
          // R4：作废（错发货，不是迟发货）⇒ 不进任何分母
          seedRecord(a, "VOID", SLA_LATE, SHIP_AT, "EXCEPTION");

          // ---- 代发路径：1 条跟踪 + 1 条未结案异常 ----
          seedDropship(a);
          return null;
        });
  }

  UUID seedPendingUnit(UUID tenant, String status, OffsetDateTime sla, OffsetDateTime packed) {
    UUID id = UUID.randomUUID();
    // 占位符与列**逐个对齐**：jOOQ 的纯文本语句按位置绑定，多一个少一个都会报
    // 「未设定参数值 N 的内容」，而报错完全指向不了真正的原因。
    sql.execute(
        """
        insert into shipment_pending_unit(
          id, tenant_id, source_packing_order_id, source_outbound_order_id, packed_at,
          parcel_count, total_weight_kg, warehouse_id, warehouse_ref, outbound_order_no,
          sales_order_id, sales_order_no, shop_ref, carrier_channel, carrier_code,
          sla_deadline_at, sla_source, sla_registered_by_membership_id, sla_registered_at,
          status, handed_over_at, cancel_reason, synced_at, version, created_at, updated_at)
        values(
          ?, ?, ?, ?, ?::timestamptz, 1, 1.000000, ?, ?, ?, ?, ?, ?,
          'THIRD_PARTY', 'C1',
          ?::timestamptz, ?, ?, ?::timestamptz,
          ?, ?::timestamptz, ?, ?::timestamptz, 1, ?::timestamptz, ?::timestamptz)
        """,
        id,
        tenant,
        UUID.randomUUID(),
        UUID.randomUUID(),
        packed == null ? SHIP_AT.minusDays(2) : packed,
        UUID.randomUUID(),
        "WH-1",
        "OB-" + status,
        UUID.randomUUID(),
        "SO-1",
        "SHOP-1",
        sla,
        sla == null ? "" : "MANUAL_ATTESTATION",
        sla == null ? null : user,
        sla == null ? null : SHIP_AT,
        status,
        status.equals("HANDED_OVER") ? SHIP_AT : null,
        status.equals("CANCELLED") ? "测试作废" : "",
        SHIP_AT,
        SHIP_AT,
        SHIP_AT);
    return id;
  }

  UUID seedRecord(
      UUID tenant,
      String status,
      OffsetDateTime sla,
      OffsetDateTime shippedAt,
      String trackingStatus) {
    UUID id = UUID.randomUUID();
    sql.execute(
        """
        insert into shipment_record(
          id, tenant_id, record_no, source_handover_id, source_handover_code,
          source_packing_order_id, source_outbound_order_id, outbound_order_no,
          warehouse_id, warehouse_ref, carrier_code, carrier_name, master_waybill_no,
          shipped_at, parcel_count, parcel_item_count, total_quantity, total_gross_weight_g,
          total_volume_cbm, sales_order_id, sales_order_no, shop_ref, buyer_name,
          destination_country, sla_deadline_at, sla_source, status, status_changed_at,
          status_reason, tracking_status, tracking_updated_at, buyer_visible_status,
          buyer_visible_source, platform_callback_status, platform_callback_source,
          synced_at, version)
        values(
          ?, ?, ?, ?, ?,
          ?, ?, ?,
          ?, ?, ?, ?, ?,
          ?::timestamptz, 1, 1, 1.000000, 100.000000, 0.100000,
          ?, ?, ?, 'buyer', 'US',
          ?::timestamptz, ?, ?,
          ?::timestamptz, ?, ?, ?::timestamptz,
          'UNSET', '',
          'UNSET', '',
          ?::timestamptz, 1)
        """,
        id,
        tenant,
        "SR-" + UUID.randomUUID().toString().substring(0, 8),
        UUID.randomUUID(),
        "HO-1",
        UUID.randomUUID(),
        UUID.randomUUID(),
        "OB-1",
        UUID.randomUUID(),
        "WH-1",
        "C1",
        "承运商一",
        "WB-" + UUID.randomUUID().toString().substring(0, 8),
        shippedAt,
        UUID.randomUUID(),
        "SO-1",
        "SHOP-1",
        sla,
        sla == null ? "" : "MANUAL_ATTESTATION",
        status,
        status.equals("SHIPPED") ? null : SHIP_AT,
        status.equals("VOID") ? "错发货" : "",
        trackingStatus,
        SHIP_AT,
        SHIP_AT);
    return id;
  }

  void seedObservation(UUID tenant, UUID recordId, String status) {
    sql.execute(
        "insert into shipment_record_tracking_observation(id,tenant_id,record_id,status,source,"
            + "observation_ref,detail,observed_at,observed_by_membership_id)"
            + " values(?,?,?,?,'CARRIER_FEED','OBS-1','承运商轨迹',?::timestamptz,?)",
        UUID.randomUUID(),
        tenant,
        recordId,
        status,
        SHIP_AT,
        user);
  }

  /** 代发路径：销售订单 → 配置 → 代发单 → 跟踪 → 未结案异常。 */
  void seedDropship(UUID tenant) {
    UUID salesOrderId = UUID.randomUUID();
    sql.execute(
        "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
            + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
            + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
            + " values(?,?,?,'AMAZON','','USD','PREPAID','SUBMITTED',?::timestamptz,100.0000,0,0,0,0,"
            + "100.0000,0,?::timestamptz,?::timestamptz)",
        salesOrderId, tenant, "SO-DS-1", SHIP_AT, SHIP_AT, SHIP_AT);

    // 手工单必须至少有一行商品（DB 触发器钉着：「一张没有商品行的手工单在审单队列里无事可审」）。
    sql.execute(
        "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
            + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
            + " values(?,?,?,1,?,?,'商品',1,100.0000,0,0,100.0000,'',?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        salesOrderId,
        UUID.randomUUID(),
        "SKU-DS-1",
        SHIP_AT);

    // 收货地址快照：下单当时冻结的那一份，没有它无法发货（DB 触发器钉着）。
    sql.execute(
        "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
            + "recipient_phone,recipient_email,company_name,country_code,province,city,"
            + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
            + " values(?,?,?,'SHIPPING','收件人','+1-213-555-0100','buyer@example.com','',"
            + "?,'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        salesOrderId,
        "US",
        SHIP_AT);

    // 客户快照：手工单必须挂到对手方名下。
    sql.execute(
        "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
            + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
            + " values(?,?,?,?,?,'收件人','INDIVIDUAL','USD',?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        salesOrderId,
        UUID.randomUUID(),
        "C-DS-1",
        SHIP_AT);

    UUID configId = UUID.randomUUID();
    sql.execute(
        // 配置的每一列都踩在它自己的 CHECK 上：SKU 范围必须给 sku_ref、
        // SUPPLIER_STOCK 必须给安全库存缓冲、ACTIVE 必须给激活时刻与生效时刻。
        "insert into fulfillment_dropship_config(id,tenant_id,config_code,config_name,scope_kind,"
            + "shop_code,sku_ref,fulfillment_path,supplier_code,ownership_mode,settlement_timing,"
            + "settlement_basis,currency_code,supplier_cost,reference_sell_price,"
            + "availability_check_mode,min_supplier_stock_buffer,listing_policy,priority,status,"
            + "effective_from,activated_at,version,created_at,updated_at)"
            + " values(?,?,'CFG-1','代发配置','SKU','','SKU-1','SUPPLIER_DIRECT','SUP-1','PURE_AGENCY',"
            + "'ON_ORDER','FLAT_COST','USD',10.0000,20.0000,'SUPPLIER_STOCK',0.000000,'ALWAYS_LIST',"
            + "100,'ACTIVE',?::timestamptz,?::timestamptz,1,?::timestamptz,?::timestamptz)",
        configId, tenant, SHIP_AT, SHIP_AT, SHIP_AT, SHIP_AT);

    UUID orderId = UUID.randomUUID();
    sql.execute(
        "insert into fulfillment_dropship_order(id,tenant_id,order_no,sales_order_id,sales_order_no,"
            + "currency_code,config_id,config_code,supplier_code,fulfillment_path,ownership_mode,"
            + "counts_against_own_inventory,status,dispatch_mode,vendor_code,sell_amount,"
            + "purchase_amount,supplier_shipping_amount,version,created_at,updated_at)"
            + " values(?,?,'DO-1',?,'SO-DS-1','USD',?,'CFG-1','SUP-1','SUPPLIER_DIRECT','PURE_AGENCY',"
            + "false,'READY','AUTO','MANUAL_ENTRY',20.0000,10.0000,0,1,?::timestamptz,?::timestamptz)",
        orderId, tenant, salesOrderId, configId, SHIP_AT, SHIP_AT);

    // 代发单行：金额必须与单头的 sell/purchase 合计一致（DB 守卫钉着）。
    sql.execute(
        "insert into fulfillment_dropship_order_line(id,tenant_id,order_id,line_no,sku_id,"
            + "sku_ref,sku_name,quantity,sell_unit_price,purchase_unit_price,sell_amount,"
            + "purchase_amount,created_at,updated_at)"
            + " values(?,?,?,1,?,'SKU-DS-1','商品',1.000000,20.0000,10.0000,20.0000,10.0000,"
            + "?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        orderId,
        UUID.randomUUID(),
        SHIP_AT,
        SHIP_AT);

    UUID trackingId = UUID.randomUUID();
    sql.execute(
        "insert into fulfillment_dropship_tracking(id,tenant_id,tracking_no,order_id,order_no,"
            + "sales_order_id,sales_order_no,supplier_code,active_leg,supplier_leg_status,"
            + "supplier_carrier_code,supplier_tracking_no,supplier_shipped_at,supplier_promised_ship_at,"
            + "own_leg_status,version,created_at,updated_at)"
            + " values(?,?,'TRK-1',?,'DO-1',?,'SO-DS-1','SUP-1','SUPPLIER','IN_TRANSIT','C1','ST-1',"
            + "?::timestamptz,?::timestamptz,'NOT_HANDED_OVER',1,?::timestamptz,?::timestamptz)",
        trackingId, tenant, orderId, salesOrderId, SHIP_AT, SHIP_AT, SHIP_AT, SHIP_AT);

    sql.execute(
        "insert into fulfillment_dropship_tracking_exception(id,tenant_id,exception_no,tracking_id,"
            + "order_id,order_no,sales_order_no,supplier_code,exception_kind,severity,rule_code,"
            + "rule_threshold_hours,rule_evidence,status,detected_at)"
            + " values(?,?,?,?,?,'DO-1','SO-DS-1','SUP-1','PARCEL_LOST','CRITICAL','RULE-LOST',24,"
            + "'承运商 24 小时未更新轨迹','OPEN',?::timestamptz)",
        UUID.randomUUID(),
        tenant,
        "EX-" + UUID.randomUUID().toString().substring(0, 8),
        trackingId,
        orderId,
        SHIP_AT);
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  @Test
  void 能力声明逐维度报出口径与分母() {
    var caps = asA(() -> service.capabilities());
    assertThat(caps.dimensions()).hasSize(7);
    assertThat(caps.redFlagKinds())
        .containsExactly(
            "PENDING_SHIPMENT_OVERDUE",
            "SHIP_OVERDUE",
            "LOGISTICS_EXCEPTION",
            "UNTRACKABLE_SHIPMENT",
            "DROPSHIP_TRACKING_EXCEPTION");

    for (var d : caps.dimensions()) {
      if (!d.available()) {
        // 没上游的维度**不给**公式与分母：给了就是编一个。
        assertThat(d.formula()).as(d.key() + " 没有上游就不该有公式").isBlank();
        assertThat(d.denominator()).as(d.key() + " 没有上游就不该有分母").isBlank();
        assertThat(d.sourceTables()).as(d.key() + " 没有上游就不该有来源表").isBlank();
        continue;
      }
      assertThat(d.formula()).as(d.key() + " 必须写清算法").isNotBlank();
      assertThat(d.denominator()).as(d.key() + " 必须写清分母").isNotBlank();
      assertThat(d.sourceTables()).as(d.key() + " 必须写清来源表").isNotBlank();
    }
    var od = caps.dimensions().stream()
        .filter(d -> d.key().equals("PLATFORM_ORDER_DEFECT_RATE"))
        .findFirst()
        .orElseThrow();
    assertThat(od.available()).isFalse();
    assertThat(od.reason()).contains("platform_performance_metric");
    assertThat(od.ownerModule()).contains("02");
    assertThat(od.impact()).isNotBlank();
  }

  // ==========================================================================
  // 汇总口径
  // ==========================================================================

  @Test
  void 待发货积压只算已打包未交运未作废() {
    var summary = asA(() -> service.summary(a));
    var card = card(summary, "PENDING_SHIPMENT_BACKLOG");

    // U1/U2/U3 在积压里；U4 已交接、U5 已作废，都不算。
    assertThat(card.value()).isEqualByComparingTo("3");
    assertThat(card.valueText()).isEqualTo("3 件");
    // 这是**计数**口径：没有分子分母，硬塞一个会让「3 件」读成「3/5」。
    assertThat(card.metricKind()).isEqualTo("COUNT");
    assertThat(card.numerator()).isNull();
    assertThat(card.denominator()).isNull();
    // 红灯项（U1 已过时限）挂在同一张卡上，且能点进去看。
    assertThat(card.alertCount()).isEqualTo(1L);
    assertThat(card.redFlagKind()).isEqualTo("PENDING_SHIPMENT_OVERDUE");
    // U1 已过时限 ⇒ 红灯；U2 时限未登记 ⇒ 也在积压里
    assertThat(card.level()).isEqualTo("RED");
    assertThat(summary.coverage().pendingProjectionTotal()).isEqualTo(5L);
    assertThat(summary.coverage().pendingHandedOverUnits()).isEqualTo(1L);
    assertThat(summary.coverage().pendingCancelledUnits()).isEqualTo(1L);

    var bySla = breakdown(summary, "PENDING_BY_SLA");
    assertThat(bucket(bySla, "OVERDUE").count()).isEqualTo(1L);
    assertThat(bucket(bySla, "DUE_TODAY").count()).isEqualTo(1L);
    assertThat(bucket(bySla, "UNSET").count()).isEqualTo(1L);
  }

  @Test
  void 超时发货率的分母不含作废也不含未登记时限() {
    var summary = asA(() -> service.summary(a));
    var card = card(summary, "SHIP_OVERDUE_RATE");

    // 已发货 4 条里 R4 是作废 ⇒ 分母总体 3；其中 R1 迟发、R2 准时、R3 时限未登记。
    // 分母 = 登记过时限且非作废 = R1 + R2 = 2；分子 = R1 = 1。
    assertThat(card.numerator()).isEqualTo(1L);
    assertThat(card.denominator()).isEqualTo(2L);
    assertThat(card.value()).isEqualByComparingTo("0.5000");
    assertThat(card.valueText()).isEqualTo("50.00%");
    assertThat(card.level()).isEqualTo("RED");
    // 未登记时限的那条必须被单独报出来，而不是悄悄算进分母。
    assertThat(summary.coverage().shipmentRecordsUnsetSla()).isEqualTo(1L);
    assertThat(summary.coverage().shipmentRecordsEvaluated()).isEqualTo(3L);
  }

  @Test
  void 物流异常件占比与有效追踪率用同一个总体() {
    var summary = asA(() -> service.summary(a));
    var exception = card(summary, "LOGISTICS_EXCEPTION_RATE");
    var tracking = card(summary, "PLATFORM_VALID_TRACKING_RATE");

    // 总体都是 3 条非作废发货记录；异常 1 条（R3）；可追踪 2 条（R2、R3 有观测，R1 没有）。
    assertThat(exception.denominator()).isEqualTo(3L);
    assertThat(exception.numerator()).isEqualTo(1L);
    assertThat(exception.value()).isEqualByComparingTo("0.3333");
    assertThat(tracking.denominator()).isEqualTo(3L);
    assertThat(tracking.numerator()).isEqualTo(2L);
    assertThat(tracking.value()).isEqualByComparingTo("0.6667");
    assertThat(tracking.level()).isEqualTo("AMBER");
    assertThat(summary.coverage().shipmentRecordsUntrackable()).isEqualTo(1L);
  }

  @Test
  void 平台迟发率与超时发货率是同一个测量不是两套算法() {
    var summary = asA(() -> service.summary(a));
    var ship = card(summary, "SHIP_OVERDUE_RATE");
    var platform = card(summary, "PLATFORM_LATE_SHIPMENT_RATE");
    assertThat(platform.numerator()).isEqualTo(ship.numerator());
    assertThat(platform.denominator()).isEqualTo(ship.denominator());
    assertThat(platform.value()).isEqualByComparingTo(ship.value());
    assertThat(platform.basis()).contains("同一个测量");
  }

  @Test
  void 平台ODR没有上游就显示未接通而不是0() {
    var summary = asA(() -> service.summary(a));
    var card = card(summary, "PLATFORM_ORDER_DEFECT_RATE");
    assertThat(card.available()).isFalse();
    assertThat(card.value()).isNull();
    assertThat(card.valueText()).isEqualTo("未接通");
    assertThat(card.level()).isEqualTo("UNKNOWN");
    assertThat(card.ownerModule()).contains("02");
    assertThat(card.unavailableReason()).isNotBlank();
  }

  // ==========================================================================
  // 红灯项明细
  // ==========================================================================

  @Test
  void 红灯项总数与卡片告警数同源() {
    var summary = asA(() -> service.summary(a));
    var page = asA(() -> service.redFlags(a, null, 0, 50));

    // 五类红灯项各 1 条，总数 5。
    assertThat(page.total()).isEqualTo(5L);
    assertThat(page.items()).hasSize(5);

    // 每一类的 total 必须等于对应卡片的 alertCount —— 这是「点进去看到的条数」
    // 与「卡片上的数字」不会打架的保证。
    for (var c : summary.cards()) {
      if (c.redFlagKind() == null) {
        continue;
      }
      assertThat(asA(() -> service.redFlags(a, c.redFlagKind(), 0, 50)).total())
          .as(c.key() + " 的红灯项条数必须等于它的告警数")
          .isEqualTo(c.alertCount());
    }
    // 作废那条（R4）也是轨迹异常，但它不该出现在红灯项里。
    assertThat(page.items().stream().filter(i -> i.kind().equals("LOGISTICS_EXCEPTION")).count())
        .isEqualTo(1L);
    // 有效追踪率那格挂的是「有运单号但无轨迹观测」（R1）。
    assertThat(card(summary, "PLATFORM_VALID_TRACKING_RATE").alertCount()).isEqualTo(1L);
    assertThat(card(summary, "PLATFORM_VALID_TRACKING_RATE").redFlagKind())
        .isEqualTo("UNTRACKABLE_SHIPMENT");
  }

  @Test
  void 红灯项分页是真的() {
    var first = asA(() -> service.redFlags(a, null, 0, 2));
    var second = asA(() -> service.redFlags(a, null, 1, 2));
    var third = asA(() -> service.redFlags(a, null, 2, 2));
    var fourth = asA(() -> service.redFlags(a, null, 3, 2));
    assertThat(first.total()).isEqualTo(5L);
    assertThat(second.total()).isEqualTo(5L);
    assertThat(third.total()).isEqualTo(5L);
    assertThat(fourth.total()).isEqualTo(5L);
    assertThat(first.items()).hasSize(2);
    assertThat(second.items()).hasSize(2);
    assertThat(third.items()).hasSize(1);
    assertThat(fourth.items()).isEmpty();
    var ids = new java.util.ArrayList<String>();
    first.items().forEach(i -> ids.add(i.kind() + ":" + i.refId()));
    second.items().forEach(i -> ids.add(i.kind() + ":" + i.refId()));
    assertThat(ids).doesNotHaveDuplicates();
  }

  @Test
  void 每条红灯项都能定位到那一条事实() {
    var page = asA(() -> service.redFlags(a, null, 0, 50));
    for (var item : page.items()) {
      assertThat(item.refId()).as(item.kind() + " 必须带归属事实主键").isNotNull();
      assertThat(item.refNo()).as(item.kind() + " 必须带人类可读单号").isNotBlank();
      assertThat(item.title()).isNotBlank();
      assertThat(item.detail()).isNotBlank();
      assertThat(item.linkPath()).as(item.kind() + " 必须能点进去").isNotBlank();
      assertThat(item.severity()).isIn("INFO", "WARNING", "CRITICAL");
    }
    // 待发货超时那一格带上「已超期多少小时」。
    var pending =
        page.items().stream().filter(i -> i.kind().equals("PENDING_SHIPMENT_OVERDUE")).findFirst().orElseThrow();
    assertThat(pending.detail()).contains("已超期");
  }

  // ==========================================================================
  // HTTP 端点
  // ==========================================================================

  @Test
  void 三个只读端点都通() throws Exception {
    http.perform(get("/trade/fulfillment/health/capabilities").header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimensions.length()").value(7))
        .andExpect(jsonPath("$.dimensions[7].key").doesNotExist());

    http.perform(get("/trade/fulfillment/health/summary").header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cards.length()").value(6))
        .andExpect(jsonPath("$.cards[0].key").value("PENDING_SHIPMENT_BACKLOG"));

    http.perform(get("/trade/fulfillment/health/red-flags").header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(5))
        .andExpect(jsonPath("$.items.length()").value(5));
  }

  @Test
  void 未知红灯类型报400而不是给一个空列表() throws Exception {
    http.perform(
            get("/trade/fulfillment/health/red-flags")
                .param("kind", "NOT_A_KIND")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 本命名空间没有写端点() throws Exception {
    http.perform(post("/trade/fulfillment/health/summary").header("Authorization", "Bearer read-only"))
        .andExpect(status().isMethodNotAllowed());
    http.perform(post("/trade/fulfillment/health/red-flags").header("Authorization", "Bearer read-only"))
        .andExpect(status().isMethodNotAllowed());
  }

  @Test
  void 没带凭证拿不到任何数() throws Exception {
    http.perform(get("/trade/fulfillment/health/summary")).andExpect(status().isUnauthorized());
  }

  // ==========================================================================
  // 写入回读：时间值不许静默偏 8 小时
  // ==========================================================================

  @Test
  void 写入的发货时限读回来是同一个瞬间() {
    UUID unitId =
        asA(
            () ->
                seedPendingUnit(
                    a, "PENDING", OffsetDateTime.parse("2026-03-05T14:30:00+08:00"), null));
    var read =
        asA(
            () ->
                sql.fetchOne("select sla_deadline_at from shipment_pending_unit where id = ?", unitId)
                    .get("sla_deadline_at", OffsetDateTime.class));
    assertThat(read).isNotNull();
    assertThat(read.toInstant())
        .isEqualTo(OffsetDateTime.parse("2026-03-05T14:30:00+08:00").toInstant());
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  static FulfillmentHealthCard card(FulfillmentHealthSummary s, String key) {
    return s.cards().stream()
        .filter(c -> c.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("健康度卡片缺 " + key));
  }

  static FulfillmentHealthBreakdown breakdown(FulfillmentHealthSummary s, String key) {
    return s.breakdowns().stream()
        .filter(x -> x.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("拆分缺 " + key));
  }

  static FulfillmentHealthBucket bucket(FulfillmentHealthBreakdown d, String key) {
    return d.buckets().stream()
        .filter(x -> x.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("拆分桶缺 " + key));
  }
}
