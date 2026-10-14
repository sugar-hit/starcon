package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.1.1.1 订单列表（全渠道统一订单列表读投影）。
 *
 * <p>全部走 <b>HTTP 端点 + 真实 PostgreSQL 16 + 强制 RLS</b>，不使用任何内存替身。
 *
 * <p><b>夹具一律经 9.1.2.2 的真实写端点创建</b>（POST /trade/sales-orders）， 而不是在本测试里直接 INSERT ——
 * 本模块是读投影，它读到的必须是<b>真正的写入者</b> 写出来的数据。直接 INSERT 会让「读投影读到的形状」与「写入者实际写的形状」悄悄分叉， 而这种分叉只有在对账时才会暴露。
 *
 * <p><b>本测试刻意证明三件「没有」的事</b>（蓝图要而本服务做不到的）： 支付状态、履约状态、发货仓、平台状态、售后状态、平台店铺 <b>六个字段在响应里必须是 null 而不是 0
 * 或假枚举</b>； 「待处理数」必须为 null；四条批量操作必须 available=false。 这类断言才是本模块真正在守的东西 —— 只断言「列表能返回数据」的话， 哪天有人拿
 * payment_method 冒充 paymentStatus 也照样全绿。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SalesOrderListIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static final UUID userB = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("orders")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

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
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(userB, b, List.of(grant("ALL", allPermissions))));
      // SELF 数据范围：本租户、只有 orders.read、范围 = 仅本人。
      actors.put(
          "Bearer self-scope",
          actor(userB, a, List.of(grant("SELF", List.of("trade.orders.read")))));
      actors.put(
          "Bearer read-only", actor(user, a, List.of(grant("ALL", List.of("trade.orders.read")))));
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

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @Autowired SalesOrderListService listService;
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
  String postOrder(String token, String orderNo, Map<String, Object> extra) throws Exception {
    Map<String, Object> m = validBody(orderNo);
    m.putAll(extra);
    String resp =
        http.perform(
                post("/trade/sales-orders")
                    .header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(m)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(resp).get("id").asText();
  }

  String uniqueNo() {
    return "MO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
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
    l.put("attributes", List.of());
    m.put("lines", List.of(l));
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
    m.put("addresses", List.of(shipping));
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

  SalesOrderListModels.SalesOrderListQuery q(String keyword, int page, int size) {
    return new SalesOrderListModels.SalesOrderListQuery(
        null, null, null, null, null, null, null, null, null, null, keyword, null, page, size, null,
        null);
  }

  SalesOrderListModels.SalesOrderListPage inA(SalesOrderListModels.SalesOrderListQuery query) {
    return access.run(actorA, "trade.orders.read", () -> listService.list(query));
  }

  /** 把自己经手过 = 有 actor_id = user 的留痕（写端点用 A 的会话创建即满足）。 */
  SalesOrderListModels.SalesOrderListPage selfScoped(String keyword) {
    var self = actors.get("Bearer self-scope");
    return access.run(
        self,
        "trade.orders.read",
        () ->
            listService.list(
                new SalesOrderListModels.SalesOrderListQuery(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    keyword,
                    null,
                    0,
                    50,
                    self.userId(),
                    true)));
  }

  // ==========================================================================
  // 一、真实维度：可得、可筛、可排
  // ==========================================================================

  @Test
  void 列表读得到真实列_单号金额币种买家与SKU行数() throws Exception {
    String no = uniqueNo();
    postOrder("Bearer tenant-a", no, Map.of());
    var page = inA(q(no, 0, 50));
    assertThat(page.total()).isPositive();
    var row = page.rows().stream().filter(r -> r.orderNo().equals(no)).findFirst().orElseThrow();
    assertThat(row.orderSource()).isEqualTo("MANUAL");
    assertThat(row.documentStatus()).isEqualTo("DRAFT");
    assertThat(row.currency()).isEqualTo("USD");
    assertThat(row.buyerName()).isEqualTo("Acme Trading Co.");
    assertThat(row.lineCount()).isEqualTo(1);
    // 小计 2×10.50=21.00 − 折扣(行 1.00 + 费用 3.00)=4.00 + 税 2.00 + 运费 8.00 = 27.00
    assertThat(row.grandTotalAmount()).isEqualByComparingTo(new BigDecimal("27.0000"));
    assertThat(row.skuCodes()).containsExactly("SKU-BLUE-M");
    assertThat(row.createdAt()).isNotNull();
  }

  @Test
  void 真分页_LIMIT_OFFSET与总数查询一致() throws Exception {
    for (int i = 0; i < 5; i++) postOrder("Bearer tenant-a", "PG" + UUID.randomUUID(), Map.of());
    var first = inA(q(null, 0, 2));
    var second = inA(q(null, 1, 2));
    assertThat(first.rows()).hasSize(2);
    assertThat(second.rows()).hasSize(2);
    // 两页必须是**不同**的行 —— 假分页（不套 OFFSET）会返回同一页两次。
    var ids1 = first.rows().stream().map(r -> r.id()).toList();
    var ids2 = second.rows().stream().map(r -> r.id()).toList();
    assertThat(ids1).doesNotContainAnyElementsOf(ids2);
    assertThat(first.total()).isGreaterThanOrEqualTo(5);
  }

  @Test
  void 按金额筛选与排序都真的生效() throws Exception {
    postOrder("Bearer tenant-a", uniqueNo(), Map.of());
    var desc = inA(sortQuery("AMOUNT_DESC", null, null));
    var asc = inA(sortQuery("AMOUNT_ASC", null, null));
    assertThat(desc.rows()).isNotEmpty();
    var descAmounts = desc.rows().stream().map(r -> r.grandTotalAmount()).toList();
    var ascAmounts = asc.rows().stream().map(r -> r.grandTotalAmount()).toList();
    assertThat(descAmounts).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    assertThat(ascAmounts).isSortedAccordingTo(java.util.Comparator.naturalOrder());
    // 金额区间筛选
    var high = inA(sortQuery(null, new BigDecimal("10000"), null));
    assertThat(high.rows()).isEmpty();
  }

  @Test
  void 按币种与单据状态筛选() throws Exception {
    String no = uniqueNo();
    postOrder("Bearer tenant-a", no, Map.of("currency", "EUR"));
    var eur = inA(currencyQuery("EUR"));
    assertThat(ordersOf(eur.rows())).contains(no);
    var draft = inA(statusQuery("DRAFT"));
    assertThat(draft.total()).isPositive();
    var submitted = inA(statusQuery("SUBMITTED"));
    assertThat(submitted.total()).isZero();
  }

  @Test
  void 按SKU筛选能反查订单() throws Exception {
    // 用固定 SKU 建一张单，再按这个 skuId 反查。
    UUID sku = UUID.randomUUID();
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("skuId", sku.toString());
    line.put("skuCode", "SKU-FIND-ME");
    line.put("skuName", "可反查商品");
    line.put("specSnapshot", "");
    line.put("skuMappingVersion", "");
    line.put("quantity", "1");
    line.put("unitPrice", "5.00");
    line.put("discountAmount", "0");
    line.put("taxAmount", "0");
    line.put("note", "");
    line.put("attributes", List.of());
    Map<String, Object> body = validBody(uniqueNo());
    body.put("lines", List.of(line));
    body.put("charges", List.of());
    String no = (String) body.get("orderNo");
    http.perform(
            post("/trade/sales-orders")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
    var bySku =
        access.run(
            actorA,
            "trade.orders.read",
            () ->
                listService.list(
                    new SalesOrderListModels.SalesOrderListQuery(
                        null, null, null, null, null, sku, null, null, null, null, null, null, 0,
                        50, null, null)));
    assertThat(bySku.rows()).extracting(r -> r.orderNo()).contains(no);
  }

  @Test
  void 按时间筛选真的按timestamptz走() throws Exception {
    String no = uniqueNo();
    postOrder("Bearer tenant-a", no, Map.of());
    // 把订单的 created_at 改到一个确定的历史时刻（2026-01-15T08:30:00Z）。
    // ⚠️ 必须在**可信租户事务**里改：RLS 在没有 app.current_tenant 的连接上直接看不见行，
    // 裸 sql.execute 会被静默挡下（改动 0 行），那会让「时间筛选失效」看起来像筛选的错。
    OffsetDateTime past = OffsetDateTime.of(2026, 1, 15, 8, 30, 0, 0, ZoneOffset.UTC);
    int changed =
        access.run(
            actorA,
            "trade.orders.read",
            () ->
                sql.execute(
                    "update sales_order set created_at = ?::timestamptz where order_no = ?",
                    past,
                    no));
    assertThat(changed).as("测试夹具必须真的改到那一行").isEqualTo(1);
    var hit =
        inA(
            new SalesOrderListModels.SalesOrderListQuery(
                null,
                null,
                null,
                null,
                null,
                null,
                past.minusDays(1),
                past.plusDays(1),
                null,
                null,
                null,
                null,
                0,
                50,
                null,
                null));
    assertThat(hit.rows()).extracting(r -> r.orderNo()).contains(no);
    // ⚠️ 时区往返断言：写进去的是 +08:00，读回来必须是**同一个瞬间**。
    // Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，这里逐值断言。
    var byOffset =
        inA(
            new SalesOrderListModels.SalesOrderListQuery(
                null,
                null,
                null,
                null,
                null,
                null,
                past.toInstant().atOffset(ZoneOffset.ofHours(8)).minusDays(1),
                past.toInstant().atOffset(ZoneOffset.ofHours(8)).plusDays(1),
                null,
                null,
                null,
                null,
                0,
                50,
                null,
                null));
    assertThat(byOffset.rows()).extracting(r -> r.orderNo()).contains(no);
    // 读回的 created_at 必须与写入瞬间一致（不是偏 8 小时的另一个瞬间）。
    var row =
        byOffset.rows().stream().filter(r -> r.orderNo().equals(no)).findFirst().orElseThrow();
    assertThat(row.createdAt().toInstant()).isEqualTo(past.toInstant());
    // 区间不覆盖时必须筛不到。
    var miss =
        inA(
            new SalesOrderListModels.SalesOrderListQuery(
                null,
                null,
                null,
                null,
                null,
                null,
                past.plusDays(30),
                past.plusDays(31),
                null,
                null,
                null,
                null,
                0,
                50,
                null,
                null));
    assertThat(miss.rows()).extracting(r -> r.orderNo()).doesNotContain(no);
  }

  @Test
  void 关键字能搜到单号与买家名() throws Exception {
    String no = uniqueNo();
    postOrder("Bearer tenant-a", no, Map.of());
    assertThat(inA(q(no, 0, 50)).total()).isPositive();
    assertThat(inA(q("Acme", 0, 50)).total()).isPositive();
  }

  @Test
  void 非法排序值报400而不是静默回落() throws Exception {
    http.perform(
            get("/trade/sales-orders/list")
                .header("Authorization", "Bearer tenant-a")
                .param("sort", "FULFILLMENT_STATUS"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 数据范围SELF真的收窄到本人经手过的订单() throws Exception {
    String mine = uniqueNo();
    postOrder("Bearer tenant-a", mine, Map.of());
    // self-scope 是 userB：它看不到 user（=userId() 常量 user）经手的单。
    assertThat(selfScoped(mine).total()).isZero();
    // 同一个 self-scope 会话用只读角色（ALL 范围）看得到 —— 证明上一条不是「没数据」。
    var viaSelfActorUser =
        access.run(
            actors.get("Bearer self-scope"),
            "trade.orders.read",
            () ->
                listService.list(
                    new SalesOrderListModels.SalesOrderListQuery(
                        null, null, null, null, null, null, null, null, null, null, mine, null, 0,
                        50, null, null)));
    assertThat(viaSelfActorUser.total()).as("SELF 收窄的是范围，不是数据本身").isPositive();
  }

  // ==========================================================================
  // 二、拿不到的维度：**必须是 null / available=false**
  // ==========================================================================

  @Test
  void 六个拿不到的维度在响应里必须是null而不是0或假枚举() throws Exception {
    String no = uniqueNo();
    postOrder("Bearer tenant-a", no, Map.of());
    var row =
        inA(q(no, 0, 50)).rows().stream()
            .filter(r -> r.orderNo().equals(no))
            .findFirst()
            .orElseThrow();
    // ⚠️ 绝不能拿 payment_method 冒充 paymentStatus：
    // 付款方式是「约定」（CREDIT_TERM / 预付 / 货到付），支付状态是「实收流水」。
    assertThat(row.paymentStatus()).as("paymentMethod 不是 paymentStatus").isNull();
    // ⚠️ 绝不能拿 status（DRAFT/SUBMITTED）冒充履约状态：
    // 那是「有没有进审单队列」，不是「拣货/发货/签收」。
    assertThat(row.fulfillmentStatus()).as("status 不是履约状态").isNull();
    assertThat(row.shippingWarehouse()).isNull();
    assertThat(row.platformStatus()).isNull();
    assertThat(row.aftersalesStatus()).isNull();
    // 单据状态仍如实返回，且**与**履约状态是两个不同字段。
    assertThat(row.documentStatus()).isIn("DRAFT", "SUBMITTED");
  }

  @Test
  void 能力声明逐条报出六个不可得维度的归属模块与原因() {
    var caps = access.run(actorA, "trade.orders.read", () -> listService.capabilities());
    var missing =
        List.of(
            "platformShop",
            "paymentStatus",
            "fulfillmentStatus",
            "shippingWarehouse",
            "platformStatus",
            "aftersalesStatus");
    assertThat(caps.unavailableDimensions()).hasSize(missing.size());
    for (String field : missing) {
      var d =
          caps.unavailableDimensions().stream()
              .filter(x -> x.field().equals(field))
              .findFirst()
              .orElseThrow(() -> new AssertionError("缺少维度声明：" + field));
      assertThat(d.available()).as(field + " 必须 available=false").isFalse();
      assertThat(d.ownerModule()).as(field + " 必须带 ownerModule").isNotBlank();
      assertThat(d.reason()).as(field + " 必须带原因").isNotBlank();
    }
    // 拿不到支付状态的理由里必须点明 payment_method 的区别，否则有人会拿它顶替。
    var pay =
        caps.unavailableDimensions().stream()
            .filter(d -> d.field().equals("paymentStatus"))
            .findFirst()
            .orElseThrow();
    assertThat(pay.reason()).contains("payment_method").contains("约定");
  }

  @Test
  void 汇总条的待处理数报null而不自造口径() {
    var s = inA(q(null, 0, 1)).summary();
    assertThat(s.pendingCount()).as("9.2 未交付，待处理数没有口径").isNull();
    assertThat(s.pendingCountOwner()).contains("9.2");
    assertThat(s.pendingCountNote()).contains("待处理数");
    assertThat(s.totalCount()).isPositive();
  }

  @Test
  void 汇总金额按币种分开报而不是跨币种相加() throws Exception {
    postOrder("Bearer tenant-a", uniqueNo(), Map.of("currency", "USD"));
    postOrder("Bearer tenant-a", uniqueNo(), Map.of("currency", "EUR"));
    var s = inA(q(null, 0, 1)).summary();
    assertThat(s.amountsByCurrency())
        .extracting(SalesOrderListModels.SalesOrderListCurrencyAmount::currency)
        .contains("USD", "EUR");
    // 每种币种各自一个金额 —— 把 USD 与 EUR 加成一个数不是任何一个金额。
    assertThat(s.amountsByCurrency()).allSatisfy(c -> assertThat(c.amount()).isNotNull());
  }

  @Test
  void 四条批量操作全部available_false并带归属与前置条件() {
    var caps = access.run(actorA, "trade.orders.read", () -> listService.capabilities());
    assertThat(caps.bulkOperations()).hasSize(4);
    for (var op : caps.bulkOperations()) {
      assertThat(op.available()).as(op.code() + " 必须 available=false").isFalse();
      assertThat(op.ownerModule()).as(op.code() + " 必须带 ownerModule").isNotBlank();
      assertThat(op.precondition()).as(op.code() + " 必须带前置条件").isNotBlank();
      assertThat(op.reason()).as(op.code() + " 必须带原因").isNotBlank();
    }
    // 备注的归属必须是 sales_order.note 的写入所有者 9.1.2.2 —— 本模块是读投影不抢写。
    var note =
        caps.bulkOperations().stream()
            .filter(o -> o.code().equals("NOTE"))
            .findFirst()
            .orElseThrow();
    assertThat(note.ownerModule()).contains("9.1.2.2");
    assertThat(note.reason()).contains("读投影");
  }

  @Test
  void 本模块不提供任何写端点() throws Exception {
    // POST 到列表路径必须 405/404 —— 蓝图职责句写了「批量操作」不等于本模块能写。
    http.perform(
            post("/trade/sales-orders/list")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().is4xxClientError());
    http.perform(
            post("/trade/sales-orders/list/bulk")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 筛选项下拉的币种来自真实数据() {
    var opts = access.run(actorA, "trade.orders.read", () -> listService.filterOptions());
    assertThat(opts.orderSources()).contains("MANUAL");
    assertThat(opts.documentStatuses()).containsExactly("DRAFT", "SUBMITTED");
    assertThat(opts.sorts())
        .extracting(SalesOrderListModels.SalesOrderListSortOption::code)
        .contains("ORDERED_AT_DESC", "AMOUNT_ASC");
  }

  // ==========================================================================
  // 三、多租户三断言（AGENTS.md 明文要求，缺一不可）
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() throws Exception {
    String noA = "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    postOrder("Bearer tenant-a", noA, Map.of());
    var pageB = access.run(actorB, "trade.orders.read", () -> listService.list(q(noA, 0, 50)));
    assertThat(pageB.total()).as("B 读不到 A 的单").isZero();
    assertThat(inA(q(noA, 0, 50)).total()).as("A 用自己的单号当然查得到 —— 否则上面可能只是搜索没命中").isPositive();
  }

  @Test
  void 断言2_伪造租户头无效且行落在自称租户名下() throws Exception {
    String noA = "F" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    String idA = postOrder("Bearer tenant-a", noA, Map.of());
    // 带 A 的合法会话 + 伪造 B 的 X-Tenant-Id：请求成功，但**必须仍是 A 的行**。
    http.perform(
            get("/trade/sales-orders/list")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .param("keyword", noA))
        .andExpect(status().isOk());
    // ⚠️ 关键断言：伪造头不能把行「搬」到自称的租户名下。
    // 只断言「A 看不见 B 的」是不够的 —— 那可能被「两边都搜不到」骗过。
    var seenByA = inA(q(noA, 0, 50));
    assertThat(seenByA.rows()).extracting(r -> r.id().toString()).contains(idA);
    assertThat(seenByA.dataScope()).isNotNull();
    var pageB = access.run(actorB, "trade.orders.read", () -> listService.list(q(noA, 0, 50)));
    assertThat(pageB.total()).as("伪造的租户头不得让 A 的单出现在 B 的列表里").isZero();
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
    postOrder("Bearer tenant-a", noA, Map.of());
    postOrder("Bearer tenant-b", noB, Map.of());
    // 池大小为 1：交替读写同一批行，5 轮都必须读到本租户。
    for (int i = 0; i < 5; i++) {
      var inAA = inA(q(noA, 0, 50));
      var inBB = access.run(actorB, "trade.orders.read", () -> listService.list(q(noB, 0, 50)));
      assertThat(inAA.rows()).extracting(r -> r.orderNo()).contains(noA).doesNotContain(noB);
      assertThat(inBB.rows()).extracting(r -> r.orderNo()).contains(noB).doesNotContain(noA);
    }
  }

  // ==========================================================================
  // 四、端点边界
  // ==========================================================================

  @Test
  void 未认证访问被拒() throws Exception {
    http.perform(get("/trade/sales-orders/list")).andExpect(status().isUnauthorized());
  }

  @Test
  void 预留路径trade_orders仍然是404() throws Exception {
    // TradeTenancyIntegrationTest 为别的车道预留了 /trade/orders，本模块不得占用。
    http.perform(get("/trade/orders").header("X-Tenant-Id", a)).andExpect(status().isNotFound());
    http.perform(
            post("/trade/orders")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 不与手工单列表的端点冲突() throws Exception {
    String no = uniqueNo();
    postOrder("Bearer tenant-a", no, Map.of());
    // 9.1.2.2 的两个端点原样可用 —— 本模块是并列子路径，不是覆盖。
    http.perform(get("/trade/sales-orders").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/trade/sales-orders/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/trade/sales-orders/list").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(
            get("/trade/sales-orders/list/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/trade/sales-orders/list/filters").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
  }

  // ---------------------------------------------------------------- 查询构造

  SalesOrderListModels.SalesOrderListQuery sortQuery(String sort, BigDecimal min, BigDecimal max) {
    return new SalesOrderListModels.SalesOrderListQuery(
        null, null, null, null, null, null, null, null, min, max, null, sort, 0, 50, null, null);
  }

  SalesOrderListModels.SalesOrderListQuery currencyQuery(String currency) {
    return new SalesOrderListModels.SalesOrderListQuery(
        null, null, currency, null, null, null, null, null, null, null, null, null, 0, 50, null,
        null);
  }

  SalesOrderListModels.SalesOrderListQuery statusQuery(String status) {
    return new SalesOrderListModels.SalesOrderListQuery(
        null, status, null, null, null, null, null, null, null, null, null, null, 0, 50, null,
        null);
  }

  static List<String> ordersOf(List<SalesOrderListModels.SalesOrderListRow> rows) {
    List<String> out = new ArrayList<>();
    rows.forEach(r -> out.add(r.orderNo()));
    return out;
  }
}
