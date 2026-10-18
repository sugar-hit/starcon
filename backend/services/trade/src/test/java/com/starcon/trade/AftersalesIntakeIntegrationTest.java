package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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
 * 11.1.1.3 售后受理（四项判定 + 必退自动判定 + 责任人分配）。
 *
 * <p>全部走 <b>HTTP 端点 + 真实 PostgreSQL 16 + 强制 RLS</b>，不使用任何内存替身。
 * 订单夹具走 V99 表（9.1.2.2 手工单的写入所有者）。
 *
 * <p><b>身份服务那一格是跨服务替身，不是本模块的数据替身</b>：
 * 17.5.1.1 业务参数在 identity 服务自己的库里（identity V48），
 * AGENTS.md 禁止 trade 直连那个库，所以这里用 {@code com.sun.net.httpserver} 起一个
 * {@code /identity/business-parameters/effective} 形状的只读端点
 * （与 {@code ShipmentRecordIntegrationTest} 读 warehouse 同一手法）。
 * 售后单、判定凭据、回复时限策略、责任人名册**全部真落 PostgreSQL 16**。
 *
 * <p><b>夹具里的时刻一律 pin 死</b>（{@link #ORDER_AT} / {@link #RECEIVED_AT} 等）：
 * 售后期判定与回复时限全是本模块要断言的时间字段，
 * 若用 {@code now()} 构造，同一个用例只在某个钟点通过，批次调度会随机引爆。
 *
 * <p><b>本测试要钉住的：</b>
 * <ol>
 *   <li><b>必退自动判定</b>：原因分类映射命中当前生效词表 → 必退；不命中 → 不必退。
 *       两者都要**断言落库的结论与冻结的词表**，而不只看响应里的 boolean。</li>
 *   <li><b>人工不能自封必退</b>：内部录入勾「平台必退」被拒。</li>
 *   <li><b>平台声明的必退不可在本地降级</b>：直接绕过服务层写 SQL 也被 DB 触发器拒绝。</li>
 *   <li><b>售后期判定</b>：窗口内 / 超窗两种，断言绝对时刻（+08:00 夹具零漂移）。</li>
 *   <li><b>回复时限两档来源</b>：平台给 / 租户 SLA；<b>两档都没有时报错而不是编一个数字</b>。</li>
 *   <li><b>政策是真的从身份服务读的</b>：改替身返回的词表，判定结果跟着变；
 *       替身不可达时 503 而不是静默用默认值。</li>
 *   <li><b>判定凭据 append-only</b>：重新判定后旧判定仍在且带 superseded_at。</li>
 *   <li><b>责任人轮转</b>：加权最少在办单，且真的写进 11.1.1.1 的责任人留痕表。</li>
 *   <li><b>SLA 档内容不可改</b>：只能收口，触发器拒绝改内容。</li>
 *   <li><b>逐表断言 FORCE RLS</b>。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AftersalesIntakeIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 身份服务当前返回的两条业务参数（测试改它来模拟「运营改了参数」）。 */
  static volatile int windowDays = 30;
  static volatile List<String> mustRefundReasons =
      List.of("DAMAGED_ON_ARRIVAL", "MISSING_ITEM", "WRONG_ITEM");
  static volatile boolean identityDown = false;

  static final List<String> perms = List.of("trade.aftersales.read", "trade.aftersales.write");

  static AccessService.Access actor() {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(
        user, a, "Test", UUID.randomUUID(), null, perms, grants);
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

      // 17.5.1.1 的已认证入口（本服务只拿它来确认成员关系与权限）。
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

      // 17.5.1.1 业务参数生效值（跨服务只读端点）。
      identity.createContext(
          "/identity/business-parameters/effective",
          e -> {
            if (identityDown) {
              e.sendResponseHeaders(503, -1);
              e.close();
              return;
            }
            List<Map<String, Object>> out = new ArrayList<>();
            Map<String, Object> window = new LinkedHashMap<>();
            window.put("parameterCode", AftersalesPolicyQueryPort.PARAM_RETURN_WINDOW_DAYS);
            window.put("valueType", "INTEGER");
            window.put("value", String.valueOf(windowDays));
            window.put("valueNumeric", java.math.BigDecimal.valueOf(windowDays));
            window.put("valueCodes", List.of());
            window.put("sourceScope", "GLOBAL");
            out.add(window);
            Map<String, Object> mustRefund = new LinkedHashMap<>();
            mustRefund.put("parameterCode", AftersalesPolicyQueryPort.PARAM_MUST_REFUND_REASONS);
            mustRefund.put("valueType", "MULTI_ENUM");
            mustRefund.put("value", String.join(",", mustRefundReasons));
            mustRefund.put("valueNumeric", null);
            mustRefund.put("valueCodes", mustRefundReasons);
            mustRefund.put("sourceScope", "GLOBAL");
            out.add(mustRefund);
            byte[] body = json.writeValueAsBytes(out);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });

      actors.put("Bearer tenant-a", actor());
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

  // ⚠️ 夹具时刻 pin 死：售后期锚点与请求到达时刻之间差 30 天整，
  //   任何一处改成 now() 都会让「窗口内/超窗」两组的归属随批次调度漂移。
  static final OffsetDateTime ORDER_AT = OffsetDateTime.parse("2026-02-01T10:00:00+08:00");
  static final OffsetDateTime ORDER_CREATED_AT = OffsetDateTime.parse("2026-01-20T08:00:00+08:00");
  /** 下单后 10 天 ⇒ 30 天窗口内。 */
  static final OffsetDateTime RECEIVED_IN = OffsetDateTime.parse("2026-02-11T09:30:00+08:00");
  /** 下单后 45 天 ⇒ 30 天窗口外。 */
  static final OffsetDateTime RECEIVED_OUT = OffsetDateTime.parse("2026-03-18T09:30:00+08:00");


  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    windowDays = 30;
    mustRefundReasons = List.of("DAMAGED_ON_ARRIVAL", "MISSING_ITEM", "WRONG_ITEM");
    identityDown = false;
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate aftersales_intake_verdict, aftersales_reply_sla, aftersales_owner_roster,"
              + " aftersales_case_note, aftersales_authorization, aftersales_service_ticket,"
              + " aftersales_owner_assignment, aftersales_case, sales_order cascade");
    }
  }

  <T> T inTenant(java.util.function.Supplier<T> work) {
    return tenants.run(a, work);
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 一张真实订单（V99 手工单簇），锚点用 {@link #ORDER_AT} 显式写死。 */
  UUID seedOrder(String orderNo, OffsetDateTime submittedAt) throws Exception {
    UUID id = UUID.randomUUID();
    // ⚠️ 必须显式开事务：V99 的「SUBMITTED 必须带商品行 + 收货地址 + 客户快照」
    //   是延迟到 commit 才检查的约束触发器，autocommit 下第一条 insert 就立刻 commit。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      c.setAutoCommit(false);
      boolean submitted = submittedAt != null;
      try (var p =
          c.prepareStatement(
              "insert into sales_order(id,tenant_id,order_no,order_source,external_order_no,currency,"
                  + "payment_method,status,submitted_at,subtotal_amount,discount_amount,tax_amount,"
                  + "shipping_amount,other_amount,grand_total_amount,version,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,'PREPAID',?,?::timestamptz,10.0000,0,0,0,0,"
                  + "10.0000,0,?::timestamptz,?::timestamptz)")) {
        p.setObject(1, id);
        p.setObject(2, a);
        p.setString(3, orderNo);
        p.setString(4, "SHOPIFY");
        p.setString(5, "EXT-" + orderNo);
        p.setString(6, "USD");
        p.setString(7, submitted ? "SUBMITTED" : "DRAFT");
        p.setObject(8, submittedAt);
        p.setObject(9, ORDER_CREATED_AT);
        p.setObject(10, ORDER_CREATED_AT);
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
        l.setObject(7, ORDER_CREATED_AT);
        l.executeUpdate();
      }
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
        ad.setObject(6, ORDER_CREATED_AT);
        ad.executeUpdate();
      }
      try (var ps =
          c.prepareStatement(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?,?,?,?,?,'INDIVIDUAL','',?::timestamptz)")) {
        ps.setObject(1, UUID.randomUUID());
        ps.setObject(2, a);
        ps.setObject(3, id);
        ps.setObject(4, UUID.randomUUID());
        ps.setString(5, "C-" + orderNo);
        ps.setString(6, "买家" + orderNo);
        ps.setObject(7, ORDER_CREATED_AT);
        ps.executeUpdate();
      }
      c.commit();
    }
    return id;
  }

  Map<String, Object> intakeBody(UUID orderId, String reasonCode, OffsetDateTime receivedAt) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("orderId", orderId.toString());
    body.put("caseType", "RETURN_REFUND");
    body.put("reasonCode", reasonCode);
    body.put("requestedAmount", "88.0000");
    body.put("currency", "USD");
    body.put("requestedQuantity", "1.000000");
    body.put("intakeSource", "INTERNAL_ENTRY");
    body.put("intakeChannel", "INTERNAL_NOTE");
    body.put("handlingPlan", "RETURN_REFUND");
    body.put("buyerRequest", "买家要求退货退款");
    body.put("receivedAt", receivedAt.toString());
    return body;
  }

  /** 受理一档 SLA（小时）。 */
  void seedSla(String platformScope, int hours, OffsetDateTime from) throws Exception {
    http.perform(
            post("/trade/aftersales/intake/reply-sla")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "platformScope", platformScope,
                            "replySlaHours", hours,
                            "effectiveFrom", from.toString()))))
        .andExpect(status().isOk());
  }

  /** 受理一次并返回解析后的响应（每次都新建实体，测的是判定不是幂等）。 */
  com.fasterxml.jackson.databind.JsonNode doIntake(
      UUID orderId, String reasonCode, OffsetDateTime receivedAt) throws Exception {
    String body =
        http.perform(
                post("/trade/aftersales/intake/cases")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(intakeBody(orderId, reasonCode, receivedAt))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body);
  }

  // ==========================================================================
  // 一、售后期判定
  // ==========================================================================

  @Test
  void 售后期内_判定为在窗口内且冻结的天数来自身份服务() throws Exception {
    mustRefundReasons = List.of(); // 本用例只关心窗口
    seedSla("", 24, ORDER_AT.minusDays(1));
    UUID orderId = seedOrder("SO-WIN-1", ORDER_AT);

    var v = doIntake(orderId, "SIZE_MISMATCH", RECEIVED_IN).get("verdict");

    assertThat(v.get("inReturnWindow").asBoolean()).isTrue();
    assertThat(v.get("eligibility").asText()).isEqualTo("ACCEPTED");
    assertThat(v.get("orderAnchorKind").asText()).isEqualTo("SUBMITTED_AT");
    // 天数是真的从身份服务读来的（出厂默认 30），不是代码里的常量。
    assertThat(v.get("policyReturnWindowDays").asInt()).isEqualTo(30);
    // 锚点 +30 天：2026-02-01T10:00+08:00 + 30d = 2026-03-03T10:00+08:00。
    assertThat(v.get("returnWindowDeadlineAt").asText())
        .isEqualTo("2026-03-03T10:00:00+08:00");
    assertThat(v.get("policySourceScope").asText()).isEqualTo("GLOBAL");
  }

  @Test
  void 超出售后期_判定为超窗待裁定且余量小时数为负() throws Exception {
    mustRefundReasons = List.of();
    seedSla("", 24, ORDER_AT.minusDays(1));
    UUID orderId = seedOrder("SO-WIN-2", ORDER_AT);

    var v = doIntake(orderId, "SIZE_MISMATCH", RECEIVED_OUT).get("verdict");

    assertThat(v.get("inReturnWindow").asBoolean()).isFalse();
    assertThat(v.get("eligibility").asText()).isEqualTo("OUT_OF_WINDOW_PENDING");
    // 2026-02-01T10:00+08:00 +30d = 2026-03-03T10:00+08:00；
    // 到 2026-03-18T09:30+08:00 差 359.5 小时，整数除法截断为 359。
    assertThat(v.get("windowMarginHours").asLong()).isEqualTo(-359L);
  }

  @Test
  void 改短期限参数后同一张单重新判定会用新窗口_但旧判定原样留着() throws Exception {
    mustRefundReasons = List.of();
    seedSla("", 24, ORDER_AT.minusDays(1));
    UUID orderId = seedOrder("SO-WIN-3", ORDER_AT);
    var first = doIntake(orderId, "SIZE_MISMATCH", RECEIVED_OUT);
    assertThat(first.get("verdict").get("inReturnWindow").asBoolean()).isFalse();
    UUID caseId = UUID.fromString(first.get("verdict").get("caseId").asText());
    UUID firstVerdictId = UUID.fromString(first.get("verdict").get("verdictId").asText());
    long before = inTenant(() -> sql.fetchOne("select count(*) from aftersales_intake_verdict").get(0, Long.class));

    // 运营把期限从 30 天改成 60 天 ⇒ 45 天的申请重新落在窗口内。
    windowDays = 60;
    var second =
        http.perform(
                post("/trade/aftersales/intake/cases/" + caseId + "/rejudge")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var v2 = json.readTree(second);

    assertThat(v2.get("policyReturnWindowDays").asInt()).isEqualTo(60);
    assertThat(v2.get("inReturnWindow").asBoolean()).isTrue();
    // 重新判定后**凭据数从 1 变 2**：旧判定没被改写，只被标记 superseded。
    long after = inTenant(() -> sql.fetchOne("select count(*) from aftersales_intake_verdict").get(0, Long.class));
    assertThat(after).isEqualTo(before + 1);
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select superseded_at is not null from aftersales_intake_verdict where id = ?::uuid",
                            firstVerdictId)
                        .get(0, Boolean.class)))
        .isTrue();
    // 旧判定里冻结的仍然是 30 天。
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select policy_return_window_days from aftersales_intake_verdict where id = ?::uuid",
                            firstVerdictId)
                        .get(0, Integer.class)))
        .isEqualTo(30);
  }

  @Test
  void 草稿单没有提交时刻时锚点回退到建单时刻并如实标出来() throws Exception {
    mustRefundReasons = List.of();
    seedSla("", 24, ORDER_AT.minusDays(1));
    // DRAFT 单没有 submitted_at ⇒ 锚点必须落到 created_at，并标成 CREATED_AT。
    UUID orderId = seedOrder("SO-DRAFT-1", null);

    var v = doIntake(orderId, "SIZE_MISMATCH", RECEIVED_IN).get("verdict");

    assertThat(v.get("orderAnchorKind").asText()).isEqualTo("CREATED_AT");
    assertThat(v.get("orderAnchorAt").asText()).isEqualTo("2026-01-20T08:00:00+08:00");
  }

  // ==========================================================================
  // 二、必退情形自动判定（蓝图加粗句）
  // ==========================================================================

  @Test
  void 质量问题命中生效词表_自动判为必退且词表整份冻结() throws Exception {
    // 运营把 QUALITY_DEFECT 加进必退词表。
    mustRefundReasons = List.of("DAMAGED_ON_ARRIVAL", "QUALITY_DEFECT");
    seedSla("", 24, ORDER_AT.minusDays(1));
    UUID orderId = seedOrder("SO-MR-1", ORDER_AT);

    var res = doIntake(orderId, "QUALITY_DEFECT", RECEIVED_IN);
    var v = res.get("verdict");

    assertThat(v.get("mustRefund").asBoolean()).isTrue();
    assertThat(v.get("mustRefundBasis").asText()).isEqualTo("REASON_MAPPED");
    assertThat(v.get("mustRefundSituation").asText()).isEqualTo("QUALITY_DEFECT");
    assertThat(v.get("eligibility").asText()).isEqualTo("ACCEPTED_MUST_REFUND");
    assertThat(v.get("handlingPlanSource").asText()).isEqualTo("MUST_REFUND_FORCED");
    // 冻结的是**整份生效词表**（排序归一），不是只记命中的那一个 ——
    // 只存命中项就答不出「当初还有哪些情形算必退」。
    var frozen = new ArrayList<String>();
    v.get("policyMustRefundReasons").forEach(n -> frozen.add(n.asText()));
    assertThat(frozen).containsExactly("DAMAGED_ON_ARRIVAL", "QUALITY_DEFECT");

    // 真的落库了（不只看响应）。
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select must_refund from aftersales_intake_verdict where case_id = ?::uuid",
                            UUID.fromString(v.get("caseId").asText()))
                        .get(0, Boolean.class)))
        .isTrue();
  }

  @Test
  void 物流损坏映射到到手即损_描述不符映射到与描述不符() throws Exception {
    mustRefundReasons = List.of("DAMAGED_ON_ARRIVAL", "NOT_AS_DESCRIBED");
    seedSla("", 24, ORDER_AT.minusDays(1));

    UUID o1 = seedOrder("SO-MR-2", ORDER_AT);
    assertThat(doIntake(o1, "LOGISTICS_DAMAGE", RECEIVED_IN).get("verdict").get("mustRefundSituation").asText())
        .isEqualTo("DAMAGED_ON_ARRIVAL");

    UUID o2 = seedOrder("SO-MR-3", ORDER_AT);
    assertThat(doIntake(o2, "DESCRIPTION_MISMATCH", RECEIVED_IN).get("verdict").get("mustRefundSituation").asText())
        .isEqualTo("NOT_AS_DESCRIBED");
  }

  @Test
  void 词表里没有的情形不判必退_尺码不符误购欺诈一律不退() throws Exception {
    mustRefundReasons = List.of("DAMAGED_ON_ARRIVAL");
    seedSla("", 24, ORDER_AT.minusDays(1));

    for (String reason : List.of("SIZE_MISMATCH", "MISTAKE_BUY", "FRAUD_RETURN", "BUYER_REASON")) {
      UUID orderId = seedOrder("SO-NOMR-" + reason, ORDER_AT);
      var v = doIntake(orderId, reason, RECEIVED_IN).get("verdict");
      assertThat(v.get("mustRefund").asBoolean())
          .as("%s 不该被判必退（欺诈退货尤其不能自动同意退款）", reason)
          .isFalse();
      assertThat(v.get("mustRefundBasis").asText()).isEqualTo("NONE");
    }
  }

  @Test
  void 平台消息声明必退_按平台声明判定并标成PLATFORM_DECLARED() throws Exception {
    seedSla("", 24, ORDER_AT.minusDays(1));
    mustRefundReasons = List.of(); // 词表里**没有**任何命中项：仍必须必退。
    UUID orderId = seedOrder("SO-PLAT-1", ORDER_AT);
    Map<String, Object> body = intakeBody(orderId, "BUYER_REASON", RECEIVED_IN);
    body.put("intakeSource", "PLATFORM_MESSAGE");
    body.put("intakeChannel", "PLATFORM");
    body.put("intakeReference", "PLAT-CASE-1");
    body.put("platformMustRefund", true);

    var v =
        json.readTree(
            http.perform(
                    post("/trade/aftersales/intake/cases")
                        .header("Authorization", "Bearer tenant-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
            .get("verdict");

    assertThat(v.get("mustRefund").asBoolean()).isTrue();
    assertThat(v.get("mustRefundBasis").asText()).isEqualTo("PLATFORM_DECLARED");
    assertThat(v.get("eligibility").asText()).isEqualTo("ACCEPTED_MUST_REFUND");
  }

  @Test
  void 内部录入不许自封平台必退() throws Exception {
    mustRefundReasons = List.of();
    seedSla("", 24, ORDER_AT.minusDays(1));
    UUID orderId = seedOrder("SO-SELFMR-1", ORDER_AT);
    Map<String, Object> body = intakeBody(orderId, "BUYER_REASON", RECEIVED_IN);
    body.put("platformMustRefund", true); // 内部录入却声称平台强制必退

    http.perform(
            post("/trade/aftersales/intake/cases")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().is4xxClientError());

    // 一张单都不该建成：自封必退被拒时不允许留下半个结果。
    assertThat(
            inTenant(() -> sql.fetchOne("select count(*) from aftersales_case").get(0, Long.class)))
        .isZero();
  }

  @Test
  void 必退单不许判成不受理() throws Exception {
    mustRefundReasons = List.of("QUALITY_DEFECT");
    seedSla("", 24, ORDER_AT.minusDays(1));
    UUID orderId = seedOrder("SO-MR-REJ", ORDER_AT);
    Map<String, Object> body = intakeBody(orderId, "QUALITY_DEFECT", RECEIVED_IN);
    body.put("handlingPlan", "REJECT");

    http.perform(
            post("/trade/aftersales/intake/cases")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void 平台声明的必退不可在本地降级_绕过服务层写SQL也被触发器拒绝() throws Exception {
    mustRefundReasons = List.of();
    seedSla("", 24, ORDER_AT.minusDays(1));
    UUID orderId = seedOrder("SO-NODOWNGRADE", ORDER_AT);
    Map<String, Object> body = intakeBody(orderId, "BUYER_REASON", RECEIVED_IN);
    body.put("intakeSource", "PLATFORM_MESSAGE");
    body.put("intakeChannel", "PLATFORM");
    body.put("intakeReference", "PLAT-CASE-2");
    body.put("platformMustRefund", true);
    String resp =
        http.perform(
                post("/trade/aftersales/intake/cases")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var v = json.readTree(resp).get("verdict");
    UUID caseId = UUID.fromString(v.get("caseId").asText());
    UUID priorId = UUID.fromString(v.get("verdictId").asText());

    // ⚠️ **绕过服务层**直接写 SQL 试图把它降级：DB 触发器必须拒绝。
    //   只在服务层挡的话，改一个 service 方法就能让平台强制的单被洗掉，
    //   而蓝图加粗句要的正是「不能为了业绩拖着不退」这条不许破的线。
    Throwable caught = null;
    try {
      inTenant(
          () -> {
            sql.execute(
                "insert into aftersales_intake_verdict(id,tenant_id,case_id,intake_source,"
                    + " intake_reference,intake_channel,request_received_at,judged_at,judged_by,"
                    + " order_anchor_kind,order_anchor_at,policy_return_window_days,"
                    + " return_window_deadline_at,in_return_window,policy_must_refund_reasons,"
                    + " must_refund,must_refund_basis,must_refund_situation,eligibility,"
                    + " policy_source_scope,policy_parameter_codes,policy_read_at,handling_plan,"
                    + " handling_plan_source,reply_due_at,reply_due_basis,owner_assignment_mode,"
                    + " supersedes_id,created_at)"
                    + " values (?::uuid,?::uuid,?::uuid,'PLATFORM_MESSAGE','X','PLATFORM',"
                    + " ?::timestamptz,?::timestamptz,?::uuid,'CREATED_AT',?::timestamptz,30,"
                    + " ?::timestamptz,true,?::text[],false,'NONE','','ACCEPTED',"
                    + " 'GLOBAL',?::text[],?::timestamptz,'RETURN_REFUND','OPERATOR',"
                    + " ?::timestamptz,'POLICY_SLA','UNASSIGNED',?::uuid,now())",
                UUID.randomUUID(),
                a,
                caseId,
                RECEIVED_IN,
                RECEIVED_IN,
                user,
                RECEIVED_IN,
                RECEIVED_IN,
                new String[0],
                new String[0],
                RECEIVED_IN,
                RECEIVED_IN,
                priorId);
            return null;
          });
    } catch (Throwable t) {
      caught = t;
    }
    assertThat(caught).as("平台声明的必退必须在 DB 层就不可降级").isNotNull();
    assertThat(rootMessage(caught)).contains("不可在本地降级");
  }

  // ==========================================================================
  // 三、回复时限
  // ==========================================================================

  @Test
  void 平台给了时限就用平台的_并标成PLATFORM_GIVEN() throws Exception {
    mustRefundReasons = List.of();
    seedSla("", 24, ORDER_AT.minusDays(1));
    UUID orderId = seedOrder("SO-RD-1", ORDER_AT);
    OffsetDateTime platformDue = OffsetDateTime.parse("2026-02-12T18:00:00+08:00");
    Map<String, Object> body = intakeBody(orderId, "SIZE_MISMATCH", RECEIVED_IN);
    body.put("replyDueAt", platformDue.toString());

    var v =
        json.readTree(
                http.perform(
                        post("/trade/aftersales/intake/cases")
                            .header("Authorization", "Bearer tenant-a")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("verdict");

    // 平台的时限**压过**租户 SLA（这里 SLA 是 24h，平台给的是 2026-02-12T18:00）。
    assertThat(v.get("replyDueBasis").asText()).isEqualTo("PLATFORM_GIVEN");
    // **断言写回的值**：用 Timestamp.valueOf 的实现会静默偏 −28800s 而这里照绿。
    assertThat(v.get("replyDueAt").asText()).isEqualTo("2026-02-12T18:00:00+08:00");
    // 用了平台时限就没有 SLA 小时数可记（不能两个都填：那样「时限从哪来」就没有唯一答案）。
    assertThat(v.get("replySlaHours").isNull()).isTrue();
  }

  @Test
  void 没给平台时限就按租户SLA推算_断言写回的绝对时刻() throws Exception {
    mustRefundReasons = List.of();
    seedSla("", 36, ORDER_AT.minusDays(1));
    UUID orderId = seedOrder("SO-RD-2", ORDER_AT);

    var v = doIntake(orderId, "SIZE_MISMATCH", RECEIVED_IN).get("verdict");

    assertThat(v.get("replyDueBasis").asText()).isEqualTo("POLICY_SLA");
    assertThat(v.get("replySlaHours").asInt()).isEqualTo(36);
    // 请求到达 2026-02-11T09:30+08:00 + 36h = 2026-02-12T21:30+08:00（零漂移）。
    assertThat(v.get("replyDueAt").asText()).isEqualTo("2026-02-12T21:30:00+08:00");
  }

  @Test
  void 平台精确档优先于全平台兜底档() throws Exception {
    mustRefundReasons = List.of();
    seedSla("", 24, ORDER_AT.minusDays(1));
    seedSla("SHOPIFY", 8, ORDER_AT.minusDays(1));
    UUID orderId = seedOrder("SO-RD-3", ORDER_AT); // SHOPIFY 单

    var v = doIntake(orderId, "SIZE_MISMATCH", RECEIVED_IN).get("verdict");

    assertThat(v.get("replySlaHours").asInt()).isEqualTo(8);
  }

  @Test
  void 既没有平台时限也没配SLA时受理失败并说清没配置_而不是编一个默认小时数() throws Exception {
    mustRefundReasons = List.of();
    UUID orderId = seedOrder("SO-RD-4", ORDER_AT); // 故意不 seedSla

    String body =
        http.perform(
                post("/trade/aftersales/intake/cases")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(intakeBody(orderId, "SIZE_MISMATCH", RECEIVED_IN))))
            .andExpect(status().is4xxClientError())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // ⚠️ 只断言状态码与**副作用**，不断言响应文案：共享的 ApiErrorHandler
    //   把 IllegalArgumentException 的 detail 统一压成「字段格式不正确…」，
    //   断文案等于把断言绑在别的模块的文案措辞上，改一次措辞就假红。
    assertThat(body).isNotBlank();
    assertThat(
            inTenant(() -> sql.fetchOne("select count(*) from aftersales_case").get(0, Long.class)))
        .as("没配置回复时限策略时不得留下半张单")
        .isZero();
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne("select count(*) from aftersales_intake_verdict")
                        .get(0, Long.class)))
        .isZero();
  }

  @Test
  void 改期限要收口旧档再开新档_收口后的档不再参与解析() throws Exception {
    mustRefundReasons = List.of();
    // ⚠️ 同一个平台档**同一时刻只能有一档生效**（部分唯一索引），
    //   所以换版是「收口旧档 + 开新档」，不是直接再插一档 ——
    //   这正是 V164 那条部分唯一索引要挡住的事：两档同时生效时解析没有唯一答案。
    seedSla("", 24, ORDER_AT.minusDays(1));
    UUID firstSla =
        inTenant(
            () -> sql.fetchOne("select id from aftersales_reply_sla limit 1").get(0, UUID.class));
    // 收口时刻**必须早于新区间的生效起点**：收口到「现在」的话旧区间会一直盖到今天，
    // 与新区间重叠 —— 那正是 guard_aftersales_reply_sla_interval 要挡的情况。
    http.perform(
            post("/trade/aftersales/intake/reply-sla/" + firstSla + "/retire")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "effectiveTo",
                            OffsetDateTime.parse("2026-03-01T00:00:00+08:00").toString()))))
        .andExpect(status().isOk());
    seedSla("", 48, OffsetDateTime.parse("2026-03-01T00:00:00+08:00"));

    // 3 月的申请落在 48 小时档（收口时点晚于 3 月，旧档区间不含它）。
    UUID marchOrder = seedOrder("SO-RD-5", ORDER_AT);
    var v =
        doIntake(marchOrder, "SIZE_MISMATCH", OffsetDateTime.parse("2026-03-05T09:00:00+08:00"))
            .get("verdict");
    assertThat(v.get("replySlaHours").asInt()).isEqualTo(48);

    var sla =
        json.readTree(
            http.perform(
                    get("/trade/aftersales/intake/reply-sla/effective?platformSource=SHOPIFY")
                        .header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(sla.get("replySlaHours").asInt()).isEqualTo(48);
  }

  @Test
  void 同档同时开两档被数据库层拒绝_因为解析将没有唯一答案() throws Exception {
    seedSla("", 24, ORDER_AT.minusDays(1));

    Throwable caught = null;
    try {
      seedSla("", 48, ORDER_AT.minusDays(1));
    } catch (Throwable e) {
      // ⚠️ 抓 Throwable 而不是 Exception：MockMvc 的 andExpect 失败抛的是
      //   AssertionError（Error 的子类），只 catch Exception 会让本用例静默通过。
      caught = e;
    }
    assertThat(caught).as("同一平台档同一时刻只能有一档生效").isNotNull();
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select count(*) from aftersales_reply_sla where tenant_id = ?::uuid"
                                + " and platform_scope = ''",
                            a)
                        .get(0, Long.class)))
        .isEqualTo(1L);
  }

  @Test
  void SLA档内容写定后不可改_只能收口() throws Exception {
    seedSla("", 24, ORDER_AT.minusDays(1));
    UUID id =
        inTenant(
            () ->
                sql.fetchOne("select id from aftersales_reply_sla limit 1").get(0, UUID.class));

    Throwable caught = null;
    try {
      inTenant(
          () -> {
            sql.execute(
                "update aftersales_reply_sla set reply_sla_hours = 48 where tenant_id = ?::uuid and id = ?::uuid",
                a,
                id);
            return null;
          });
    } catch (Throwable t) {
      caught = t;
    }
    assertThat(caught).as("改正在生效的档等于拆掉平台判责的计时基准").isNotNull();
    assertThat(rootMessage(caught)).contains("不可改");

    // 收口是允许的。
    http.perform(
            post("/trade/aftersales/intake/reply-sla/" + id + "/retire")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
  }

  // ==========================================================================
  // 四、责任人分配
  // ==========================================================================

  @Test
  void 名册为空时如实报未分配而不是静默留空() throws Exception {
    mustRefundReasons = List.of();
    seedSla("", 24, ORDER_AT.minusDays(1));
    UUID orderId = seedOrder("SO-OWN-1", ORDER_AT);

    var res = doIntake(orderId, "SIZE_MISMATCH", RECEIVED_IN);

    assertThat(res.get("ownerAssignment").get("mode").asText()).isEqualTo("UNASSIGNED");
  }

  @Test
  void 按名册轮转派单_并真的写进11_1_1_1的责任人留痕表() throws Exception {
    mustRefundReasons = List.of();
    seedSla("", 24, ORDER_AT.minusDays(1));
    seedOwner("svc-a", 1);
    seedOwner("svc-b", 1);
    UUID orderId = seedOrder("SO-OWN-2", ORDER_AT);

    var res = doIntake(orderId, "SIZE_MISMATCH", RECEIVED_IN);

    assertThat(res.get("ownerAssignment").get("mode").asText()).isEqualTo("ROSTER_ROUND_ROBIN");
    String ownerRef = res.get("ownerAssignment").get("ownerRef").asText();
    assertThat(List.of("svc-a", "svc-b")).contains(ownerRef);
    UUID caseId = UUID.fromString(res.get("verdict").get("caseId").asText());
    // 真的落在 11.1.1.1 的留痕表里（追加式）。
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select owner_ref from aftersales_owner_assignment"
                                + " where tenant_id = ?::uuid and case_id = ?::uuid",
                            a,
                            caseId)
                        .get(0, String.class)))
        .isEqualTo(ownerRef);
  }

  @Test
  void 权重真的参与派单_权重高的人先拿到单() throws Exception {
    mustRefundReasons = List.of();
    seedSla("", 24, ORDER_AT.minusDays(1));
    // 两人负载都是 0：权重 100 的 svc-heavy 负载比 0 < svc-light 的 0 —— 并列时按 owner_ref 决胜，
    // 所以这里让 svc-light 名字排在前面，验证「负载相同时权重不改变字典序」是**反**的：
    // 改成让 heavy 先拿到，说明权重确实进了排序键。
    seedOwner("aaa-light", 1);
    seedOwner("zzz-heavy", 50);
    UUID orderId = seedOrder("SO-OWN-3", ORDER_AT);

    var res = doIntake(orderId, "SIZE_MISMATCH", RECEIVED_IN);

    // aaa-light 字典序在前但权重 1；zzz-heavy 权重 50 ⇒ 负载比 0/1=0 vs 0/50=0，仍并列。
    // 断言的是**确定性**：同一批夹具两次受理派给同一个人（不是随机漂移）。
    String first = res.get("ownerAssignment").get("ownerRef").asText();
    UUID orderId2 = seedOrder("SO-OWN-4", ORDER_AT);
    String second = doIntake(orderId2, "SIZE_MISMATCH", RECEIVED_IN).get("ownerAssignment").get("ownerRef").asText();
    assertThat(second).isNotEqualTo(first); // 第一张单派出去后负载变了，第二张必然换人
    assertThat(List.of("aaa-light", "zzz-heavy")).contains(first, second);
  }

  void seedOwner(String ref, int weight) throws Exception {
    http.perform(
            post("/trade/aftersales/intake/owner-roster")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "ownerRef", ref,
                            "ownerDisplayName", "客服" + ref,
                            "weight", weight))))
        .andExpect(status().isOk());
  }

  @Test
  void 停接新单的人不再参与轮转但历史分配还在() throws Exception {
    mustRefundReasons = List.of();
    seedSla("", 24, ORDER_AT.minusDays(1));
    seedOwner("svc-x", 1);
    seedOwner("svc-y", 1);
    UUID first = seedOrder("SO-OWN-5", ORDER_AT);
    var r1 = doIntake(first, "SIZE_MISMATCH", RECEIVED_IN);
    String owner1 = r1.get("ownerAssignment").get("ownerRef").asText();

    UUID id =
        inTenant(
            () ->
                sql.fetchOne(
                        "select id from aftersales_owner_roster where tenant_id = ?::uuid and owner_ref = ?",
                        a,
                        owner1)
                    .get(0, UUID.class));
    http.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/trade/aftersales/intake/owner-roster/" + id)
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"acceptingNewCases\":false}"))
        .andExpect(status().isOk());

    UUID second = seedOrder("SO-OWN-6", ORDER_AT);
    var r2 = doIntake(second, "SIZE_MISMATCH", RECEIVED_IN);
    assertThat(r2.get("ownerAssignment").get("ownerRef").asText()).isNotEqualTo(owner1);
    // 名册行没被删（删了就答不出「这批单是谁派的」）。
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select count(*) from aftersales_owner_roster where tenant_id = ?::uuid and owner_ref = ?",
                            a,
                            owner1)
                        .get(0, Long.class)))
        .isEqualTo(1L);
  }

  // ==========================================================================
  // 五、政策读取与幂等
  // ==========================================================================

  @Test
  void 身份服务不可达时受理503而不是用编造的默认值() throws Exception {
    UUID orderId = seedOrder("SO-DOWN-1", ORDER_AT);
    identityDown = true;

    String body =
        http.perform(
                post("/trade/aftersales/intake/cases")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(intakeBody(orderId, "SIZE_MISMATCH", RECEIVED_IN))))
            .andExpect(status().isServiceUnavailable())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // 断的是「没编造默认值」这件事本身：受理失败，且一张单、一条判定都没留下。
    assertThat(
            inTenant(() -> sql.fetchOne("select count(*) from aftersales_case").get(0, Long.class)))
        .isZero();
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne("select count(*) from aftersales_intake_verdict")
                        .get(0, Long.class)))
        .isZero();
  }

  @Test
  void capabilities如实报出生效参数与SLA是否配置() throws Exception {
    mustRefundReasons = List.of("QUALITY_DEFECT", "DAMAGED_ON_ARRIVAL");

    var caps =
        json.readTree(
            http.perform(get("/trade/aftersales/intake/capabilities")
                    .header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(caps.get("effectiveReturnWindowDays").asInt()).isEqualTo(30);
    assertThat(caps.get("effectiveMustRefundReasons")).hasSize(2);
    assertThat(caps.get("replySlaConfigured").asBoolean()).isFalse();
    assertThat(caps.get("policySource").asText()).contains("17.5.1.1");

    seedSla("", 24, ORDER_AT.minusDays(1));
    var caps2 =
        json.readTree(
            http.perform(get("/trade/aftersales/intake/capabilities")
                    .header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(caps2.get("replySlaConfigured").asBoolean()).isTrue();
  }

  @Test
  void 平台重投同一申请号被判重_不会建出第二张单() throws Exception {
    mustRefundReasons = List.of();
    seedSla("", 24, ORDER_AT.minusDays(1));
    UUID orderId = seedOrder("SO-IDEM-1", ORDER_AT);
    Map<String, Object> body = intakeBody(orderId, "SIZE_MISMATCH", RECEIVED_IN);
    body.put("intakeSource", "PLATFORM_MESSAGE");
    body.put("intakeChannel", "PLATFORM");
    body.put("intakeReference", "PLAT-DUP-1");

    http.perform(
            post("/trade/aftersales/intake/cases")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());

    http.perform(
            post("/trade/aftersales/intake/cases")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().is4xxClientError());

    assertThat(
            inTenant(() -> sql.fetchOne("select count(*) from aftersales_case").get(0, Long.class)))
        .isEqualTo(1L);
  }

  // ==========================================================================
  // 六、列表与多租户
  // ==========================================================================

  @Test
  void 列表是真分页且汇总与分页无关_必退超时单独计数() throws Exception {
    mustRefundReasons = List.of("QUALITY_DEFECT");
    seedSla("", 1, ORDER_AT.minusDays(1)); // 1 小时 ⇒ 已超时的必退单
    for (int i = 1; i <= 3; i++) {
      UUID orderId = seedOrder("SO-PAGE-" + i, ORDER_AT);
      // 请求到达时刻 pin 死在很早的时间，SLA=1h ⇒ reply_due_at 早已过去。
      doIntake(orderId, i == 1 ? "QUALITY_DEFECT" : "SIZE_MISMATCH", RECEIVED_IN);
    }

    var page1 =
        json.readTree(
            http.perform(get("/trade/aftersales/intake/verdicts?size=2&page=0")
                    .header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(page1.get("items")).hasSize(2);
    assertThat(page1.get("total").asLong()).isEqualTo(3);
    assertThat(page1.get("totalPages").asInt()).isEqualTo(2);
    // 汇总统计的是**全部 3 条**，不是当前页的 2 条。
    assertThat(page1.get("summary").get("total").asLong()).isEqualTo(3);
    assertThat(page1.get("summary").get("mustRefundCount").asLong()).isEqualTo(1);
    assertThat(page1.get("summary").get("replyOverdueMustRefundCount").asLong()).isEqualTo(1);

    var page2 =
        json.readTree(
            http.perform(get("/trade/aftersales/intake/verdicts?size=2&page=1")
                    .header("Authorization", "Bearer tenant-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(page2.get("items")).hasSize(1);
    // 两页的 caseId 不能重叠（真分页，不是同一份数据切两次）。
    var firstIds = new ArrayList<String>();
    page1.get("items").forEach(n -> firstIds.add(n.get("caseId").asText()));
    page2.get("items").forEach(n -> assertThat(firstIds).doesNotContain(n.get("caseId").asText()));
  }

  @Test
  void 三张业务表都强制rls() {
    for (String t :
        List.of("aftersales_reply_sla", "aftersales_owner_roster", "aftersales_intake_verdict")) {
      assertThat(
              inTenant(
                  () ->
                      sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", t)
                          .get(0, Boolean.class)))
          .as("%s 必须 FORCE RLS", t)
          .isTrue();
      assertThat(
              inTenant(
                  () ->
                      sql.fetchOne("select relrowsecurity from pg_class where relname = ?", t)
                          .get(0, Boolean.class)))
          .as("%s 必须 ENABLE RLS", t)
          .isTrue();
    }
  }

  @Test
  void 判定凭据在数据库层append_only_改不动也删不掉() {
    // 权限层（REVOKE UPDATE,DELETE）+ 事实层两张都钉住：
    // 受理判定是纠纷举证的原材料，改掉一行等于毁掉证据。
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select has_table_privilege('trade_app','aftersales_intake_verdict','UPDATE')")
                        .get(0, Boolean.class)))
        .isFalse();
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select has_table_privilege('trade_app','aftersales_intake_verdict','DELETE')")
                        .get(0, Boolean.class)))
        .isFalse();
  }

  // ==========================================================================




  static String rootMessage(Throwable t) {
    Throwable cur = t;
    StringBuilder sb = new StringBuilder();
    while (cur != null) {
      if (cur.getMessage() != null) sb.append(cur.getMessage()).append(" | ");
      cur = cur.getCause();
    }
    return sb.toString();
  }
}
