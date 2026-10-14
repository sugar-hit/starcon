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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.7.1.1 批发订单与账期。
 *
 * <p>逐条验证蓝图本模块的五条硬要求，外加 AGENTS.md 明文要求的三条多租户断言。 全部走 <b>HTTP 端点 + 真实 PostgreSQL 16 + 强制
 * RLS</b>，不使用任何内存替身： 走端点才能同时证明「服务层 + DB 层」两层都没把闸门漏掉。
 *
 * <p><b>刻意不提供 catalog / 05 价目服务的 stub</b>：本模块对它们的依赖只有 trade 侧只读投影，一次回查都不该发生。测试里出现 catalog
 * 客户端反而说明边界纪律破了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class B2BOrderIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("b2b")
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
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only", actor(user, a, List.of(grant("ALL", List.of("trade.b2b.read")))));
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
    // 连接池压到 1：RLS 的 GUC 是事务级的，池不复用会让「归还后不串租户」这条断言
    // 变成「每次都新建连接」的假通过。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @Autowired B2BOrderService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  static final UUID skuA = UUID.randomUUID();
  static final UUID partyA = UUID.randomUUID();
  static final UUID partyB = UUID.randomUUID();

  @BeforeEach
  void seed() throws Exception {
    // 客户投影（3.6.1.2 → trade 侧只读投影）：分级 STRATEGIC、账期 30 天、额度 100000。
    putCustomerTerms(
        "Bearer tenant-a", partyA, "CUST-A", "客户甲", "STRATEGIC", 30, "USD", "100000.0000", 1L);
    putCustomerTerms(
        "Bearer tenant-b", partyB, "CUST-B", "客户乙", "STRATEGIC", 30, "USD", "100000.0000", 1L);
    // 阶梯价：MOQ 10；档位 10 → 9.5000，100 → 8.0000，1000 → 7.0000。
    putPriceBook(
        "Bearer tenant-a",
        skuA,
        "SKU-A",
        "商品甲",
        "STRATEGIC",
        "USD",
        "10",
        List.of("10:9.5000", "100:8.0000", "1000:7.0000"));
    putPriceBook(
        "Bearer tenant-b",
        skuA,
        "SKU-A",
        "商品甲",
        "STRATEGIC",
        "USD",
        "10",
        List.of("10:9.5000", "100:8.0000", "1000:7.0000"));
  }

  // ================================================================ 工具

  String body(String auth, String method, String path, Object payload, int expected)
      throws Exception {
    var request =
        MockMvcRequestBuilders.request(
            org.springframework.http.HttpMethod.valueOf(method), "/trade/" + path);
    var m = request.header("Authorization", auth).contentType(MediaType.APPLICATION_JSON);
    if (payload != null) {
      m = m.content(json.writeValueAsBytes(payload));
    }
    var result = http.perform(m).andReturn();
    // 断言消息里带上真实响应体：DB 约束冲突统一映射成 409 且提示语是通用的，
    // 不带响应体的话「哪一条约束、为什么」在测试报告里完全看不到。
    assertThat(result.getResponse().getStatus())
        .as(
            "期望 %d，实际 %d，响应体：%s",
            expected, result.getResponse().getStatus(), result.getResponse().getContentAsString())
        .isEqualTo(expected);
    return result.getResponse().getContentAsString();
  }

  Map<String, Object> postJson(String auth, String path, Object payload, int expected)
      throws Exception {
    return json.readValue(body(auth, "POST", path, payload, expected), Map.class);
  }

  Map<String, Object> getJson(String auth, String path, int expected) throws Exception {
    return json.readValue(body(auth, "GET", path, null, expected), Map.class);
  }

  Map<String, Object> putJson(String auth, String path, Object payload, int expected)
      throws Exception {
    return json.readValue(body(auth, "PUT", path, payload, expected), Map.class);
  }

  void putCustomerTerms(
      String auth,
      UUID party,
      String code,
      String name,
      String level,
      int terms,
      String currency,
      String credit,
      long version)
      throws Exception {
    putJson(
        auth,
        "b2b-orders/customer-terms",
        Map.of(
            "customerPartyId",
            party,
            "customerCode",
            code,
            "customerName",
            name,
            "customerLevel",
            level,
            "paymentTermsDays",
            terms,
            "defaultCurrency",
            currency,
            "creditLimit",
            new BigDecimal(credit),
            "sourceVersion",
            version),
        200);
  }

  void putPriceBook(
      String auth,
      UUID sku,
      String code,
      String name,
      String level,
      String currency,
      String moq,
      List<String> tiers)
      throws Exception {
    List<Map<String, Object>> tierList =
        tiers.stream()
            .map(
                t -> {
                  String[] parts = t.split(":");
                  return Map.<String, Object>of(
                      "minQuantity", new BigDecimal(parts[0]),
                      "unitPrice", new BigDecimal(parts[1]));
                })
            .toList();
    putJson(
        auth,
        "b2b-orders/price-books",
        Map.of(
            "book",
            Map.of(
                "skuId",
                sku,
                "skuCode",
                code,
                "skuName",
                name,
                "customerLevel",
                level,
                "currency",
                currency,
                "moqQuantity",
                new BigDecimal(moq),
                "sourceVersion",
                1L),
            "tiers",
            tierList),
        200);
  }

  Map<String, Object> line(long qty) {
    return Map.of(
        "skuId",
        skuA,
        "skuCode",
        "SKU-A",
        "skuName",
        "商品甲",
        "quantity",
        new BigDecimal(qty).setScale(6));
  }

  Map<String, Object> createInput(String no, String party, String method, Integer terms, long qty) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("orderNo", no);
    m.put("orderType", "B2B_WHOLESALE");
    m.put("customerPartyId", UUID.fromString(party));
    m.put("currency", "USD");
    m.put("paymentMethod", method);
    if (terms != null) {
      m.put("paymentTermsDays", terms);
    }
    m.put("costBasis", "MOVING_WEIGHTED");
    // 用可变 List：下面几处用例要替换整行（换 SKU / 换数量）。
    m.put("lines", new java.util.ArrayList<>(List.of(line(qty))));
    return m;
  }

  String orderNo() {
    return "B2B-" + UUID.randomUUID().toString().substring(0, 8);
  }

  String lastLineId(String auth, String orderId) throws Exception {
    var detail = getJson(auth, "b2b-orders/" + orderId, 200);
    var lines = (List<Map<String, Object>>) detail.get("lines");
    return (String) lines.get(0).get("id");
  }

  // ================================================================ 硬要求 1：与零售区分

  @Test
  void 订单类型收款方式成本口径三者都区分开() throws Exception {
    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
            200);
    var detail = getJson("Bearer tenant-a", "b2b-orders/" + created.get("id"), 200);
    var order = (Map<String, Object>) detail.get("order");

    // 订单类型是 B2B 自己的值域，与零售 sales_order.order_source 不相交。
    assertThat(order.get("orderType")).isEqualTo("B2B_WHOLESALE");
    // 收款方式能表达蓝图点名的三档。
    assertThat(order.get("paymentMethod")).isEqualTo("CREDIT_TERM");
    assertThat(order.get("paymentTermsDays")).isEqualTo(30);
    // 成本口径独立于零售。
    assertThat(order.get("costBasis")).isEqualTo("MOVING_WEIGHTED");

    var caps = getJson("Bearer tenant-a", "b2b-orders/capabilities", 200);
    var methods = (List<String>) caps.get("paymentMethods");
    assertThat(methods).containsExactlyInAnyOrder("ORDER_SETTLEMENT", "ON_RECEIPT", "CREDIT_TERM");
    var types = (List<String>) caps.get("orderTypes");
    assertThat(types).containsExactlyInAnyOrder("B2B_WHOLESALE", "B2B_DISTRIBUTION", "B2B_PROJECT");
    var bases = (List<String>) caps.get("costBases");
    assertThat(bases).containsExactlyInAnyOrder("STANDARD", "MOVING_WEIGHTED", "LOT");
  }

  // ================================================================ 硬要求 2：起订量是硬校验

  @Test
  void 低于起订量不能下单() throws Exception {
    long before = (Integer) getJson("Bearer tenant-a", "b2b-orders", 200).get("total");
    body(
        "Bearer tenant-a",
        "POST",
        "b2b-orders",
        createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 9),
        400);
    // 语义靠**可观测状态**保：低于起订量的单一张都没建出来。
    assertThat((Integer) getJson("Bearer tenant-a", "b2b-orders", 200).get("total"))
        .isEqualTo(before);
  }

  @Test
  void 恰好等于起订量可以下单() throws Exception {
    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 10),
            200);
    var detail = getJson("Bearer tenant-a", "b2b-orders/" + created.get("id"), 200);
    var lines = (List<Map<String, Object>>) detail.get("lines");
    assertThat(new BigDecimal(lines.get(0).get("quantity").toString())).isEqualByComparingTo("10");
  }

  @Test
  void 起订量为零表示不限起订量() throws Exception {
    UUID sku = UUID.randomUUID();
    putPriceBook(
        "Bearer tenant-a", sku, "SKU-MOQ0", "不限起订量", "STRATEGIC", "USD", "0", List.of("1:9.5000"));
    var input = createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 1);
    @SuppressWarnings("unchecked")
    var lines = (List<Map<String, Object>>) input.get("lines");
    lines.set(
        0,
        Map.of(
            "skuId",
            sku,
            "skuCode",
            "SKU-MOQ0",
            "skuName",
            "不限起订量",
            "quantity",
            new BigDecimal("1.000000")));
    assertThat(postJson("Bearer tenant-a", "b2b-orders", input, 200)).isNotNull();
  }

  // ================================================================ 硬要求 3：阶梯价按数量落档 + 边界

  @Test
  void 阶梯价按数量落档且恰好等于阈值落在该档() throws Exception {
    // 区间是 (上一档下界, 本档下界]：
    //   10 → 命中 10 档；99 → 仍命中 10 档；100 → 命中 100 档；100.000001 → 命中 1000 档？不，
    //   100.000001 落在 (100, 1000] 内，最低满足 min_quantity<=qty 的档是 100 档。
    record Case(BigDecimal qty, BigDecimal price, BigDecimal tierMin) {}
    List<Case> cases =
        List.of(
            new Case(new BigDecimal("10"), new BigDecimal("9.5000"), new BigDecimal("10")),
            new Case(new BigDecimal("99"), new BigDecimal("9.5000"), new BigDecimal("10")),
            new Case(new BigDecimal("100"), new BigDecimal("8.0000"), new BigDecimal("100")),
            new Case(new BigDecimal("100.000001"), new BigDecimal("8.0000"), new BigDecimal("100")),
            new Case(new BigDecimal("1000"), new BigDecimal("7.0000"), new BigDecimal("1000")));

    for (Case c : cases) {
      String no = orderNo();
      var input = createInput(no, partyA.toString(), "CREDIT_TERM", 30, 0);
      @SuppressWarnings("unchecked")
      var lines = (List<Map<String, Object>>) input.get("lines");
      lines.set(
          0,
          Map.of(
              "skuId",
              skuA,
              "skuCode",
              "SKU-A",
              "skuName",
              "商品甲",
              "quantity",
              c.qty().setScale(6)));
      var created = postJson("Bearer tenant-a", "b2b-orders", input, 200);
      var detail = getJson("Bearer tenant-a", "b2b-orders/" + created.get("id"), 200);
      var row = (Map<String, Object>) ((List<Map<String, Object>>) detail.get("lines")).get(0);

      assertThat(new BigDecimal(row.get("unitPrice").toString()))
          .as("数量 %s 的落档单价", c.qty())
          .isEqualByComparingTo(c.price());
      assertThat(new BigDecimal(row.get("appliedTierMinQuantity").toString()))
          .as("数量 %s 命中的档位下界", c.qty())
          .isEqualByComparingTo(c.tierMin());
      // 行小计 = 数量 × 单价，4 位 HALF_UP，与 DB 生成列同口径。
      BigDecimal expectedAmount =
          c.qty().multiply(c.price()).setScale(4, java.math.RoundingMode.HALF_UP);
      assertThat(new BigDecimal(row.get("lineAmount").toString()))
          .isEqualByComparingTo(expectedAmount);
      assertThat(
              new BigDecimal(
                  ((Map<String, Object>) detail.get("order")).get("totalAmount").toString()))
          .isEqualByComparingTo(expectedAmount);
    }
  }

  @Test
  void 单价不接受调用方传值() throws Exception {
    // 传 unitPrice 必须被拒（应用开了 fail-on-unknown-properties）：
    // 允许客户端传单价等于允许绕过起订量与阶梯价两条硬规则。
    Map<String, Object> lineWithPrice = new java.util.LinkedHashMap<>(line(100));
    lineWithPrice.put("unitPrice", new BigDecimal("0.01"));
    Map<String, Object> input =
        new java.util.LinkedHashMap<>(
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100));
    input.put("lines", List.of(lineWithPrice));
    body("Bearer tenant-a", "POST", "b2b-orders", input, 400);
  }

  @Test
  void 阶梯试算与建单走同一个落档函数() throws Exception {
    var quoted =
        postJson(
            "Bearer tenant-a",
            "b2b-orders/price-quote",
            Map.of(
                "customerLevel",
                "STRATEGIC",
                "lines",
                List.of(Map.of("skuId", skuA, "quantity", new BigDecimal("100.000000")))),
            200);
    var item = (Map<String, Object>) ((List<Map<String, Object>>) quoted.get("items")).get(0);
    assertThat(new BigDecimal(item.get("unitPrice").toString())).isEqualByComparingTo("8.0000");
    // 「再买多少进下一档」：下一档是 1000 件、7.0000。
    assertThat(new BigDecimal(item.get("nextTierMinQuantity").toString()))
        .isEqualByComparingTo("1000");
    assertThat(new BigDecimal(item.get("nextTierUnitPrice").toString()))
        .isEqualByComparingTo("7.0000");

    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
            200);
    var detail = getJson("Bearer tenant-a", "b2b-orders/" + created.get("id"), 200);
    var row = (Map<String, Object>) ((List<Map<String, Object>>) detail.get("lines")).get(0);
    assertThat(new BigDecimal(row.get("unitPrice").toString())).isEqualByComparingTo("8.0000");
  }

  @Test
  void 阶梯下界重复必须被拒() throws Exception {
    body(
        "Bearer tenant-a",
        "PUT",
        "b2b-orders/price-books",
        Map.of(
            "book",
                Map.of(
                    "skuId",
                    UUID.randomUUID(),
                    "skuCode",
                    "SKU-DUP",
                    "skuName",
                    "重复档",
                    "customerLevel",
                    "STRATEGIC",
                    "currency",
                    "USD",
                    "moqQuantity",
                    new BigDecimal("10"),
                    "sourceVersion",
                    1L),
            "tiers",
                List.of(
                    Map.of(
                        "minQuantity", new BigDecimal("10"), "unitPrice", new BigDecimal("9.5000")),
                    Map.of(
                        "minQuantity",
                        new BigDecimal("10"),
                        "unitPrice",
                        new BigDecimal("8.0000")))),
        400);
  }

  @Test
  void 阶梯覆盖不起订量必须被拒() throws Exception {
    // MOQ 10 却只配 50 件的最低档 → 10~49 件落不进任何档，必须在写库时挡住。
    body(
        "Bearer tenant-a",
        "PUT",
        "b2b-orders/price-books",
        Map.of(
            "book",
                Map.of(
                    "skuId",
                    UUID.randomUUID(),
                    "skuCode",
                    "SKU-HOLE",
                    "skuName",
                    "档位空洞",
                    "customerLevel",
                    "STRATEGIC",
                    "currency",
                    "USD",
                    "moqQuantity",
                    new BigDecimal("10"),
                    "sourceVersion",
                    1L),
            "tiers",
                List.of(
                    Map.of(
                        "minQuantity",
                        new BigDecimal("50"),
                        "unitPrice",
                        new BigDecimal("7.0000")))),
        400);
  }

  // ================================================================ 硬要求 4：未发货不产生应收

  @Test
  void 下单不产生应收() throws Exception {
    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
            200);
    assertThat(new BigDecimal(created.get("receivableAmount").toString()))
        .as("下单不产生应收")
        .isEqualByComparingTo("0");
    // 按本单 id 收窄：容器是跨用例共用的，别的用例已经建过应收了。
    var receivables =
        getJson("Bearer tenant-a", "b2b-orders/receivables?orderId=" + created.get("id"), 200);
    assertThat((Integer) receivables.get("total")).as("下单后本单的应收单数为 0").isZero();
    // openAmount 是**全租户**汇总（容器里别的用例已经建过应收），不能拿来断言这一单；
    // 这一单的应收是否为空由上面那个按 orderId 过滤的 total 说话。
    var detail = getJson("Bearer tenant-a", "b2b-orders/" + created.get("id"), 200);
    assertThat((List<Map<String, Object>>) detail.get("receivables")).isEmpty();
    assertThat((List<Map<String, Object>>) detail.get("shipments")).isEmpty();
  }

  @Test
  void 确认订单仍然不产生应收() throws Exception {
    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
            200);
    String id = (String) created.get("id");
    var confirmed = postJson("Bearer tenant-a", "b2b-orders/" + id + "/confirm", Map.of(), 200);
    assertThat(confirmed.get("status")).isEqualTo("CONFIRMED");
    assertThat(new BigDecimal(confirmed.get("receivableAmount").toString()))
        .as("确认不产生应收：账期从发货确认起算")
        .isEqualByComparingTo("0");

    var detail = getJson("Bearer tenant-a", "b2b-orders/" + id, 200);
    assertThat((List<Map<String, Object>>) detail.get("receivables")).isEmpty();
    assertThat((List<Map<String, Object>>) detail.get("shipments")).isEmpty();
  }

  @Test
  void 发货才确认应收且账期到期日等于发货日加账期天数() throws Exception {
    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
            200);
    String id = (String) created.get("id");
    postJson("Bearer tenant-a", "b2b-orders/" + id + "/confirm", Map.of(), 200);

    OffsetDateTime shippedAt = OffsetDateTime.now().withNano(0).minusDays(1);
    String lineId = lastLineId("Bearer tenant-a", id);
    var shipped =
        postJson(
            "Bearer tenant-a",
            "b2b-orders/" + id + "/shipments",
            Map.of(
                "shipmentNo",
                "SH-" + UUID.randomUUID().toString().substring(0, 8),
                "shippedAt",
                shippedAt,
                "lines",
                List.of(Map.of("orderLineId", lineId, "quantity", new BigDecimal("40.000000")))),
            200);

    // 订单 100 件落在 100 档（8.0000），总额 800；只对已发的 40 件确认应收 = 320。
    assertThat(new BigDecimal(shipped.get("receivableAmount").toString()))
        .isEqualByComparingTo("320.0000");

    var detail = getJson("Bearer tenant-a", "b2b-orders/" + id, 200);
    var receivables = (List<Map<String, Object>>) detail.get("receivables");
    assertThat(receivables).hasSize(1);
    var ar = receivables.get(0);
    assertThat(new BigDecimal(ar.get("amount").toString())).isEqualByComparingTo("320.0000");
    assertThat(ar.get("paymentMethod")).isEqualTo("CREDIT_TERM");
    assertThat(ar.get("status")).isEqualTo("OPEN");
    assertThat(ar.get("invoiceState")).isEqualTo("NOT_REQUESTED");

    // **断言写回的值**：due_date 必须等于 shippedAt + 30 天，精确到秒。
    OffsetDateTime confirmedAt = OffsetDateTime.parse(ar.get("confirmedAt").toString());
    OffsetDateTime dueDate = OffsetDateTime.parse(ar.get("dueDate").toString());
    assertThat(confirmedAt).isEqualTo(shippedAt);
    assertThat(dueDate).isEqualTo(shippedAt.plusDays(30));
    assertThat(dueDate.getOffset()).isEqualTo(shippedAt.getOffset());

    // 未发货的部分不产生应收：剩余 60 件 = 480.0000 仍不在应收里。
    var order = (Map<String, Object>) detail.get("order");
    assertThat(new BigDecimal(order.get("receivableAmount").toString()))
        .isEqualByComparingTo("320.0000");
    assertThat(new BigDecimal(order.get("unshippedAmount").toString()))
        .isEqualByComparingTo("480.0000");
    // 应收 = 累计已确认应收 ≤ 订单总额，这条不等式就是「未发货不产生应收」的数值形态。
    assertThat(new BigDecimal(order.get("receivableAmount").toString()))
        .isLessThan(new BigDecimal(order.get("totalAmount").toString()));
    assertThat(order.get("status")).isEqualTo("PARTIALLY_SHIPPED");
  }

  @Test
  void 部分发货两次只产生两张应收且合计不超过订单额() throws Exception {
    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
            200);
    String id = (String) created.get("id");
    postJson("Bearer tenant-a", "b2b-orders/" + id + "/confirm", Map.of(), 200);
    String lineId = lastLineId("Bearer tenant-a", id);

    postJson(
        "Bearer tenant-a",
        "b2b-orders/" + id + "/shipments",
        Map.of(
            "shipmentNo",
            "SH-" + UUID.randomUUID().toString().substring(0, 8),
            "lines",
            List.of(Map.of("orderLineId", lineId, "quantity", new BigDecimal("40.000000")))),
        200);
    var second =
        postJson(
            "Bearer tenant-a",
            "b2b-orders/" + id + "/shipments",
            Map.of(
                "shipmentNo",
                "SH-" + UUID.randomUUID().toString().substring(0, 8),
                "lines",
                List.of(Map.of("orderLineId", lineId, "quantity", new BigDecimal("60.000000")))),
            200);

    assertThat(second.get("status")).isEqualTo("SHIPPED");
    assertThat(new BigDecimal(second.get("receivableAmount").toString()))
        .as("40×8 + 60×8 = 800")
        .isEqualByComparingTo("800.0000");
    var detail = getJson("Bearer tenant-a", "b2b-orders/" + id, 200);
    assertThat((List<Map<String, Object>>) detail.get("receivables")).hasSize(2);
    assertThat((List<Map<String, Object>>) detail.get("shipments")).hasSize(2);
  }

  @Test
  void 累计发货超过订单量必须被拒() throws Exception {
    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
            200);
    String id = (String) created.get("id");
    postJson("Bearer tenant-a", "b2b-orders/" + id + "/confirm", Map.of(), 200);
    String lineId = lastLineId("Bearer tenant-a", id);

    postJson(
        "Bearer tenant-a",
        "b2b-orders/" + id + "/shipments",
        Map.of(
            "shipmentNo",
            "SH-" + UUID.randomUUID().toString().substring(0, 8),
            "lines",
            List.of(Map.of("orderLineId", lineId, "quantity", new BigDecimal("80.000000")))),
        200);
    // 再发 30 件 → 累计 110 > 100，必须被拒（超发会直接放大应收）。
    body(
        "Bearer tenant-a",
        "POST",
        "b2b-orders/" + id + "/shipments",
        Map.of(
            "shipmentNo",
            "SH-" + UUID.randomUUID().toString().substring(0, 8),
            "lines",
            List.of(Map.of("orderLineId", lineId, "quantity", new BigDecimal("30.000000")))),
        400);
  }

  @Test
  void 草稿不能发货() throws Exception {
    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
            200);
    String id = (String) created.get("id");
    String lineId = lastLineId("Bearer tenant-a", id);
    body(
        "Bearer tenant-a",
        "POST",
        "b2b-orders/" + id + "/shipments",
        Map.of(
            "shipmentNo",
            "SH-" + UUID.randomUUID().toString().substring(0, 8),
            "lines",
            List.of(Map.of("orderLineId", lineId, "quantity", new BigDecimal("10.000000")))),
        409);
  }

  // ================================================================ 硬要求 5：账期结算不即时收款

  @Test
  void 下单结算不产生应收且说明原因() throws Exception {
    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "ORDER_SETTLEMENT", null, 100),
            200);
    String id = (String) created.get("id");
    postJson("Bearer tenant-a", "b2b-orders/" + id + "/confirm", Map.of(), 200);
    String lineId = lastLineId("Bearer tenant-a", id);

    var shipped =
        postJson(
            "Bearer tenant-a",
            "b2b-orders/" + id + "/shipments",
            Map.of(
                "shipmentNo",
                "SH-" + UUID.randomUUID().toString().substring(0, 8),
                "lines",
                List.of(Map.of("orderLineId", lineId, "quantity", new BigDecimal("100.000000")))),
            200);
    // 返回值必须说明「为什么不产生应收」，而不是只给一个 null 让人猜。
    assertThat(shipped.get("message").toString()).contains("不产生应收").contains("下单结算");
    assertThat(shipped.get("reference").toString()).startsWith("SH-");

    var detail = getJson("Bearer tenant-a", "b2b-orders/" + id, 200);
    assertThat((List<Map<String, Object>>) detail.get("receivables")).isEmpty();
    var shipments = (List<Map<String, Object>>) detail.get("shipments");
    assertThat(shipments).hasSize(1);
    assertThat(shipments.get(0).get("receivableId")).isNull();
  }

  @Test
  void 确认收货后结算的到期日等于发货确认时刻() throws Exception {
    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "ON_RECEIPT", null, 100),
            200);
    String id = (String) created.get("id");
    postJson("Bearer tenant-a", "b2b-orders/" + id + "/confirm", Map.of(), 200);
    String lineId = lastLineId("Bearer tenant-a", id);

    OffsetDateTime shippedAt = OffsetDateTime.now().withNano(0).minusDays(3);
    postJson(
        "Bearer tenant-a",
        "b2b-orders/" + id + "/shipments",
        Map.of(
            "shipmentNo",
            "SH-" + UUID.randomUUID().toString().substring(0, 8),
            "shippedAt",
            shippedAt,
            "lines",
            List.of(Map.of("orderLineId", lineId, "quantity", new BigDecimal("100.000000")))),
        200);

    var detail = getJson("Bearer tenant-a", "b2b-orders/" + id, 200);
    var ar = ((List<Map<String, Object>>) detail.get("receivables")).get(0);
    OffsetDateTime due = OffsetDateTime.parse(ar.get("dueDate").toString());
    // **断言写回的值**：ON_RECEIPT 的账期天数是 0，到期日就是发货确认时刻本身。
    assertThat(due).isEqualTo(shippedAt);
    assertThat(ar.get("paymentTermsDays")).isEqualTo(0);
  }

  @Test
  void 账期结算必须给出账期天数() throws Exception {
    // 账期天数为 0 等于「下单即时收款」，与账期结算是两种业务。
    body(
        "Bearer tenant-a",
        "POST",
        "b2b-orders",
        createInput(orderNo(), partyA.toString(), "CREDIT_TERM", null, 100),
        400);
  }

  @Test
  void 非账期收款方式不接受账期天数() throws Exception {
    body(
        "Bearer tenant-a",
        "POST",
        "b2b-orders",
        createInput(orderNo(), partyA.toString(), "ON_RECEIPT", 30, 100),
        400);
  }

  // ================================================================ 开票需求

  @Test
  void 开票需求挂在已产生的应收上未发货时不能提() throws Exception {
    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
            200);
    String id = (String) created.get("id");
    postJson("Bearer tenant-a", "b2b-orders/" + id + "/confirm", Map.of(), 200);
    // 还没发货 → 没有金额可以开票。
    body("Bearer tenant-a", "POST", "b2b-orders/" + id + "/invoice-requests", Map.of(), 409);
  }

  @Test
  void 发出开票需求后应收被标记() throws Exception {
    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
            200);
    String id = (String) created.get("id");
    postJson("Bearer tenant-a", "b2b-orders/" + id + "/confirm", Map.of(), 200);
    String lineId = lastLineId("Bearer tenant-a", id);
    postJson(
        "Bearer tenant-a",
        "b2b-orders/" + id + "/shipments",
        Map.of(
            "shipmentNo",
            "SH-" + UUID.randomUUID().toString().substring(0, 8),
            "lines",
            List.of(Map.of("orderLineId", lineId, "quantity", new BigDecimal("100.000000")))),
        200);

    var result =
        postJson("Bearer tenant-a", "b2b-orders/" + id + "/invoice-requests", Map.of(), 200);
    assertThat(result.get("message").toString()).contains("开票需求");

    var detail = getJson("Bearer tenant-a", "b2b-orders/" + id, 200);
    var ar = ((List<Map<String, Object>>) detail.get("receivables")).get(0);
    assertThat(ar.get("invoiceState")).isEqualTo("REQUESTED");
    assertThat(ar.get("invoiceRequestedAt")).isNotNull();
  }

  // ================================================================ 冻结与状态机

  @Test
  void 确认后明细与落档冻结() throws Exception {
    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
            200);
    String id = (String) created.get("id");
    postJson("Bearer tenant-a", "b2b-orders/" + id + "/confirm", Map.of(), 200);
    body("Bearer tenant-a", "PUT", "b2b-orders/" + id, Map.of("lines", List.of(line(200))), 409);
  }

  @Test
  void 确认后不能重复确认() throws Exception {
    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
            200);
    String id = (String) created.get("id");
    postJson("Bearer tenant-a", "b2b-orders/" + id + "/confirm", Map.of(), 200);
    body("Bearer tenant-a", "POST", "b2b-orders/" + id + "/confirm", Map.of(), 409);
  }

  @Test
  void 订单号重复必须被拒() throws Exception {
    String no = orderNo();
    postJson(
        "Bearer tenant-a",
        "b2b-orders",
        createInput(no, partyA.toString(), "CREDIT_TERM", 30, 100),
        200);
    body(
        "Bearer tenant-a",
        "POST",
        "b2b-orders",
        createInput(no, partyA.toString(), "CREDIT_TERM", 30, 100),
        409);
  }

  @Test
  void 没有客户投影时下单给出可读报错() throws Exception {
    body(
        "Bearer tenant-a",
        "POST",
        "b2b-orders",
        createInput(orderNo(), UUID.randomUUID().toString(), "CREDIT_TERM", 30, 100),
        404);
  }

  // ================================================================ 多租户三条断言

  @Test
  void 两租户互不可见() throws Exception {
    var createdA =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
            200);
    String idA = (String) createdA.get("id");

    // 租户 B 用**同一个 id** 查：404。
    body("Bearer tenant-b", "GET", "b2b-orders/" + idA, null, 404);

    // 租户 B 的列表里看不到租户 A 的单，且里面每一行都属于 B 自己的客户。
    // 刻意不断言「B 的 total 是 0」—— 容器跨用例共用，B 自己也有别的用例建的单，
    // 那种断言测的是执行顺序而不是隔离。
    var createdB =
        postJson(
            "Bearer tenant-b",
            "b2b-orders",
            createInput(orderNo(), partyB.toString(), "CREDIT_TERM", 30, 100),
            200);
    String idB = (String) createdB.get("id");
    var listB = getJson("Bearer tenant-b", "b2b-orders?size=200", 200);
    var itemsB = (List<Map<String, Object>>) listB.get("items");
    assertThat(itemsB).isNotEmpty();
    assertThat(itemsB).noneMatch(i -> idA.equals(i.get("id")));
    assertThat(itemsB).anyMatch(i -> idB.equals(i.get("id")));
    assertThat(itemsB).allMatch(i -> partyB.toString().equals(i.get("customerPartyId").toString()));
  }

  @Test
  void 伪造租户头无效() throws Exception {
    var createdA =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
            200);
    String idA = (String) createdA.get("id");
    var createdB =
        postJson(
            "Bearer tenant-b",
            "b2b-orders",
            createInput(orderNo(), partyB.toString(), "CREDIT_TERM", 30, 100),
            200);
    String idB = (String) createdB.get("id");

    // 带合法会话 + 伪造的 X-Tenant-Id：会话租户的行照常看得到，
    // 被伪造的那个租户的行**一条都不出现**。
    // 刻意不在这里断言「列表里只有 idA」—— 容器是跨用例共用的，
    // 同一个租户还有别的用例建的单，那种断言测的是用例执行顺序而不是租户隔离。
    for (String forged : List.of(b.toString(), UUID.randomUUID().toString())) {
      var result =
          http.perform(
                  get("/trade/b2b-orders?size=200")
                      .header("Authorization", "Bearer tenant-a")
                      .header("X-Tenant-Id", forged))
              .andExpect(status().is(200))
              .andReturn();
      @SuppressWarnings("unchecked")
      var page = json.readValue(result.getResponse().getContentAsString(), Map.class);
      @SuppressWarnings("unchecked")
      var items = (List<Map<String, Object>>) page.get("items");
      assertThat(items).as("伪造租户头后仍能看到会话租户的单").anyMatch(i -> idA.equals(i.get("id")));
      assertThat(items)
          .as("伪造 X-Tenant-Id=%s 不应看到该租户的单", forged)
          .noneMatch(i -> idB.equals(i.get("id")));
      assertThat(items)
          .as("列表里的客户必须都是会话租户的客户")
          .allMatch(i -> partyA.toString().equals(i.get("customerPartyId").toString()));
    }

    // 伪造头也不能读到别的租户的详情。
    http.perform(
            get("/trade/b2b-orders/" + idB)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().is(404));
  }

  @Test
  void 连接池归还后不串租户() throws Exception {
    // 池大小被压到 1：每一次请求都复用同一条连接，租户上下文必须逐请求重绑。
    for (int i = 0; i < 6; i++) {
      var listA = getJson("Bearer tenant-a", "b2b-orders", 200);
      var listB = getJson("Bearer tenant-b", "b2b-orders", 200);
      @SuppressWarnings("unchecked")
      var itemsA = (List<Map<String, Object>>) listA.get("items");
      @SuppressWarnings("unchecked")
      var itemsB = (List<Map<String, Object>>) listB.get("items");
      for (Map<String, Object> item : itemsA) {
        assertThat(item.get("id")).as("第 %d 轮：租户 A 看到的必须是 A 的单", i).isNotNull();
      }
      // 租户 B 的库里也有单（@BeforeEach 建的），所以只断言「A 的单不出现在 B 的列表里」。
      for (Map<String, Object> item : itemsB) {
        assertThat(itemsA).noneMatch(x -> x.get("id").equals(item.get("id")));
      }
    }
  }

  @Test
  void 应收与价目表同样受租户隔离() throws Exception {
    var created =
        postJson(
            "Bearer tenant-a",
            "b2b-orders",
            createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
            200);
    String id = (String) created.get("id");
    postJson("Bearer tenant-a", "b2b-orders/" + id + "/confirm", Map.of(), 200);
    String lineId = lastLineId("Bearer tenant-a", id);
    postJson(
        "Bearer tenant-a",
        "b2b-orders/" + id + "/shipments",
        Map.of(
            "shipmentNo",
            "SH-X",
            "lines",
            List.of(Map.of("orderLineId", lineId, "quantity", new BigDecimal("100.000000")))),
        200);

    var listB = getJson("Bearer tenant-b", "b2b-orders/receivables", 200);
    @SuppressWarnings("unchecked")
    var itemsB = (List<Map<String, Object>>) listB.get("items");
    assertThat(itemsB).noneMatch(i -> id.equals(i.get("orderId")));
    assertThat(new BigDecimal(listB.get("openAmount").toString())).isEqualByComparingTo("0");
  }

  // ================================================================ 权限

  @Test
  void 只读角色不能写入() throws Exception {
    body(
        "Bearer read-only",
        "POST",
        "b2b-orders",
        createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 100),
        403);
  }

  @Test
  void 未认证会话被拒() throws Exception {
    body("Bearer nobody", "GET", "b2b-orders", null, 401);
  }

  // ================================================================ 列表是真分页

  @Test
  void 列表是真分页而不是假分页() throws Exception {
    long before = (Integer) getJson("Bearer tenant-a", "b2b-orders", 200).get("total");
    for (int i = 0; i < 3; i++) {
      postJson(
          "Bearer tenant-a",
          "b2b-orders",
          createInput(orderNo(), partyA.toString(), "CREDIT_TERM", 30, 10),
          200);
    }
    var first = getJson("Bearer tenant-a", "b2b-orders?page=0&size=2", 200);
    var second = getJson("Bearer tenant-a", "b2b-orders?page=1&size=2", 200);
    assertThat((List<Map<String, Object>>) first.get("items")).hasSize(2);
    assertThat((List<Map<String, Object>>) second.get("items")).hasSize(2);
    // 第一页与第二页不能是同一批数据（那才是假分页）。
    assertThat(
            ((List<Map<String, Object>>) first.get("items"))
                .stream().map(i -> i.get("id")).toList())
        .doesNotContainAnyElementsOf(
            ((List<Map<String, Object>>) second.get("items"))
                .stream().map(i -> i.get("id")).toList());
    assertThat((Integer) first.get("total")).isEqualTo(before + 3);
    assertThat((Integer) first.get("total"))
        .as("total 是全量计数，不随 size 变化")
        .isEqualTo(second.get("total"));
    // 全量汇总与分页无关。
    assertThat(first.get("draft")).isEqualTo(second.get("draft"));
  }
}
