package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.1.1.2 订单详情（控制塔读投影）。
 *
 * <p>全部走 <b>HTTP 端点 + 真实 PostgreSQL 16 + 强制 RLS</b>，不使用任何内存替身。
 *
 * <p><b>夹具一律经 9.1.2.2 的真实写端点创建</b>（POST /trade/sales-orders）， 而不是在本测试里直接 INSERT ——
 * 本模块是读投影，它读到的必须是<b>真正的写入者</b> 写出来的数据。
 *
 * <p><b>本测试刻意证明三件「没有」的事</b>（蓝图要而本服务做不到的）： 费用分摊、组件展开、支付与退款、履约批次与包裹、物流轨迹、售后关联、 平台侧原始报文 <b>七段在响应里必须是
 * null 而不是空数组或 0</b>； 四条操作入口必须 available=false； <b>状态时间线必须只有单据维度的 CREATED / SUBMITTED</b> —— 绝不能拿
 * documentStatus 冒充履约状态。 这类断言才是本模块真正在守的东西 —— 只断言「详情能返回数据」的话， 哪天有人拿 charge_kind 冒充分摊、拿
 * documentStatus 冒充履约状态也照样全绿。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SalesOrderOverviewIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static final UUID userB = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
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
          actor(
              user,
              a,
              List.of(
                  grant(
                      "ALL",
                      List.of(
                          "trade.orders.read", "trade.orders.create", "trade.orders.update")))));
      actors.put(
          "Bearer tenant-b",
          actor(
              userB,
              b,
              List.of(
                  grant(
                      "ALL",
                      List.of(
                          "trade.orders.read", "trade.orders.create", "trade.orders.update")))));
      // SELF 数据范围：本租户、只有 orders.read、范围 = 仅本人。
      actors.put(
          "Bearer self-scope",
          actor(userB, a, List.of(grant("SELF", List.of("trade.orders.read")))));
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
    // 池大小 1：断言 3（连接池归还后不串租户）才有意义 —— 池大于 1 时
    // 每个请求都可能拿到一条全新的连接，「串租户」根本不会发生，断言会假绿。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @Autowired SalesOrderOverviewService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  @BeforeEach
  void seed() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  // ================================================================ 工具

  /** 经 9.1.2.2 的**真实写端点**建一张手工单 —— 本模块读的是真写入者的产出。 */
  String postOrder(String token, String orderNo) throws Exception {
    String resp =
        http.perform(
                post("/trade/sales-orders")
                    .header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(validBody(orderNo))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(resp).get("id").asText();
  }

  static final UUID partyOrg = UUID.randomUUID();

  Map<String, Object> validBody(String orderNo) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("orderNo", orderNo);
    m.put("externalOrderNo", "PO-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
    m.put("manualKind", "B2B");
    m.put("currency", "USD");
    m.put("paymentMethod", "CREDIT_TERM");
    m.put("paymentTermsDays", 30);
    m.put("note", "线下 B2B 订单");
    m.put(
        "customer",
        Map.of(
            "customerPartyId", partyOrg.toString(),
            "customerCode", "CUST-001",
            "customerName", "Acme Trading Co.",
            "customerType", "ORGANIZATION",
            "customerDefaultCurrency", "USD"));
    Map<String, Object> l = new LinkedHashMap<>();
    l.put("skuId", UUID.randomUUID().toString());
    l.put("skuCode", "SKU-BLUE-M");
    l.put("skuName", "蓝色中码衬衫");
    l.put("specSnapshot", "蓝色 / M");
    l.put("skuMappingVersion", "map-v3");
    l.put("quantity", "2");
    l.put("unitPrice", "10.50");
    l.put("discountAmount", "-1.00");
    l.put("taxAmount", "2.00");
    l.put("note", "");
    l.put(
        "attributes",
        List.of(
            Map.of(
                "attributeName", "克重",
                "attributeValue", "180",
                "attributeUnit", "克",
                "sourceVersion", "map-v3")));
    m.put("lines", List.of(l));
    // ⚠️ 用 LinkedHashMap 而不是 Map.of：Map.of 最多支持 10 对，
    // 地址快照有 15 个字段（照 9.1.1.1 集成测试里的同一处写法）。
    Map<String, Object> shipping = new LinkedHashMap<>();
    shipping.put("addressRole", "SHIPPING");
    shipping.put("recipientName", "Jane Doe");
    shipping.put("recipientPhone", "+1-555-0100");
    shipping.put("recipientEmail", "jane@acme.example");
    shipping.put("companyName", "Acme Trading Co.");
    shipping.put("countryCode", "US");
    shipping.put("province", "CA");
    shipping.put("city", "Los Angeles");
    shipping.put("district", "");
    shipping.put("postcode", "90001");
    shipping.put("addressLine1", "100 Main St");
    shipping.put("addressLine2", "");
    shipping.put("taxId", "");
    shipping.put("note", "");

    Map<String, Object> billing = new LinkedHashMap<>();
    billing.put("addressRole", "BILLING");
    billing.put("recipientName", "Acme Trading Co. 财务部");
    billing.put("recipientPhone", "");
    billing.put("recipientEmail", "ap@acme.example");
    billing.put("companyName", "Acme Trading Co.");
    billing.put("countryCode", "US");
    billing.put("province", "CA");
    billing.put("city", "Los Angeles");
    billing.put("district", "");
    billing.put("postcode", "90001");
    billing.put("addressLine1", "200 Finance Ave");
    billing.put("addressLine2", "");
    billing.put("taxId", "");
    billing.put("note", "");
    m.put("addresses", List.of(shipping, billing));
    m.put(
        "charges",
        List.of(
            Map.of(
                "chargeType",
                "SHIPPING",
                "label",
                "海运费",
                "amount",
                "8.00",
                "chargeLevel",
                "ORDER",
                "note",
                ""),
            Map.of(
                "chargeType",
                "DISCOUNT",
                "label",
                "老客户折扣",
                "amount",
                "-3.00",
                "chargeLevel",
                "ORDER",
                "note",
                "")));
    return m;
  }

  String uniqueNo() {
    return "MO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }

  SalesOrderOverviewModels.SalesOrderOverview detailOf(AccessService.Access who, UUID id) {
    return access.run(who, "trade.orders.read", () -> service.overview(id, who.userId(), false));
  }

  // ==========================================================================
  // 一、真实分区：可得、内容真实
  // ==========================================================================

  @Test
  void 详情一次调用返回整屏_头买家地址商品行费用与时间线() throws Exception {
    String no = uniqueNo();
    UUID id = UUID.fromString(postOrder("Bearer tenant-a", no));
    var d = detailOf(actorA, id);

    // 头：单号、来源、单据状态、币种、付款方式全部真实。
    assertThat(d.header().orderNo()).isEqualTo(no);
    assertThat(d.header().orderSource()).isEqualTo("MANUAL");
    assertThat(d.header().documentStatus()).isIn("DRAFT", "SUBMITTED");
    assertThat(d.header().currency()).isEqualTo("USD");
    assertThat(d.header().paymentMethod()).isEqualTo("CREDIT_TERM");
    assertThat(d.header().paymentTermsDays()).isEqualTo(30);

    // 买家快照。
    assertThat(d.buyer()).isNotNull();
    assertThat(d.buyer().customerName()).isEqualTo("Acme Trading Co.");
    assertThat(d.buyer().customerType()).isEqualTo("ORGANIZATION");

    // 地址快照：收货与账单**各一行**（合并会让「收货改了、账单没改」无法表达）。
    assertThat(d.addresses()).hasSize(2);
    assertThat(d.addresses())
        .extracting(x -> x.addressRole())
        .containsExactly("BILLING", "SHIPPING");
    var ship =
        d.addresses().stream()
            .filter(x -> x.addressRole().equals("SHIPPING"))
            .findFirst()
            .orElseThrow();
    assertThat(ship.recipientName()).isEqualTo("Jane Doe");
    assertThat(ship.countryCode()).isEqualTo("US");
    assertThat(ship.postcode()).isEqualTo("90001");

    // 商品行：SKU / 规格 / 单价 / 数量 / 税全部是**快照列**。
    assertThat(d.lines()).hasSize(1);
    var line = d.lines().get(0);
    assertThat(line.skuCode()).isEqualTo("SKU-BLUE-M");
    assertThat(line.specSnapshot()).isEqualTo("蓝色 / M");
    assertThat(line.skuMappingVersion()).isEqualTo("map-v3");
    assertThat(line.quantity()).isEqualByComparingTo("2");
    assertThat(line.unitPrice()).isEqualByComparingTo("10.50");
    assertThat(line.lineAmount()).isEqualByComparingTo("21.0000");

    // 行属性快照按 lineId 分组。
    assertThat(d.attributesByLineId()).containsKey(line.id().toString());
    assertThat(d.attributesByLineId().get(line.id().toString()))
        .singleElement()
        .satisfies(
            at -> {
              assertThat(at.attributeName()).isEqualTo("克重");
              assertThat(at.attributeValue()).isEqualTo("180");
              assertThat(at.attributeUnit()).isEqualTo("克");
              assertThat(at.sourceVersion()).isEqualTo("map-v3");
            });
  }

  @Test
  void 费用构成逐项对齐订单头汇总_但绝不是分摊() throws Exception {
    UUID id = UUID.fromString(postOrder("Bearer tenant-a", uniqueNo()));
    var d = detailOf(actorA, id);

    // 构成：两笔费用行。
    assertThat(d.charges()).hasSize(2);
    assertThat(d.charges())
        .extracting(c -> c.chargeType())
        .containsExactlyInAnyOrder("DISCOUNT", "SHIPPING");

    // 汇总与订单头逐项相等 —— V99 的 guard_sales_order_totals 触发器保证头与明细永远相等。
    assertThat(d.chargeTotals().subtotalAmount()).isEqualByComparingTo(d.header().subtotalAmount());
    assertThat(d.chargeTotals().discountAmount()).isEqualByComparingTo(d.header().discountAmount());
    assertThat(d.chargeTotals().taxAmount()).isEqualByComparingTo(d.header().taxAmount());
    assertThat(d.chargeTotals().shippingAmount()).isEqualByComparingTo(d.header().shippingAmount());
    assertThat(d.chargeTotals().grandTotalAmount())
        .isEqualByComparingTo(d.header().grandTotalAmount());
    assertThat(d.chargeTotals().currency()).isEqualTo("USD");
    assertThat(d.chargeTotals().chargeRowCount()).isEqualTo(2);

    // ⚠️ 关键断言：**构成有，分摊没有**。
    // 拿 chargeTotals 冒充分摊，是本模块最容易犯也最有害的一种造假：
    // 它会让「这一行到底买了多少钱」保持沉默，而界面上却显示着一个像分摊的数字。
    assertThat(d.chargeAllocations()).as("本服务没有分摊表，绝不能拿费用构成冒充分摊").isNull();
    assertThat(d.chargeTotals().allocationNote()).contains("不是费用分摊");
  }

  @Test
  void 状态时间线是单据维度_绝不拿documentStatus冒充履约状态() throws Exception {
    UUID id = UUID.fromString(postOrder("Bearer tenant-a", uniqueNo()));
    // 提交一次，事件流里就有两条 CREATED + SUBMITTED。
    http.perform(
            post("/trade/sales-orders/{id}/submit", id).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    var d = detailOf(actorA, id);

    assertThat(d.timeline()).hasSizeGreaterThanOrEqualTo(2);
    assertThat(d.timeline()).extracting(e -> e.eventType()).contains("CREATED", "SUBMITTED");
    // 严格断言：**只有**这两个事件类型 —— 该表没有支付事件也没有履约事件。
    assertThat(d.timeline())
        .extracting(e -> e.eventType())
        .allMatch(t -> t.equals("CREATED") || t.equals("SUBMITTED"));

    // 时间线按 (occurred_at, id) 升序：id 兜底是必要的，
    // 同一毫秒发生的事件光看 occurred_at 顺序不确定，界面上会随机跳行。
    var times = d.timeline().stream().map(e -> e.occurredAt()).toList();
    assertThat(times).isSorted();

    // 能力声明必须点名：履约状态的时间线维度归 9.3，且说明为什么不能用 documentStatus。
    var caps = access.run(actorA, "trade.orders.read", service::capabilities);
    var fulfilment =
        caps.unavailableSections().stream()
            .filter(s -> s.key().equals("fulfillmentStatus"))
            .findFirst()
            .orElseThrow();
    assertThat(fulfilment.available()).isFalse();
    assertThat(fulfilment.ownerModule()).startsWith("9.3");
    assertThat(fulfilment.reason()).contains("CREATED").contains("SUBMITTED");
  }

  // ==========================================================================
  // 二、七段不可得分区：必须是 null，不是空数组、不是 0
  // ==========================================================================

  @Test
  void 七段不可得分区在响应里是null而不是空数组或0() throws Exception {
    UUID id = UUID.fromString(postOrder("Bearer tenant-a", uniqueNo()));
    var d = detailOf(actorA, id);

    // ⚠️ 每一条都写成 isNull()：写成 isEmpty() 就等于允许「返回空数组冒充成功」，
    // 而「空的包裹列表会被读成『这单没有包裹』而不是『这段能力还没接入』」。
    assertThat(d.chargeAllocations()).as("费用分摊：没有 order_charge_allocation 表").isNull();
    assertThat(d.lineComponents()).as("组件展开：order_line_component 在 V99 里没建").isNull();
    assertThat(d.payments()).as("支付记录：12 财务未交付").isNull();
    assertThat(d.refunds()).as("退款记录：12 财务未交付").isNull();
    assertThat(d.fulfillmentBatches()).as("履约批次：9.3.3.1 未交付").isNull();
    assertThat(d.packages()).as("包裹：8.3.4.1 未交付").isNull();
    assertThat(d.logisticsTrace()).as("物流轨迹：10 未交付").isNull();
    assertThat(d.aftersalesRefs()).as("售后关联：11 未交付").isNull();
    assertThat(d.platformRawPayload()).as("平台原始报文：9.1.2.1 未交付").isNull();
  }

  @Test
  void 不可得分区逐条点名ownerModule与原因_组件展开与费用分摊归9_1_2_2() {
    var caps = access.run(actorA, "trade.orders.read", service::capabilities);
    Map<String, SalesOrderOverviewModels.SalesOrderOverviewSection> byKey =
        new java.util.HashMap<>();
    caps.unavailableSections().forEach(s -> byKey.put(s.key(), s));
    // ⚠️ 用 containsExactlyInAnyOrder 而不是 containsExactly：keySet() 是
    // HashMap 的视图，**迭代顺序不保证**（本车道实测过一次 containsExactly
    // 因为顺序不同而红，那种红是断言写错，不是产品坏了）。
    assertThat(byKey.keySet())
        .containsExactlyInAnyOrder(
            "chargeAllocations",
            "lineComponents",
            "payments",
            "refunds",
            "fulfillmentBatches",
            "packages",
            "logisticsTrace",
            "aftersalesRefs",
            "platformRawPayload",
            "fulfillmentStatus");

    for (var s : caps.unavailableSections()) {
      assertThat(s.available()).as(s.key() + " 必须显式报 false").isFalse();
      assertThat(s.ownerModule()).as(s.key() + " 必须点名归属模块").isNotBlank();
      assertThat(s.reason()).as(s.key() + " 必须给出原因").isNotBlank();
    }

    // 组件展开：V99 没建这张表，属 9.1.2.2 的交付缺口 —— **点名它**。
    var components = byKey.get("lineComponents");
    assertThat(components.ownerModule()).startsWith("9.1.2.2");
    assertThat(components.reason())
        .as("必须点名 order_line_component 这张表没建，否则下一个人会去 catalog 找")
        .contains("order_line_component")
        .contains("V99")
        .contains("交付缺口");

    // 费用分摊：没有分摊表；且必须说清 charge_kind 不是分摊记号。
    var allocations = byKey.get("chargeAllocations");
    assertThat(allocations.ownerModule()).startsWith("9.1.2.2");
    assertThat(allocations.reason()).contains("order_charge_allocation").contains("charge_kind");

    // 其余各段的归属。
    assertThat(byKey.get("payments").ownerModule()).startsWith("12");
    assertThat(byKey.get("refunds").ownerModule()).startsWith("12");
    assertThat(byKey.get("fulfillmentBatches").ownerModule()).startsWith("9.3");
    assertThat(byKey.get("packages").ownerModule()).startsWith("8.3");
    assertThat(byKey.get("logisticsTrace").ownerModule()).startsWith("10");
    assertThat(byKey.get("aftersalesRefs").ownerModule()).startsWith("11");
    assertThat(byKey.get("platformRawPayload").ownerModule()).startsWith("9.1.2.1");

    // 可得分区同样要报出来，避免调用方从 null 里猜。
    assertThat(caps.availableSections()).anyMatch(s -> s.contains("order_charge"));
    assertThat(caps.availableSections()).anyMatch(s -> s.contains("sales_order_line"));
  }

  @Test
  void 四条操作入口全部available为false_本模块没有任何写端点() throws Exception {
    var caps = access.run(actorA, "trade.orders.read", service::capabilities);
    assertThat(caps.actions())
        .extracting(SalesOrderOverviewModels.SalesOrderOverviewAction::code)
        .containsExactly("REVIEW", "NOTE", "CANCEL", "DUNNING");
    assertThat(caps.actions()).allMatch(x -> !x.available());
    assertThat(caps.actions()).allMatch(x -> x.ownerModule() != null && !x.ownerModule().isBlank());

    // 备注归 9.1.2.2：它是 sales_order.note 的**写入所有者**，本模块是读投影不抢写。
    var note =
        caps.actions().stream().filter(x -> x.code().equals("NOTE")).findFirst().orElseThrow();
    assertThat(note.ownerModule()).startsWith("9.1.2.2");
    assertThat(note.reason()).contains("写入所有权");

    // HTTP 边界上同样没有写端点：本模块在这个路径上只映射了 GET。
    // ⚠️ 断言的是 **405 而不是 404** —— 这是有意为之，而且比 404 更强：
    // 405 说明 Spring 认得这个路径、只是不接受该方法，也就是「资源存在但本模块不写它」；
    // 404 反而会掩盖「这个路径其实有 GET、只是方法不对」这个事实。
    // （若哪天有人给本模块加了写端点，这里会变成 2xx 并当场红掉。）
    UUID id = UUID.fromString(postOrder("Bearer tenant-a", uniqueNo()));
    http.perform(
            post("/trade/sales-orders/detail/{id}", id).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isMethodNotAllowed());
    http.perform(
            put("/trade/sales-orders/detail/{id}", id)
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isMethodNotAllowed());
    http.perform(
            delete("/trade/sales-orders/detail/{id}", id)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isMethodNotAllowed());
  }

  // ==========================================================================
  // 三、履约引用（给 warehouse 的授权 API）
  // ==========================================================================

  @Test
  void 履约引用按订单号加来源返回关联订单与买家地址() throws Exception {
    String no = uniqueNo();
    UUID id = UUID.fromString(postOrder("Bearer tenant-a", no));

    var ref =
        access.run(actorA, "trade.orders.read", () -> service.fulfillmentReference(no, "MANUAL"));
    assertThat(ref.salesOrderId()).isEqualTo(id);
    assertThat(ref.orderNo()).isEqualTo(no);
    assertThat(ref.orderSource()).isEqualTo("MANUAL");

    // 这两块正是 8.3.1.2 出库单详情上 available=false 的两块灰块（ownerModule = 9.1.1.2）。
    assertThat(ref.buyer()).isNotNull();
    assertThat(ref.buyer().customerName()).isEqualTo("Acme Trading Co.");
    assertThat(ref.shipTo()).isNotNull();
    assertThat(ref.shipTo().recipientName()).isEqualTo("Jane Doe");
    assertThat(ref.shipTo().addressLine1()).isEqualTo("100 Main St");
    assertThat(ref.billTo()).isNotNull();
    assertThat(ref.lines()).hasSize(1);

    // 必须说清「地址是订单侧冻结的那一份，warehouse 不要抄第二份」。
    assertThat(ref.consumptionNote()).contains("不要在出库单上抄第二份");
    // documentStatus 是单据状态，**不是**履约状态 —— 消费方不能误用。
    assertThat(ref.consumptionNote()).contains("不是履约状态");
  }

  @Test
  void 履约引用必须同时给来源_同号不同来源是两件不相干的事() throws Exception {
    String no = uniqueNo();
    postOrder("Bearer tenant-a", no);

    // 来源必填：只给订单号会漏掉「同号不同来源」这一维。
    assertThatThrownBy(() -> service.fulfillmentReference(no, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("订单来源不能为空");
    assertThatThrownBy(() -> service.fulfillmentReference(no, "  "))
        .isInstanceOf(IllegalArgumentException.class);

    // 来源给错 → 查不到（而不是查到一张不相干的单）。
    var self = actors.get("Bearer tenant-a");
    assertThatThrownBy(
            () ->
                access.run(
                    self, "trade.orders.read", () -> service.fulfillmentReference(no, "PLATFORM")))
        .hasMessageContaining("不存在或无权查看");

    // 订单号也必填。
    assertThatThrownBy(() -> service.fulfillmentReference(null, "MANUAL"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("订单号不能为空");
  }

  @Test
  void 履约引用走HTTP端点且需要已认证() throws Exception {
    String no = uniqueNo();
    postOrder("Bearer tenant-a", no);

    // 已认证 → 200，且订单号与地址都在。
    String body =
        http.perform(
                get("/trade/sales-orders/detail/fulfillment-reference")
                    .header("Authorization", "Bearer tenant-a")
                    .param("orderNo", no)
                    .param("orderSource", "MANUAL"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var node = json.readTree(body);
    assertThat(node.get("orderNo").asText()).isEqualTo(no);
    assertThat(node.get("shipTo").get("recipientName").asText()).isEqualTo("Jane Doe");

    // 未认证 → 401。
    http.perform(
            get("/trade/sales-orders/detail/fulfillment-reference")
                .param("orderNo", no)
                .param("orderSource", "MANUAL"))
        .andExpect(status().isUnauthorized());
  }

  // ==========================================================================
  // 四、端点与命名空间（不撞 9.1.2.2 已交付的 /{id}，不撞预留的 /trade/orders）
  // ==========================================================================

  @Test
  void 详情端点在detail子路径_且不覆盖9_1_2_2的既有端点() throws Exception {
    UUID id = UUID.fromString(postOrder("Bearer tenant-a", uniqueNo()));

    // 本模块的详情端点。
    http.perform(
            get("/trade/sales-orders/detail/{id}", id).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(
            get("/trade/sales-orders/detail/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    // ⚠️ 9.1.2.2 已交付的三个端点必须**逐字不变**地继续工作。
    // 本模块刻意挂在 /detail 子路径而不是再加一个 /{id} —— 同一个
    // @RequestMapping 下两个 @GetMapping("/{id}") 会让 Spring 在启动时抛
    // ambiguous mapping，把整个 trade 服务弄挂。
    http.perform(get("/trade/sales-orders").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/trade/sales-orders/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/trade/sales-orders/{id}", id).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/trade/sales-orders/list").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    // TradeTenancyIntegrationTest 预留的 /trade/orders 仍然 404 —— 本模块不碰它。
    http.perform(get("/trade/orders").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 未认证与无权限被拒() throws Exception {
    UUID id = UUID.fromString(postOrder("Bearer tenant-a", uniqueNo()));
    http.perform(get("/trade/sales-orders/detail/{id}", id)).andExpect(status().isUnauthorized());
    http.perform(get("/trade/sales-orders/detail/capabilities"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 数据范围SELF收窄到本人经手过的订单() throws Exception {
    // A 建单（actor = user），B 的 self 角色在同一租户但 actor = userB。
    UUID id = UUID.fromString(postOrder("Bearer tenant-a", uniqueNo()));
    var self = actors.get("Bearer self-scope");

    // SELF 收窄：userB 经手过的订单里没有这张（A 建的）→ 404，与「不存在」同一形状。
    assertThatThrownBy(
            () ->
                access.run(
                    self, "trade.orders.read", () -> service.overview(id, self.userId(), true)))
        .hasMessageContaining("不存在或无权查看");

    // ALL 范围（同一个 self 身份但 selfOnly=false）能读到 —— 证明上面不是「RLS 没生效」。
    assertThat(
            access
                .run(self, "trade.orders.read", () -> service.overview(id, self.userId(), false))
                .header()
                .orderNo())
        .isNotBlank();
  }

  // ==========================================================================
  // 五、多租户三断言（AGENTS.md 明文要求，缺一不可）
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() throws Exception {
    String noA = "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    UUID idA = UUID.fromString(postOrder("Bearer tenant-a", noA));

    // A 读得到自己的单 —— 否则下面可能只是「两张单都没建成功」。
    assertThat(detailOf(actorA, idA).header().orderNo()).isEqualTo(noA);

    // B 拿 A 的 id → 404，且与「随机 id 不存在」**完全同一个形状**。
    UUID randomId = UUID.randomUUID();
    ResponseStatusException bSees =
        org.junit.jupiter.api.Assertions.assertThrows(
            ResponseStatusException.class, () -> detailOf(actorB, idA));
    ResponseStatusException random =
        org.junit.jupiter.api.Assertions.assertThrows(
            ResponseStatusException.class, () -> detailOf(actorB, randomId));
    assertThat(bSees.getStatusCode().value()).isEqualTo(404);
    assertThat(random.getStatusCode().value()).isEqualTo(404);
    // ⚠️ 文案模板必须逐字相同：只有「同一个 404」才不泄露存在性。
    // 若两者文案不同（哪怕只是把 id 换成"您无权查看"），响应时间与措辞的差异
    // 就能让 A 拿 B 的 id 逐个探测出哪些 id 是真的 —— 那正是要防的泄露面。
    assertThat(bSees.getReason()).isEqualTo("订单 " + idA + " 不存在或无权查看");
    assertThat(random.getReason()).isEqualTo("订单 " + randomId + " 不存在或无权查看");
  }

  @Test
  void 断言2_伪造租户头无效且行落在自称租户名下() throws Exception {
    String noA = "F" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    UUID idA = UUID.fromString(postOrder("Bearer tenant-a", noA));

    // 带 A 的合法会话 + 伪造 B 的 X-Tenant-Id：请求成功，但**必须仍是 A 的行**。
    http.perform(
            get("/trade/sales-orders/detail/{id}", idA)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());

    // ⚠️ 关键断言：伪造头不能把行「搬」到自称的租户名下。
    // 只断言「A 看不见 B 的」是不够的 —— 那可能被「两边都查不到」骗过。
    assertThat(detailOf(actorA, idA).header().orderNo()).isEqualTo(noA);
    assertThatThrownBy(() -> detailOf(actorB, idA))
        .as("伪造的租户头不得让 A 的单出现在 B 的视野里")
        .hasMessageContaining("不存在或无权查看");

    // 直接查库确认这一行的 tenant_id 仍是 A。
    // ⚠️ 同样必须在可信租户事务里查：RLS 下没有租户上下文就看不见任何行，
    // 那样断言会「因为查不到而通过」，等于没断言。
    var owner =
        access.run(
            actorA,
            "trade.orders.read",
            () -> sql.fetchOne("select tenant_id from sales_order where id = ?::uuid", idA));
    assertThat(owner).as("这一行必须落在 A 名下，不能被伪造头搬到 B").isNotNull();
    assertThat(owner.get(0, UUID.class)).isEqualTo(a);
  }

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    String noA = "P" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    String noB = "P" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    UUID idA = UUID.fromString(postOrder("Bearer tenant-a", noA));
    UUID idB = UUID.fromString(postOrder("Bearer tenant-b", noB));

    // 池大小为 1：交替读写同一批行，5 轮都必须读到本租户。
    for (int i = 0; i < 5; i++) {
      assertThat(detailOf(actorA, idA).header().orderNo()).isEqualTo(noA);
      assertThat(detailOf(actorB, idB).header().orderNo()).isEqualTo(noB);
      assertThatThrownBy(() -> detailOf(actorB, idA));
      assertThatThrownBy(() -> detailOf(actorA, idB));
    }
  }

  @Test
  void 履约引用同样跨租户不可见() throws Exception {
    String noA = "R" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    postOrder("Bearer tenant-a", noA);

    // B 用 A 的订单号查履约引用 → 同一个 404 形状，不能泄露 A 有这么一张单。
    assertThatThrownBy(
            () ->
                access.run(
                    actorB, "trade.orders.read", () -> service.fulfillmentReference(noA, "MANUAL")))
        .hasMessageContaining("不存在或无权查看");
    // 且 B 用 A 的订单号走 HTTP 端点同样是 404。
    http.perform(
            get("/trade/sales-orders/detail/fulfillment-reference")
                .header("Authorization", "Bearer tenant-b")
                .param("orderNo", noA)
                .param("orderSource", "MANUAL"))
        .andExpect(status().isNotFound());
  }
}
