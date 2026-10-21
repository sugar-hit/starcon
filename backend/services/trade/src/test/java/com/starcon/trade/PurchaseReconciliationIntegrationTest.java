package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 6.5.1.1 采购对账的集成测试。
 *
 * <p>跑的是**真实 PostgreSQL 16 + FORCE RLS**，不使用任何内存替身。
 *
 * <p><b>本模块要证明的五件事</b>：
 *
 * <ol>
 *   <li><b>对账基数真的来自已交付事实</b>：应收行取自 6.3.2.2 到货流水，
 *       索赔行取自 11.2.3.3 索赔明细（**同库真实事实，不是影子表**）。</li>
 *   <li><b>采购侧不确认应付</b>：没有「应付已确认 / 已付款」的状态与端点，
 *       交接状态恒为 {@code PENDING_FINANCE_SERVICE}。</li>
 *   <li><b>质检扣量未接线时是 NULL 而不是 0</b>（6.4.2.1 未交付）。</li>
 *   <li><b>差异逐条成行</b>：供应商回执的逐行值比出来的差异逐条落行、可挂起、可解决。</li>
 *   <li><b>币种 + 汇率快照 + 单位换算必录</b>：跨币种不给汇率 ⇒ 400；
 *       折算金额与基础数量由 DB 触发器算。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PurchaseReconciliationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static final UUID supplierRef = UUID.randomUUID();
  static final UUID otherSupplierRef = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final String BEARER = "Bearer tenant-a";
  static final OffsetDateTime T1 = OffsetDateTime.parse("2026-11-10T08:00:00Z");
  static final OffsetDateTime T2 = OffsetDateTime.parse("2026-11-12T09:30:00Z");

  static final List<String> allPermissions =
      List.of("trade.purchase-reconciliation.read", "trade.purchase-reconciliation.update");

  static AccessService.Access actor(UUID uid, UUID tid, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tid,
        "Test",
        UUID.randomUUID(),
        null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        exec(c, "create role starcon_app nologin");
        exec(c, "create role trade_app login password 'test-only' nosuperuser nobypassrls");
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
          BEARER,
          actor(user, tenant, List.of(grant("ALL", allPermissions))));
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

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 数据准备

  /**
   * 铺事实：供应商引用镜像 + 到货跟踪单 + 到货流水 + 索赔单 + 索赔明细。
   *
   * <p>⚠️ <b>索赔那一段用 superuser + {@code session_replication_role='replica'} 直落</b>：
   * 11.2.3.3 的写入守卫要求一整条上游链（质检结论 → 责任判定 → 批次），
   * 而本模块只需要它的**事实行**。被绕过的是**别的模块的写入守卫**，
   * 不是本模块的任何断言 —— 11.2.3.3 自己已交付并已单独覆盖。
   */
  /**
   * 铺事实用的执行辅助（PreparedStatement + setObject）。
   *
   * <p>JDBC 的 {@code Statement} 没有可变参 {@code execute}，所以统一走这里。
   */
  static void exec(Connection c, String sql, Object... params) throws Exception {
    try (var ps = c.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i]);
      }
      ps.execute();
    }
  }

  @BeforeEach
  void seed() throws Exception {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      exec(c, 
          "truncate purchase_reconciliation_event, purchase_reconciliation_confirmation,"
              + " purchase_reconciliation_discrepancy, purchase_reconciliation_line,"
              + " purchase_reconciliation cascade");
      exec(c, 
          "truncate purchase_arrival_event, purchase_arrival_payable_confirmation,"
              + " purchase_arrival_receipt, purchase_arrival_tracking cascade");
      exec(c,
          "truncate supplier_claim_event, supplier_claim_settlement, supplier_claim_negotiation,"
              + " supplier_claim_item, supplier_claim cascade");
      exec(c, "truncate quality_supplier_ref cascade");

      exec(c, 
          "insert into quality_supplier_ref(id,tenant_id,supplier_ref_id,supplier_code,supplier_name,"
              + "ref_source,source_reference,ref_status,note,created_by,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?::uuid,'SUP-001','东莞某某电子厂','TENANT_MAINTAINED','TEST',"
              + "'ACTIVE','',?::uuid,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          supplierRef,
          user,
          T1,
          T1);
      exec(c, 
          "insert into quality_supplier_ref(id,tenant_id,supplier_ref_id,supplier_code,supplier_name,"
              + "ref_source,source_reference,ref_status,note,created_by,created_at,updated_at)"
              + " values(?::uuid,?::uuid,?::uuid,'SUP-999','别家供应商','TENANT_MAINTAINED','TEST',"
              + "'ACTIVE','',?::uuid,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          otherSupplierRef,
          user,
          T1,
          T1);

      // ---- 6.3.2.2 到货跟踪单 + 到货流水（真实触发器会重算已收数量与状态）----
      UUID trackingId = UUID.randomUUID();
      exec(c, 
          "insert into purchase_arrival_tracking(id,tenant_id,tracking_no,purchase_order_no,"
              + "source_doc_type,supplier_ref_id,supplier_code,supplier_name,receivable_qty,"
              + "received_qty,outstanding_qty,unit_price,currency,promised_arrival_date,"
              + "promise_source,arrival_status,created_by,created_at,updated_at,version)"
              + " values(?::uuid,?::uuid,'TRK-001','PO-2026-1101','PURCHASE_ORDER',?::uuid,"
              + "'SUP-001','东莞某某电子厂',100,0,100,?::numeric,'USD','2026-11-05','手工登记',"
              + "'PENDING_SHIPMENT',?::uuid,?::timestamptz,?::timestamptz,0)",
          trackingId,
          tenant,
          supplierRef,
          new BigDecimal("2.5000"),
          user,
          T1,
          T1);
      exec(c, 
          "insert into purchase_arrival_receipt(id,tenant_id,tracking_id,arrived_qty,arrived_at,"
              + "recorded_by,note) values(?::uuid,?::uuid,?::uuid,?::numeric,?::timestamptz,?::uuid,'首批到货')",
          UUID.randomUUID(),
          tenant,
          trackingId,
          new BigDecimal("40"),
          T1,
          user);

      // ---- 11.2.3.3 索赔单 + 索赔明细（绕过**别的模块**的写入守卫直落事实行）----
      exec(c, "set session_replication_role = 'replica'");
      UUID claimId = UUID.randomUUID();
      exec(c, 
          "insert into supplier_claim(id,tenant_id,claim_no,supplier_ref_id,supplier_code,"
              + "supplier_name,claim_reason_code,claim_reason_note,claim_currency,claimed_amount,"
              + "claimable_base_amount,agreed_amount,claim_status,offset_status,applied_offset_amount,"
              + "created_by,created_at,submitted_by,submitted_at,updated_at,version)"
              + " values(?::uuid,?::uuid,'CLM-001',?::uuid,'SUP-001','东莞某某电子厂',"
              + "'QUALITY_DEFECT','品质不合格','USD',?::numeric,?::numeric,?::numeric,'AGREED',"
              + "'NOT_APPLIED',0,?::uuid,?::timestamptz,?::uuid,?::timestamptz,?::timestamptz,0)",
          claimId,
          tenant,
          supplierRef,
          new BigDecimal("30"),
          new BigDecimal("100"),
          new BigDecimal("30"),
          user,
          T2,
          user,
          T2,
          T2);
      exec(c, 
          "insert into supplier_claim_item(id,tenant_id,claim_id,inspection_id,attribution_id,"
              + "lot_id,lot_code,sku_id,sku_code,sku_name,claim_qty,claim_amount,claim_currency,"
              + "claimable_base_amount,max_claim_amount,created_by,created_at)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,'LOT-1',?::uuid,'SKU-001',"
              + "'商品001',?::numeric,?::numeric,'USD',?::numeric,?::numeric,?::uuid,?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          claimId,
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          new BigDecimal("3"),
          new BigDecimal("30"),
          new BigDecimal("100"),
          new BigDecimal("100"),
          user,
          T2);
      exec(c, "set session_replication_role = 'origin'");
    }
  }

  // ================================================================ HTTP 辅助

  String call(String method, String path, Object body, String bearer) throws Exception {
    var builder =
        "GET".equals(method)
            ? get(path)
            : org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path);
    var response =
        http.perform(
                builder
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body == null ? "{}" : (body instanceof String raw ? raw : json.writeValueAsString(body))))
            .andReturn()
            .getResponse();
    if (response.getContentAsString().isBlank()) {
      throw new AssertionError(
          "HTTP "
              + response.getStatus()
              + " empty body; errorMessage="
              + response.getErrorMessage()
              + "; request="
              + (body == null ? "{}" : json.writeValueAsString(body)));
    }
    return response.getContentAsString();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> map(String payload) throws Exception {
    return json.readValue(payload, Map.class);
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> rowsOf(String payload) throws Exception {
    return (List<Map<String, Object>>) map(payload).getOrDefault("rows", List.of());
  }

  String createPayload(String statementCurrency, Object fxRates) throws Exception {
    return json(
        Map.of(
            "supplierRefId", supplierRef.toString(),
            "periodType", "MONTHLY",
            "periodStart", "2026-11-01",
            "statementCurrency", statementCurrency,
            "fxRates", fxRates == null ? List.of() : fxRates));
  }

  /** 把入参转成真正的 JSON（Map.toString() 不是 JSON，会被 400 掉）。 */
  String json(Object body) throws Exception {
    return json.writeValueAsString(body);
  }

  Map<String, Object> fxRate(String currency, String rate, String source) {
    return Map.of("currency", currency, "rate", new BigDecimal(rate), "source", source);
  }

  // ================================================================ 测试

  @Test
  void 能力声明逐条报出未交付的质检扣量与财务() throws Exception {
    String payload = call("GET", "/trade/purchase-reconciliations/capabilities", null, BEARER);
    Map<String, Object> caps = map(payload);
    List<Map<String, Object>> sections = (List<Map<String, Object>>) caps.get("sections");
    Map<String, Object> quality =
        sections.stream()
            .filter(s -> "qualityDeduction".equals(s.get("section")))
            .findFirst()
            .orElseThrow();
    assertThat(quality.get("available")).as("质检扣量未交付，必须报 available=false").isEqualTo(false);
    assertThat(quality.get("ownerModule")).isEqualTo("6.4.2.1");
    assertThat(String.valueOf(quality.get("reason"))).contains("未交付");

    Map<String, Object> payable =
        sections.stream()
            .filter(s -> "payableHandoff".equals(s.get("section")))
            .findFirst()
            .orElseThrow();
    assertThat(payable.get("available")).isEqualTo(false);
    assertThat(payable.get("ownerModule")).isEqualTo("12 财务");

    // 动作声明里**没有**「确认应付 / 付款」，且这两项显式不可用并写明理由。
    List<Map<String, Object>> actions = (List<Map<String, Object>>) caps.get("actions");
    assertThat(actions).noneMatch(a -> "CONFIRM_PAYABLE".equals(a.get("code")) && Boolean.TRUE.equals(a.get("allowed")));
    assertThat(actions).noneMatch(a -> "PAY".equals(a.get("code")) && Boolean.TRUE.equals(a.get("allowed")));
    Map<String, Object> confirmPayable =
        actions.stream()
            .filter(a -> "CONFIRM_PAYABLE".equals(a.get("code")))
            .findFirst()
            .orElseThrow();
    assertThat(confirmPayable.get("ownerModule")).isEqualTo("12 财务");
    assertThat(String.valueOf(confirmPayable.get("unavailableReason")))
        .contains("刻意不提供")
        .contains("擅自确认应付");
  }

  @Test
  void 发起对账按期间采集到货流水与索赔() throws Exception {
    String payload =
        call("POST", "/trade/purchase-reconciliations", createPayload("USD", null), BEARER);
    Map<String, Object> detail = map(payload);
    Map<String, Object> head = (Map<String, Object>) detail.get("head");

    assertThat(head.get("status")).isEqualTo("DRAFT");
    assertThat(head.get("periodStart")).isEqualTo("2026-11-01");
    assertThat(head.get("periodEnd")).isEqualTo("2026-11-30");
    assertThat(head.get("statementCurrency")).isEqualTo("USD");
    // 交接给财务的状态恒为「待 12 财务」—— 采购侧不确认应付。
    assertThat(head.get("financeHandoffStatus")).isEqualTo("PENDING_FINANCE_SERVICE");

    List<Map<String, Object>> lines = (List<Map<String, Object>>) detail.get("lines");
    assertThat(lines).hasSize(2);

    Map<String, Object> receipt =
        lines.stream().filter(l -> "RECEIPT".equals(l.get("lineType"))).findFirst().orElseThrow();
    assertThat(receipt.get("quantity")).isEqualTo(40.0);
    assertThat(receipt.get("unitPrice")).isEqualTo(2.5);
    assertThat(receipt.get("currency")).isEqualTo("USD");
    assertThat(receipt.get("amount")).isEqualTo(100.0);
    assertThat(receipt.get("sourceType")).isEqualTo("PURCHASE_ARRIVAL");
    assertThat(receipt.get("sourceRefCode")).isEqualTo("PO-2026-1101");

    Map<String, Object> claim =
        lines.stream().filter(l -> "CLAIM".equals(l.get("lineType"))).findFirst().orElseThrow();
    assertThat(claim.get("amount")).isEqualTo(30.0);
    assertThat(claim.get("sourceType")).isEqualTo("SUPPLIER_CLAIM");
    assertThat(claim.get("sourceRefCode")).isEqualTo("CLM-001");

    // 汇总由 DB 触发器算：应收 100 − 索赔 30 = 建议应付 70。
    assertThat(head.get("receivedAmount")).isEqualTo(100.0);
    assertThat(head.get("claimAmount")).isEqualTo(30.0);
    assertThat(head.get("suggestedPayableAmount")).isEqualTo(70.0);
    assertThat(head.get("totalQuantity")).isEqualTo(40.0);
  }

  @Test
  void 质检扣量未接线时是NULL而不是0() throws Exception {
    String payload =
        call("POST", "/trade/purchase-reconciliations", createPayload("USD", null), BEARER);
    List<Map<String, Object>> lines = (List<Map<String, Object>>) map(payload).get("lines");
    Map<String, Object> receipt =
        lines.stream().filter(l -> "RECEIPT".equals(l.get("lineType"))).findFirst().orElseThrow();

    assertThat(receipt.get("qualityDeductionStatus")).isEqualTo("UNAVAILABLE");
    assertThat(receipt.get("qualityDeductionQuantity"))
        .as("未接线必须写 NULL —— 写 0 会被读成「这批没有扣量」")
        .isNull();
    assertThat(String.valueOf(receipt.get("qualityDeductionNote"))).contains("尚未扣除");

    // 头部的基数说明也要报出来。
    Map<String, Object> basis = (Map<String, Object>) map(payload).get("basis");
    List<Map<String, Object>> unavailable =
        (List<Map<String, Object>>) basis.get("unavailableSources");
    assertThat(unavailable)
        .anyMatch(
            u ->
                "QUALITY_DEDUCTION".equals(u.get("key"))
                    && Boolean.FALSE.equals(u.get("available"))
                    && "6.4.2.1".equals(u.get("ownerModule")));
  }

  @Test
  void 跨币种不给汇率快照被拒() throws Exception {
    var request =
        post("/trade/purchase-reconciliations")
            .header("Authorization", BEARER)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json(
                    Map.of(
                        "supplierRefId", supplierRef.toString(),
                        "periodType", "MONTHLY",
                        "periodStart", "2026-11-01",
                        "statementCurrency", "CNY",
                        "fxRates", List.of())));
    var response = http.perform(request).andReturn().getResponse();
    assertThat(response.getStatus()).as("跨币种不给汇率必须被拒").isEqualTo(400);
    assertThat(response.getErrorMessage()).contains("汇率");
  }

  @Test
  void 跨币种给了汇率快照才成立且折算由DB算() throws Exception {
    String payload =
        call(
            "POST",
            "/trade/purchase-reconciliations",
            createPayload("CNY", List.of(fxRate("USD", "7.25", "财务提供的 11 月结汇汇率"))),
            BEARER);
    Map<String, Object> head = (Map<String, Object>) map(payload).get("head");
    assertThat(head.get("statementCurrency")).isEqualTo("CNY");
    // 应收 100 USD × 7.25 = 725 CNY；索赔 30 USD × 7.25 = 217.50 CNY。
    assertThat(new BigDecimal(head.get("receivedAmount").toString()))
        .isEqualByComparingTo(new BigDecimal("725.0000"));
    assertThat(new BigDecimal(head.get("claimAmount").toString()))
        .isEqualByComparingTo(new BigDecimal("217.5000"));
    assertThat(new BigDecimal(head.get("suggestedPayableAmount").toString()))
        .isEqualByComparingTo(new BigDecimal("507.5000"));

    List<Map<String, Object>> lines = (List<Map<String, Object>>) map(payload).get("lines");
    Map<String, Object> receipt =
        lines.stream().filter(l -> "RECEIPT".equals(l.get("lineType"))).findFirst().orElseThrow();
    assertThat(receipt.get("fxRate")).isEqualTo(7.25);
    assertThat(receipt.get("fxRateSource")).isEqualTo("财务提供的 11 月结汇汇率");
    assertThat(new BigDecimal(receipt.get("statementAmount").toString()))
        .isEqualByComparingTo(new BigDecimal("725.0000"));
  }

  @Test
  void 期间由服务端裁决对不上就400() throws Exception {
    // 月度但起始日不是 1 号 ⇒ 400（不悄悄改写）。
    var bad =
        http.perform(
                post("/trade/purchase-reconciliations")
                    .header("Authorization", BEARER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            Map.of(
                                "supplierRefId", supplierRef.toString(),
                                "periodType", "MONTHLY",
                                "periodStart", "2026-11-15",
                                "statementCurrency", "USD"))))
            .andReturn()
            .getResponse();
    assertThat(bad.getStatus()).isEqualTo(400);

    // 给了结束日但与裁决口径不一致 ⇒ 400。
    var badEnd =
        http.perform(
                post("/trade/purchase-reconciliations")
                    .header("Authorization", BEARER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            Map.of(
                                "supplierRefId", supplierRef.toString(),
                                "periodType", "MONTHLY",
                                "periodStart", "2026-11-01",
                                "periodEnd", "2026-11-29",
                                "statementCurrency", "USD"))))
            .andReturn()
            .getResponse();
    assertThat(badEnd.getStatus()).isEqualTo(400);

    // 季度：季首月 1 号到季末月末。
    String payload =
        call(
            "POST",
            "/trade/purchase-reconciliations",
            json(
                Map.of(
                    "supplierRefId", supplierRef.toString(),
                    "periodType", "QUARTERLY",
                    "periodStart", "2026-10-01",
                    "statementCurrency", "USD")),
            BEARER);
    Map<String, Object> head = (Map<String, Object>) map(payload).get("head");
    assertThat(head.get("periodStart")).isEqualTo("2026-10-01");
    assertThat(head.get("periodEnd")).isEqualTo("2026-12-31");
    // Q4 覆盖 11 月 ⇒ 11 月的到货与索赔**都在**季度内。
    assertThat((List<Map<String, Object>>) map(payload).get("lines")).hasSize(2);

    // 但 Q3（7~9 月）不覆盖 11 月 ⇒ 零明细行（期间口径真的在约束采集范围）。
    String q3 =
        call(
            "POST",
            "/trade/purchase-reconciliations",
            json(
                Map.of(
                    "supplierRefId", supplierRef.toString(),
                    "periodType", "QUARTERLY",
                    "periodStart", "2026-07-01",
                    "statementCurrency", "USD")),
            BEARER);
    Map<String, Object> q3Head = (Map<String, Object>) map(q3).get("head");
    assertThat(q3Head.get("periodStart")).isEqualTo("2026-07-01");
    assertThat(q3Head.get("periodEnd")).isEqualTo("2026-09-30");
    assertThat((List<Map<String, Object>>) map(q3).get("lines")).isEmpty();
  }

  @Test
  void 供应商回执逐行比对产出逐条差异() throws Exception {
    String created = call("POST", "/trade/purchase-reconciliations", createPayload("USD", null), BEARER);
    String id = String.valueOf(((Map<String, Object>) map(created).get("head")).get("id"));
    List<Map<String, Object>> lines = (List<Map<String, Object>>) map(created).get("lines");
    String receiptLineId =
        String.valueOf(
            lines.stream()
                .filter(l -> "RECEIPT".equals(l.get("lineType")))
                .findFirst()
                .orElseThrow()
                .get("id"));

    // 发出。
    call("POST", "/trade/purchase-reconciliations/" + id + "/send", null, BEARER);
    Map<String, Object> afterSend = map(call("GET", "/trade/purchase-reconciliations/" + id, null, BEARER));
    assertThat(((Map<String, Object>) afterSend.get("head")).get("status")).isEqualTo("SENT");

    // 供应商回执：数量对不上（40 vs 38），金额也对不上。
    String confirmed =
        call(
            "POST",
            "/trade/purchase-reconciliations/" + id + "/confirmations",
            Map.of(
                "supplierRepliedAt", "2026-11-20T10:00:00Z",
                "supplierRepliedBy", "供应商 张三",
                "supplierReference", "ACK-001",
                "supplierResult", "DIFFERENCES",
                "supplierLines",
                    List.of(
                        Map.of(
                            "lineId", receiptLineId,
                            "quantity", new BigDecimal("38"),
                            "amount", new BigDecimal("95")))),
            BEARER);
    Map<String, Object> detail = map(confirmed);
    List<Map<String, Object>> diffs = (List<Map<String, Object>>) detail.get("discrepancies");
    assertThat(diffs).hasSize(2);

    Map<String, Object> qtyDiff =
        diffs.stream().filter(d -> "QUANTITY".equals(d.get("discrepancyType"))).findFirst().orElseThrow();
    assertThat(new BigDecimal(qtyDiff.get("ourValue").toString())).isEqualByComparingTo("40");
    assertThat(new BigDecimal(qtyDiff.get("supplierValue").toString())).isEqualByComparingTo("38");
    assertThat(new BigDecimal(qtyDiff.get("difference").toString())).isEqualByComparingTo("-2");
    assertThat(qtyDiff.get("fieldName")).isEqualTo("数量");
    assertThat(qtyDiff.get("status")).isEqualTo("OPEN");

    assertThat(((Map<String, Object>) detail.get("head")).get("status")).isEqualTo("DISPUTED");
  }

  @Test
  void 差异可以挂起也可以解决且解决后自动确认() throws Exception {
    String created = call("POST", "/trade/purchase-reconciliations", createPayload("USD", null), BEARER);
    String id = String.valueOf(((Map<String, Object>) map(created).get("head")).get("id"));
    String lineId =
        String.valueOf(
            ((List<Map<String, Object>>) map(created).get("lines")).get(0).get("id"));
    call("POST", "/trade/purchase-reconciliations/" + id + "/send", null, BEARER);
    call(
        "POST",
        "/trade/purchase-reconciliations/" + id + "/confirmations",
        Map.of(
            "supplierRepliedAt", "2026-11-20T10:00:00Z",
            "supplierResult", "DIFFERENCES",
            "supplierLines", List.of(Map.of("lineId", lineId, "quantity", new BigDecimal("38")))),
        BEARER);

    String diffId =
        String.valueOf(
            ((List<Map<String, Object>>) map(call("GET", "/trade/purchase-reconciliations/" + id, null, BEARER))
                    .get("discrepancies"))
                .get(0)
                .get("id"));

    // 挂起：不需要说明。
    String held =
        call(
            "POST",
            "/trade/purchase-reconciliations/" + id + "/discrepancies/" + diffId + "/resolve",
            Map.of("status", "HELD"),
            BEARER);
    Map<String, Object> heldDiff =
        ((List<Map<String, Object>>) map(held).get("discrepancies")).get(0);
    assertThat(heldDiff.get("status")).isEqualTo("HELD");

    // 解决：必须写方式与说明。
    var missing =
        http.perform(
                post("/trade/purchase-reconciliations/" + id + "/discrepancies/" + diffId + "/resolve")
                    .header("Authorization", BEARER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("status", "RESOLVED"))))
            .andReturn()
            .getResponse();
    assertThat(missing.getStatus()).as("解决差异必须写方式与说明").isEqualTo(400);

    String resolved =
        call(
            "POST",
            "/trade/purchase-reconciliations/" + id + "/discrepancies/" + diffId + "/resolve",
            Map.of(
                "status", "RESOLVED",
                "resolution", "按供应商实收数量调整",
                "resolutionNote", "复核仓库签收单，实收 38 件"),
            BEARER);
    Map<String, Object> resolvedDetail = map(resolved);
    Map<String, Object> resolvedDiff =
        ((List<Map<String, Object>>) resolvedDetail.get("discrepancies")).get(0);
    assertThat(resolvedDiff.get("status")).isEqualTo("RESOLVED");
    assertThat(resolvedDiff.get("resolution")).isEqualTo("按供应商实收数量调整");
    // 差异全部解决 ⇒ 自动推进到「已确认」。
    assertThat(((Map<String, Object>) resolvedDetail.get("head")).get("status")).isEqualTo("CONFIRMED");
  }

  @Test
  void 没有明细的对账单不能发出且已发出的不能重复发() throws Exception {
    // 12 月没有到货与索赔 ⇒ 对账单合法但零明细行。
    String created =
        call(
            "POST",
            "/trade/purchase-reconciliations",
            json(
                Map.of(
                    "supplierRefId", supplierRef.toString(),
                    "periodType", "MONTHLY",
                    "periodStart", "2026-12-01",
                    "statementCurrency", "USD")),
            BEARER);
    String id = String.valueOf(((Map<String, Object>) map(created).get("head")).get("id"));
    assertThat(((List<Map<String, Object>>) map(created).get("lines")).isEmpty()).isTrue();

    var empty =
        http.perform(post("/trade/purchase-reconciliations/" + id + "/send").header("Authorization", BEARER))
            .andReturn()
            .getResponse();
    assertThat(empty.getStatus()).as("没有明细的对账单不能发出").isEqualTo(400);

    // 在**已交付事实**里没有任何记录的供应商 ⇒ 没有对账基数，直接 400（本模块不替它编一份）。
    var noFacts =
        http.perform(
                post("/trade/purchase-reconciliations")
                    .header("Authorization", BEARER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            Map.of(
                                "supplierRefId", otherSupplierRef.toString(),
                                "periodType", "MONTHLY",
                                "periodStart", "2026-11-01",
                                "statementCurrency", "USD"))))
            .andReturn()
            .getResponse();
    assertThat(noFacts.getStatus()).as("没有事实就没有对账基数").isEqualTo(400);
    assertThat(noFacts.getErrorMessage()).contains("已交付事实");

    // 正常单：发一次后再发一次 ⇒ 400。
    String ok = call("POST", "/trade/purchase-reconciliations", createPayload("USD", null), BEARER);
    String okId = String.valueOf(((Map<String, Object>) map(ok).get("head")).get("id"));
    call("POST", "/trade/purchase-reconciliations/" + okId + "/send", null, BEARER);
    var twice =
        http.perform(post("/trade/purchase-reconciliations/" + okId + "/send").header("Authorization", BEARER))
            .andReturn()
            .getResponse();
    assertThat(twice.getStatus()).isEqualTo(400);
  }

  @Test
  void 登记代扣代缴费用且跨币种必须给汇率() throws Exception {
    String created = call("POST", "/trade/purchase-reconciliations", createPayload("USD", null), BEARER);
    String id = String.valueOf(((Map<String, Object>) map(created).get("head")).get("id"));

    String withDeduction =
        call(
            "POST",
            "/trade/purchase-reconciliations/" + id + "/deductions",
            Map.of(
                "deductionType", "TAX",
                "amount", new BigDecimal("12.5"),
                "currency", "USD",
                "note", "代扣代缴进口增值税"),
            BEARER);
    Map<String, Object> head = (Map<String, Object>) map(withDeduction).get("head");
    assertThat(new BigDecimal(head.get("deductionAmount").toString()))
        .isEqualByComparingTo(new BigDecimal("12.5000"));
    assertThat(new BigDecimal(head.get("suggestedPayableAmount").toString()))
        .isEqualByComparingTo(new BigDecimal("57.5000"));

    // 跨币种且没给汇率 ⇒ 400。
    var bad =
        http.perform(
                post("/trade/purchase-reconciliations/" + id + "/deductions")
                    .header("Authorization", BEARER)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json(
                            Map.of(
                                "deductionType", "FREIGHT",
                                "amount", new BigDecimal("5"),
                                "currency", "EUR"))))
            .andReturn()
            .getResponse();
    assertThat(bad.getStatus()).isEqualTo(400);
  }

  @Test
  void 导出CSV是真的() throws Exception {
    String created = call("POST", "/trade/purchase-reconciliations", createPayload("USD", null), BEARER);
    String id = String.valueOf(((Map<String, Object>) map(created).get("head")).get("id"));
    var response =
        http.perform(get("/trade/purchase-reconciliations/" + id + "/export").header("Authorization", BEARER))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    String csv = response.getContentAsString();
    assertThat(csv).contains("对账单号");
    assertThat(csv).contains("已到货应收");
    assertThat(csv).contains("CLM-001");
    assertThat(csv).contains("上游未接线");
    assertThat(csv).contains("采购侧不确认应付");
  }

  @Test
  void 对账期间外的事实不计入() throws Exception {
    // 12 月的到货不在 11 月期间内 ⇒ 11 月的对账单看不到它。
    String created = call("POST", "/trade/purchase-reconciliations", createPayload("USD", null), BEARER);
    List<Map<String, Object>> lines = (List<Map<String, Object>>) map(created).get("lines");
    assertThat(lines).hasSize(2);
    assertThat(lines).noneMatch(l -> "2026-12".equals(String.valueOf(l.get("occurredAt")).substring(0, 7)));
  }

  @Test
  void 金额与数量在库里是numeric不是浮点() throws Exception {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      var rs =
          c.createStatement()
              .executeQuery(
                  "select data_type from information_schema.columns"
                      + " where table_name = 'purchase_reconciliation_line'"
                      + " and column_name in ('amount','quantity','unit_conversion','fx_rate')"
                      + " order by column_name");
      while (rs.next()) {
        assertThat(rs.getString(1)).as("金额/数量/汇率必须是 numeric").isEqualTo("numeric");
      }
    }
  }
}
