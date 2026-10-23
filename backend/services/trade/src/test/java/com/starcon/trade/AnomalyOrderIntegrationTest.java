package com.starcon.trade;

import static com.starcon.trade.AddressPaymentCheckModels.*;
import static com.starcon.trade.AnomalyOrderModels.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 1.1.1.2 异常订单队列的模块测试。
 *
 * <p>全部跑在 Testcontainers 的真实 PostgreSQL 16 上（连接池压到 1），
 * 数据真落 V99 / V127 / V147 / V150 / V207 / V210 的事实表，
 * **不 mock 任何判定**：清单里的每一行都必须能顺着凭据回溯到真实的那条事实。
 *
 * <p>本类钉住三件最容易做错的事：
 *
 * <ul>
 *   <li><b>阈值是真的</b>：平台回传没超过 24 小时的、平台单没挂超过 24 小时的，
 *       一律<b>不进</b>异常队列 —— 否则「超时」这个词就没有含义了。</li>
 *   <li><b>停留时长是逐类锚定的</b>：不是「订单创建至今」，
 *       而是从「进入当前异常状态」算起；一张单命中多类时取最久的那一条。</li>
 *   <li><b>清单与明细同口径</b>：明细页给出的异常类型集合必须与清单行逐字一致，
 *       否则点进去看到的与列表上说的对不上。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AnomalyOrderIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      java.util.stream.Stream.of("orders")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

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
      actors.put(
          "Bearer tenant-a",
          new AccessService.Access(
              user,
              a,
              "Test",
              UUID.randomUUID(),
              null,
              allPermissions,
              List.of(new AccessService.Grant(UUID.randomUUID(), "ALL", "ALL", allPermissions))));
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired AnomalyOrderService service;
  @Autowired AddressPaymentCheckService addressChecks;
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
    return access.run(actorA, "trade.orders.read", work);
  }

  // ------------------------------------------------------------ 夹具

  /** 基准时刻：截断到秒，避免亚秒误差混进停留时长的断言。 */
  static final OffsetDateTime NOW =
      OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);

  static final OffsetDateTime H72 = NOW.minusHours(72);
  static final OffsetDateTime H48 = NOW.minusHours(48);
  static final OffsetDateTime H24 = NOW.minusHours(24);
  static final OffsetDateTime H3 = NOW.minusHours(3);

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate order_risk_assessment_component, order_risk_assessment, order_risk_rule,"
              + " shipment_writeback_task, shipment_record, platform_order,"
              + " order_address_check_item, order_address_check, order_address_check_payment_rule,"
              + " order_address_check_country_rule, order_address_check_embargo_rule,"
              + " order_address_check_policy, sales_order_status_history,"
              + " order_charge, order_address_snapshot, sales_order_party_snapshot,"
              + " sales_order_line, sales_order cascade");
    }
  }

  /** 一张已提交的平台单：订单头 + 一行商品 + 客户快照 + 收货地址 + 一笔运费 + 状态留痕。 */
  UUID seedOrder(String orderNo, UUID skuId, BigDecimal quantity, OffsetDateTime submittedAt) {
    UUID orderId = UUID.randomUUID();
    // 订单头汇总必须与「行 + 费用」实算一致（V99 的守卫会当场核对），
    // 所以这里按数量算出来而不是写死。
    BigDecimal lineAmount = quantity.multiply(new BigDecimal("100.0000"));
    BigDecimal shipping = new BigDecimal("10.0000");
    BigDecimal grand = lineAmount.add(shipping);
    asA(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,'AMAZON',?,'USD','PREPAID','SUBMITTED',?::timestamptz,"
                  + "?,0,0,?,0,?,0,?::timestamptz,?::timestamptz)",
              orderId, a, orderNo, "EXT-" + orderNo, submittedAt, lineAmount, shipping, grand,
              submittedAt, submittedAt);
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
                  + "quantity,unit_price,discount_amount,tax_amount,line_amount,note,created_at)"
                  + " values(?,?,?,1,?,?,'商品',?,100.0000,0,0,?,'',?::timestamptz)",
              UUID.randomUUID(), a, orderId, skuId, "SKU-" + orderNo, quantity, lineAmount,
              submittedAt);
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?,?,?,'C-1','收件人','INDIVIDUAL','',?::timestamptz)",
              UUID.randomUUID(), a, orderId, UUID.randomUUID(), submittedAt);
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,recipient_name,"
                  + "recipient_phone,recipient_email,company_name,country_code,province,city,"
                  + "district,postcode,address_line1,address_line2,tax_id,note,created_at)"
                  + " values(?,?,?,'SHIPPING','收件人','+1-213-555-0100','buyer@example.com','',"
                  + "'US','CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), a, orderId, submittedAt);
          sql.execute(
              "insert into order_charge(id,tenant_id,order_id,charge_type,label,amount,charge_kind,"
                  + "note,created_at) values(?,?,?,'SHIPPING','运费',10.0000,'ORDER','',?::timestamptz)",
              UUID.randomUUID(), a, orderId, submittedAt);
          sql.execute(
              "insert into sales_order_status_history(id,tenant_id,order_id,event_type,from_status,"
                  + "to_status,reason,actor_id,occurred_at,created_at)"
                  + " values(?,?,?,'SUBMITTED','DRAFT','SUBMITTED','手工录入',?,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(), a, orderId, user, submittedAt, submittedAt);
          return null;
        });
    return orderId;
  }

  /** 平台单投影：{@code normalized_status} 决定它是不是「待确认」。 */
  void seedPlatformOrder(
      UUID orderId, String externalOrderNo, String shopCode, String normalizedStatus,
      OffsetDateTime placedAt) {
    asA(
        () -> {
          sql.execute(
              "insert into platform_order(id,tenant_id,platform_code,shop_code,external_order_no,"
                  + "idempotency_key,sales_order_id,platform_status,normalized_status,"
                  + "platform_event_version,platform_placed_at,platform_updated_at,currency,"
                  + "platform_grand_total_amount,restored_grand_total_amount,mapping_version,"
                  + "content_fingerprint,synced_at)"
                  + " values(?,?,'AMAZON',?,?,?,?,'Pending',?,1,?::timestamptz,?::timestamptz,'USD',"
                  + "210.0000,210.0000,'v1',?,?::timestamptz)",
              UUID.randomUUID(), a, shopCode, externalOrderNo, "idem-" + externalOrderNo, orderId,
              normalizedStatus, placedAt, placedAt, "fp-" + externalOrderNo, placedAt);
          return null;
        });
  }

  /** 发货回传任务（平台侧迟迟没确认的那一笔）。 */
  void seedWritebackTask(
      UUID orderId, String orderNo, String shopCode, String status, OffsetDateTime createdAt) {
    asA(
        () -> {
          UUID recordId = UUID.randomUUID();
          sql.execute(
              "insert into shipment_record(id,tenant_id,record_no,source_handover_id,"
                  + "source_packing_order_id,source_outbound_order_id,warehouse_id,carrier_code,"
                  + "carrier_name,master_waybill_no,shipped_at,parcel_count,sales_order_id,"
                  + "sales_order_no,shop_ref,synced_at,created_at,updated_at)"
                  + " values(?,?,?,?::uuid,?::uuid,?::uuid,?::uuid,'DHL','DHL',?,"
                  + "?::timestamptz,1,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz)",
              recordId, a, "SR-" + orderNo, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
              UUID.randomUUID(), "WB-" + orderNo, createdAt, orderId, orderNo, shopCode, createdAt,
              createdAt, createdAt);
          sql.execute(
              "insert into shipment_writeback_task(id,tenant_id,task_no,source_record_id,"
                  + "source_record_no,sales_order_no,carrier_code,master_waybill_no,shipped_at,"
                  + "platform_code,shop_code,shop_id,platform_order_ref,idempotency_key,payload,"
                  + "status,verification,attempt_count,max_attempts,next_attempt_at,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,'DHL',?,?::timestamptz,'AMAZON',?,?::uuid,"
                  + "?,?,'{}'::jsonb,?,'NOT_REQUIRED',0,5,?::timestamptz,"
                  + "?::timestamptz,?::timestamptz)",
              UUID.randomUUID(), a, "PB-" + orderNo, recordId, "SR-" + orderNo, orderNo,
              "WB-" + orderNo, createdAt, shopCode, UUID.randomUUID(), "EXT-" + orderNo,
              "idem-wb-" + orderNo, status, createdAt, createdAt, createdAt);
          return null;
        });
  }

  /**
   * 风控评估：一套规则 + 一条评估 + **五个维度各一行**（少一行 DB 的延迟约束会挡下）。
   *
   * @param scoredDimensions 命中的维度（{@code ORDER_FREQUENCY} / {@code ADDRESS_CONCENTRATION}）
   */
  void seedRiskAssessment(UUID orderId, String orderNo, OffsetDateTime submittedAt,
      List<String> scoredDimensions) {
    asA(
        () -> {
          UUID ruleId = UUID.randomUUID();
          sql.execute(
              "insert into order_risk_rule(id,tenant_id,rule_name,enabled,"
                  + "weight_buyer_history,weight_device_ip_link,weight_order_frequency,"
                  + "weight_amount_anomaly,weight_address_concentration,manual_review_threshold,"
                  + "hold_shipment_threshold,frequency_window_hours,frequency_max_orders,"
                  + "device_link_max_links,amount_anomaly_ratio,amount_anomaly_min_orders,"
                  + "address_concentration_window_hours,address_concentration_max_orders,"
                  + "signal_max_age_hours,missing_signal_policy,created_at,updated_at)"
                  + " values(?,?,'默认风控',true,0.2000,0.2000,0.2000,0.2000,0.2000,50.0000,80.0000,"
                  + "24,3,3,2.0000,2,24,2,72,'SCORE_KNOWN_ONLY',?::timestamptz,?::timestamptz)",
              ruleId, a, submittedAt, submittedAt);
          UUID assessmentId = UUID.randomUUID();
          sql.execute(
              "insert into order_risk_assessment(id,tenant_id,order_id,order_no,order_currency,"
                  + "order_amount,order_submitted_at,rule_id,rule_version,rule_name,"
                  + "evaluated_reference_at,risk_score,recommended_action,manual_review_required,"
                  + "evaluation_status,scored_dimension_count,missing_dimension_count,created_at)"
                  + " values(?,?,?,?,'USD',210.0000,?::timestamptz,?,0,'默认风控',?::timestamptz,"
                  + "80.0000,'MANUAL_REVIEW',true,'SCORED',?,?,?::timestamptz)",
              assessmentId, a, orderId, orderNo, submittedAt, ruleId, submittedAt,
              scoredDimensions.size(), 5 - scoredDimensions.size(), submittedAt);
          for (String dimension :
              List.of(
                  "BUYER_HISTORY",
                  "DEVICE_IP_LINK",
                  "ORDER_FREQUENCY",
                  "AMOUNT_ANOMALY",
                  "ADDRESS_CONCENTRATION")) {
            boolean scored = scoredDimensions.contains(dimension);
            BigDecimal score = scored ? new BigDecimal("80.0000") : BigDecimal.ZERO;
            BigDecimal contribution = scored ? new BigDecimal("16.0000") : BigDecimal.ZERO;
            sql.execute(
                "insert into order_risk_assessment_component(id,tenant_id,assessment_id,dimension,"
                    + "status,weight,raw_value,normalized_score,contribution,signal_source,"
                    + "explanation,created_at)"
                    + " values(?,?,?,?,?,0.2000,?,?,?,'DERIVED_FROM_ORDERS',?,?::timestamptz)",
                UUID.randomUUID(), a, assessmentId, dimension, scored ? "SCORED" : "MISSING_SIGNAL",
                scored ? new BigDecimal("4.000000") : null, score, contribution,
                scored ? "该维度量到了信号" : "该维度没有可用信号", submittedAt);
          }
          return null;
        });
  }

  /** 地址校验：登记禁运国 + 跑一次真校验，得到 NOT_DELIVERABLE 的结论。 */
  void seedUndeliverableAddress(UUID orderId) {
    asA(
        () -> {
          addressChecks.savePolicy(
              new AddressPaymentPolicyInput(
                  "默认校验策略", AddressPaymentMissingReferencePolicy.ASSESS_KNOWN_ONLY));
          addressChecks.saveEmbargoRule(
              new AddressPaymentEmbargoRuleInput(
                  "US",
                  AddressPaymentEmbargoLevel.BLOCK,
                  AddressPaymentRuleSource.TENANT_MAINTAINED,
                  null,
                  "测试禁运",
                  null,
                  null,
                  null));
          addressChecks.assess(
              new AddressPaymentCheckInput(
                  orderId,
                  AddressPaymentStatus.PAID,
                  AddressPaymentStatusSource.PLATFORM_CALLBACK,
                  NOW),
              user);
          return null;
        });
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================
  @Test
  void 能力声明把七类异常逐条点名且跨服务两类归到别的服务() {
    var caps = asA(() -> service.capabilities());

    assertThat(caps.dimensions()).hasSize(7);
    assertThat(caps.dimensions())
        .extracting(AnomalyOrderCapability::dimension)
        .containsExactlyInAnyOrder(
            "PLATFORM_SHIPMENT_TIMEOUT",
            "ADDRESS_UNDELIVERABLE",
            "BUYER_MESSAGE_PENDING",
            "BLACKLIST_BUYER",
            "INSUFFICIENT_STOCK",
            "ADDRESS_FRAUD_SUSPECTED",
            "DUPLICATE_ORDER");

    // 跨服务两类必须点名归属服务与前端要读的端点。
    assertThat(caps.crossServiceSources()).hasSize(2);
    assertThat(caps.crossServiceSources())
        .extracting(AnomalyOrderCrossSource::ownerModule)
        .contains("16 合规与风控（channel）", "07 库存（warehouse）");
    assertThat(caps.crossServiceSources())
        .allSatisfy(
            source -> {
              assertThat(source.endpoint()).startsWith("GET /");
              assertThat(source.joinKey()).isNotBlank();
            });

    // 🔴 没有「平台绩效指标」这条线：本仓没有那张表，必须如实报出来而不是假装接通了。
    assertThat(caps.notConnected())
        .anySatisfy(text -> assertThat(text).contains("platform_performance_metric"));

    // 「标记忽略」没有归属写端点 ⇒ available=false 且必须说清影响。
    var ignored =
        caps.actions().stream()
            .filter(a -> "MARK_IGNORED".equals(a.code()))
            .findFirst()
            .orElseThrow();
    assertThat(ignored.available()).isFalse();
    assertThat(ignored.impact()).contains("没有「忽略一条异常」的写端点");
    assertThat(ignored.ownerModule()).isNotBlank();

    // 其余动作都是归属系统的真实入口。
    assertThat(caps.actions())
        .filteredOn(a -> !"MARK_IGNORED".equals(a.code()))
        .allSatisfy(a -> assertThat(a.available()).isTrue());
  }

  @Test
  void 选项给出异常类型词表与停留时长档位() {
    var options = asA(() -> service.options());
    assertThat(options.anomalyTypes()).hasSize(7);
    assertThat(options.anomalyTypes())
        .extracting(AnomalyOrderTypeOption::code)
        .contains("BLACKLIST_BUYER", "INSUFFICIENT_STOCK");
    assertThat(options.dwellPresets())
        .extracting(AnomalyOrderDwellPreset::code)
        .contains("ALL", "H24");
    assertThat(options.dataAsOf()).isNotBlank();
  }

  // ==========================================================================
  // 五类本服务侧信号
  // ==========================================================================
  @Test
  void 地址不可达的单进队列并带停留时长与凭据() {
    UUID orderId = seedOrder("SO-ADDR", UUID.randomUUID(), new BigDecimal("2"), H72);
    seedUndeliverableAddress(orderId);

    var page = asA(() -> service.page(null, null, null, null, null, null, 0, 50));
    assertThat(page.total()).isEqualTo(1);
    var row = page.items().get(0);
    assertThat(row.orderNo()).isEqualTo("SO-ADDR");
    assertThat(row.anomalyTypes()).containsExactly("ADDRESS_UNDELIVERABLE");
    assertThat(row.severity()).isEqualTo("HIGH");
    // 停留时长锚在地址校验的判定基准时刻（= 订单提交时刻），不是「订单创建至今」的别的口径。
    assertThat(row.dwellSeconds()).isBetween(72 * 3600L - 120L, 72 * 3600L + 120L);
    assertThat(row.recommendedActions()).contains("ESCALATE_MANUAL", "OPEN_ORDER");

    var detail = asA(() -> service.detail(orderId));
    assertThat(detail.evidence()).hasSize(1);
    assertThat(detail.evidence().get(0).dimension()).isEqualTo("ADDRESS_UNDELIVERABLE");
    assertThat(detail.evidence().get(0).facts())
        .anySatisfy(fact -> assertThat(fact).contains("NOT_DELIVERABLE"));
    assertThat(detail.lines()).hasSize(1);
    assertThat(detail.charges()).hasSize(1);
    assertThat(detail.statusHistory()).hasSize(1);
    assertThat(detail.buyerName()).isEqualTo("收件人");
    assertThat(detail.order().buyerKey()).hasSize(32);
    assertThat(detail.order().skuNeeds()).hasSize(1);
    assertThat(detail.order().skuNeeds().get(0).quantity()).isEqualByComparingTo("2.000000");
  }

  @Test
  void 平台超时未回传发货只认超过阈值的回传任务() {
    UUID stuck = seedOrder("SO-STUCK", UUID.randomUUID(), new BigDecimal("1"), H72);
    UUID fresh = seedOrder("SO-FRESH", UUID.randomUUID(), new BigDecimal("1"), H72);
    seedWritebackTask(stuck, "SO-STUCK", "SHOP-1", "PENDING", H72);
    seedWritebackTask(fresh, "SO-FRESH", "SHOP-2", "PENDING", H3);

    var page = asA(() -> service.page(null, null, null, null, null, null, 0, 50));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).orderNo()).isEqualTo("SO-STUCK");
    assertThat(page.items().get(0).anomalyTypes()).containsExactly("PLATFORM_SHIPMENT_TIMEOUT");
    assertThat(page.items().get(0).shopCode()).isEqualTo("SHOP-1");
    assertThat(page.items().get(0).recommendedActions()).contains("QUERY_PLATFORM");
  }

  @Test
  void 买家留言待确认只认超过阈值的平台单() {
    UUID stuck = seedOrder("SO-PEND", UUID.randomUUID(), new BigDecimal("1"), H72);
    UUID fresh = seedOrder("SO-CONF", UUID.randomUUID(), new BigDecimal("1"), H72);
    seedPlatformOrder(stuck, "EXT-SO-PEND", "SHOP-1", "PENDING_CONFIRMATION", H72);
    seedPlatformOrder(fresh, "EXT-SO-CONF", "SHOP-1", "CONFIRMED", H3);

    var page = asA(() -> service.page(null, null, null, null, null, null, 0, 50));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items().get(0).orderNo()).isEqualTo("SO-PEND");
    assertThat(page.items().get(0).anomalyTypes()).containsExactly("BUYER_MESSAGE_PENDING");
    assertThat(page.items().get(0).shopCode()).isEqualTo("SHOP-1");
  }

  @Test
  void 风控的两个维度分别给出地址欺诈疑似与重复下单() {
    UUID orderId = seedOrder("SO-RISK", UUID.randomUUID(), new BigDecimal("1"), H72);
    seedRiskAssessment(
        orderId, "SO-RISK", H72, List.of("ORDER_FREQUENCY", "ADDRESS_CONCENTRATION"));

    var page = asA(() -> service.page(null, null, null, null, null, null, 0, 50));
    assertThat(page.total()).isEqualTo(1);
    var row = page.items().get(0);
    assertThat(row.anomalyTypes()).containsExactlyInAnyOrder("DUPLICATE_ORDER", "ADDRESS_FRAUD_SUSPECTED");
    assertThat(row.severity()).isEqualTo("HIGH");
    assertThat(row.dwellSeconds()).isBetween(72 * 3600L - 120L, 72 * 3600L + 120L);

    var detail = asA(() -> service.detail(orderId));
    assertThat(detail.evidence()).hasSize(2);
    assertThat(detail.evidence())
        .extracting(AnomalyOrderEvidence::dimension)
        .containsExactlyInAnyOrder("DUPLICATE_ORDER", "ADDRESS_FRAUD_SUSPECTED");
  }

  // ==========================================================================
  // 分页 / 筛选 / 排序
  // ==========================================================================
  @Test
  void 分页是真的且合计不受分页影响() {
    UUID p1 = seedOrder("SO-P1", UUID.randomUUID(), new BigDecimal("1"), H72);
    UUID p2 = seedOrder("SO-P2", UUID.randomUUID(), new BigDecimal("1"), H48);
    UUID p3 = seedOrder("SO-P3", UUID.randomUUID(), new BigDecimal("1"), H24);
    seedWritebackTask(p1, "SO-P1", "SHOP-1", "PENDING", H72);
    seedWritebackTask(p2, "SO-P2", "SHOP-1", "PENDING", H48);
    seedWritebackTask(p3, "SO-P3", "SHOP-1", "PENDING", H24);

    // 默认排序是**卡得最久的排最前**（停留时长从长到短）⇒ 停了三天的在第一页。
    var first = asA(() -> service.page(null, null, null, null, null, null, 0, 1));
    assertThat(first.total()).isEqualTo(3);
    assertThat(first.totalPages()).isEqualTo(3);
    assertThat(first.items()).hasSize(1);
    assertThat(first.items().get(0).orderNo()).isEqualTo("SO-P1");
    assertThat(first.summary().total()).isEqualTo(3);

    var second = asA(() -> service.page(null, null, null, null, null, null, 1, 1));
    assertThat(second.items()).hasSize(1);
    assertThat(second.items().get(0).orderNo()).isEqualTo("SO-P2");

    var third = asA(() -> service.page(null, null, null, null, null, null, 2, 1));
    assertThat(third.items()).hasSize(1);
    assertThat(third.items().get(0).orderNo()).isEqualTo("SO-P3");
    // 合计**不随翻页变**：三页看到的都是同一个 3。
    assertThat(third.summary().total()).isEqualTo(3);
  }

  @Test
  void 筛选按异常类型与店铺与关键字生效() {
    UUID addrOrder = seedOrder("SO-A", UUID.randomUUID(), new BigDecimal("1"), H72);
    seedUndeliverableAddress(addrOrder);
    UUID wbOrder = seedOrder("SO-B", UUID.randomUUID(), new BigDecimal("1"), H72);
    seedWritebackTask(wbOrder, "SO-B", "SHOP-9", "PENDING", H72);

    var byType = asA(() -> service.page("ADDRESS_UNDELIVERABLE", null, null, null, null, null, 0, 50));
    assertThat(byType.total()).isEqualTo(1);
    assertThat(byType.items().get(0).orderNo()).isEqualTo("SO-A");

    var byShop = asA(() -> service.page(null, "SHOP-9", null, null, null, null, 0, 50));
    assertThat(byShop.total()).isEqualTo(1);
    assertThat(byShop.items().get(0).orderNo()).isEqualTo("SO-B");

    var byKeyword = asA(() -> service.page(null, null, "SO-B", null, null, null, 0, 50));
    assertThat(byKeyword.total()).isEqualTo(1);
    assertThat(byKeyword.items().get(0).orderNo()).isEqualTo("SO-B");

    var bySource = asA(() -> service.page(null, null, null, "AMAZON", null, null, 0, 50));
    assertThat(bySource.total()).isEqualTo(2);

    var byDwell = asA(() -> service.page(null, null, null, null, 48, null, 0, 50));
    assertThat(byDwell.total()).isEqualTo(2);
  }

  @Test
  void 非法排序键与非法异常类型报四百() {
    assertThatThrownBy(() -> asA(() -> service.page(null, null, null, null, null, "nope", 0, 50)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("排序键");
    assertThatThrownBy(
            () -> asA(() -> service.page("NOT_A_TYPE", null, null, null, null, null, 0, 50)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("异常类型不存在");
  }

  @Test
  void 清单与明细口径一致且明细给出费用与留痕() {
    UUID orderId = seedOrder("SO-SAME", UUID.randomUUID(), new BigDecimal("2"), H72);
    seedWritebackTask(orderId, "SO-SAME", "SHOP-1", "PENDING", H72);

    var row = asA(() -> service.page(null, null, null, null, null, null, 0, 50)).items().get(0);
    var detail = asA(() -> service.detail(orderId));
    assertThat(detail.order().anomalyTypes()).isEqualTo(row.anomalyTypes());
    assertThat(detail.order().anomalyLabels()).isEqualTo(row.anomalyLabels());
    assertThat(detail.order().severity()).isEqualTo(row.severity());
    assertThat(detail.charges()).hasSize(1);
    assertThat(detail.charges().get(0).chargeType()).isEqualTo("SHIPPING");
    assertThat(detail.charges().get(0).amount()).isEqualByComparingTo("10.0000");
    assertThat(detail.statusHistory()).hasSize(1);
    assertThat(detail.order().lineCount()).isEqualTo(1);
    assertThat(detail.order().totalQuantity()).isEqualByComparingTo("2.000000");
  }

  @Test
  void 明细跨租户或者不存在时是四零四而不是空对象() {
    var e =
        catchThrowable(
            () -> asA(() -> service.detail(UUID.randomUUID())));
    assertThat(e).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404);
  }

  @Test
  void 端点真的挂在命名空间下并且要认证() throws Exception {
    UUID orderId = seedOrder("SO-HTTP", UUID.randomUUID(), new BigDecimal("1"), H72);
    seedWritebackTask(orderId, "SO-HTTP", "SHOP-1", "PENDING", H72);
    var unauthorized =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/trade/anomaly-orders"))
            .andReturn()
            .getResponse();
    assertThat(unauthorized.getStatus()).isEqualTo(401);

    var ok =
        http.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/trade/anomaly-orders")
                    .header("Authorization", "Bearer tenant-a"))
            .andReturn()
            .getResponse();
    assertThat(ok.getStatus()).isEqualTo(200);
    assertThat(ok.getContentAsString()).contains("SO-HTTP");
  }
}
