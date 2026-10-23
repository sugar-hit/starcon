package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.SupplierReconciliationModels.DifferenceType;
import com.starcon.finance.SupplierReconciliationModels.ReconciliationStatus;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.jooq.Record;
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
 * 12.1.2.3 供应商对账（财务侧）的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 里那条**加粗句**与本模块的「关键约束」：
 *
 * <ul>
 *   <li>「<b>财务是最终确认方，采购不能自行确认应付</b>——这是职责分离」⇒
 *       采购侧权限（read + update）**裁决不了差异、确认不了对账单**（403）；
 *       没有财务确认权限就**没有应付单**；数据层直接改状态也会被触发器挡下来。
 *   <li>「核对采购侧对账单（6.5.1）与收货/质检数据，差异处理与确认，最终生成应付依据」⇒
 *       导入 / 核对 / 差异 / 说明 / 裁决 / 确认 / 应付依据**逐段**都能走通。
 *   <li>「金额一律 BigDecimal + NUMERIC，禁止浮点」⇒ 金额与数量逐位相等；
 *       确认时刻与到期日按 **UTC 日历日**写回并断言。
 * </ul>
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层**
 * （{@code assertThatThrownBy(...).hasStackTraceContaining(...)}），HTTP 层只断言**状态码**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SupplierReconciliationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID supplierRefId = UUID.randomUUID();
  static final UUID upstreamReconId = UUID.randomUUID();
  static final UUID trackingId = UUID.randomUUID();
  static final UUID receiptOne = UUID.randomUUID();
  static final UUID receiptTwo = UUID.randomUUID();
  static final UUID inspectionId = UUID.randomUUID();

  static final String READ = "finance.suppliereconciliation.read";
  static final String UPDATE = "finance.suppliereconciliation.update";
  static final String CONFIRM = "finance.suppliereconciliation.confirm";

  /** 财务侧角色：读 + 采购侧动作 + 财务确认。 */
  static final List<String> FINANCE_PERMISSIONS = List.of(READ, UPDATE, CONFIRM);

  /** 采购侧角色：读 + 导入 / 核对 / 登记说明，**没有**财务确认权。 */
  static final List<String> PURCHASE_PERMISSIONS = List.of(READ, UPDATE);

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer tradeUpstream;

  /** 上游对账单列表响应。 */
  static volatile String statementListPayload = "{\"rows\":[],\"page\":0,\"size\":200,\"total\":0}";

  /** 上游对账单详情响应。 */
  static volatile String statementDetailPayload = "{\"head\":{},\"lines\":[],\"discrepancies\":[]}";

  /** 上游到货跟踪详情响应。 */
  static volatile String arrivalPayload = "{\"receipts\":[]}";

  /** 上游质检单列表响应。 */
  static volatile String inspectionPayload = "{\"rows\":[],\"page\":0,\"size\":200,\"total\":0}";

  /** 上游「这张收货单没有质检记录」的响应。 */
  static final String emptyInspectionPayload = "{\"rows\":[],\"page\":0,\"size\":200,\"total\":0}";

  /** 上游打故障：模拟「上游读不到」。 */
  static volatile boolean upstreamDown = false;

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put("session", actor(FINANCE_PERMISSIONS));
    actors.put("purchase", actor(PURCHASE_PERMISSIONS));
    try {
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
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();

      tradeUpstream = HttpServer.create(new InetSocketAddress(0), 0);
      tradeUpstream.createContext(
          "/trade/purchase-reconciliations",
          e -> {
            if (upstreamDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            String path = e.getRequestURI().getPath();
            respond(e, path.endsWith("/purchase-reconciliations") ? statementListPayload : statementDetailPayload);
          });
      tradeUpstream.createContext(
          "/trade/purchase-arrivals",
          e -> {
            if (upstreamDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            respond(e, arrivalPayload);
          });
      tradeUpstream.createContext(
          "/trade/quality-inspections",
          e -> {
            if (upstreamDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            // 上游按**仓库收货单号**过滤：没有质检单的收货单返回空列表。
            var query = e.getRequestURI().getQuery();
            String receiptCode = "";
            if (query != null) {
              for (var part : query.split("&")) {
                if (part.startsWith("receiptCode=")) {
                  receiptCode = part.substring("receiptCode=".length());
                }
              }
            }
            respond(e, "GR-001".equals(receiptCode) ? inspectionPayload : emptyInspectionPayload);
          });
      tradeUpstream.setExecutor(Executors.newFixedThreadPool(2));
      tradeUpstream.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static void respond(com.sun.net.httpserver.HttpExchange e, String body) throws java.io.IOException {
    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  static AccessService.Access actor(List<String> permissions) {
    return new AccessService.Access(
        UUID.randomUUID(),
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + tradeUpstream.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired SupplierReconciliationService reconciliations;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_supplier_reconciliation_explanation,"
              + " finance_supplier_reconciliation_difference,"
              + " finance_supplier_reconciliation_inspection,"
              + " finance_supplier_reconciliation_line,"
              + " finance_supplier_reconciliation_event,"
              + " finance_supplier_reconciliation_payable,"
              + " finance_supplier_reconciliation,"
              + " finance_payable_event, finance_payable_line, finance_payable,"
              + " finance_payable_policy restart identity cascade");
    }
    upstreamDown = false;
    seedUpstream();
  }

  /** 财务侧角色。 */
  AccessService.Access actor() {
    return actors.get("session");
  }

  /** 采购侧角色（没有财务确认权）。 */
  AccessService.Access purchaseActor() {
    return actors.get("purchase");
  }

  /** 在**租户事务里**跑一段工作（与生产同一条路径：RLS 靠它隔离）。 */
  <T> T as(Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  Record one(String statement, Object... binds) {
    return as(() -> sql.fetchOne(statement, binds));
  }

  long count(String statement, Object... binds) {
    return as(() -> sql.fetchOne(statement, binds).get(0, Long.class));
  }

  /**
   * 用迁移用户（超管）直连跑一条**预期失败**的 SQL，返回错误消息。
   *
   * <p>触发器断言必须这么跑：超管不受 RLS 干扰，报出来的错就是**触发器**的错。
   */
  String dbError(String statement, Object... binds) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var ps = c.prepareStatement(statement)) {
      for (int i = 0; i < binds.length; i++) ps.setObject(i + 1, binds[i]);
      ps.execute();
      return "NO_ERROR";
    } catch (Exception e) {
      return e.getMessage();
    }
  }

  /**
   * 上游夹具：一份**供应商已确认**的采购对账单，两行到货应收。
   *
   * <p>第 1 行：数量一致（100），但质检扣了 10 ⇒ 质检扣量未在对账单中扣除。
   * 第 2 行：数量不一致（对账单 50 / 实到 45），且没有质检记录 ⇒ 数量差异 + 质检依据缺失。
   */
  void seedUpstream() {
    statementListPayload =
        """
        {"rows":[%s],"page":0,"size":200,"total":1}
        """
            .formatted(statementHead("CONFIRMED"));
    statementDetailPayload =
        """
        {"head":%s,"lines":[%s,%s],"discrepancies":[]}
        """
            .formatted(
                statementHead("CONFIRMED"),
                receiptLine(1, receiptOne, "GR-001", "100.000000", "10.0000", "1000.0000"),
                receiptLine(2, receiptTwo, "GR-002", "50.000000", "20.0000", "1000.0000"));
    arrivalPayload =
        """
        {"receipts":[{"id":"%s","arrivalSeq":1,"arrivedQty":100.000000,
          "arrivedAt":"2026-10-10T02:00:00Z","warehouseReceiptCode":"GR-001",
          "warehouseReceiptReceivedQty":100.000000},
         {"id":"%s","arrivalSeq":1,"arrivedQty":45.000000,
          "arrivedAt":"2026-10-12T02:00:00Z","warehouseReceiptCode":"GR-002",
          "warehouseReceiptReceivedQty":45.000000}]}
        """
            .formatted(receiptOne, receiptTwo);
    inspectionPayload =
        """
        {"rows":[{"id":"%s","inspectionNo":"QI-001","supplierId":"%s","supplierName":"供应商甲",
          "receiptCode":"GR-001","skuCode":"SKU-1","status":"COMPLETED","result":"PARTIAL",
          "arrivedQuantity":100.000000,"lotSize":100.000000,"qualifiedQuantity":90.000000,
          "defectiveQuantity":6.000000,"concessionQuantity":4.000000,"grossDeductedQuantity":10.000000,
          "currencyCode":"USD","unitPrice":10.0000,"deductedAmount":100.0000,
          "completedAt":"2026-10-12T02:00:00Z"}],"page":0,"size":200,"total":1}
        """
            .formatted(inspectionId, supplierRefId);
  }

  String statementHead(String status) {
    return """
        {"id":"%s","reconNo":"PR-20261031-ABCD1234","supplierRefId":"%s","supplierCode":"SUP-001",
         "supplierName":"供应商甲","periodType":"MONTHLY","periodStart":"2026-10-01",
         "periodEnd":"2026-10-31","statementCurrency":"USD","status":"%s",
         "receivedAmount":2000.0000,"deductionAmount":0.0000,"claimAmount":0.0000,
         "suggestedPayableAmount":2000.0000,"totalQuantity":150.000000,"lineCount":2,
         "discrepancyCount":0,"openDiscrepancyCount":0,"supplierConfirmedAt":"2026-11-01T02:00:00Z",
         "supplierReference":"SUP-CONF-1"}
        """
        .formatted(upstreamReconId, supplierRefId, status);
  }

  String receiptLine(int lineNo, UUID receiptId, String warehouseCode, String qty, String price, String amount) {
    return """
        {"id":"%s","lineNo":%d,"lineType":"RECEIPT","sourceType":"PURCHASE_ARRIVAL",
         "sourceRefId":"%s","sourceRefCode":"PO-001","sourceLineRefId":"%s","sourceLineRefCode":"%s",
         "occurredAt":"2026-10-10T02:00:00Z","quantity":%s,"unitCode":"AS_RECORDED",
         "unitConversion":1.000000,"baseQuantity":%s,"unitPrice":%s,"currency":"USD",
         "fxRate":1.00000000,"fxRateSource":"SAME_CURRENCY","fxRateAt":"2026-10-10T02:00:00Z",
         "amount":%s,"statementAmount":%s,"qualityDeductionStatus":"UNAVAILABLE",
         "qualityDeductionQuantity":null,
         "qualityDeductionNote":"6.4.2.1 质检单未接入","note":"到货流水"}
        """
        .formatted(
            UUID.nameUUIDFromBytes(("line-" + lineNo).getBytes()),
            lineNo,
            trackingId,
            receiptId,
            warehouseCode,
            qty,
            qty,
            price,
            amount,
            amount);
  }

  /** 导入一张对账单（走服务层）。 */
  SupplierReconciliationModels.FinanceSupplierReconciliationImportResult importOnce() {
    return as(() -> reconciliations.importFromPurchase("session", actor()));
  }

  /** 启用 SUPPLIER_STATEMENT 的应付确认政策（12.1.2.1 的闸门）。 */
  void enableStatementPolicy() {
    as(
        () -> {
          sql.execute(
              "insert into finance_payable_policy(id,tenant_id,source,obligation_kind,enabled,due_days,"
                  + " owner_module,created_at,updated_at)"
                  + " values(?,?,'SUPPLIER_STATEMENT','PAYABLE',true,30,'',now(),now())",
              UUID.randomUUID(),
              tenant);
          return null;
        });
  }

  UUID reconId() {
    return one("select id from finance_supplier_reconciliation limit 1").get("id", UUID.class);
  }

  // ================================================================ 导入

  @Test
  void 导入把采购侧对账单落成快照并按上游单号幂等() {
    var result = importOnce();
    assertThat(result.scanned()).isEqualTo(1);
    assertThat(result.imported()).isEqualTo(1);
    assertThat(result.skipped()).isZero();

    var head =
        one(
            "select * from finance_supplier_reconciliation where upstream_recon_id = ?",
            upstreamReconId);
    assertThat(head).isNotNull();
    assertThat(head.get("status", String.class)).isEqualTo("IMPORTED");
    assertThat(head.get("upstream_recon_no", String.class)).isEqualTo("PR-20261031-ABCD1234");
    assertThat(head.get("statement_currency", String.class)).isEqualTo("USD");
    assertThat(head.get("statement_received_amount", BigDecimal.class).toPlainString())
        .isEqualTo("2000.0000");
    // 行快照也落了（数量 / 单价 / 汇率都是上游的值，本模块不自己算）。
    assertThat(count("select count(*) from finance_supplier_reconciliation_line")).isEqualTo(2);
    var line = one("select * from finance_supplier_reconciliation_line order by line_no limit 1");
    assertThat(line.get("quantity", BigDecimal.class).toPlainString()).isEqualTo("100.000000");
    assertThat(line.get("unit_price", BigDecimal.class).toPlainString()).isEqualTo("10.0000");
    assertThat(line.get("fx_rate", BigDecimal.class).toPlainString()).isEqualTo("1.00000000");

    // 幂等：重复导入命中同一条，不长出第二张。
    var again = importOnce();
    assertThat(again.imported()).isZero();
    assertThat(again.refreshed()).isEqualTo(1);
    assertThat(count("select count(*) from finance_supplier_reconciliation")).isEqualTo(1);
    assertThat(count("select count(*) from finance_supplier_reconciliation_line")).isEqualTo(2);
  }

  @Test
  void 上游还没供应商确认的对账单不导入() {
    statementListPayload =
        """
        {"rows":[%s],"page":0,"size":200,"total":1}
        """
            .formatted(statementHead("SENT"));
    var result = importOnce();
    assertThat(result.imported()).isZero();
    assertThat(result.skipped()).isEqualTo(1);
    assertThat(count("select count(*) from finance_supplier_reconciliation")).isZero();
  }

  @Test
  void 上游读不到就整体失败而不是导入零条() {
    upstreamDown = true;
    assertThatThrownBy(() -> importOnce())
        .hasStackTraceContaining("读不到")
        .hasStackTraceContaining("503");
    assertThat(count("select count(*) from finance_supplier_reconciliation")).isZero();
  }

  // ================================================================ 核对与差异

  @Test
  void 核对把质检扣量与收货数量差逐条落成差异() {
    importOnce();
    var result = as(() -> reconciliations.match(reconId(), "session", actor()));
    assertThat(result.matchedLines()).isEqualTo(2);
    assertThat(result.raisedDifferences()).isEqualTo(3);
    assertThat(result.openDifferences()).isEqualTo(3);

    var differences =
        as(
            () ->
                sql.fetch(
                    "select * from finance_supplier_reconciliation_difference order by line_no, id"));
    assertThat(differences).hasSize(3);
    // 第 1 行：质检扣量 10 未在对账单中扣除 ⇒ 金额差 −100。
    var quality =
        differences.stream()
            .filter(r -> DifferenceType.QUALITY_DEDUCTION_NOT_APPLIED.name().equals(r.get("difference_type", String.class)))
            .findFirst()
            .orElseThrow();
    assertThat(quality.get("variance_quantity", BigDecimal.class).toPlainString()).isEqualTo("-10.000000");
    assertThat(quality.get("variance_amount", BigDecimal.class).toPlainString()).isEqualTo("-100.0000");
    // 第 2 行：数量差 5 × 单价 20 ⇒ −100；另有一条「质检依据缺失」（金额差 0）。
    var quantity =
        differences.stream()
            .filter(r -> DifferenceType.RECEIPT_QUANTITY_MISMATCH.name().equals(r.get("difference_type", String.class)))
            .findFirst()
            .orElseThrow();
    assertThat(quantity.get("variance_quantity", BigDecimal.class).toPlainString()).isEqualTo("-5.000000");
    assertThat(quantity.get("variance_amount", BigDecimal.class).toPlainString()).isEqualTo("-100.0000");
    assertThat(
            differences.stream()
                .filter(
                    r ->
                        DifferenceType.QUALITY_EVIDENCE_MISSING
                            .name()
                            .equals(r.get("difference_type", String.class)))
                .count())
        .isEqualTo(1);

    // 财务侧认定的数量与金额落在行上（采购侧口径与财务侧口径**都留着**）。
    var lineOne = one("select * from finance_supplier_reconciliation_line where line_no = 1");
    assertThat(lineOne.get("receipt_arrived_qty", BigDecimal.class).toPlainString()).isEqualTo("100.000000");
    assertThat(lineOne.get("finance_quantity", BigDecimal.class).toPlainString()).isEqualTo("90.000000");
    assertThat(lineOne.get("finance_amount", BigDecimal.class).toPlainString()).isEqualTo("900.0000");
    assertThat(lineOne.get("quality_deducted_qty", BigDecimal.class).toPlainString()).isEqualTo("10.000000");
    // 质检证据也落了快照。
    assertThat(count("select count(*) from finance_supplier_reconciliation_inspection")).isEqualTo(1);
  }

  @Test
  void 找不到到货事实时记依据缺失且本行金额留空而不是零() {
    importOnce();
    // 让上游的到货跟踪读不到（404）。
    arrivalPayload = "{\"receipts\":[]}";
    as(() -> reconciliations.match(reconId(), "session", actor()));
    var line = one("select * from finance_supplier_reconciliation_line where line_no = 1");
    assertThat(line.get("receipt_matched", Boolean.class)).isFalse();
    assertThat(line.get("finance_amount", BigDecimal.class)).isNull();
    assertThat(line.get("finance_quantity", BigDecimal.class)).isNull();
    assertThat(
            count(
                "select count(*) from finance_supplier_reconciliation_difference"
                    + " where difference_type = 'RECEIPT_EVIDENCE_MISSING'"))
        .isEqualTo(2);
  }

  @Test
  void 差异说明可以登记但不会自己裁决() {
    importOnce();
    as(() -> reconciliations.match(reconId(), "session", actor()));
    var differenceId =
        one("select id from finance_supplier_reconciliation_difference limit 1").get("id", UUID.class);
    var detail =
        as(
            () ->
                reconciliations.addExplanation(
                    reconId(),
                    differenceId,
                    new SupplierReconciliationModels.FinanceSupplierReconciliationExplanationInput(
                        "供应商已确认该批按扣量后结算", "SUPPLIER", "SUP-CONF-1"),
                    actor()));
    var difference =
        detail.differences().stream().filter(d -> d.id().equals(differenceId)).findFirst().orElseThrow();
    assertThat(difference.status().name()).isEqualTo("EXPLAINED");
    assertThat(difference.resolution()).isNull();
    assertThat(difference.explanation()).contains("扣量后结算");
    // 说明是**追加**的举证：库里两条记录（一条说明 + 一条差异摘要）。
    assertThat(count("select count(*) from finance_supplier_reconciliation_explanation")).isEqualTo(1);
    assertThat(count("select count(*) from finance_supplier_reconciliation_event")).isGreaterThanOrEqualTo(2);
  }

  // ================================================================ 职责分离

  @Test
  void 采购侧权限裁决不了差异也确认不了对账单() throws Exception {
    importOnce();
    as(() -> reconciliations.match(reconId(), "session", actor()));
    var differenceId =
        one("select id from finance_supplier_reconciliation_difference limit 1").get("id", UUID.class);

    // 采购侧角色：**不能**裁决（权限码在端点层校验 —— finance.suppliereconciliation.confirm）。
    http.perform(
            post("/finance/supplier-reconciliation/{id}/differences/{did}/resolve", reconId(), differenceId)
                .header("Authorization", "purchase")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolution\":\"APPLIED\",\"note\":\"采购侧越权裁决\"}"))
        .andExpect(status().isForbidden());
    // 采购侧角色：**不能**确认。
    http.perform(
            post("/finance/supplier-reconciliation/{id}/confirm", reconId())
                .header("Authorization", "purchase"))
        .andExpect(status().isForbidden());

    // 库里的差异仍是未裁决状态，没有应付单。
    assertThat(
            count(
                "select count(*) from finance_supplier_reconciliation_difference"
                    + " where status = 'RESOLVED'"))
        .isZero();
    assertThat(count("select count(*) from finance_payable")).isZero();
  }

  @Test
  void 没有裁决完差异就对账不了() {
    importOnce();
    as(() -> reconciliations.match(reconId(), "session", actor()));
    assertThatThrownBy(() -> as(() -> reconciliations.confirm(reconId(), actor())))
        .hasStackTraceContaining("差异未裁决");
    assertThat(count("select count(*) from finance_payable")).isZero();
  }

  @Test
  void 没有启用的应付确认政策就产生不了应付() {
    importOnce();
    as(() -> reconciliations.match(reconId(), "session", actor()));
    resolveAll("APPLIED");
    assertThatThrownBy(() -> as(() -> reconciliations.confirm(reconId(), actor())))
        .hasStackTraceContaining("应付确认政策");
    assertThat(count("select count(*) from finance_payable")).isZero();
  }

  // ================================================================ 财务确认 → 应付

  @Test
  void 财务确认生成应付依据与应付单且金额等于净额() {
    enableStatementPolicy();
    importOnce();
    as(() -> reconciliations.match(reconId(), "session", actor()));
    resolveAll("APPLIED");

    var detail = as(() -> reconciliations.confirm(reconId(), actor()));
    assertThat(detail.head().status()).isEqualTo(ReconciliationStatus.CONFIRMED);
    assertThat(detail.head().confirmedBy()).isNotNull();
    assertThat(detail.head().confirmedAt()).isNotNull();
    assertThat(detail.payableBasis()).isNotNull();

    // 应付依据：采购侧口径 2000 + 调整 −200 = 1800。
    assertThat(detail.payableBasis().statementAmount().toPlainString()).isEqualTo("2000.0000");
    assertThat(detail.payableBasis().adjustmentAmount().toPlainString()).isEqualTo("-200.0000");
    assertThat(detail.payableBasis().amount().toPlainString()).isEqualTo("1800.0000");

    // 应付单：来源 = SUPPLIER_STATEMENT、来源单号 = 对账单号（V340 的幂等键）。
    var payable =
        one("select * from finance_payable where source = 'SUPPLIER_STATEMENT'");
    assertThat(payable).isNotNull();
    assertThat(payable.get("source_reference", String.class))
        .isEqualTo(detail.head().reconNo());
    assertThat(payable.get("status", String.class)).isEqualTo("OPEN");
    assertThat(payable.get("currency", String.class)).isEqualTo("USD");
    assertThat(payable.get("counterparty_type", String.class)).isEqualTo("SUPPLIER");
    assertThat(payable.get("counterparty_ref", UUID.class)).isEqualTo(supplierRefId);
    // ⚠️ 单头金额由 V340 的触发器从明细求和派生 ⇒ 必须等于明细合计。
    assertThat(payable.get("amount", BigDecimal.class).toPlainString()).isEqualTo("1800.0000");
    assertThat(
            one("select coalesce(sum(amount),0) from finance_payable_line where payable_id = ?", payable.get("id", UUID.class))
                .get(0, BigDecimal.class)
                .toPlainString())
        .isEqualTo("1800.0000");
    // 已付恒为 0（付款属 12.2.2.1，未交付）。
    assertThat(payable.get("paid_amount", BigDecimal.class).toPlainString()).isEqualTo("0.0000");
    // 应付单也有事件流。
    assertThat(count("select count(*) from finance_payable_event where payable_id = ?", payable.get("id", UUID.class)))
        .isEqualTo(2);
  }

  @Test
  void 确认时刻与到期日按UTC日历日写回() {
    enableStatementPolicy();
    importOnce();
    as(() -> reconciliations.match(reconId(), "session", actor()));
    resolveAll("APPLIED");
    var detail = as(() -> reconciliations.confirm(reconId(), actor()));

    // ⚠️ 必须把时刻折算到 UTC 再取日期（Jackson 出来带 JVM 本地时区偏移）。
    var confirmedUtc = detail.head().confirmedAt().withOffsetSameInstant(ZoneOffset.UTC);
    var payable =
        one("select * from finance_payable where source = 'SUPPLIER_STATEMENT'");
    assertThat(payable.get("confirmed_at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC))
        .isEqualTo(confirmedUtc);
    // 账期 30 天：到期日 = 确认日（UTC 日历日）+ 30。
    assertThat(payable.get("due_date", LocalDate.class))
        .isEqualTo(confirmedUtc.toLocalDate().plusDays(30));
  }

  @Test
  void 沿用采购侧口径时应付等于采购侧金额() {
    enableStatementPolicy();
    importOnce();
    as(() -> reconciliations.match(reconId(), "session", actor()));
    resolveAll("KEPT");
    var detail = as(() -> reconciliations.confirm(reconId(), actor()));
    assertThat(detail.payableBasis().adjustmentAmount().toPlainString()).isEqualTo("0.0000");
    assertThat(detail.payableBasis().amount().toPlainString()).isEqualTo("2000.0000");
    assertThat(
            one("select amount from finance_payable where source = 'SUPPLIER_STATEMENT'")
                .get("amount", BigDecimal.class)
                .toPlainString())
        .isEqualTo("2000.0000");
  }

  @Test
  void 同一份对账单重复确认不会造出第二张应付() {
    enableStatementPolicy();
    importOnce();
    as(() -> reconciliations.match(reconId(), "session", actor()));
    resolveAll("APPLIED");
    as(() -> reconciliations.confirm(reconId(), actor()));
    assertThatThrownBy(() -> as(() -> reconciliations.confirm(reconId(), actor())))
        .hasStackTraceContaining("终态");
    assertThat(count("select count(*) from finance_payable")).isEqualTo(1);
    assertThat(count("select count(*) from finance_supplier_reconciliation_payable")).isEqualTo(1);
  }

  @Test
  void 驳回必须写理由且是终态() {
    importOnce();
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        reconciliations.reject(
                            reconId(),
                            new SupplierReconciliationModels.FinanceSupplierReconciliationRejectInput("  "),
                            actor())))
        .hasStackTraceContaining("驳回必须写明理由");
    var detail =
        as(
            () ->
                reconciliations.reject(
                    reconId(),
                    new SupplierReconciliationModels.FinanceSupplierReconciliationRejectInput(
                        "供应商回执缺章，本期不确认"),
                    actor()));
    assertThat(detail.head().status()).isEqualTo(ReconciliationStatus.REJECTED);
    assertThat(detail.head().rejectReason()).contains("缺章");
    // 终态冻结：确认字段一律为空，没有应付单。
    assertThat(detail.head().payableId()).isNull();
    assertThat(count("select count(*) from finance_payable")).isZero();
    // 终态后再改快照字段会被触发器挡下。
    assertThat(
            dbError(
                "update finance_supplier_reconciliation set note = '改一笔' where id = ?", reconId()))
        .contains("终态");
  }

  // ================================================================ 数据层守卫

  @Test
  void 没有应付依据就不许把对账单置为已确认() {
    importOnce();
    as(() -> reconciliations.match(reconId(), "session", actor()));
    var id = reconId();
    var message =
        dbError(
            "update finance_supplier_reconciliation set status='CONFIRMED',confirmed_by=?,confirmed_at=now(),"
                + " finance_confirmed_amount=100.0000,payable_id=? where id=?",
            UUID.randomUUID(),
            UUID.randomUUID(),
            id);
    assertThat(message).contains("应付依据");
  }

  @Test
  void 应付依据必须指向一张来源为供应商对账单的应付单() {
    enableStatementPolicy();
    importOnce();
    as(() -> reconciliations.match(reconId(), "session", actor()));
    resolveAll("APPLIED");
    var id = reconId();
    // 先造一张**来源不对**的应付单（MANUAL），再拿它当依据 ⇒ 触发器必须拒绝。
    as(
        () -> {
          sql.execute(
              "insert into finance_payable(id,tenant_id,payable_no,source,source_system,"
                  + " counterparty_type,counterparty_name,currency,due_date,confirmed_at,status,"
                  + " created_at,updated_at) values(?,?,?,'MANUAL','manual','SUPPLIER','供应商甲','USD',"
                  + " ?::date,?::timestamptz,'DRAFT',?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              "AP-WRONG-SOURCE",
              LocalDate.now(ZoneOffset.UTC).plusDays(30),
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
    var wrong =
        one("select id from finance_payable where payable_no = 'AP-WRONG-SOURCE'").get("id", UUID.class);
    var message =
        dbError(
            "insert into finance_supplier_reconciliation_payable(id,tenant_id,recon_id,payable_id,"
                + " payable_no,amount,currency,statement_amount,adjustment_amount,basis_note,generated_by,"
                + " generated_at,created_at) values(?,?,?,?,'AP-WRONG-SOURCE',100.0000,'USD',100.0000,0,"
                + " '手工绕过',?,now(),now())",
            UUID.randomUUID(),
            tenant,
            id,
            wrong,
            UUID.randomUUID());
    assertThat(message).contains("SUPPLIER_STATEMENT");
  }

  @Test
  void 已确认的对账单不可再改() {
    enableStatementPolicy();
    importOnce();
    as(() -> reconciliations.match(reconId(), "session", actor()));
    resolveAll("APPLIED");
    as(() -> reconciliations.confirm(reconId(), actor()));
    assertThat(
            dbError(
                "update finance_supplier_reconciliation set statement_suggested_amount = 1 where id = ?",
                reconId()))
        .contains("终态");
    // 明细行也随对账单冻结。
    assertThat(
            dbError(
                "update finance_supplier_reconciliation_line set finance_amount = 1 where recon_id = ?",
                reconId()))
        .contains("终态");
  }

  // ================================================================ 能力声明 / HTTP

  @Test
  void 能力声明逐条报出上游接线与职责分离() {
    var caps = as(() -> reconciliations.capabilities());
    assertThat(caps.capabilities()).isNotEmpty();
    var statement =
        caps.capabilities().stream()
            .filter(c -> "6.5.1.1".equals(c.moduleId()))
            .findFirst()
            .orElseThrow();
    assertThat(statement.available()).isTrue();
    assertThat(statement.detail()).contains("只导入供应商已确认");
    var separation =
        caps.capabilities().stream()
            .filter(c -> c.title().contains("职责分离"))
            .findFirst()
            .orElseThrow();
    assertThat(separation.available()).isTrue();
    assertThat(separation.detail()).contains("finance.suppliereconciliation.confirm");
  }

  @Test
  void 端点按权限码分开采购侧与财务侧() throws Exception {
    enableStatementPolicy();
    importOnce();
    as(() -> reconciliations.match(reconId(), "session", actor()));
    var id = reconId();

    // 采购侧：能导入 / 能核对 / 能登记说明，**不能**裁决 / 确认。
    http.perform(post("/finance/supplier-reconciliation/{id}/match", id).header("Authorization", "purchase"))
        .andExpect(status().isOk());
    var differenceId =
        one("select id from finance_supplier_reconciliation_difference limit 1").get("id", UUID.class);
    http.perform(
            post("/finance/supplier-reconciliation/{id}/differences/{did}/resolve", id, differenceId)
                .header("Authorization", "purchase")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolution\":\"APPLIED\",\"note\":\"采购侧越权\"}"))
        .andExpect(status().isForbidden());
    http.perform(post("/finance/supplier-reconciliation/{id}/confirm", id).header("Authorization", "purchase"))
        .andExpect(status().isForbidden());
    // 财务侧：能裁决、能确认。
    // 财务侧：能裁决 —— 但**必须把每条差异都裁决掉**才能确认。
    var allDifferenceIds =
        as(
            () ->
                sql.fetch("select id from finance_supplier_reconciliation_difference")
                    .map(r -> r.get("id", UUID.class)));
    for (var did : allDifferenceIds) {
      http.perform(
              post("/finance/supplier-reconciliation/{id}/differences/{did}/resolve", id, did)
                  .header("Authorization", "session")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"resolution\":\"APPLIED\",\"note\":\"财务认可扣量\"}"))
          .andExpect(status().isOk());
    }
    http.perform(post("/finance/supplier-reconciliation/{id}/confirm", id).header("Authorization", "session"))
        .andExpect(status().isOk());
    // 读端点。
    http.perform(get("/finance/supplier-reconciliation/{id}", id).header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(get("/finance/supplier-reconciliation/capabilities").header("Authorization", "session"))
        .andExpect(status().isOk());
  }

  /** 把全部差异按同一口径裁决掉。 */
  void resolveAll(String resolution) {
    var ids =
        as(
            () ->
                sql.fetch("select id from finance_supplier_reconciliation_difference")
                    .map(r -> r.get("id", UUID.class)));
    for (var id : ids) {
      as(
          () ->
              reconciliations.resolveDifference(
                  reconId(),
                  id,
                  new SupplierReconciliationModels.FinanceSupplierReconciliationResolveInput(
                      resolution, "测试裁决"),
                  actor()));
    }
  }
}
