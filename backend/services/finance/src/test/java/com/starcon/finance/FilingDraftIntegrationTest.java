package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.FilingDraftModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
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
 * 12.6.1.2 VAT/IOSS 申报数据的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 里 12.6.1.2 那一节的**职责句**与**关系句**：
 *
 * <ul>
 *   <li>「按辖区与周期汇总申报所需数据： taxable 销售额、已收 VAT、IOSS 订单与代收金额、
 *       可抵扣进项」⇒ 四类数各有来源、各有口径；</li>
 *   <li>「<b>本系统提供申报底稿，不替代税务申报动作</b>」⇒ 产出的是**底稿 + 汇总 + 真文件**，
 *       **没有**任何「已申报 / 已受理」的结论；</li>
 *   <li>「🔴 多币种绝不相加」⇒ 汇总按币种分组，底稿头没有任何金额列；</li>
 *   <li>「🔴 辖区码不是自由文本」⇒ 辖区码实时校验，美国辖区录不进去；</li>
 *   <li>「🔴 税率按生效时间取，命中不到就报错」⇒ 不退回最新一条、不按 0% 处理；</li>
 *   <li>「🔴 读不到就如实报读不到，不得退化成 0」⇒ IOSS 清单 / 可抵扣进项 / 汇率三处。</li>
 * </ul>
 *
 * <p><b>上游用真实的 HTTP 服务模拟</b>（{@code com.sun.net.httpserver}，与同服务其它模块
 * 逐字同构）：identity 返回已认证成员关系，channel 返回受控辖区码表与 IOSS 订单清单，
 * trade 返回订单列表与订单明细，catalog 返回汇率快照。上游读不到时本模块如实报
 * 「读不到」，不用空集合冒充「没有数据」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FilingDraftIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID orderEu = UUID.randomUUID();
  static final UUID orderUsd = UUID.randomUUID();
  static final UUID orderFr = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of("finance.filing_draft.read", "finance.filing_draft.create");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer channel;
  static HttpServer trade;
  static HttpServer catalog;

  /** channel 的辖区码表响应。 */
  static volatile String jurisdictionPayload = "[]";

  /** channel 的 IOSS 订单清单响应；null ⇒ 上游 404（16.3.2.1 未交付）。 */
  static volatile String iossPayload = null;

  /** trade 的订单列表响应。 */
  static volatile String orderListPayload = "{\"rows\":[],\"page\":0,\"size\":200,\"total\":0}";

  /** trade 的订单明细响应（按订单 id 分派）。 */
  static volatile Map<String, String> orderDetailPayloads = new ConcurrentHashMap<>();

  /** catalog 的汇率响应；null ⇒ 上游 404（汇率不可得）。 */
  static volatile String ratePayload = null;

  /** trade 的订单列表读不到时置 true（模拟上游 500）。 */
  static volatile boolean tradeListDown = false;

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put(
        "session",
        new AccessService.Access(
            UUID.randomUUID(),
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            PERMISSIONS,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", PERMISSIONS))));

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

      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/tax-registration/jurisdictions", e -> respond(e, jurisdictionPayload, 200));
      channel.createContext(
          "/channel/vat-ioss/orders",
          e -> {
            var payload = iossPayload;
            respond(e, payload == null ? "" : payload, payload == null ? 404 : 200);
          });
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();

      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/sales-orders/list",
          e -> respond(e, tradeListDown ? "" : orderListPayload, tradeListDown ? 500 : 200));
      trade.createContext(
          "/trade/sales-orders/detail/",
          e -> {
            var path = e.getRequestURI().getPath();
            var id = path.substring(path.lastIndexOf('/') + 1);
            var payload = orderDetailPayloads.get(id);
            respond(e, payload == null ? "" : payload, payload == null ? 404 : 200);
          });
      trade.setExecutor(Executors.newFixedThreadPool(4));
      trade.start();

      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/exchange-rates/latest",
          e -> {
            var payload = ratePayload;
            respond(e, payload == null ? "" : payload, payload == null ? 404 : 200);
          });
      catalog.setExecutor(Executors.newFixedThreadPool(2));
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 统一的响应器。 */
  static void respond(HttpExchange e, String payload, int status) {
    try {
      if (status != 200) {
        e.sendResponseHeaders(status, -1);
        e.close();
        return;
      }
      byte[] body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, body.length);
      e.getResponseBody().write(body);
      e.close();
    } catch (Exception ex) {
      e.close();
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired FilingDraftService filingDrafts;

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_filing_draft_export, finance_filing_draft_line,"
              + " finance_filing_draft restart identity cascade");
      s.execute("truncate table finance_voucher_line, finance_voucher restart identity cascade");
      s.execute("truncate table finance_voucher_period restart identity cascade");
      s.execute("truncate table finance_receipt restart identity cascade");
      s.execute("truncate table finance_tax_rate restart identity cascade");
    }

    jurisdictionPayload =
        """
        {"items":[
          {"code":"EU-DE","name":"德国","region":"EUROPEAN_UNION","countryCode":"DE",
           "subdivisionCode":"","taxRegime":"VAT","vatRequired":true,"requiresEpr":true,
           "defaultFilingCycle":"MONTHLY","derivationScope":"COUNTRY"},
          {"code":"EU-FR","name":"法国","region":"EUROPEAN_UNION","countryCode":"FR",
           "subdivisionCode":"","taxRegime":"VAT","vatRequired":true,"requiresEpr":true,
           "defaultFilingCycle":"MONTHLY","derivationScope":"COUNTRY"},
          {"code":"US","name":"美国（联邦）","region":"UNITED_STATES","countryCode":"US",
           "subdivisionCode":"","taxRegime":"SALES_TAX","vatRequired":false,"requiresEpr":false,
           "defaultFilingCycle":"QUARTERLY","derivationScope":"SUBNATIONAL"}],
         "total":3,"note":"channel 16.3.1.1 受控辖区码表"}
        """;
    iossPayload = null;
    ratePayload = null;
    tradeListDown = false;
    orderDetailPayloads = new ConcurrentHashMap<>();

    seedTaxRate("VAT", "EU-DE", "STANDARD", "0.19");
    seedTaxRate("VAT", "EU-FR", "STANDARD", "0.20");
    seedTaxRate("IOSS", "EU-DE", "STANDARD", "0.19");
  }

  /** 登记一版生效税率（12.6.1.1 的表，本模块只读）。 */
  void seedTaxRate(String taxType, String jurisdiction, String taxCode, String rate) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_tax_rate(id,tenant_id,tax_type,jurisdiction_code,jurisdiction_name,"
                  + " country_code,tax_code,rate,effective_from,effective_to,status,version,note,"
                  + " created_at,updated_at)"
                  + " values(?,?,?,?,?,?,?,?::numeric,?::timestamptz,null,'ACTIVE',1,?,"
                  + " ?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              taxType,
              jurisdiction,
              jurisdiction,
              jurisdiction.substring(3),
              taxCode,
              new BigDecimal(rate),
              OffsetDateTime.parse("2026-01-01T00:00:00Z"),
              "测试税率",
              now,
              now);
          return null;
        });
  }

  /** 一张订单：金额口径 + 收货国（辖区归属的判据）。 */
  void seedOrder(
      UUID orderId,
      String orderNo,
      String currency,
      String subtotal,
      String discount,
      String tax,
      String country,
      String submittedAt) {
    orderDetailPayloads.put(
        orderId.toString(),
        "{\"header\":{\"id\":\""
            + orderId
            + "\",\"orderNo\":\""
            + orderNo
            + "\",\"orderSource\":\"PLATFORM\",\"documentStatus\":\"SUBMITTED\",\"currency\":\""
            + currency
            + "\",\"subtotalAmount\":"
            + subtotal
            + ",\"discountAmount\":"
            + discount
            + ",\"taxAmount\":"
            + tax
            + ",\"shippingAmount\":0,\"otherAmount\":0,\"grandTotalAmount\":"
            + subtotal
            + ",\"submittedAt\":\""
            + submittedAt
            + "\"},"
            + "\"addresses\":[{\"addressRole\":\"SHIPPING\",\"countryCode\":\""
            + country
            + "\"}]}");
  }

  /** 让 trade 的订单列表返回指定的几张订单。 */
  void publishOrders(List<UUID> ids, long total) {
    var rows = new StringBuilder();
    for (var i = 0; i < ids.size(); i++) {
      if (i > 0) rows.append(",");
      rows.append("{\"id\":\"")
          .append(ids.get(i))
          .append("\",\"orderNo\":\"O")
          .append(i + 1)
          .append("\",\"orderSource\":\"PLATFORM\",\"documentStatus\":\"SUBMITTED\",")
          .append("\"currency\":\"EUR\",\"submittedAt\":\"2026-01-15T10:00:00Z\"}");
    }
    orderListPayload =
        "{\"rows\":[" + rows + "],\"page\":0,\"size\":200,\"total\":" + total + "}";
  }

  /** 登记一个应交税费科目（category = LIABILITY，tax_treatment = TAXABLE）。 */
  void seedInputTaxSubject(String subjectCode) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into finance_account_subject(id,tenant_id,accounting_book_id,legal_entity_id,"
                  + " subject_code,subject_name,category,balance_direction,is_detail,"
                  + " parent_subject_code,tax_treatment,tax_rate,tax_note,status,source,"
                  + " created_at,updated_at)"
                  + " values(?,?,?,?,?,?,'LIABILITY','CREDIT',true,null,'TAXABLE',null,'',"
                  + " 'ACTIVE','CUSTOM',?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              UUID.randomUUID(),
              UUID.randomUUID(),
              subjectCode,
              "进项税额",
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
  }

  /** 一张**已过账**凭证：借方落在应交税费科目上（= 可抵扣进项的来源）。 */
  void seedPostedVoucher(String subjectCode, String currency, String debitAmount) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          var periodId = UUID.randomUUID();
          sql.execute(
              "insert into finance_voucher_period(id,tenant_id,period_code,period_start,period_end,"
                  + " status,base_currency,note,version,created_at,updated_at)"
                  + " values(?,?, '2026-01','2026-01-01','2026-01-31','OPEN',?,'',0,"
                  + " ?::timestamptz,?::timestamptz)",
              periodId,
              tenant,
              currency,
              now,
              now);
          var voucherId = UUID.randomUUID();
          sql.execute(
              "insert into finance_voucher(id,tenant_id,voucher_no,source_type,source_no,"
                  + " period_id,voucher_date,description,currency,status,total_debit,total_credit,"
                  + " line_count,note,version,created_at,updated_at)"
                  + " values(?,?,'V-2026-01-001','MANUAL','',?,'2026-01-20','进项税','"
              + currency
              + "','DRAFT',0,0,0,'',0,?::timestamptz,?::timestamptz)",
              voucherId,
              tenant,
              periodId,
              now,
              now);
          sql.execute(
              "insert into finance_voucher_line(id,tenant_id,voucher_id,line_no,line_type,"
                  + " subject_code,subject_name,summary,amount,currency,auxiliary_type,"
                  + " auxiliary_name,created_at)"
                  + " values(?,?,?,1,'DEBIT',?,?,'进项',?::numeric,?,'NONE','',?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              voucherId,
              subjectCode,
              "进项税额",
              new BigDecimal(debitAmount),
              currency,
              now);
          sql.execute(
              "insert into finance_voucher_line(id,tenant_id,voucher_id,line_no,line_type,"
                  + " subject_code,subject_name,summary,amount,currency,auxiliary_type,"
                  + " auxiliary_name,created_at)"
                  + " values(?,?,?,2,'CREDIT','6001','主营业务收入','冲抵',?::numeric,?,'NONE','',"
                  + " ?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              voucherId,
              new BigDecimal(debitAmount),
              currency,
              now);
          // 过账：借贷必须平、至少两行、必须写明过账人与过账时刻。
          sql.execute(
              "update finance_voucher set status = 'POSTED', total_debit = ?::numeric,"
                  + " total_credit = ?::numeric, line_count = 2, posted_at = ?::timestamptz,"
                  + " posted_by = ? where tenant_id = ? and id = ?",
              new BigDecimal(debitAmount),
              new BigDecimal(debitAmount),
              now,
              UUID.randomUUID(),
              tenant,
              voucherId);
          return null;
        });
  }

  /** 登记一笔到账（12.2.1.1 收款单）。 */
  void seedReceipt(String currency, String amount, String receivedAt) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_receipt(id,tenant_id,receipt_no,source,source_system,"
                  + " currency,amount,settled_amount,advance_flag,status,received_at,payer_type,"
                  + " payer_name,note,version,created_at,updated_at)"
                  + " values(?,?,'R-2026-01-001','PLATFORM_SETTLEMENT','manual',?,?::numeric,0,"
                  + " false,'RECORDED',?::timestamptz,'PLATFORM','亚马逊','',0,"
                  + " ?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              currency,
              new BigDecimal(amount),
              OffsetDateTime.parse(receivedAt),
              now,
              now);
          return null;
        });
  }

  // ================================================================ 工具

  /** 生成底稿（HTTP 层）。 */
  String body(String jurisdiction, String taxType, String taxCode, String cycle, String base) {
    return "{\"jurisdictionCode\":\""
        + jurisdiction
        + "\",\"taxType\":\""
        + taxType
        + "\",\"taxCode\":\""
        + taxCode
        + "\",\"periodStart\":\"2026-01-01\",\"periodEnd\":\"2026-01-31\","
        + "\"filingCycle\":\""
        + cycle
        + "\",\"baseCurrency\":\""
        + base
        + "\"}";
  }

  /** 生成底稿并解析响应。 */
  FinanceFilingDraftDetail generate(String jurisdiction, String taxType, String taxCode, String base)
      throws Exception {
    var response =
        http.perform(
                post("/finance/filing-draft/drafts")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(jurisdiction, taxType, taxCode, "MONTHLY", base)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return json.readValue(response.getContentAsString(), FinanceFilingDraftDetail.class);
  }

  /** 在服务层调一次取数（**理由断言走这里** —— MockMvc 的 ResponseStatusException 不带 body）。 */
  FinanceFilingDraftDetail generateInService(
      String jurisdiction, String taxType, String taxCode, String base, LocalDate from, LocalDate to) {
    var input =
        new FinanceFilingDraftInput(
            jurisdiction, taxType, taxCode, from, to, "MONTHLY", base, null, null);
    return tenants.run(
        tenant, () -> filingDrafts.generate(input, actors.get("session"), "session"));
  }

  /** 本租户的底稿数（**必须在租户事务里查** —— RLS 在事务外一行都看不见）。 */
  long draftCount() {
    return tenants.run(
        tenant, () -> sql.fetchOne("select count(*) from finance_filing_draft").get(0, Long.class));
  }

  /** 某一行汇总（按币种取）。 */
  FinanceFilingDraftLine lineOf(FinanceFilingDraftDetail detail, String currency) {
    return detail.lines().stream()
        .filter(l -> currency.equals(l.currency()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("没有 " + currency + " 这一行"));
  }

  // ==========================================================================
  // 一、汇总：四类数各有来源
  // ==========================================================================

  @Test
  void 汇总按币种分组且四类数各有来源() throws Exception {
    seedOrder(orderEu, "O-EUR", "EUR", "1000.0000", "100.0000", "190.0000", "DE", "2026-01-15T10:00:00Z");
    seedOrder(orderUsd, "O-USD", "USD", "500.0000", "0.0000", "95.0000", "DE", "2026-01-16T10:00:00Z");
    seedOrder(orderFr, "O-FR", "EUR", "800.0000", "0.0000", "160.0000", "FR", "2026-01-17T10:00:00Z");
    publishOrders(List.of(orderEu, orderUsd, orderFr), 3);

    seedInputTaxSubject("2221.01");
    seedPostedVoucher("2221.01", "EUR", "38.0000");
    seedReceipt("EUR", "900.0000", "2026-01-25T08:00:00Z");

    var detail = generate("EU-DE", "VAT", "STANDARD", "");

    // EUR 一行：应税销售额 = 小计 − 折扣 = 900；已收 VAT = 190；可抵扣进项 = 38；应缴 = 152。
    var eur = lineOf(detail, "EUR");
    assertThat(eur.taxableSales()).isEqualByComparingTo("900.0000");
    assertThat(eur.vatCollected()).isEqualByComparingTo("190.0000");
    assertThat(eur.deductibleInput()).isEqualByComparingTo("38.0000");
    assertThat(eur.deductibleAvailable()).isTrue();
    assertThat(eur.netPayable()).isEqualByComparingTo("152.0000");
    assertThat(eur.orderCount()).isEqualTo(1);
    // 期间实际到账（12.2.1.1 收款单口径）与「已收 VAT」对照。
    assertThat(eur.receivedAmount()).isEqualByComparingTo("900.0000");

    // USD 一行：只有 USD 订单 ⇒ 独立一行，**不与 EUR 相加**。
    var usd = lineOf(detail, "USD");
    assertThat(usd.taxableSales()).isEqualByComparingTo("500.0000");
    assertThat(usd.vatCollected()).isEqualByComparingTo("95.0000");
    // USD 没有可抵扣进项 ⇒ 如实报「无可抵扣进项数据」，金额是 null 而不是 0。
    assertThat(usd.deductibleAvailable()).isFalse();
    assertThat(usd.deductibleInput()).isNull();
    assertThat(usd.netPayable()).isNull();
    assertThat(usd.deductibleNote()).contains("无可抵扣进项数据");

    // 法国订单不在本辖区 ⇒ 归属到别的辖区，**不混进德国底稿**。
    assertThat(detail.draft().includedCount()).isEqualTo(2);
    assertThat(detail.draft().outOfScopeCount()).isEqualTo(1);
    assertThat(detail.draft().unmappedCount()).isZero();
  }

  @Test
  void 多币种不相加底稿头也没有金额列() throws Exception {
    seedOrder(orderEu, "O-EUR", "EUR", "1000.0000", "0.0000", "190.0000", "DE", "2026-01-15T10:00:00Z");
    seedOrder(orderUsd, "O-USD", "USD", "500.0000", "0.0000", "95.0000", "DE", "2026-01-16T10:00:00Z");
    publishOrders(List.of(orderEu, orderUsd), 2);

    var raw =
        http.perform(
                post("/finance/filing-draft/drafts")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("EU-DE", "VAT", "STANDARD", "MONTHLY", "")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var node = json.readTree(raw);
    var draft = node.get("draft");
    // 🔴 底稿头**没有任何金额字段** —— 页面上不存在一个「跨币种总额」。
    assertThat(draft.has("totalAmount")).isFalse();
    assertThat(draft.has("total")).isFalse();
    assertThat(draft.has("taxableSales")).isFalse();
    // 汇总是**按币种分组**的两行。
    var lines = node.get("lines");
    assertThat(lines.size()).isEqualTo(2);
    assertThat(lines.get(0).get("currency").asText()).isEqualTo("EUR");
    assertThat(lines.get(1).get("currency").asText()).isEqualTo("USD");
  }

  // ==========================================================================
  // 二、辖区码不是自由文本 / 美国辖区归 channel
  // ==========================================================================

  @Test
  void 辖区码不在受控码表里直接被拒() throws Exception {
    http.perform(
            post("/finance/filing-draft/drafts")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("XX-XX", "VAT", "STANDARD", "MONTHLY", "")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 美国辖区录不进去并写明归channel() throws Exception {
    http.perform(
            post("/finance/filing-draft/drafts")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("US", "VAT", "STANDARD", "MONTHLY", "")))
        .andExpect(status().isBadRequest());
    // 🔴 理由走服务层：写明美国州销售税归 channel 16.3.2.3。
    assertThatThrownBy(
            () ->
                generateInService(
                    "US",
                    "VAT",
                    "STANDARD",
                    "",
                    LocalDate.parse("2026-01-01"),
                    LocalDate.parse("2026-01-31")))
        .hasStackTraceContaining("16.3.2.3")
        .hasStackTraceContaining("本模块不重复建表");
  }

  // ==========================================================================
  // 三、税率按生效时间取：命中不到就报错
  // ==========================================================================

  @Test
  void 命中不到税率直接报错不退回最新一条也不按零处理() throws Exception {
    // 期间落在税率生效区间之外（2025-12）⇒ 命中不到。
    http.perform(
            post("/finance/filing-draft/drafts")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"jurisdictionCode\":\"EU-DE\",\"taxType\":\"VAT\",\"taxCode\":\"STANDARD\","
                        + "\"periodStart\":\"2025-12-01\",\"periodEnd\":\"2025-12-31\","
                        + "\"filingCycle\":\"MONTHLY\"}"))
        .andExpect(status().isNotFound());
    // 🔴 理由走服务层（MockMvc 的 ResponseStatusException 不带 body）。
    assertThatThrownBy(
            () ->
                generateInService(
                    "EU-DE",
                    "VAT",
                    "STANDARD",
                    "",
                    LocalDate.parse("2025-12-01"),
                    LocalDate.parse("2025-12-31")))
        .hasStackTraceContaining("没有生效税率")
        .hasStackTraceContaining("不按 0% 处理");
    // 底稿**没有**被生成（失败就是失败，不留半份底稿）。
    assertThat(draftCount()).as("取数失败时不该留一份底稿").isZero();
  }

  @Test
  void 底稿行快照了当时命中的那一版税率() throws Exception {
    seedOrder(orderEu, "O-EUR", "EUR", "1000.0000", "0.0000", "190.0000", "DE", "2026-01-15T10:00:00Z");
    publishOrders(List.of(orderEu), 1);

    var detail = generate("EU-DE", "VAT", "STANDARD", "");
    var eur = lineOf(detail, "EUR");
    assertThat(eur.taxRate()).isEqualByComparingTo("0.190000");
    assertThat(eur.taxCode()).isEqualTo("STANDARD");
    assertThat(eur.taxRateId()).isNotNull();
    assertThat(eur.taxRateEffectiveFrom()).isNotNull();
  }

  // ==========================================================================
  // 四、读不到就如实报读不到，不得退化成 0
  // ==========================================================================

  @Test
  void 读不到IOSS订单清单时留空而不是零() throws Exception {
    // 16.3.2.1 与本模块同批开发中 ⇒ 上游 404。
    iossPayload = null;
    seedOrder(orderEu, "O-EUR", "EUR", "1000.0000", "0.0000", "190.0000", "DE", "2026-01-15T10:00:00Z");
    publishOrders(List.of(orderEu), 1);

    var detail = generate("EU-DE", "IOSS", "STANDARD", "");
    var eur = lineOf(detail, "EUR");
    assertThat(eur.iossOrderCount()).as("读不到就不能是 0").isNull();
    assertThat(eur.iossCollected()).isNull();
    // 数据来源里如实标出「读不到」。
    var ioss =
        detail.sources().stream()
            .filter(s -> "IOSS_ORDER_LIST".equals(s.key()))
            .findFirst()
            .orElseThrow();
    assertThat(ioss.available()).isFalse();
    assertThat(ioss.detail()).contains("读不到 IOSS 订单清单");
    // 口径里也写明了。
    assertThat(detail.draft().generationBasis()).contains("读不到 IOSS 订单清单");
  }

  @Test
  void IOSS清单读得到时订单数与代收金额真落库() throws Exception {
    iossPayload =
        """
        {"rows":[{"id":"11111111-1111-4111-8111-111111111111","orderNo":"I-1","shopCode":"SHOP-DE",
                  "countryCode":"DE","currency":"EUR","taxableAmount":900.0000,"vatAmount":171.0000,
                  "orderDate":"2026-01-20"}],
         "total":1}
        """;
    seedOrder(orderEu, "O-EUR", "EUR", "1000.0000", "0.0000", "190.0000", "DE", "2026-01-15T10:00:00Z");
    publishOrders(List.of(orderEu), 1);

    var detail = generate("EU-DE", "IOSS", "STANDARD", "");
    var eur = lineOf(detail, "EUR");
    assertThat(eur.iossOrderCount()).isEqualTo(1);
    assertThat(eur.iossCollected()).isEqualByComparingTo("171.0000");
    // 🔴 IOSS 代收金额**不并进** vat_collected（两套口径，不相加）。
    assertThat(eur.vatCollected()).isEqualByComparingTo("190.0000");
  }

  @Test
  void 汇率不可得时不折算绝不按一比一兜底() throws Exception {
    ratePayload = null;
    seedOrder(orderUsd, "O-USD", "USD", "500.0000", "0.0000", "95.0000", "DE", "2026-01-16T10:00:00Z");
    publishOrders(List.of(orderUsd), 1);

    var detail = generate("EU-DE", "VAT", "STANDARD", "EUR");
    var usd = lineOf(detail, "USD");
    assertThat(usd.exchangeRateAvailable()).isFalse();
    assertThat(usd.exchangeRate()).as("汇率不可得就不能是 1").isNull();
    assertThat(usd.convertedNetPayable()).isNull();
    assertThat(usd.exchangeRateSource()).contains("绝不按 1:1 兜底");
  }

  @Test
  void 汇率取得到时折算值真的算出来() throws Exception {
    ratePayload =
        """
        {"id":"22222222-2222-4222-8222-222222222222","baseCurrency":"USD","quoteCurrency":"EUR",
         "rate":0.900000,"effectiveDate":"2026-01-31","sourceCode":"MANUAL","sourceName":"人工登记",
         "display":"1 USD = 0.900000 EUR"}
        """;
    seedOrder(orderUsd, "O-USD", "USD", "500.0000", "0.0000", "95.0000", "DE", "2026-01-16T10:00:00Z");
    publishOrders(List.of(orderUsd), 1);

    var detail = generate("EU-DE", "VAT", "STANDARD", "EUR");
    var usd = lineOf(detail, "USD");
    assertThat(usd.exchangeRateAvailable()).isTrue();
    assertThat(usd.exchangeRate()).isEqualByComparingTo("0.900000");
    assertThat(usd.exchangeRateAsOf()).isEqualTo(LocalDate.parse("2026-01-31"));
  }

  @Test
  void 订单明细读不到时如实计数不悄悄丢掉() throws Exception {
    // 订单列表里有两张，但其中一张的明细上游 404。
    publishOrders(List.of(orderEu, orderUsd), 2);
    seedOrder(orderEu, "O-EUR", "EUR", "1000.0000", "0.0000", "190.0000", "DE", "2026-01-15T10:00:00Z");

    var detail = generate("EU-DE", "VAT", "STANDARD", "");
    assertThat(detail.draft().unmappedCount()).isEqualTo(1);
    assertThat(detail.draft().includedCount()).isEqualTo(1);
    assertThat(detail.draft().generationBasis()).contains("订单明细读不到");
  }

  @Test
  void 订单列表读不到时整个取数失败而不是做成零张订单的底稿() throws Exception {
    tradeListDown = true;

    http.perform(
            post("/finance/filing-draft/drafts")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("EU-DE", "VAT", "STANDARD", "MONTHLY", "")))
        .andExpect(status().isServiceUnavailable());
    assertThat(draftCount()).as("读不到订单事实时不该留一份「0 张订单」的底稿").isZero();
  }

  // ==========================================================================
  // 五、底稿是不可改写的快照
  // ==========================================================================

  @Test
  void 底稿与汇总行连直连SQL都改不动() throws Exception {
    seedOrder(orderEu, "O-EUR", "EUR", "1000.0000", "0.0000", "190.0000", "DE", "2026-01-15T10:00:00Z");
    publishOrders(List.of(orderEu), 1);
    var detail = generate("EU-DE", "VAT", "STANDARD", "");
    var draftId = detail.draft().id();

    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "update finance_filing_draft set order_count = 9999"
                                + " where tenant_id = ? and id = ?",
                            tenant,
                            draftId)))
        .as("覆盖情况是生成时的快照，不许事后改写")
        .hasStackTraceContaining("不可改写");

    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "update finance_filing_draft_line set vat_collected = 999999"
                                + " where tenant_id = ? and draft_id = ?",
                            tenant,
                            draftId)))
        .as("汇总行是不可改写的快照")
        .hasStackTraceContaining("只读快照");

    assertThatThrownBy(
            () ->
                tenants.run(
                    tenant,
                    () ->
                        sql.execute(
                            "update finance_filing_draft set generation_basis = '改口径'"
                                + " where tenant_id = ? and id = ?",
                            tenant,
                            draftId)))
        .as("口径是生成时的快照，不许事后改写")
        .hasStackTraceContaining("不可改写");
  }

  @Test
  void 重新取数时旧底稿作废新底稿生效() throws Exception {
    seedOrder(orderEu, "O-EUR", "EUR", "1000.0000", "0.0000", "190.0000", "DE", "2026-01-15T10:00:00Z");
    publishOrders(List.of(orderEu), 1);

    var first = generate("EU-DE", "VAT", "STANDARD", "");
    var second = generate("EU-DE", "VAT", "STANDARD", "");

    assertThat(second.draft().id()).isNotEqualTo(first.draft().id());
    var old = json.readValue(
        http.perform(get("/finance/filing-draft/drafts/{id}", first.draft().id())
                .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        FinanceFilingDraftDetail.class);
    assertThat(old.draft().status()).isEqualTo("SUPERSEDED");
    assertThat(second.draft().status()).isEqualTo("GENERATED");
  }

  // ==========================================================================
  // 六、导出是真文件 + 留痕
  // ==========================================================================

  @Test
  void 导出Csv是真文件而且留痕() throws Exception {
    seedOrder(orderEu, "O-EUR", "EUR", "1000.0000", "0.0000", "190.0000", "DE", "2026-01-15T10:00:00Z");
    publishOrders(List.of(orderEu), 1);
    var detail = generate("EU-DE", "VAT", "STANDARD", "");

    var response =
        http.perform(get("/finance/filing-draft/drafts/{id}/export", detail.draft().id())
                .header("Authorization", "session")
                .param("format", "CSV"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var csv = response.getContentAsString();
    // 真文件：有表头、有数据行、有可核信息（生成时间 / 口径 / 覆盖期间 / 币种 / 税率）。
    assertThat(csv).contains("jurisdiction_code,jurisdiction_name,tax_type,period_start");
    assertThat(csv).contains("EU-DE,德国,VAT,2026-01-01,2026-01-31,MONTHLY,EUR");
    assertThat(csv).contains("# generated_at=");
    assertThat(csv).contains("generation_basis=");
    assertThat(response.getHeader("Content-Disposition")).contains("filing-draft-EU-DE-VAT");
    assertThat(response.getHeader("Content-Type")).contains("text/csv");

    // 留痕：行数 / 字节数 / 内容哈希。
    var exports =
        json.readValue(
            http.perform(get("/finance/filing-draft/exports").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFilingExportPage.class);
    assertThat(exports.total()).isEqualTo(1);
    var export = exports.items().get(0);
    assertThat(export.format()).isEqualTo("CSV");
    assertThat(export.rowCount()).isEqualTo(1);
    assertThat(export.byteSize()).isGreaterThan(0);
    assertThat(export.contentHash()).hasSize(64);
    assertThat(export.draftId()).isEqualTo(detail.draft().id());
  }

  @Test
  void 导出Json是真文件而且带口径与数据来源() throws Exception {
    seedOrder(orderEu, "O-EUR", "EUR", "1000.0000", "0.0000", "190.0000", "DE", "2026-01-15T10:00:00Z");
    publishOrders(List.of(orderEu), 1);
    var detail = generate("EU-DE", "VAT", "STANDARD", "");

    var response =
        http.perform(get("/finance/filing-draft/drafts/{id}/export", detail.draft().id())
                .header("Authorization", "session")
                .param("format", "JSON"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    var node = json.readTree(response.getContentAsString());
    assertThat(node.get("module").asText()).isEqualTo("12.6.1.2");
    assertThat(node.get("productBoundary").asText()).contains("不替代税务申报动作");
    assertThat(node.get("draft").get("generationBasis").asText()).isNotBlank();
    assertThat(node.get("lines").get(0).get("currency").asText()).isEqualTo("EUR");
  }

  // ==========================================================================
  // 七、列表 / 选项 / 能力声明
  // ==========================================================================

  @Test
  void 底稿列表真分页真筛选() throws Exception {
    seedOrder(orderEu, "O-EUR", "EUR", "1000.0000", "0.0000", "190.0000", "DE", "2026-01-15T10:00:00Z");
    publishOrders(List.of(orderEu), 1);
    generate("EU-DE", "VAT", "STANDARD", "");
    generate("EU-FR", "VAT", "STANDARD", "");

    var page =
        json.readValue(
            http.perform(
                    get("/finance/filing-draft/drafts")
                        .header("Authorization", "session")
                        .param("jurisdictionCode", "EU-DE")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFilingDraftPage.class);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).jurisdictionCode()).isEqualTo("EU-DE");
    assertThat(page.liveCount()).isEqualTo(2);
  }

  @Test
  void 选项端点的辖区来自受控码表币种来自真实底稿() throws Exception {
    seedOrder(orderEu, "O-EUR", "EUR", "1000.0000", "0.0000", "190.0000", "DE", "2026-01-15T10:00:00Z");
    publishOrders(List.of(orderEu), 1);
    generate("EU-DE", "VAT", "STANDARD", "");

    var options =
        json.readValue(
            http.perform(get("/finance/filing-draft/options").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFilingOptions.class);
    assertThat(options.taxTypes()).extracting(FinanceFilingTypeOption::value).containsExactly("VAT", "IOSS");
    assertThat(options.filingCycles()).extracting(FinanceFilingCycleOption::value)
        .containsExactly("MONTHLY", "QUARTERLY");
    assertThat(options.currencies()).contains("EUR");
    assertThat(options.jurisdictions().items()).extracting(
            com.starcon.finance.TaxRateModels.FinanceTaxJurisdictionOption::code)
        .contains("EU-DE");
  }

  @Test
  void 能力声明写明产品边界与上游缺口() throws Exception {
    var capabilities =
        json.readValue(
            http.perform(get("/finance/filing-draft/capabilities").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFilingCapabilities.class);
    assertThat(capabilities.productBoundary()).contains("不替代税务申报动作");
    assertThat(capabilities.gaps()).anyMatch(g -> "16.3.1.2".equals(g.module()));
    assertThat(capabilities.gaps()).anyMatch(g -> "16.3.2.1".equals(g.module()));
    assertThat(capabilities.capabilities()).anyMatch(c -> "16.3.1.1".equals(c.module()));
  }
}
