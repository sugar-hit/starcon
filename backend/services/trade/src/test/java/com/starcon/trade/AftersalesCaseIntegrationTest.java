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
 * 11.1.1.1 售后单列表（列表 + 多维筛选 + 时效预警 + 三件批量操作）。
 *
 * <p>全部走 <b>HTTP 端点 + 真实 PostgreSQL 16 + 强制 RLS</b>，不使用任何内存替身。
 * 订单夹具走 V99 表（9.1.2.2 手工单的写入所有者），售后单夹具走本模块自己的登记端点。
 *
 * <p><b>本测试刻意断言那些「不该发生」的事</b>，而不只是「列表能返回数据」：
 *
 * <ul>
 *   <li><b>店铺维度恒为 null</b>，且 capabilities 明说 sales_order 没有 shop_code 列
 *       —— 哪天有人加了假 shop 列让界面上出现一个恒空的店铺列，这条会红。
 *   <li><b>时效的四个态都要真的分得开</b>：OVERDUE / DUE_SOON / ON_TRACK / RESOLVED / NO_DEADLINE。
 *       只测 OVERDUE 的话，「临期阈值写错成 2 小时」这类问题永远发现不了。
 *   <li><b>金额区间是闭区间</b>：用 min == max == 某张单的金额，断言**它自己也在结果里**。
 *   <li><b>批量是逐条回报的</b>：造一个 4 条混合批次，断言哪条成功、哪条失败、失败原因是什么，
 *       并且断言结果**按请求顺序**（重复 ID 的那一条自己失败，不会顶掉第一条的成功）。
 *   <li><b>写回的时刻值</b>用 +08:00 非 UTC 偏移的夹具断言到绝对时刻，
 *       而不是只断言「没抛异常」—— 用 Timestamp.valueOf 的实现会静默偏 −28800s 而测试照绿。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AftersalesCaseIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> allPermissions =
      List.of("trade.aftersales.read", "trade.aftersales.write");

  static AccessService.Access actor(UUID uid, UUID tenant, String scope, List<String> perms) {
    var grants =
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", scope, perms));
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null, perms, grants);
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
      actors.put("Bearer tenant-a", actor(user, a, "ALL", allPermissions));
      // SELF 数据范围：同租户、同一套权限，但范围 = 仅本人。
      // SELF 承载者与租户 A 的操作人**是同一个人**：本模块的 SELF 口径是
      // 「责任人流转里有本人」，承载者与责任人必须是同一个 user 才测的是过滤本身。
      actors.put("Bearer self-scope", actor(user, a, "SELF", allPermissions));
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
  }

  @Autowired DSLContext sql;
  @Autowired com.starcon.tenancy.TenantTransactions tenants;
  @Autowired MockMvc http;

  /**
   * 在**租户事务**里跑一段直写 SQL。
   *
   * <p>⚠️ V156 的五张表全部 ENABLE + <b>FORCE</b> RLS：在事务外用 {@code sql} 直查直写
   * 一行都碰不到（读回来是 null、写进去是 0 行），而 jOOQ 不会报错 ——
   * 失败会以「断言说没查到」的形式出现，排查时完全想不到是 RLS。
   * 所有直接碰 aftersales_* 的 SQL 都必须走这里。
   */
  <T> T inTenant(java.util.function.Supplier<T> work) {
    return tenants.run(a, work);
  }

  static final OffsetDateTime T0 = OffsetDateTime.parse("2026-03-01T00:00:00Z");

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate aftersales_case_note, aftersales_authorization, aftersales_service_ticket,"
              + " aftersales_owner_assignment, aftersales_case, sales_order cascade");
    }
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 一张真实订单（V99 手工单簇）。orderSource 决定平台级筛选。 */
  UUID seedOrder(String orderNo, String orderSource) throws Exception {
    UUID id = UUID.randomUUID();
    // ⚠️ 必须**显式开事务**：V99 的「订单必须带商品行 / 收货地址才能 SUBMITTED」是
    //   延迟到 commit 才检查的约束触发器。autocommit 下第一条 insert 就立刻 commit，
    //   那一刻商品行还不存在 ⇒ 建单被拒，而错误信息指向订单规则、与本模块毫无关系。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      c.setAutoCommit(false);
      try (var p =
          c.prepareStatement(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,'PREPAID','SUBMITTED',?::timestamptz,10.0000,0,0,0,0,"
                  + "10.0000,0,?::timestamptz,?::timestamptz)")) {
        p.setObject(1, id);
        p.setObject(2, a);
        p.setString(3, orderNo);
        p.setString(4, orderSource);
        p.setString(5, "EXT-" + orderNo);
        p.setString(6, "USD");
        p.setObject(7, OffsetDateTime.parse("2026-02-01T00:00:00Z"));
        p.setObject(8, OffsetDateTime.parse("2026-02-01T00:00:00Z"));
        p.setObject(9, OffsetDateTime.parse("2026-02-01T00:00:00Z"));
        p.executeUpdate();
      }
      try (var l =
          c.prepareStatement(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,"
                  + "sku_name,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
                  + "created_at) values(?,?,?,1,?,?,?,1,10.0000,0,0,10.0000,'',?::timestamptz)")) {
        l.setObject(1, UUID.randomUUID());
        l.setObject(2, a);
        l.setObject(3, id);
        l.setObject(4, UUID.randomUUID());
        l.setString(5, "SKU-" + orderNo);
        l.setString(6, "商品");
        l.setObject(7, OffsetDateTime.parse("2026-02-01T00:00:00Z"));
        l.executeUpdate();
      }
      // 收货地址快照：V99 的延迟约束触发器要求「商品行 + 收货地址」两样都在才允许 SUBMITTED。
      // 收货地址是下单当时冻结的那一份，没有它就无法发货。
      try (var ad =
          c.prepareStatement(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,"
                  + "recipient_name,recipient_phone,recipient_email,company_name,country_code,"
                  + "province,city,district,postcode,address_line1,address_line2,tax_id,note,"
                  + "created_at) values(?,?,?,'SHIPPING',?,'+1-213-555-0100',?::varchar,'','US',"
                  + "'CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)")) {
        ad.setObject(1, UUID.randomUUID());
        ad.setObject(2, a);
        ad.setObject(3, id);
        ad.setString(4, "买家" + orderNo);
        ad.setString(5, "buyer@example.com");
        ad.setObject(6, OffsetDateTime.parse("2026-02-01T00:00:00Z"));
        ad.executeUpdate();
      }
      // 客户快照：V99 的延迟约束触发器第三样必备输入（「客户选择」是手工单的必备输入）。
      try (var ps =
          c.prepareStatement(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?,?,?,?,?,'INDIVIDUAL','',?::timestamptz)")) {
        UUID partyId = UUID.randomUUID();
        ps.setObject(1, UUID.randomUUID());
        ps.setObject(2, a);
        ps.setObject(3, id);
        ps.setObject(4, partyId);
        ps.setString(5, "C-" + orderNo);
        ps.setString(6, "买家" + orderNo);
        ps.setObject(7, OffsetDateTime.parse("2026-02-01T00:00:00Z"));
        ps.executeUpdate();
      }
      c.commit();
    }
    return id;
  }

  /** 经**真实写端点**登记一张售后单（不用测试里直接 INSERT —— 夹具形状必须等于写入者实际写的形状）。 */
  com.fasterxml.jackson.databind.JsonNode createCase(
      UUID orderId,
      String caseType,
      String reasonCode,
      String amount,
      String currency,
      String quantity,
      OffsetDateTime replyDueAt,
      OffsetDateTime resolveDueAt) throws Exception {
    return createCase(
        orderId, caseType, reasonCode, amount, currency, quantity, replyDueAt, resolveDueAt, null);
  }

  com.fasterxml.jackson.databind.JsonNode createCase(
      UUID orderId,
      String caseType,
      String reasonCode,
      String amount,
      String currency,
      String quantity,
      OffsetDateTime replyDueAt,
      OffsetDateTime resolveDueAt,
      String ownerRef) throws Exception {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("orderId", orderId.toString());
    body.put("caseType", caseType);
    body.put("reasonCode", reasonCode);
    body.put("requestedAmount", amount);
    body.put("currency", currency);
    body.put("requestedQuantity", quantity);
    body.put("platformCaseNo", "PLAT-" + UUID.randomUUID().toString().substring(0, 8));
    body.put("buyerRequest", "买家说收到的货有质量问题");
    body.put("receivedAt", OffsetDateTime.parse("2026-02-20T10:00:00Z"));
    if (replyDueAt != null) body.put("replyDueAt", replyDueAt.toString());
    if (resolveDueAt != null) body.put("resolveDueAt", resolveDueAt.toString());
    if (ownerRef != null) {
      body.put("ownerRef", ownerRef);
      body.put("ownerDisplayName", "客服小林");
      body.put("ownerRoleLabel", "售后专员");
      body.put("ownerCapturedAt", OffsetDateTime.parse("2026-02-20T11:00:00Z"));
    }
    var response =
        http.perform(
                post("/trade/aftersales/cases")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response);
  }

  com.fasterxml.jackson.databind.JsonNode page(String query) throws Exception {
    var response =
        http.perform(
                get("/trade/aftersales/cases" + query)
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response);
  }

  com.fasterxml.jackson.databind.JsonNode bulk(Map<String, Object> body) throws Exception {
    var response =
        http.perform(
                post("/trade/aftersales/cases/bulk")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response);
  }

  static Map<String, Object> bulkBody(String op, List<String> ids) {
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("operation", op);
    body.put("caseIds", ids);
    return body;
  }

  OffsetDateTime hours(int h) {
    return OffsetDateTime.now(ZoneOffset.UTC).plusHours(h);
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  @Test
  void 能力声明如实报出上游缺口与下游未交付() throws Exception {
    var caps =
        json.readTree(
            http.perform(get("/trade/aftersales/capabilities").header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    // 跨服务读必须显式报 false，而不是让调用方从空数组里猜。
    assertThat(caps.get("crossServiceReadAvailable").asBoolean())
        .as("15 客服与卷 2 平台消息都在别的服务独立库，跨服务读一律禁止")
        .isFalse();

    // 店铺维度必须报 unavailable —— sales_order 没有 shop_code 列（实查 V99）。
    var shop =
        caps.get("unavailableDimensions").valueStream()
            .filter(d -> d.get("field").asText().equals("platformShopCode"))
            .findFirst()
            .orElseThrow();
    assertThat(shop.get("available").asBoolean()).isFalse();
    assertThat(shop.get("reason").asText()).contains("shop_code").contains("跨服务");

    // 责任人必须报上游未交付 + 租户可维护的解法。
    var owner =
        caps.get("unavailableDimensions").valueStream()
            .filter(d -> d.get("field").asText().equals("ownerFromUpstream"))
            .findFirst()
            .orElseThrow();
    assertThat(owner.get("reason").asText()).contains("captured_at").contains("同步器");

    // 五种类型一个都不能少。
    var filters =
        json.readTree(
            http.perform(get("/trade/aftersales/filters").header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var types =
        filters.get("caseTypes").valueStream().map(t -> t.get("code").asText()).toList();
    assertThat(types)
        .containsExactlyInAnyOrder(
            "RETURN_REFUND", "REFUND_ONLY", "EXCHANGE", "REISSUE", "REPAIR");

    // 三件批量操作全部可用，且各自报出落在哪张表（不假装已对接下游模块）。
    var ops =
        caps.get("bulkOperations").valueStream()
            .map(o -> o.get("code").asText())
            .toList();
    assertThat(ops).containsExactlyInAnyOrder("AUTHORIZE", "NOTE", "ESCALATE");
    for (var op : caps.get("bulkOperations")) {
      assertThat(op.get("available").asBoolean()).as(op.get("code").asText()).isTrue();
      assertThat(op.get("landsIn").asText()).contains("aftersales_");
      assertThat(op.get("maxBatchSize").asInt()).isEqualTo(100);
    }

    // 下游四个模块全部未交付 —— 列表行不许跳到不存在的路由。
    var down = caps.get("downstream");
    assertThat(down.get("detailDelivered").asBoolean()).isFalse();
    assertThat(down.get("intakeDelivered").asBoolean()).isFalse();
    assertThat(down.get("returnsDelivered").asBoolean()).isFalse();
    assertThat(down.get("refundsDelivered").asBoolean()).isFalse();
    assertThat(down.get("ticketDelivered").asBoolean()).isFalse();
    assertThat(down.get("note").asText()).contains("不跳转到任何不存在的路由");
  }

  // ==========================================================================
  // 五种类型
  // ==========================================================================

  @Test
  void 五种类型都能登记也能列出来() throws Exception {
    var order = seedOrder("SO-TYPES", "AMAZON");
    for (var type :
        List.of("RETURN_REFUND", "REFUND_ONLY", "EXCHANGE", "REISSUE", "REPAIR")) {
      var row =
          createCase(order, type, "QUALITY_DEFECT", "50.0000", "USD", "1.000000", hours(72), null);
      assertThat(row.get("caseType").asText()).isEqualTo(type);
    }
    var page = page("?size=200");
    assertThat(page.get("total").asLong()).isEqualTo(5);
    var seen = new ArrayList<String>();
    page.get("rows").forEach(r -> seen.add(r.get("caseType").asText()));
    assertThat(seen)
        .containsExactlyInAnyOrder("RETURN_REFUND", "REFUND_ONLY", "EXCHANGE", "REISSUE", "REPAIR");

    // 汇总条按类型分列，五类都在。
    var byType = page.get("summary").get("countsByType");
    assertThat(byType.size()).isEqualTo(5);
  }

  @Test
  void 非法类型被拒而不是悄悄落一条别的类型() throws Exception {
    var order = seedOrder("SO-BADTYPE", "AMAZON");
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("orderId", order.toString());
    body.put("caseType", "MADE_UP");
    body.put("reasonCode", "QUALITY_DEFECT");
    body.put("requestedAmount", "10.0000");
    body.put("currency", "USD");
    body.put("requestedQuantity", "1.000000");
    body.put("replyDueAt", hours(48).toString());
    http.perform(
            post("/trade/aftersales/cases")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
    assertThat(page("").get("total").asLong()).isZero();
  }

  // ==========================================================================
  // 时效：蓝图加粗句的落点
  // ==========================================================================

  @Test
  void 平台时限用非utc偏移写入后写回的是同一个绝对时刻() throws Exception {
    var order = seedOrder("SO-TZ", "SHOPIFY");
    // 刻意用 +08:00：用 Timestamp.valueOf 的实现会静默偏 −28800s（8 小时），
    // 而那种错误在「只断言没抛异常」的测试里完全看不见。
    var reply = OffsetDateTime.parse("2026-03-01T20:00:00+08:00");   // = 12:00Z
    var resolve = OffsetDateTime.parse("2026-03-05T09:30:00+08:00"); // = 01:30Z
    var row = createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "80.0000", "USD", "2.000000", reply, resolve);

    // API 回读：绝对时刻必须逐位相同（时区不同没关系）。
    assertThat(OffsetDateTime.parse(row.get("replyDueAt").asText()).toInstant())
        .as("回复时限写回必须还是同一个绝对时刻")
        .isEqualTo(reply.toInstant());
    assertThat(OffsetDateTime.parse(row.get("resolveDueAt").asText()).toInstant())
        .isEqualTo(resolve.toInstant());

    // 再直接查库逐位断言 —— 不信任任何一层序列化。
    var stored =
        inTenant(
            () ->
                sql.fetchOne(
                        "select reply_due_at, resolve_due_at, effective_due_at from aftersales_case"
                            + " where case_no = ?",
                        row.get("caseNo").asText()));
    assertThat(stored).as("刚登记的售后单必须真的落库了").isNotNull();
    assertThat(stored.get("reply_due_at", OffsetDateTime.class).toInstant())
        .isEqualTo(reply.toInstant());
    assertThat(stored.get("resolve_due_at", OffsetDateTime.class).toInstant())
        .isEqualTo(resolve.toInstant());

    // effective_due_at = **更紧**的那个（这里回复时限更早）。
    assertThat(stored.get("effective_due_at", OffsetDateTime.class).toInstant())
        .as("effective_due_at 必须取两个时限里更紧的那个")
        .isEqualTo(reply.toInstant());
  }

  @Test
  void 时效状态四个态分得开() throws Exception {
    var order = seedOrder("SO-SLA", "AMAZON");
    // 两个时限都在过去 → OVERDUE
    var overdue =
        createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "30.0000", "USD", "1.000000", hours(-48), null);
    // 24 小时内 → DUE_SOON
    var dueSoon =
        createCase(order, "REFUND_ONLY", "BUYER_REASON", "20.0000", "USD", "1.000000", hours(3), null);
    // 远期 → ON_TRACK
    var onTrack =
        createCase(order, "EXCHANGE", "SIZE_MISMATCH", "40.0000", "USD", "1.000000", hours(120), null);
    // 已过期时限但已结案 → RESOLVED（倒计时停止，不再报警）
    var resolved =
        createCase(order, "REISSUE", "MISTAKE_BUY", "10.0000", "USD", "1.000000", hours(-48), null);
    inTenant(
        () -> {
          sql.execute(
              "update aftersales_case set status='RESOLVED', resolved_at=?::timestamptz where case_no=?",
              OffsetDateTime.now(ZoneOffset.UTC).minusHours(1),
              resolved.get("caseNo").asText());
          return null;
        });

    assertThat(overdue.get("timelinessState").asText()).isEqualTo("OVERDUE");
    assertThat(overdue.get("remainingSeconds").asLong()).isNegative();
    assertThat(dueSoon.get("timelinessState").asText()).isEqualTo("DUE_SOON");
    assertThat(onTrack.get("timelinessState").asText()).isEqualTo("ON_TRACK");

    var page = page("?size=200");
    var states = new java.util.LinkedHashMap<String, String>();
    page.get("rows").forEach(r -> states.put(r.get("caseNo").asText(), r.get("timelinessState").asText()));
    assertThat(states.get(resolved.get("caseNo").asText())).isEqualTo("RESOLVED");
    // 已结案行的 remainingSeconds 必须是 null 而不是 0 —— 「剩余 0 秒」会被读成「现在就超时了」。
    var resolvedRow =
        page.get("rows").valueStream()
            .filter(r -> r.get("caseNo").asText().equals(resolved.get("caseNo").asText()))
            .findFirst()
            .orElseThrow();
    assertThat(resolvedRow.get("remainingSeconds").isNull()).isTrue();
  }

  @Test
  void 时效可以在数据库层筛选() throws Exception {
    var order = seedOrder("SO-SLAFILTER", "AMAZON");
    createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "30.0000", "USD", "1.000000", hours(-48), null);
    createCase(order, "REFUND_ONLY", "BUYER_REASON", "20.0000", "USD", "1.000000", hours(3), null);
    createCase(order, "EXCHANGE", "SIZE_MISMATCH", "40.0000", "USD", "1.000000", hours(120), null);

    assertThat(page("?timelinessState=OVERDUE").get("total").asLong()).isEqualTo(1);
    assertThat(page("?timelinessState=DUE_SOON").get("total").asLong()).isEqualTo(1);
    assertThat(page("?timelinessState=ON_TRACK").get("total").asLong()).isEqualTo(1);
    assertThat(page("?timelinessState=RESOLVED").get("total").asLong()).isZero();

    // 汇总条与筛选一致（统计命中筛选条件的全部行，与分页无关）。
    var overdue = page("?timelinessState=OVERDUE");
    assertThat(overdue.get("summary").get("overdueCount").asLong()).isEqualTo(1);
  }

  @Test
  void 时效可以排序且超时的排最前() throws Exception {
    var order = seedOrder("SO-SLASORT", "AMAZON");
    createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "10.0000", "USD", "1.000000", hours(300), null);
    createCase(order, "REFUND_ONLY", "BUYER_REASON", "10.0000", "USD", "1.000000", hours(-5), null);
    createCase(order, "EXCHANGE", "SIZE_MISMATCH", "10.0000", "USD", "1.000000", hours(60), null);

    var rows = page("?sort=DUE_ASC&size=200").get("rows");
    assertThat(rows.size()).isEqualTo(3);
    assertThat(rows.get(0).get("timelinessState").asText()).as("超时的必须排最前").isEqualTo("OVERDUE");
    assertThat(rows.get(1).get("timelinessState").asText()).isEqualTo("ON_TRACK");
    assertThat(rows.get(2).get("timelinessState").asText()).isEqualTo("ON_TRACK");

    // 反向排序也必须真的反。
    var desc = page("?sort=DUE_DESC&size=200").get("rows");
    assertThat(desc.get(0).get("timelinessState").asText()).isEqualTo("ON_TRACK");

    // 非法排序 400，不静默回落。
    http.perform(
            get("/trade/aftersales/cases?sort=NOT_A_SORT").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 至少要给一个平台时限() throws Exception {
    var order = seedOrder("SO-NOSLA", "AMAZON");
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("orderId", order.toString());
    body.put("caseType", "RETURN_REFUND");
    body.put("reasonCode", "QUALITY_DEFECT");
    body.put("requestedAmount", "10.0000");
    body.put("currency", "USD");
    body.put("requestedQuantity", "1.000000");
    http.perform(
            post("/trade/aftersales/cases")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 多维筛选 + 分页 + 汇总
  // ==========================================================================

  @Test
  void 金额区间是闭区间两端都取() throws Exception {
    var order = seedOrder("SO-AMT", "AMAZON");
    createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "99.9999", "USD", "1.000000", hours(48), null);
    createCase(order, "REFUND_ONLY", "BUYER_REASON", "100.0000", "USD", "1.000000", hours(48), null);
    createCase(order, "EXCHANGE", "SIZE_MISMATCH", "100.0001", "USD", "1.000000", hours(48), null);

    // min == max == 100.0000：闭区间下**恰好等于边界的那张单必须在结果里**。
    // 开区间或左开右闭都会漏掉它 —— 而漏掉边界在售后场景里会被当成系统丢单。
    var exact = page("?minAmount=100.0000&maxAmount=100.0000");
    assertThat(exact.get("total").asLong()).as("闭区间：恰好等于下界的单必须在内").isEqualTo(1);
    assertThat(new BigDecimal(exact.get("rows").get(0).get("requestedAmount").asText()))
        .as("闭区间命中的正是那张 100.0000 的单")
        .isEqualByComparingTo("100.0000");

    // 下界闭合
    assertThat(page("?minAmount=100.0000").get("total").asLong()).isEqualTo(2);
    // 上界闭合
    assertThat(page("?maxAmount=100.0000").get("total").asLong()).isEqualTo(2);

    // 口径由响应原文报出，界面上要能写出来
    assertThat(exact.get("amountRangeSemantics").asText()).contains("闭区间");

    // 下限大于上限 → 400
    http.perform(
            get("/trade/aftersales/cases?minAmount=100&maxAmount=50")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 多维筛选能同时生效() throws Exception {
    var amazon = seedOrder("SO-AMZ", "AMAZON");
    var shopify = seedOrder("SO-SHP", "SHOPIFY");
    createCase(amazon, "RETURN_REFUND", "QUALITY_DEFECT", "10.0000", "USD", "1.000000", hours(48), null);
    createCase(amazon, "EXCHANGE", "SIZE_MISMATCH", "10.0000", "USD", "1.000000", hours(48), null);
    createCase(shopify, "RETURN_REFUND", "QUALITY_DEFECT", "20.0000", "EUR", "1.000000", hours(48), null);

    assertThat(page("").get("total").asLong()).isEqualTo(3);
    assertThat(page("?caseType=RETURN_REFUND").get("total").asLong()).isEqualTo(2);
    assertThat(page("?caseType=RETURN_REFUND&platformSource=AMAZON").get("total").asLong()).isEqualTo(1);
    assertThat(page("?caseType=RETURN_REFUND&reasonCode=SIZE_MISMATCH").get("total").asLong()).isZero();
    assertThat(page("?currency=EUR").get("total").asLong()).isEqualTo(1);
    assertThat(page("?platformSource=SHOPIFY&currency=USD").get("total").asLong()).isZero();
    assertThat(page("?status=RECEIVED").get("total").asLong()).isEqualTo(3);
    assertThat(page("?status=RESOLVED").get("total").asLong()).isZero();
  }

  @Test
  void 关键字是真搜索() throws Exception {
    var order = seedOrder("SO-SEARCH", "AMAZON");
    createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "10.0000", "USD", "1.000000", hours(48), null);
    var hit = page("?keyword=SO-SEARCH");
    assertThat(hit.get("total").asLong()).isEqualTo(1);
    assertThat(page("?keyword=根本没有这个词").get("total").asLong()).isZero();
  }

  @Test
  void 分页是真分页且汇总与分页无关() throws Exception {
    var order = seedOrder("SO-PAGE", "AMAZON");
    for (int i = 0; i < 5; i++) {
      createCase(
          order,
          "RETURN_REFUND",
          "QUALITY_DEFECT",
          (10 + i) + ".0000",
          "USD",
          "1.000000",
          hours(48 + i),
          null);
    }
    var p0 = page("?page=0&size=2");
    var p1 = page("?page=1&size=2");
    var p2 = page("?page=2&size=2");
    assertThat(p0.get("total").asLong()).isEqualTo(5);
    assertThat(p0.get("rows").size()).isEqualTo(2);
    assertThat(p1.get("rows").size()).isEqualTo(2);
    assertThat(p2.get("rows").size()).isEqualTo(1);

    // 三页不重不漏
    var all = new ArrayList<String>();
    for (var page : List.of(p0, p1, p2)) {
      page.get("rows").forEach(r -> all.add(r.get("caseNo").asText()));
    }
    assertThat(all).doesNotHaveDuplicates();
    assertThat(all).hasSize(5);

    // 汇总统计的是**命中筛选条件的全部行**，不是当前页
    assertThat(p0.get("summary").get("totalCount").asLong()).isEqualTo(5);
    assertThat(p0.get("summary").get("openCount").asLong()).isEqualTo(5);

    // 金额按币种分开，不跨币种相加
    var amounts = p0.get("summary").get("amountsByCurrency");
    assertThat(amounts.size()).isEqualTo(1);
    assertThat(amounts.get(0).get("currency").asText()).isEqualTo("USD");
    assertThat(new BigDecimal(amounts.get(0).get("amount").asText()))
        .isEqualByComparingTo("60.0000");
  }

  @Test
  void 数据范围self真的只返回本人经手的单() throws Exception {
    var order = seedOrder("SO-SCOPE", "AMAZON");
    // 一张责任人是我（user）的
    createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "10.0000", "USD", "1.000000", hours(48), null, user.toString());
    // 一张责任人是别人的
    createCase(order, "REFUND_ONLY", "BUYER_REASON", "20.0000", "USD", "1.000000", hours(48), null, UUID.randomUUID().toString());

    // ALL 范围：两张
    assertThat(page("").get("total").asLong()).isEqualTo(2);
    assertThat(page("").get("dataScope").asText()).isEqualTo("ALL");

    // SELF 范围：只能看到责任人是我自己的那一张。
    var self =
        json.readTree(
            http.perform(
                    get("/trade/aftersales/cases")
                        .header("Authorization", "Bearer self-scope"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(self.get("dataScope").asText()).isEqualTo("SELF");
    assertThat(self.get("total").asLong()).as("SELF 范围只返回本人经手的单").isEqualTo(1);
    assertThat(self.get("rows").get(0).get("caseType").asText()).isEqualTo("RETURN_REFUND");
  }

  // ==========================================================================
  // 批量操作：授权
  // ==========================================================================

  @Test
  void 批量授权逐条回报成功与失败() throws Exception {
    var order = seedOrder("SO-AUTH", "AMAZON");
    var okRefund = createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "100.0000", "USD", "1.000000", hours(48), null);
    var partial = createCase(order, "REFUND_ONLY", "BUYER_REASON", "100.0000", "USD", "1.000000", hours(48), null);
    var exchange = createCase(order, "EXCHANGE", "SIZE_MISMATCH", "100.0000", "USD", "1.000000", hours(48), null);
    var missing = UUID.randomUUID();

    var body = bulkBody("AUTHORIZE",
        List.of(okRefund.get("id").asText(), partial.get("id").asText(),
                exchange.get("id").asText(), missing.toString()));
    body.put("decision", "APPROVED");
    body.put("authorizedAmount", "40.0000");

    var result = bulk(body);
    assertThat(result.get("requested").asInt()).isEqualTo(4);
    assertThat(result.get("succeeded").asInt()).as("只有两张能批").isEqualTo(2);
    assertThat(result.get("failed").asInt()).isEqualTo(2);

    var items = result.get("items");
    assertThat(items.size()).as("逐条回报：每个目标一个元素").isEqualTo(4);

    assertThat(items.get(0).get("success").asBoolean()).isTrue();
    assertThat(items.get(0).get("outcome").asText()).isEqualTo("AUTHORIZED");
    assertThat(items.get(0).get("reference").asText()).isNotBlank();
    assertThat(items.get(0).get("caseNo").asText()).isEqualTo(okRefund.get("caseNo").asText());

    // 仅退款：金额合法，也成功
    assertThat(items.get(1).get("success").asBoolean()).isTrue();

    // 换货：授权类型不带金额 → AMOUNT_NOT_ALLOWED
    assertThat(items.get(2).get("success").asBoolean()).isFalse();
    assertThat(items.get(2).get("outcome").asText()).isEqualTo("AMOUNT_NOT_ALLOWED");
    assertThat(items.get(2).get("detail").asText()).contains("换货");

    // 不存在的单 → NOT_FOUND，而不是静默跳过
    assertThat(items.get(3).get("success").asBoolean()).isFalse();
    assertThat(items.get(3).get("outcome").asText()).isEqualTo("NOT_FOUND");

    // 授权后状态推进，但**不**推进到已解决（11.2 / 11.3 未交付）
    var after = page("?caseNo=" + okRefund.get("caseNo").asText());
    assertThat(after.get("rows").get(0).get("status").asText()).isEqualTo("IN_PROCESS");
    assertThat(after.get("rows").get(0).get("authorizationState").asText()).isEqualTo("APPROVED");
  }

  @Test
  void 授权金额超过申请金额被逐条拒绝() throws Exception {
    var order = seedOrder("SO-AUTHAMT", "AMAZON");
    var row = createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "50.0000", "USD", "1.000000", hours(48), null);
    var body = bulkBody("AUTHORIZE", List.of(row.get("id").asText()));
    body.put("decision", "APPROVED");
    body.put("authorizedAmount", "50.0001");
    var result = bulk(body);
    assertThat(result.get("failed").asInt()).isEqualTo(1);
    assertThat(result.get("items").get(0).get("outcome").asText()).isEqualTo("AMOUNT_EXCEEDS");
    // 恰好等于申请金额是允许的（部分授权的上界包含等号）
    var ok = bulkBody("AUTHORIZE", List.of(row.get("id").asText()));
    ok.put("decision", "PARTIALLY_APPROVED");
    ok.put("authorizedAmount", "50.0000");
    assertThat(bulk(ok).get("succeeded").asInt()).isEqualTo(1);
  }

  @Test
  void 批量授权拒绝决定不能带金额() throws Exception {
    var order = seedOrder("SO-AUTHREJ", "AMAZON");
    var row = createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "50.0000", "USD", "1.000000", hours(48), null);
    var bad = bulkBody("AUTHORIZE", List.of(row.get("id").asText()));
    bad.put("decision", "REJECTED");
    bad.put("authorizedAmount", "10.0000");
    assertThat(bulk(bad).get("items").get(0).get("outcome").asText()).isEqualTo("AMOUNT_EXCEEDS");

    var good = bulkBody("AUTHORIZE", List.of(row.get("id").asText()));
    good.put("decision", "REJECTED");
    assertThat(bulk(good).get("succeeded").asInt()).isEqualTo(1);
    assertThat(page("?authorizationState=REJECTED").get("total").asLong()).isEqualTo(1);
  }

  @Test
  void 同一请求里重复的单按请求顺序逐条回报() throws Exception {
    var order = seedOrder("SO-DUP", "AMAZON");
    var row = createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "100.0000", "USD", "1.000000", hours(48), null);
    String id = row.get("id").asText();
    var body = bulkBody("NOTE", List.of(id, id, id));
    body.put("note", "重复提交的批量");
    var result = bulk(body);
    assertThat(result.get("requested").asInt()).isEqualTo(3);
    assertThat(result.get("succeeded").asInt()).isEqualTo(1);
    assertThat(result.get("failed").asInt()).isEqualTo(2);
    // 顺序必须与请求一致：第 1 条成功，第 2、3 条自己失败。
    // 若按 caseId 归并，第 1 条的成功会被顶到第 2 个位置上 —— 运营会看到「1 失败、2 成功、3 失败」。
    var items = result.get("items");
    assertThat(items.get(0).get("success").asBoolean()).isTrue();
    assertThat(items.get(1).get("outcome").asText()).isEqualTo("DUPLICATE_IN_REQUEST");
    assertThat(items.get(2).get("outcome").asText()).isEqualTo("DUPLICATE_IN_REQUEST");
  }

  @Test
  void 已结案的单批量操作逐条失败() throws Exception {
    var order = seedOrder("SO-CLOSED", "AMAZON");
    var row = createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "100.0000", "USD", "1.000000", hours(-48), null);
    inTenant(
        () -> {
          sql.execute(
              "update aftersales_case set status='CLOSED', resolved_at=?::timestamptz where case_no=?",
              OffsetDateTime.now(ZoneOffset.UTC).minusHours(1),
              row.get("caseNo").asText());
          return null;
        });
    var body = bulkBody("AUTHORIZE", List.of(row.get("id").asText()));
    body.put("decision", "APPROVED");
    body.put("authorizedAmount", "10.0000");
    var result = bulk(body);
    assertThat(result.get("failed").asInt()).isEqualTo(1);
    assertThat(result.get("items").get(0).get("outcome").asText()).isEqualTo("STATUS_TERMINAL");
    assertThat(result.get("items").get(0).get("detail").asText()).contains("已结案");
  }

  @Test
  void 批量操作有单次上限() throws Exception {
    var ids = new ArrayList<String>();
    for (int i = 0; i <= 100; i++) ids.add(UUID.randomUUID().toString());
    var body = bulkBody("NOTE", ids);
    body.put("note", "超上限");
    http.perform(
            post("/trade/aftersales/cases/bulk")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 批量操作：备注
  // ==========================================================================

  @Test
  void 批量备注落到真实表并让备注条数变化() throws Exception {
    var order = seedOrder("SO-NOTE", "AMAZON");
    var one = createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "10.0000", "USD", "1.000000", hours(48), null);
    var two = createCase(order, "REFUND_ONLY", "BUYER_REASON", "20.0000", "USD", "1.000000", hours(48), null);
    assertThat(page("?caseNo=" + one.get("caseNo").asText()).get("rows").get(0).get("noteCount").asLong())
        .isZero();

    var body = bulkBody("NOTE", List.of(one.get("id").asText(), two.get("id").asText()));
    body.put("note", "已联系买家，等待寄回");
    var result = bulk(body);
    assertThat(result.get("succeeded").asInt()).isEqualTo(2);
    assertThat(result.get("items").get(0).get("outcome").asText()).isEqualTo("NOTE_ADDED");

    // 真落库了，而且带 batch_id 可追回是哪一次批量
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select count(*) from aftersales_case_note where tenant_id = ?::uuid"
                                + " and case_id = ?::uuid and note_body = ?",
                            a, UUID.fromString(one.get("id").asText()), "已联系买家，等待寄回")
                        .get(0, Long.class)))
        .as("备注必须真的落库")
        .isEqualTo(1);
    assertThat(page("?caseNo=" + one.get("caseNo").asText()).get("rows").get(0).get("noteCount").asLong())
        .as("备注条数必须真的涨上去").isEqualTo(1);
  }

  @Test
  void 空备注被整体拒绝而不是逐条标失败() throws Exception {
    var order = seedOrder("SO-NOTE2", "AMAZON");
    var row = createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "10.0000", "USD", "1.000000", hours(48), null);
    var body = bulkBody("NOTE", List.of(row.get("id").asText()));
    body.put("note", "   ");
    http.perform(
            post("/trade/aftersales/cases/bulk")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 批量操作：转工单
  // ==========================================================================

  @Test
  void 批量转工单落成本服务内的工单记录() throws Exception {
    var order = seedOrder("SO-TICKET", "AMAZON");
    var row = createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "100.0000", "USD", "1.000000", hours(48), null);
    var body = bulkBody("ESCALATE", List.of(row.get("id").asText()));
    body.put("priority", "URGENT");
    body.put("reasonNote", "平台已介入");
    var result = bulk(body);
    assertThat(result.get("succeeded").asInt()).isEqualTo(1);
    assertThat(result.get("items").get(0).get("outcome").asText()).isEqualTo("TICKET_CREATED");
    var ticketNo = result.get("items").get(0).get("reference").asText();
    assertThat(ticketNo).startsWith("TK");

    // 列表行带上工单号，且不假装下游已交付（详情里点进去也没有路由）
    var after = page("?caseNo=" + row.get("caseNo").asText());
    assertThat(after.get("rows").get(0).get("ticketNo").asText()).isEqualTo(ticketNo);
    // 工单归属模块显式记着 11.4.2.1（未交付）
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select owner_module from aftersales_service_ticket where ticket_no = ?",
                            ticketNo)
                        .get(0, String.class)))
        .as("工单必须显式记着归属 11.4.2.1（未交付），不能冒充本模块自造的影子表")
        .isEqualTo("11.4.2.1");
    // 单条详情路由：**11.1.1.2 售后单详情已交付**（V157 / GET /trade/aftersales/cases/{id}）。
    //
    // ⚠️ 这里原本断言 404 —— 那条断言的前提是「11.1.1.2 未交付」，本模块的台账当时也写着
    //   「刻意不提供 cases/{uuid}」。前提已经不成立，继续断言 404 只会把一条**真的存在**的
    //   路由钉成「不存在」，让任何人删掉它时测试照绿。11.1.1.2 交付后改为断言它真的通。
    //   未交付的**子**资源仍然 404（详情模块刻意不提供单条删除与改写）。
    http.perform(get("/trade/aftersales/cases/" + row.get("id").asText())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .delete("/trade/aftersales/cases/" + row.get("id").asText())
        .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isMethodNotAllowed());
  }

  @Test
  void 重复转工单逐条失败并附上已有工单号() throws Exception {
    var order = seedOrder("SO-TICKET2", "AMAZON");
    var row = createCase(order, "EXCHANGE", "SIZE_MISMATCH", "100.0000", "USD", "1.000000", hours(48), null);
    assertThat(bulk(bulkBody("ESCALATE", List.of(row.get("id").asText()))).get("succeeded").asInt())
        .isEqualTo(1);
    var second = bulk(bulkBody("ESCALATE", List.of(row.get("id").asText())));
    assertThat(second.get("failed").asInt()).isEqualTo(1);
    assertThat(second.get("items").get(0).get("outcome").asText()).isEqualTo("ALREADY_TICKETED");
    assertThat(second.get("items").get(0).get("detail").asText()).contains("TK");
  }

  // ==========================================================================
  // 关联订单真实性 + DB 层护栏
  // ==========================================================================

  @Test
  void 关联订单不存在时被拒() throws Exception {
    var body = new java.util.LinkedHashMap<String, Object>();
    body.put("orderId", UUID.randomUUID().toString());
    body.put("caseType", "RETURN_REFUND");
    body.put("reasonCode", "QUALITY_DEFECT");
    body.put("requestedAmount", "10.0000");
    body.put("currency", "USD");
    body.put("requestedQuantity", "1.000000");
    body.put("replyDueAt", hours(48).toString());
    http.perform(
            post("/trade/aftersales/cases")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void db触发器挡住类型不匹配的授权() throws Exception {
    var order = seedOrder("SO-GUARD", "AMAZON");
    var row = createCase(order, "EXCHANGE", "SIZE_MISMATCH", "100.0000", "USD", "1.000000", hours(48), null);
    UUID caseId = UUID.fromString(row.get("id").asText());
    // 绕过服务层直接写：给一张换货单批一笔「退款授权」必须被 DB 挡住。
    // 只靠服务层校验是不够的 —— 任何一个新写的脚本、报表工具或另一个服务都能绕过它。
    var e =
        catchThrowable(
            () ->
                sql.execute(
                    "insert into aftersales_authorization(id,tenant_id,case_id,authorization_kind,"
                        + " decision,reason_note,decided_by,decided_at,created_at)"
                        + " values (?,?::uuid,?::uuid,'REFUND_APPROVED','APPROVED','',"
                        + " ?,?::timestamptz,now())",
                    UUID.randomUUID(),
                    a,
                    caseId,
                    user,
                    OffsetDateTime.now(ZoneOffset.UTC)));
    assertThat(e).as("换货单不允许批退款授权").isNotNull();
    // ⚠️ 断言「插不进去」而不是断言异常文案：jOOQ 把触发器的 RAISE EXCEPTION
    //   （SQLState P0001）归类成 BadSqlGrammarException，文案里只有 SQL 没有原因。
    //   真正该守住的是**这一行没进去**，所以直接查库断言。
    assertThat(countAuthorizations(caseId)).as("被挡住的授权绝不能落库").isZero();

    // 对照组：类型匹配的授权**能**进去 ——
    // 否则上面那条断言可能只是因为这条插入语句本身写坏了。
    inTenant(
        () ->
            sql.execute(
                "insert into aftersales_authorization(id,tenant_id,case_id,authorization_kind,"
                    + " decision,reason_note,decided_by,decided_at,created_at)"
                    + " values (?,?::uuid,?::uuid,'EXCHANGE_APPROVED','APPROVED','',"
                    + "?,?::timestamptz,now())",
                UUID.randomUUID(), a, caseId, user, OffsetDateTime.now(ZoneOffset.UTC)));
    assertThat(countAuthorizations(caseId)).as("对照组：类型匹配的授权必须能落库").isEqualTo(1);
  }

  @Test
  void db约束要求终态必须带结案时刻() throws Exception {
    var order = seedOrder("SO-TERMINAL", "AMAZON");
    var row = createCase(order, "RETURN_REFUND", "QUALITY_DEFECT", "10.0000", "USD", "1.000000", hours(48), null);
    UUID caseId = UUID.fromString(row.get("id").asText());
    // 只改状态不改 resolved_at ⇒ 倒计时永远不停止，一张三月前结案的单会被一直报成超时。
    assertThat(
            catchThrowable(
                () ->
                    inTenant(
                        () ->
                            sql.execute(
                                "update aftersales_case set status='RESOLVED' where id = ?::uuid",
                                caseId))))
        .as("终态与 resolved_at 必须严格等价")
        .isNotNull();
  }

  @Test
  void 生效的时限列运行角色改不动() {
    // effective_due_at 由触发器维护；运行角色对它没有 UPDATE 权限，
    // 否则有人可以直接把它写成一个假的「不紧急」来掩盖超时。
    var priv =
        sql.fetchOne(
            "select has_column_privilege('trade_app','aftersales_case','effective_due_at','UPDATE')");
    assertThat(priv.get(0, Boolean.class)).isFalse();
    var tablePriv =
        sql.fetchOne(
            "select has_column_privilege('trade_app','aftersales_case','status','UPDATE')");
    assertThat(tablePriv.get(0, Boolean.class)).as("状态仍然可改").isTrue();
  }

  @Test
  void 留痕表是追加式的运行角色不能改也不能删() {
    for (String table :
        List.of(
            "aftersales_owner_assignment",
            "aftersales_case_note",
            "aftersales_authorization",
            "aftersales_service_ticket")) {
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('trade_app',?,'UPDATE')", table)
                  .get(0, Boolean.class))
          .as(table + " 必须追加式：责任人流转、备注、授权、转工单都是纠纷举证的原材料")
          .isFalse();
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('trade_app',?,'DELETE')", table)
                  .get(0, Boolean.class))
          .isFalse();
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('trade_app',?,'INSERT')", table)
                  .get(0, Boolean.class))
          .isTrue();
    }
  }

  @Test
  void 时效函数在数据库里是唯一的判定真身() {
    // Java 侧刻意不重算一遍判定。两处实现必然漂移，而超时直接关系账号绩效与资金冻结。
    var fn =
        sql.fetchOne(
            "select provolatile from pg_proc where proname = 'aftersales_timeliness_state'");
    assertThat(fn).as("V156 必须建出时效判定函数").isNotNull();
    assertThat(fn.get(0, String.class)).as("IMMUTABLE：可进索引表达式").isEqualTo("i");

    // 边界：恰好等于参考时刻 ⇒ ON_TRACK（还没超时）；早 1 微秒 ⇒ OVERDUE
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    // 恰好等于参考时刻：**还没超时**（判 OVERDUE 用的是严格小于），但已落在 24 小时窗口内 ⇒ DUE_SOON。
    // 这不是 bug 也不是巧合：时限正好是「此刻」的单，运营看到的就是「现在就要处理」。
    assertThat(stateOf(now, now)).isEqualTo("DUE_SOON");
    assertThat(stateOf(now.minusNanos(1000), now)).as("早 1 微秒就是超时").isEqualTo("OVERDUE");
    // 恰好 24 小时 ⇒ DUE_SOON（闭区间，和「剩余 0 秒」一致）
    assertThat(stateOf(now.plusHours(24), now)).isEqualTo("DUE_SOON");
    assertThat(stateOf(now.plusHours(24).plusNanos(1000), now)).isEqualTo("ON_TRACK");
    // 已结案 ⇒ RESOLVED（即使时限早就过了）
    assertThat(stateOf(now.minusDays(3), now, true)).isEqualTo("RESOLVED");
    // 无时限 ⇒ NO_DEADLINE，与 ON_TRACK 分开
    assertThat(stateOf(null, now)).isEqualTo("NO_DEADLINE");
  }

  long countAuthorizations(UUID caseId) {
    return inTenant(
        () ->
            sql.fetchOne(
                    "select count(*) from aftersales_authorization where case_id = ?::uuid", caseId)
                .get(0, Long.class));
  }

  String stateOf(OffsetDateTime due, OffsetDateTime at) {
    return stateOf(due, at, false);
  }

  String stateOf(OffsetDateTime due, OffsetDateTime at, boolean done) {
    return sql.fetchOne(
            "select aftersales_timeliness_state(?::timestamptz,?::timestamptz,?::timestamptz)",
            due,
            done ? at : null,
            at)
        .get(0, String.class);
  }
}
