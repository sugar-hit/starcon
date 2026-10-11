package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
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
 * 9.1.2.2 手工订单。
 *
 * <p>本测试逐条验证蓝图本模块的五条硬要求，外加 AGENTS.md 明文要求的三条多租户断言。
 * 全部走 <b>HTTP 端点 + 真实 PostgreSQL 16 + 强制 RLS</b>，不使用任何内存替身：
 * 走端点才能同时证明「服务层 + DB 层」两层都没把闸门漏掉。
 *
 * <p><b>刻意不提供 catalog / crm 的 stub</b>：本模块对它们的依赖只有「逻辑 ID + 快照」，
 * 一次回查都不该发生。测试里出现 catalog 客户端反而说明快照纪律破了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ManualOrderIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
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
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, List.of(grant("ALL", List.of("trade.orders.read")))));
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

  @Autowired ManualOrderService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  static final UUID partyOrg = UUID.randomUUID();

  @BeforeEach
  void seed() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  // ================================================================ 工具

  /** 在可信租户事务里执行一段工作并返回结果（直接调服务层时必须这样包）。 */
  <T> T inA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.orders.read", work);
  }

  String uniqueNo() {
    return "MO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
  }

  /** 一张能通过全部 DB 闸门的标准手工单（B2B 账期 30 天）。 */
  Map<String, Object> validBody() {
    return validBody(uniqueNo());
  }

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
    m.put("lines", List.of(line(), secondLine()));
    // Map.of 最多 10 对，地址有 15 个字段，必须用 LinkedHashMap。
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
            Map.of("chargeType", "SHIPPING", "label", "海运费", "amount", "8.00",
                "chargeLevel", "ORDER", "note", ""),
            Map.of("chargeType", "DISCOUNT", "label", "老客户折扣", "amount", "-3.00",
                "chargeLevel", "ORDER", "note", ""),
            Map.of("chargeType", "TAX", "label", "进口税", "amount", "1.50",
                "chargeLevel", "ORDER", "note", ""),
            Map.of("chargeType", "PACKAGING", "label", "加固包装", "amount", "2.00",
                "chargeLevel", "ORDER", "note", "")));
    return m;
  }

  /** 行 1：2 × 10.50 = 21.0000，行折扣 -1.0000，行税 +2.0000。 */
  Map<String, Object> line() {
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
            Map.of("attributeName", "克重", "attributeValue", "180", "attributeUnit", "g",
                "sourceVersion", "cat-v7")));
    return l;
  }

  /** 行 2：1 × 5.00 = 5.0000，无折扣无税。 */
  Map<String, Object> secondLine() {
    Map<String, Object> l = new LinkedHashMap<>();
    l.put("skuId", UUID.randomUUID().toString());
    l.put("skuCode", "SKU-BLUE-L");
    l.put("skuName", "蓝色大码衬衫");
    l.put("specSnapshot", "蓝色 / L");
    l.put("skuMappingVersion", "map-v3");
    l.put("quantity", "1");
    l.put("unitPrice", "5.00");
    l.put("discountAmount", "0.00");
    l.put("taxAmount", "0.00");
    l.put("note", "");
    l.put("attributes", List.of());
    return l;
  }

  /** 建一单并返回响应体（测试需要 id 与 orderNo 两个字段，按需取）。 */
  com.fasterxml.jackson.databind.JsonNode postOrder(Map<String, Object> body, String token)
      throws Exception {
    var res =
        http.perform(
                post("/trade/sales-orders")
                    .header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(body)))
            .andExpect(status().is2xxSuccessful())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsString());
  }

  String postOrderId(Map<String, Object> body, String token) throws Exception {
    return postOrder(body, token).get("id").asText();
  }

  // ================================================================
  // 硬要求 1：来源 = MANUAL，且来源是一个字段不是一张表
  // ================================================================

  @Test
  void 硬要求1_来源是字段不是表_只有一张sales_order且手工单只写MANUAL() throws Exception {
    String id = postOrderId(validBody(), "Bearer tenant-a");

    // 只有一张销售订单表：不存在 manual_sales_order 之类的「手工订单专用表」。
    List<String> tables =
        inA(
            () ->
                sql.fetch(
                        "select tablename from pg_tables where schemaname = current_schema()")
                    .getValues("tablename", String.class));
    assertThat(tables)
        .as("来源必须是字段而不是表：出现手工订单专用表就意味着平台单将来要另建一套流程")
        .contains("sales_order")
        .doesNotContain("manual_sales_order", "platform_sales_order");

    // 来源列的值域已经把将来的平台来源一起钉住了 —— D5 落地时不需要 ALTER 改 CHECK。
    List<String> checks =
        inA(
            () ->
                sql.fetch(
                        "select pg_get_constraintdef(oid) d from pg_constraint"
                            + " where conrelid='sales_order'::regclass and contype='c'")
                    .getValues("d", String.class));
    assertThat(checks)
        .as("order_source 的 CHECK 必须与 Java 枚举逐条对齐，否则加值要 ALTER")
        .anySatisfy(
            d ->
                assertThat(d)
                    .contains("MANUAL", "PLATFORM", "AMAZON", "SHOPIFY", "EBAY", "TIKTOK_SHOP")
                    .contains("ALIBABA_1688"));

    String source =
        inA(() -> sql.fetchOne("select order_source from sales_order where id = ?::uuid", UUID.fromString(id))
            .get(0, String.class));
    assertThat(source).as("本模块只写手工来源").isEqualTo("MANUAL");
  }

  @Test
  void 硬要求1_调用方不能自带来源() throws Exception {
    // 应用配置了 fail-on-unknown-properties: true（application.yml），所以请求体里
    // 带 orderSource / source 会被**直接拒绝**。这比「忽略它」更强：不是「传了也没用」，
    // 而是「带了就不让建单」，客户端不会以为自己设了来源。
    Map<String, Object> body = validBody();
    body.put("orderSource", "AMAZON");
    body.put("source", "SHOPIFY");
    http.perform(
            post("/trade/sales-orders")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)))
        .andExpect(status().is4xxClientError());

    // 走正常路径建的单，来源恒为 MANUAL。
    String id = postOrderId(validBody(), "Bearer tenant-a");
    String source =
        inA(
            () ->
                sql.fetchOne("select order_source from sales_order where id = ?::uuid", UUID.fromString(id))
                    .get(0, String.class));
    assertThat(source)
        .as("让客户端能选来源等于开了一条「手工录入通道写平台单」的后门")
        .isEqualTo("MANUAL");
  }

  // ================================================================
  // 硬要求 2：费用与支付方式是表单的一部分；单价由表单给，不引用未交付的价目
  // ================================================================

  @Test
  void 硬要求2_费用与支付方式随单落库_且汇总与明细逐项相符() throws Exception {
    String id = postOrderId(validBody(), "Bearer tenant-a");

    ManualOrderModels.SalesOrderDetail detail = inA(() -> service.order(UUID.fromString(id)));

    assertThat(detail.order().paymentMethod()).isEqualTo(ManualOrderModels.SalesOrderPaymentMethod.CREDIT_TERM);
    assertThat(detail.charges())
        .as("费用是表单的一部分，四笔费用各自成行")
        .hasSize(4)
        .extracting(ManualOrderModels.SalesOrderChargeRow::chargeType)
        .containsExactlyInAnyOrder(
            ManualOrderModels.SalesOrderChargeType.SHIPPING,
            ManualOrderModels.SalesOrderChargeType.DISCOUNT,
            ManualOrderModels.SalesOrderChargeType.TAX,
            ManualOrderModels.SalesOrderChargeType.PACKAGING);

    // 口径：行1 21.0000/-1.0000/+2.0000，行2 5.0000/0/0；
    // 费用 SHIPPING 8.00、DISCOUNT -3.00、TAX 1.50、PACKAGING 2.00。
    //   小计 26.0000 ｜ 折扣 4.0000 ｜ 税 3.5000 ｜ 运费 8.0000 ｜ 其他 2.0000 ｜ 合计 35.5000
    assertThat(detail.order().grandTotalAmount()).isEqualByComparingTo("35.5000");
    // 行小计由服务层算、DB 用 CHECK 复算；两者不一致时提交会被 DB 挡下。
    assertThat(detail.lines().get(0).lineAmount()).isEqualByComparingTo("21.0000");
    assertThat(detail.lines().get(1).lineAmount()).isEqualByComparingTo("5.0000");
  }

  @Test
  void 硬要求2_单价是表数据不是代码常量_没有任何默认单价() throws Exception {
    // 同一个 SKU 用两个不同单价各建一单，两单金额必须不同 ——
    // 若服务层内置了「默认单价」，第二个价格会被悄悄改回第一个。
    Map<String, Object> first = validBody();
    first.put("lines", List.of(line()));

    Map<String, Object> second = validBody();
    Map<String, Object> cheap = line();
    cheap.put("unitPrice", "3.33");
    second.put("lines", List.of(cheap));

    String idA = postOrderId(first, "Bearer tenant-a");
    String idB = postOrderId(second, "Bearer tenant-a");

    assertThat(inA(() -> service.order(UUID.fromString(idA))).order().grandTotalAmount())
        .isNotEqualByComparingTo(inA(() -> service.order(UUID.fromString(idB))).order().grandTotalAmount());
  }

  @Test
  void 账期与非账期必须自洽() throws Exception {
    Map<String, Object> body = validBody();
    body.put("paymentMethod", "PREPAID");
    body.put("paymentTermsDays", 30);
    String id = postOrderId(body, "Bearer tenant-a");
    assertThat(inA(() -> service.order(UUID.fromString(id))).order().paymentTermsDays())
        .as("「预付 + 90 天账期」在服务层就被抹平成 0，与 DB CHECK 同一口径")
        .isZero();
  }

  // ================================================================
  // 硬要求 3：订单行是快照，商品参数事后被改也不改写历史
  // ================================================================

  @Test
  void 硬要求3_订单行保留SKU规格单价税折扣与映射版本的快照() throws Exception {
    String id = postOrderId(validBody(), "Bearer tenant-a");
    ManualOrderModels.SalesOrderDetail detail = inA(() -> service.order(UUID.fromString(id)));

    ManualOrderModels.SalesOrderLineRow row = detail.lines().get(0);
    assertThat(row.skuCode()).isEqualTo("SKU-BLUE-M");
    assertThat(row.skuName()).isEqualTo("蓝色中码衬衫");
    assertThat(row.specSnapshot()).isEqualTo("蓝色 / M");
    assertThat(row.skuMappingVersion()).isEqualTo("map-v3");
    assertThat(row.discountAmount()).isEqualByComparingTo("-1.0000");
    assertThat(row.taxAmount()).isEqualByComparingTo("2.0000");
    assertThat(row.attributes())
        .as("属性快照是硬要求 3 的一部分：报关与履约各自冻结所需属性的名称/值/单位/来源版本")
        .singleElement()
        .satisfies(
            at -> {
              assertThat(at.attributeName()).isEqualTo("克重");
              assertThat(at.attributeValue()).isEqualTo("180");
              assertThat(at.attributeUnit()).isEqualTo("g");
              assertThat(at.sourceVersion()).isEqualTo("cat-v7");
            });
  }

  @Test
  void 硬要求3_属性快照表appendOnly_应用角色与触发器两层都拦得住() {
    String id;
    try {
      id = postOrderId(validBody(), "Bearer tenant-a");
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    UUID orderId = UUID.fromString(id);

    // 第一层：REVOKE 收回 trade_app 的 UPDATE/DELETE —— 应用角色连碰都碰不到。
    // 这一层比断言错误信息更硬：写根本到不了表。
    assertThatThrownBy(
            () ->
                inA(
                    () -> {
                      sql.execute(
                          "update order_line_attribute_snapshot set attribute_value = '999'"
                              + " where order_id = ?::uuid",
                          orderId);
                      return null;
                    }))
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () ->
                inA(
                    () -> {
                      sql.execute(
                          "delete from order_line_attribute_snapshot where order_id = ?::uuid",
                          orderId);
                      return null;
                    }))
        .rootCause()
        .hasMessageContaining("permission denied");

    // 第二层：触发器。因为第一层挡住了，用 catalog 断言它真的挂在表上 ——
    // REVOKE 是权限、触发器是数据，两道都要在，缺一道「绕过服务层直连 SQL」就漏了。
    assertThat(appendOnlyTriggers())
        .as("属性快照必须同时有 REVOKE 与 append-only 触发器两层")
        .contains("order_line_attribute_snapshot_append_only");
  }

  /** append-only 触发器清单（照名字前缀过滤，与 V99 第 8.4 / 8.7 节同名）。 */
  List<String> appendOnlyTriggers() {
    return inA(
        () ->
            sql.fetch(
                    "select tgname from pg_trigger where not tgisinternal"
                        + " and tgname like '%append_only'")
                .getValues("tgname", String.class));
  }

  @Test
  void 硬要求3_行内正折扣被拒_负折扣才放行() throws Exception {
    Map<String, Object> bad = validBody();
    Map<String, Object> l = line();
    l.put("discountAmount", "5.00");
    bad.put("lines", List.of(l));
    http.perform(
            post("/trade/sales-orders")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(bad)))
        .andExpect(status().is4xxClientError());
  }

  // ================================================================
  // 硬要求 4：地址是快照，买家改地址改写不了历史订单
  // ================================================================

  @Test
  void 硬要求4_地址是快照而非活地址引用() throws Exception {
    String id = postOrderId(validBody(), "Bearer tenant-a");
    ManualOrderModels.SalesOrderAddressRow addr = inA(() -> service.order(UUID.fromString(id)))
        .addresses().get(0);

    assertThat(addr.recipientName()).isEqualTo("Jane Doe");
    assertThat(addr.addressLine1()).isEqualTo("100 Main St");
    assertThat(addr.postcode()).isEqualTo("90001");

    // 本表**没有**指向 3.6.3.1 地址的外键列 —— 买家在别处改了地址，
    // 历史订单上的这一行没有可被连带更新的路径。
    List<String> cols =
        inA(
            () ->
                sql.fetch(
                        "select column_name from information_schema.columns"
                            + " where table_name='order_address_snapshot'")
                    .getValues("column_name", String.class));
    assertThat(cols)
        .as("地址快照里不该有任何指向活地址的外键列")
        .doesNotContain("party_address_id", "customer_address_id", "address_id");
  }

  @Test
  void 硬要求4_提交后地址被冻结() throws Exception {
    String id = postOrderId(validBody(), "Bearer tenant-a");
    UUID orderId = UUID.fromString(id);
    inA(
        () -> {
          service.submit(orderId, actorA);
          return null;
        });

    assertThatThrownBy(
            () ->
                inA(
                    () -> {
                      sql.execute(
                          "update order_address_snapshot set address_line1 = '改了'"
                              + " where order_id = ?::uuid",
                          orderId);
                      return null;
                    }))
        .rootCause()
        .hasMessageContaining("已提交进审单队列");
  }

  // ================================================================
  // 硬要求 5：外部订单号按来源范围唯一
  // ================================================================

  @Test
  void 硬要求5_外部订单号同租户同来源唯一() throws Exception {
    Map<String, Object> first = validBody();
    first.put("externalOrderNo", "PO-SHARED-001");
    postOrderId(first, "Bearer tenant-a");

    Map<String, Object> second = validBody();
    second.put("externalOrderNo", "PO-SHARED-001");
    http.perform(
            post("/trade/sales-orders")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(second)))
        .andExpect(status().isConflict());
  }

  @Test
  void 硬要求5_唯一约束带来源这一维() {
    // 唯一索引的前导列必须含 order_source —— 若只有 (tenant_id, external_order_no)，
    // Amazon 的 123 与 Shopify 的 123 会被判成重复，而它们是两张毫不相干的单。
    List<String> keyCols =
        inA(
            () ->
                sql.fetch(
                        "select a.attname from pg_index i"
                            + " join pg_attribute a on a.attrelid=i.indrelid"
                            + "  and a.attnum = any(i.indkey)"
                            + " where i.indexrelid = 'sales_order_external_no_unique'::regclass"
                            + " order by array_position(i.indkey, a.attnum)")
                    .getValues("attname", String.class));
    assertThat(keyCols).containsExactly("tenant_id", "order_source", "external_order_no");

    // 必须是**部分**唯一索引：external_order_no 是 NOT NULL DEFAULT ''，
    // 普通唯一约束把 '' 视为相等，会让第二张没填参考号的单撞上第一张。
    String predicate =
        inA(
            () ->
                sql.fetchOne("select pg_get_expr(indpred, indrelid) d from pg_index"
                        + " where indexrelid='sales_order_external_no_unique'::regclass")
                    .get(0, String.class));
    assertThat(predicate)
        .as("空号必须被排除在唯一性之外，否则「没填参考号」会被判成「重复」")
        .contains("external_order_no")
        .contains("<>");
  }

  @Test
  void 硬要求5_未填外部订单号不算重复() throws Exception {
    // external_order_no 落库是空串而不是 NULL：PostgreSQL 唯一约束允许多个 NULL，
    // 用 NULL 会让「没填」这件事失去唯一性语义；用空串时 DB 的 CHECK 明确放行多张空串单。
    Map<String, Object> first = validBody();
    first.remove("externalOrderNo");
    postOrderId(first, "Bearer tenant-a");
    Map<String, Object> second = validBody();
    second.remove("externalOrderNo");
    postOrderId(second, "Bearer tenant-a"); // 不冲突
  }

  // ================================================================
  // 订单自身：单号不可改、状态机、提交闸门
  // ================================================================

  @Test
  void 订单号与外部单号建后不可改() throws Exception {
    String id = postOrderId(validBody(), "Bearer tenant-a");
    UUID orderId = UUID.fromString(id);
    assertThatThrownBy(
            () ->
                inA(
                    () -> {
                      sql.execute(
                          "update sales_order set order_no = 'MO-CHANGED' where id = ?::uuid",
                          orderId);
                      return null;
                    }))
        .rootCause()
        .hasMessageContaining("订单号建后不可改");
  }

  @Test
  void 没有商品行的单不能提交() {
    // DB 侧延迟约束触发器在提交时回表核对：直接构造一个没有行的头，再提交。
    assertThatThrownBy(
            () ->
                inA(
                    () -> {
                      sql.execute(
                          "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,"
                              + "currency,payment_method,payment_terms_days,status) values"
                              + "(gen_random_uuid(),nullif(current_setting('app.current_tenant',true),'')::uuid,"
                              + "?,'MANUAL','SAMPLE','USD','PREPAID',0,'DRAFT')",
                          "MO-NOLINE-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
                      sql.execute(
                          "update sales_order set status='SUBMITTED', submitted_at=now()"
                              + " where order_no like 'MO-NOLINE-%'");
                      return null;
                    }))
        .rootCause()
        .hasMessageContaining("没有任何商品行");
  }

  @Test
  void 没有收货地址的单不能提交() {
    assertThatThrownBy(
            () ->
                inA(
                    () -> {
                      sql.execute(
                          "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,"
                              + "currency,payment_method,payment_terms_days,status) values"
                              + "(gen_random_uuid(),nullif(current_setting('app.current_tenant',true),'')::uuid,"
                              + "?,'MANUAL','SAMPLE','USD','PREPAID',0,'DRAFT')",
                          "MO-NOADDR-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
                      sql.execute(
                          "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,"
                              + "sku_code,sku_name,quantity,unit_price,line_amount) select"
                              + " gen_random_uuid(),nullif(current_setting('app.current_tenant',true),'')::uuid,"
                              + " id,1,gen_random_uuid(),'X','X',1,1,1 from sales_order"
                              + " where order_no like 'MO-NOADDR-%'");
                      sql.execute(
                          "update sales_order set status='SUBMITTED', submitted_at=now()"
                              + " where order_no like 'MO-NOADDR-%'");
                      return null;
                    }))
        .rootCause()
        .hasMessageContaining("没有收货地址快照");
  }

  @Test
  void 提交后不能再改草稿() throws Exception {
    String id = postOrderId(validBody(), "Bearer tenant-a");
    UUID orderId = UUID.fromString(id);
    inA(
        () -> {
          service.submit(orderId, actorA);
          return null;
        });

    Map<String, Object> update =
        Map.of(
            "manualKind", "SAMPLE",
            "currency", "USD",
            "paymentMethod", "PREPAID",
            "paymentTermsDays", 0,
            "note", "",
            "customer",
                Map.of(
                    "customerPartyId", partyOrg.toString(),
                    "customerCode", "CUST-001",
                    "customerName", "改名了",
                    "customerType", "ORGANIZATION",
                    "customerDefaultCurrency", "USD"),
            "lines", List.of(line()),
            "addresses", List.of(),
            "charges", List.of());
    http.perform(
            put("/trade/sales-orders/" + id)
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(update)))
        .andExpect(status().isConflict());
  }

  @Test
  void 提交两次第二次被拒() throws Exception {
    String id = postOrderId(validBody(), "Bearer tenant-a");
    UUID orderId = UUID.fromString(id);
    inA(
        () -> {
          service.submit(orderId, actorA);
          return null;
        });
    assertThatThrownBy(() -> inA(() -> service.submit(orderId, actorA)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 提交与建单可以分处两个事务_因为闸门是延迟约束() throws Exception {
    // 8.1 的闸门是 DEFERRABLE INITIALLY DEFERRED：建单事务提交时状态还是 DRAFT，
    // 闸门直接放行；submit 是独立事务，提交时才回表核对，此时行/地址都已就位。
    String id = postOrderId(validBody(), "Bearer tenant-a");
    UUID orderId = UUID.fromString(id);
    var saved = inA(() -> service.submit(orderId, actorA));
    assertThat(saved.status()).isEqualTo(ManualOrderModels.SalesOrderStatus.SUBMITTED);
    assertThat(saved.message()).contains("9.2 审单");
  }

  @Test
  void 头部汇总与明细不一致时提交会被DB挡下() {
    // 服务层算错了汇总 —— 这条测试直接改库制造「头 ≠ 明细」，模拟那种情况，
    // 证明提交时确实有一道独立的 DB 复算，而不是只有服务层自说自话。
    String id;
    try {
      id = postOrderId(validBody(), "Bearer tenant-a");
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    UUID orderId = UUID.fromString(id);
    assertThatThrownBy(
            () ->
                inA(
                    () -> {
                      sql.execute(
                          "update sales_order set grand_total_amount = grand_total_amount + 100"
                              + " where id = ?::uuid",
                          orderId);
                      sql.execute(
                          "update sales_order set status='SUBMITTED', submitted_at=now()"
                              + " where id = ?::uuid",
                          orderId);
                      return null;
                    }))
        .rootCause()
        .hasMessageContaining("订单总额");
  }

  // ================================================================
  // 多租户三条
  // ================================================================

  @Test
  void 断言1_两租户互不可见() throws Exception {
    String noA = "T" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    String idA = postOrderId(validBody(noA), "Bearer tenant-a");
    String idB = postOrderId(validBody(), "Bearer tenant-b");

    // B 读不到 A 的单：详情直接 404（RLS 下查不到）。
    assertThat(access.run(actorB, "trade.orders.read", () -> service.order(UUID.fromString(idB))))
        .isNotNull();
    assertThatThrownBy(
            () ->
                access.run(
                    actorB, "trade.orders.read", () -> service.order(UUID.fromString(idA))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("无权查看");

    // 列表同样隔离：A 的单不出现在 B 的列表里。
    long bSees =
        access.run(
            actorB,
            "trade.orders.read",
            () ->
                service.pageOrders(
                        new ManualOrderModels.SalesOrderQuery(null, null, null, null, noA, 0, 50))
                    .total());
    assertThat(bSees).isZero();
    assertThat(inA(
            () ->
                service.pageOrders(
                        new ManualOrderModels.SalesOrderQuery(null, null, null, null, noA, 0, 50))
                    .total()))
        .as("A 用自己的单号当然查得到 —— 否则上面「B 查不到」可能只是搜索没命中")
        .isPositive();
    assertThat(idB).isNotEqualTo(idA);
  }

  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    String noA = "F" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    String idA = postOrderId(validBody(noA), "Bearer tenant-a");
    // 带合法会话 + 伪造 X-Tenant-Id：仍然只看得到会话租户的行。
    http.perform(
            get("/trade/sales-orders/" + idA)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());
    assertThat(
            access.run(
                    actorB,
                    "trade.orders.read",
                    () ->
                        service.pageOrders(
                                new ManualOrderModels.SalesOrderQuery(
                                    null, null, null, null, noA, 0, 50))
                            .total()))
        .as("伪造的租户头不得让 A 的单出现在 B 的列表里")
        .isZero();
  }

  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    String noA = "P" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    postOrderId(validBody(noA), "Bearer tenant-a");
    // 池大小为 1：反复交替读写 A/B，只有事务局部绑定才能保证每轮都读到本租户。
    for (int i = 0; i < 5; i++) {
      assertThat(
              access.run(
                  actorA,
                  "trade.orders.read",
                  () ->
                      service.pageOrders(
                              new ManualOrderModels.SalesOrderQuery(
                                  null, null, null, null, noA, 0, 50))
                          .total()))
          .isPositive();
      assertThat(
              access.run(
                  actorB,
                  "trade.orders.read",
                  () ->
                      service.pageOrders(
                              new ManualOrderModels.SalesOrderQuery(
                                  null, null, null, null, noA, 0, 50))
                          .total()))
          .as("第 %d 轮：A 的单绝不能出现在 B 的列表里", i)
          .isZero();
    }
  }

  @Test
  void 未认证与权限不足被拒() throws Exception {
    http.perform(get("/trade/sales-orders")).andExpect(status().isUnauthorized());
    http.perform(get("/trade/sales-orders").header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());
    http.perform(
            post("/trade/sales-orders")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(validBody())))
        .andExpect(status().isForbidden());
  }

  // ================================================================
  // 端点命名空间：/trade/sales-orders，且不得占用 /trade/orders
  // ================================================================

  @Test
  void 端点是tradeSalesOrders而不是tradeOrders() throws Exception {
    // /trade/orders 被 TradeTenancyIntegrationTest 显式预留给另一条车道，
    // 本车道占了它会让那条已通过的测试变红。这里把它钉住。
    http.perform(get("/trade/orders")).andExpect(status().isNotFound());
    http.perform(get("/trade/sales-orders").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/trade/sales-orders/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
  }

  // ================================================================
  // 列表与详情
  // ================================================================

  @Test
  void 列表是真分页而汇总不受分页影响() throws Exception {
    for (int i = 0; i < 3; i++) {
      postOrderId(validBody(), "Bearer tenant-a");
    }
    ManualOrderModels.SalesOrderQuery q =
        new ManualOrderModels.SalesOrderQuery(null, null, null, null, null, 0, 2);
    ManualOrderModels.SalesOrderPage page = inA(() -> service.pageOrders(q));
    assertThat(page.rows()).hasSizeLessThanOrEqualTo(2);
    assertThat(page.total()).isGreaterThanOrEqualTo(3);

    ManualOrderModels.SalesOrderQuery small =
        new ManualOrderModels.SalesOrderQuery(null, null, null, null, null, 0, 1);
    ManualOrderModels.SalesOrderPage page2 = inA(() -> service.pageOrders(small));
    assertThat(page2.rows()).hasSize(1);
    assertThat(page2.total())
        .as("汇总必须与分页无关：翻页不该让「一共多少张单」变来变去")
        .isEqualTo(page.total());
  }

  @Test
  void 关键字搜索真的生效() throws Exception {
    String no = "KWD" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    Map<String, Object> body = validBody(no);
    postOrder(body, "Bearer tenant-a");

    long hit =
        inA(
            () ->
                service.pageOrders(
                        new ManualOrderModels.SalesOrderQuery(null, null, null, null, no, 0, 50))
                    .total());
    assertThat(hit).isPositive();

    long miss =
        inA(
            () ->
                service.pageOrders(
                        new ManualOrderModels.SalesOrderQuery(
                            null, null, null, null, "ZZZ" + UUID.randomUUID(), 0, 50))
                    .total());
    assertThat(miss).isZero();
  }

  @Test
  void 能力声明如实报出下游缺口与价格来源() {
    ManualOrderModels.SalesOrderCapabilities caps = inA(service::capabilities);
    assertThat(caps.priceSourceAvailable())
        .as("05 价目未交付，必须如实说没有，不能假装有")
        .isFalse();
    assertThat(caps.priceSourceNote()).contains("05 价目");
    assertThat(caps.downstreamReviewAvailable()).isFalse();
    assertThat(caps.sources())
        .as("来源是一个字段：能力声明里 MANUAL 与平台来源并列")
        .contains(ManualOrderModels.SalesOrderSource.MANUAL, ManualOrderModels.SalesOrderSource.AMAZON);
    assertThat(caps.downstreamGaps())
        .extracting(ManualOrderModels.SalesOrderDownstreamGap::moduleId)
        .contains("9.2.1.1", "9.1.2.1");
  }

  // ================================================================
  // RLS 与授权
  // ================================================================

  @Test
  void 七张表全部强制RLS() {
    List<String> forced =
        inA(
            () ->
                sql.fetch(
                        "select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace"
                            + " where n.nspname='public' and c.relrowsecurity and c.relforcerowsecurity"
                            + " and c.relname in ('sales_order','sales_order_line',"
                            + "'sales_order_party_snapshot','order_address_snapshot','order_charge',"
                            + "'order_line_attribute_snapshot','sales_order_status_history')")
                    .getValues("relname", String.class));
    assertThat(forced).hasSize(7);
  }

  @Test
  void 状态事件流appendOnly() throws Exception {
    String id = postOrderId(validBody(), "Bearer tenant-a");
    UUID orderId = UUID.fromString(id);
    assertThatThrownBy(
            () ->
                inA(
                    () -> {
                      sql.execute(
                          "update sales_order_status_history set reason = '改'"
                              + " where order_id = ?::uuid",
                          orderId);
                      return null;
                    }))
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThat(appendOnlyTriggers())
        .as("事件流与属性快照一样，REVOKE 与触发器两层都要在")
        .contains("sales_order_status_history_append_only");
  }

  @Test
  void 详情一次返回整屏() throws Exception {
    String id = postOrderId(validBody(), "Bearer tenant-a");
    ManualOrderModels.SalesOrderDetail detail = inA(() -> service.order(UUID.fromString(id)));
    assertThat(detail.order()).isNotNull();
    assertThat(detail.customer().customerName()).isEqualTo("Acme Trading Co.");
    assertThat(detail.lines()).hasSize(2);
    assertThat(detail.addresses()).hasSize(1);
    assertThat(detail.charges()).hasSize(4);
    assertThat(detail.events())
        .as("建单事件必须留痕：订单进了审单队列之后，第一个问题就是「谁在什么时候提上去的」")
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.eventType()).isEqualTo(ManualOrderModels.SalesOrderEventType.CREATED);
              assertThat(e.toStatus()).isEqualTo("DRAFT");
            });
  }

  @Test
  void 导入缺字段被拒() throws Exception {
    Map<String, Object> body = validBody();
    body.remove("customer");
    http.perform(
            post("/trade/sales-orders")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(body)))
        .andExpect(status().is4xxClientError());

    Map<String, Object> noLines = validBody();
    noLines.put("lines", List.of());
    http.perform(
            post("/trade/sales-orders")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(noLines)))
        .andExpect(status().is4xxClientError());
  }
}
