package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.InvoiceModels.*;
import com.starcon.finance.TaxRateModels.FinanceTaxRateInput;
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
import java.util.concurrent.CountDownLatch;
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
 * 12.6.2.1 发票管理的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖的是蓝图 12-finance.md 里 12.6.2.1 那几行字与本模块的硬性纪律：
 *
 * <ul>
 *   <li>「按平台/国家模板生成」⇒ 模板按（国家 × 平台 × 单据类型）配置，
 *       同一时刻只能有一张生效模板；<b>模板零预置行</b>；</li>
 *   <li>「维护税码税率税额」⇒ 税率<b>实时校验</b>（经 12.6.1.1 的 resolve），
 *       命中不到一律报错（<b>不按 0% 处理</b>）；<b>调用方填不出假的税额</b>
 *       （行金额与税额是 DB 生成列）；</li>
 *   <li>「编号与状态（草稿/开具/作废/红冲）」⇒ 编号<b>只在开具时分配</b>且
 *       <b>并发不撞号</b>；作废与红冲<b>必须留痕</b>；<b>红冲成对</b>；</li>
 *   <li>「支持批量与平台开票对接」⇒ 批量建单失败<b>逐条说清原因</b>；
 *       平台递交<b>失败也留痕</b>（写上游原话）；</li>
 *   <li>「<b>各国发票要求差异大</b>……<b>本系统不承诺覆盖所有国家税务规则</b>」
 *       ⇒ 辖区码是受控码表（美国辖区<b>录不进去</b>）、能力声明逐个辖区说清支持哪些。</li>
 * </ul>
 *
 * <p><b>上游用真实的 HTTP 服务模拟</b>（{@code com.sun.net.httpserver}，与同服务其它模块逐字同构）：
 * identity 返回已认证成员关系，channel 返回受控辖区码表与平台开票接口，
 * trade 返回待开票需求，catalog 返回汇率。读不到时本模块一律 <b>503</b>，
 * 不用空集合冒充「没有辖区 / 没有待开票需求 / 没有汇率」。
 *
 * <p><b>断言风格与本仓其它模块一致</b>：业务规则与拒绝理由走**服务层**
 * （{@code assertThatThrownBy(...).hasStackTraceContaining(...)}），HTTP 层只断言**状态码**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FinanceInvoiceIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of("finance.invoice.read", "finance.invoice.create", "finance.invoice.update");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer channel;
  static HttpServer trade;
  static HttpServer catalog;

  /** channel 的辖区码表响应（真实上游形状：欧盟国家 + 英国 + 美国联邦与州）。 */
  static volatile String jurisdictionPayload = "[]";

  /** channel 的平台开票接口：200 = 受理；404 = 上游还没提供这个接口。 */
  static volatile int platformSubmitStatus = 200;

  /** channel 的平台受理结果。 */
  static volatile String platformSubmitPayload = "{\"accepted\":true,\"platformRef\":\"PLAT-001\"}";

  /** channel 读不到时置 true（模拟上游 500）。 */
  static volatile boolean channelDown = false;

  /** trade 的待开票需求响应。 */
  static volatile String receivablePayload = "{\"items\":[],\"total\":0,\"page\":0,\"size\":200}";

  /** catalog 的汇率响应；null = 上游查不到（404）。 */
  static volatile String ratePayload = null;

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
          "/channel/tax-registration/jurisdictions", e -> respond(e, jurisdictionPayload));
      channel.createContext(
          "/channel/platform-invoices/submissions",
          e -> {
            byte[] body = platformSubmitPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (platformSubmitStatus != 200) {
              e.sendResponseHeaders(platformSubmitStatus, -1);
              e.close();
              return;
            }
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();

      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext("/trade/b2b-orders/receivables", e -> respond(e, receivablePayload));
      trade.setExecutor(Executors.newFixedThreadPool(2));
      trade.start();

      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/exchange-rates/latest",
          e -> {
            var payload = ratePayload;
            if (payload == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            byte[] body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.setExecutor(Executors.newFixedThreadPool(2));
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 统一的响应器：channel 挂掉时一律 500（模拟上游不可读）。 */
  static void respond(com.sun.net.httpserver.HttpExchange e, String payload) {
    try {
      if (channelDown) {
        e.sendResponseHeaders(500, -1);
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
  @Autowired TaxRateService taxRates;
  @Autowired InvoiceService invoices;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_invoice_event, finance_invoice_platform_submission,"
              + " finance_invoice_line, finance_invoice, finance_invoice_sequence,"
              + " finance_invoice_template, finance_tax_shop_binding, finance_tax_rate"
              + " restart identity cascade");
    }
    channelDown = false;
    platformSubmitStatus = 200;
    platformSubmitPayload = "{\"accepted\":true,\"platformRef\":\"PLAT-001\"}";
    ratePayload = null;
    jurisdictionPayload =
        """
        {"items":[
          {"code":"EU-DE","name":"德国","region":"EUROPEAN_UNION","countryCode":"DE",
           "subdivisionCode":"","taxRegime":"VAT","vatRequired":true,"requiresEpr":true,
           "defaultFilingCycle":"MONTHLY","derivationScope":"COUNTRY"},
          {"code":"EU-FR","name":"法国","region":"EUROPEAN_UNION","countryCode":"FR",
           "subdivisionCode":"","taxRegime":"VAT","vatRequired":true,"requiresEpr":true,
           "defaultFilingCycle":"MONTHLY","derivationScope":"COUNTRY"},
          {"code":"GB","name":"英国","region":"UNITED_KINGDOM","countryCode":"GB",
           "subdivisionCode":"","taxRegime":"VAT","vatRequired":true,"requiresEpr":true,
           "defaultFilingCycle":"MONTHLY","derivationScope":"COUNTRY"},
          {"code":"US","name":"美国（联邦）","region":"UNITED_STATES","countryCode":"US",
           "subdivisionCode":"","taxRegime":"SALES_TAX","vatRequired":false,"requiresEpr":false,
           "defaultFilingCycle":"QUARTERLY","derivationScope":"SUBNATIONAL"},
          {"code":"US-CA","name":"美国 加利福尼亚州","region":"UNITED_STATES","countryCode":"US",
           "subdivisionCode":"US-CA","taxRegime":"SALES_TAX","vatRequired":true,"requiresEpr":false,
           "defaultFilingCycle":"QUARTERLY","derivationScope":"SUBNATIONAL"}],
         "total":5,"note":"channel 16.3.1.1 受控辖区码表"}
        """;
    receivablePayload =
        """
        {"items":[{"id":"22222222-2222-4222-8222-222222222222","receivableNo":"AR-1",
                   "orderId":"33333333-3333-4333-8333-333333333333","orderNo":"SO-2026-001",
                   "customerName":"买家 A","currency":"EUR","amount":20.0000,
                   "invoiceRequestedAt":"2026-03-01T02:00:00Z","invoiceState":"REQUESTED",
                   "lines":[{"skuCode":"SKU-1","quantity":2.000000,"unitPrice":10.0000,
                             "lineAmount":20.0000}]}],
         "page":0,"size":200,"total":1,"openAmount":20.0000,"overdueAmount":0.0000,
         "invoicePending":1}
        """;

    // 税率（12.6.1.1）：三个辖区的标准 VAT —— 发票行取税率靠的就是它
    seedRate("EU-DE", "德国", "DE", "0.19");
    seedRate("EU-FR", "法国", "FR", "0.20");
    seedRate("GB", "英国", "GB", "0.20");
  }

  /** 在**租户事务里**跑一段工作（与生产同一条路径：RLS 靠它隔离）。 */
  <T> T as(java.util.function.Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  /** 登记一版税率（走 12.6.1.1 的真实路径）。 */
  void seedRate(String jurisdiction, String name, String country, String rate) {
    as(
        () ->
            taxRates.upsertRate(
                new FinanceTaxRateInput(
                    "VAT",
                    jurisdiction,
                    "STANDARD",
                    new BigDecimal(rate),
                    OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                    "测试税率"),
                actors.get("session"),
                "session"));
  }

  /** 登记一张模板。 */
  FinanceInvoiceTemplateRow template(
      String code, String country, String jurisdiction, String platform, String currency, String prefix) {
    return as(
        () ->
            invoices.upsertTemplate(
                new FinanceInvoiceTemplateInput(
                    code,
                    code + " 模板",
                    country,
                    jurisdiction,
                    platform,
                    "INVOICE",
                    "VAT",
                    "STANDARD",
                    currency,
                    "示例 GmbH",
                    prefix,
                    "COUNTRY",
                    "MONTHLY",
                    OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                    "测试模板"),
                actors.get("session"),
                "session"));
  }

  /** 建一张发票草稿（两行，便于看行级金额）。 */
  FinanceInvoiceRow draft(String templateCode, String currency) {
    var tpl =
        as(
            () ->
                sql.fetchOne(
                    "select id from finance_invoice_template where tenant_id = ? and template_code = ?",
                    tenant,
                    templateCode));
    return as(
        () ->
            invoices.create(
                new FinanceInvoiceCreateInput(
                    tpl.get("id", UUID.class),
                    LocalDate.parse("2026-03-01"),
                    null,
                    "",
                    null,
                    "SO-2026-001",
                    "买家 A",
                    List.of(
                        new FinanceInvoiceLineInput(
                            "SKU-1", "商品一", new BigDecimal("2"), new BigDecimal("10.00"), null, null, ""),
                        new FinanceInvoiceLineInput(
                            "SKU-2", "商品二", new BigDecimal("1"), new BigDecimal("5.50"), null, null, "")),
                    "测试草稿"),
                actors.get("session"),
                "session"));
  }

  // ==========================================================================
  // 一、模板：按平台 / 国家配置 + 受控辖区码
  // ==========================================================================
  @Test
  void 模板登记后按国家与平台列得出来() {
    var tpl = template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    assertThat(tpl.jurisdictionCode()).isEqualTo("EU-DE");
    assertThat(tpl.jurisdictionName()).isEqualTo("德国");
    assertThat(tpl.sequencePeriod()).isEqualTo("MONTHLY");

    var page = as(() -> invoices.templates("DE", "AMAZON", "INVOICE", null, null, 0, 50));
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).templateCode()).isEqualTo("DE-INV");
  }

  @Test
  void 美国辖区录不进去_州销售税归channel() {
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        invoices.upsertTemplate(
                            new FinanceInvoiceTemplateInput(
                                "US-INV", "美国发票模板", "US", "US-CA", "AMAZON", "INVOICE", "SALES_TAX",
                                "STANDARD", "USD", "Example Inc", "US-INV", "COUNTRY", "MONTHLY",
                                OffsetDateTime.parse("2026-01-01T00:00:00Z"), ""),
                            actors.get("session"),
                            "session")))
        .hasStackTraceContaining("channel 16.3.2.3");
  }

  @Test
  void 辖区码不是自由文本() {
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        invoices.upsertTemplate(
                            new FinanceInvoiceTemplateInput(
                                "XX-INV", "不存在的辖区", "DE", "EU-XX", "AMAZON", "INVOICE", "VAT",
                                "STANDARD", "EUR", "示例", "XX-INV", "COUNTRY", "MONTHLY",
                                OffsetDateTime.parse("2026-01-01T00:00:00Z"), ""),
                            actors.get("session"),
                            "session")))
        .hasStackTraceContaining("不是自由文本");
  }

  @Test
  void 模板必须带生效时间() {
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        invoices.upsertTemplate(
                            new FinanceInvoiceTemplateInput(
                                "NO-EFF", "没有生效时间", "DE", "EU-DE", "AMAZON", "INVOICE", "VAT",
                                "STANDARD", "EUR", "示例", "NO-EFF", "COUNTRY", "MONTHLY", null, ""),
                            actors.get("session"),
                            "session")))
        .hasStackTraceContaining("生效时间");
  }

  @Test
  void 同一国家同一平台的模板生效区间不许重叠() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        invoices.upsertTemplate(
                            new FinanceInvoiceTemplateInput(
                                "DE-INV-2", "重叠的模板", "DE", "EU-DE", "AMAZON", "INVOICE", "VAT",
                                "STANDARD", "EUR", "示例", "DE-INV-2", "COUNTRY", "MONTHLY",
                                OffsetDateTime.parse("2026-01-01T00:00:00Z"), ""),
                            actors.get("session"),
                            "session")))
        .hasStackTraceContaining("生效区间不许重叠");
  }

  // ==========================================================================
  // 二、税码 / 税率 / 税额：实时校验 + 调用方填不出假税额
  // ==========================================================================
  @Test
  void 建单时税率实时取_行金额与税额是生成列() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");

    // 2 × 10.00 = 20.0000；1 × 5.50 = 5.5000 ⇒ 小计 25.5000
    // 税率 0.19 ⇒ 税额 round(25.5000 × 0.19, 4) = 4.8450
    assertThat(row.subtotalAmount()).isEqualByComparingTo("25.5000");
    assertThat(row.taxAmount()).isEqualByComparingTo("4.8450");
    assertThat(row.totalAmount()).isEqualByComparingTo("30.3450");

    var detail = as(() -> invoices.invoiceDetail(row.id()));
    assertThat(detail.lines()).hasSize(2);
    assertThat(detail.lines().get(0).lineAmount()).isEqualByComparingTo("20.0000");
    assertThat(detail.lines().get(0).taxAmount()).isEqualByComparingTo("3.8000");
    assertThat(detail.lines().get(0).taxRate()).isEqualByComparingTo("0.19");
    // 税率版本 id 落在行上 ⇒ 发票自带当时的税率口径
    assertThat(detail.lines().get(0).taxRateId()).isNotNull();
  }

  @Test
  void 取不到税率一律报错_不按0处理() {
    // 没有登记税率的辖区（EU-FR 有税率，但这里用一份没配税率的模板）
    var tpl =
        as(
            () ->
                invoices.upsertTemplate(
                    new FinanceInvoiceTemplateInput(
                        "DE-NORATE", "没有税率的模板", "DE", "EU-DE", "SHOPIFY", "INVOICE", "VAT",
                        "REDUCED", "EUR", "示例", "DE-NORATE", "COUNTRY", "MONTHLY",
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"), ""),
                    actors.get("session"),
                    "session"));
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        invoices.create(
                            new FinanceInvoiceCreateInput(
                                tpl.id(),
                                LocalDate.parse("2026-03-01"),
                                null,
                                "",
                                null,
                                "SO-1",
                                "买家",
                                List.of(
                                    new FinanceInvoiceLineInput(
                                        "SKU-1", "商品", new BigDecimal("1"), new BigDecimal("10"), null,
                                        null, "")),
                                ""),
                            actors.get("session"),
                            "session")))
        .hasStackTraceContaining("没有生效税率");
  }

  @Test
  void 发票日之前没有生效税率也一样报错() {
    // 税率 2026-01-01 生效；发票日落在它之前 ⇒ 取不到
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var tpl =
        as(
            () ->
                sql.fetchOne(
                    "select id from finance_invoice_template where tenant_id = ? and template_code = ?",
                    tenant,
                    "DE-INV"));
    assertThatThrownBy(
            () ->
                as(
                    () ->
                        invoices.create(
                            new FinanceInvoiceCreateInput(
                                tpl.get("id", UUID.class),
                                LocalDate.parse("2025-06-01"),
                                null,
                                "",
                                null,
                                "SO-1",
                                "买家",
                                List.of(
                                    new FinanceInvoiceLineInput(
                                        "SKU-1", "商品", new BigDecimal("1"), new BigDecimal("10"), null,
                                        null, "")),
                                ""),
                            actors.get("session"),
                            "session")))
        .hasStackTraceContaining("没有生效税率");
  }

  // ==========================================================================
  // 三、编号与状态机
  // ==========================================================================
  @Test
  void 草稿没有编号_编号只在开具时分配() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    assertThat(row.invoiceNumber()).isNull();
    assertThat(row.status()).isEqualTo("DRAFT");

    var issued = as(() -> invoices.issue(row.id(), actors.get("session")));
    assertThat(issued.status()).isEqualTo("ISSUED");
    assertThat(issued.invoiceNumber()).startsWith("DE-INV-2026-03-");
  }

  @Test
  void 并发开具不会撞号() throws Exception {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var first = draft("DE-INV", "EUR");
    var second = draft("DE-INV", "EUR");

    var pool = Executors.newFixedThreadPool(2);
    try {
      var start = new CountDownLatch(1);
      var a =
          pool.submit(
              () -> {
                start.await();
                return as(() -> invoices.issue(first.id(), actors.get("session"))).invoiceNumber();
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                return as(() -> invoices.issue(second.id(), actors.get("session"))).invoiceNumber();
              });
      start.countDown();
      var numberA = a.get();
      var numberB = b.get();
      assertThat(numberA).isNotEqualTo(numberB);
      assertThat(List.of(numberA, numberB)).allMatch(n -> n.startsWith("DE-INV-2026-03-"));
    } finally {
      pool.shutdownNow();
    }

    // 号段落在序列表上（next_value = 2）
    var sequence =
        as(
            () ->
                sql.fetchOne(
                    "select next_value from finance_invoice_sequence where tenant_id = ?",
                    tenant));
    assertThat(sequence.get(0, Long.class)).isEqualTo(2L);
  }

  @Test
  void 开具时间写回的是正确时刻_不是偏了八小时() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    var before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(2);
    var issued = as(() -> invoices.issue(row.id(), actors.get("session")));
    var after = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(2);

    assertThat(issued.issuedAt()).isNotNull();
    assertThat(issued.issuedAt()).isBetween(before, after);
    assertThat(issued.issuedBy()).isEqualTo(actors.get("session").userId());
  }

  @Test
  void 空单不能开具() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var tpl =
        as(
            () ->
                sql.fetchOne(
                    "select id from finance_invoice_template where tenant_id = ? and template_code = ?",
                    tenant,
                    "DE-INV"));
    // 造一张没有明细的单（直连 SQL 绕过服务层）
    var id = UUID.randomUUID();
    as(
        () -> {
          sql.execute(
              "insert into finance_invoice(id,tenant_id,invoice_number,document_type,status,template_id,"
                  + " template_code,title,country_code,jurisdiction_code,jurisdiction_name,platform_code,"
                  + " shop_code,source_order_no,customer_name,currency,issue_date,subtotal_amount,"
                  + " tax_amount,total_amount,reason,created_at,updated_at)"
                  + " values(?,?,null,'INVOICE','DRAFT',?,'DE-INV','示例','DE','EU-DE','德国','AMAZON',"
                  + " '','SO-1','买家','EUR',?::date,0,0,0,'',?::timestamptz,?::timestamptz)",
              id,
              tenant,
              tpl.get("id", UUID.class),
              LocalDate.parse("2026-03-01"),
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
    assertThatThrownBy(() -> as(() -> invoices.issue(id, actors.get("session"))))
        .hasStackTraceContaining("空单不能开具");
  }

  @Test
  void 作废必须写明原因() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    as(() -> invoices.issue(row.id(), actors.get("session")));
    assertThatThrownBy(
            () -> as(() -> invoices.voidInvoice(row.id(), new FinanceInvoiceVoidInput("  "), actors.get("session"))))
        .hasStackTraceContaining("作废必须写明原因");
  }

  @Test
  void 作废留痕_谁何时为什么() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    as(() -> invoices.issue(row.id(), actors.get("session")));
    var before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(2);
    var voided = as(() -> invoices.voidInvoice(row.id(), new FinanceInvoiceVoidInput("开错了买家"), actors.get("session")));
    var after = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(2);

    assertThat(voided.status()).isEqualTo("VOID");
    assertThat(voided.reason()).isEqualTo("开错了买家");
    assertThat(voided.voidedBy()).isEqualTo(actors.get("session").userId());
    assertThat(voided.voidedAt()).isBetween(before, after);

    var events = as(() -> invoices.events(row.id()));
    var voidEvent =
        events.stream().filter(e -> "VOID".equals(e.eventType())).findFirst().orElseThrow();
    assertThat(voidEvent.reason()).isEqualTo("开错了买家");
    assertThat(voidEvent.fromStatus()).isEqualTo("ISSUED");
    assertThat(voidEvent.toStatus()).isEqualTo("VOID");
    assertThat(voidEvent.actorId()).isEqualTo(actors.get("session").userId());
    assertThat(voidEvent.occurredAt()).isBetween(before, after);
  }

  @Test
  void 已开具的发票业务列不可改写() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    as(() -> invoices.issue(row.id(), actors.get("session")));

    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_invoice set customer_name = '换个人' where tenant_id = ? and id = ?",
                          tenant,
                          row.id());
                      return null;
                    }))
        .hasStackTraceContaining("不可改写");
  }

  @Test
  void 已开具的发票明细不可改写() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    as(() -> invoices.issue(row.id(), actors.get("session")));

    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "update finance_invoice_line set unit_price = 1 where tenant_id = ? and invoice_id = ?",
                          tenant,
                          row.id());
                      return null;
                    }))
        .hasStackTraceContaining("不可改写");
  }

  // ==========================================================================
  // 四、红冲：成对 + 金额逐行带出
  // ==========================================================================
  @Test
  void 红冲生成成对的贷项通知单_金额逐行带出() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    var issued = as(() -> invoices.issue(row.id(), actors.get("session")));

    var credit = as(() -> invoices.reverse(row.id(), new FinanceInvoiceReverseInput(List.of(), "退货退款"), actors.get("session")));
    assertThat(credit.documentType()).isEqualTo("CREDIT_NOTE");
    assertThat(credit.status()).isEqualTo("DRAFT");
    assertThat(credit.originalInvoiceId()).isEqualTo(row.id());
    assertThat(credit.originalInvoiceNumber()).isEqualTo(issued.invoiceNumber());
    assertThat(credit.reason()).isEqualTo("退货退款");

    var lines = as(() -> invoices.linesOf(credit.id()));
    assertThat(lines).hasSize(2);
    // 数量取负（红冲方向），金额逐行带出
    assertThat(lines.get(0).quantity()).isEqualByComparingTo("-2");
    assertThat(lines.get(0).unitPrice()).isEqualByComparingTo("10.00");
    assertThat(lines.get(0).taxRate()).isEqualByComparingTo("0.19");
    assertThat(lines.get(0).originalLineAmount()).isEqualByComparingTo("20.0000");
    assertThat(lines.get(0).originalLineId()).isNotNull();

    // 贷项单金额是负的（与红冲方向一致）
    assertThat(credit.subtotalAmount()).isEqualByComparingTo("-25.5000");
    assertThat(credit.totalAmount()).isEqualByComparingTo("-30.3450");
  }

  @Test
  void 贷项单开具后原发票收口为已红冲() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    as(() -> invoices.issue(row.id(), actors.get("session")));
    var credit = as(() -> invoices.reverse(row.id(), new FinanceInvoiceReverseInput(List.of(), "退货退款"), actors.get("session")));
    as(() -> invoices.issue(credit.id(), actors.get("session")));

    var original = as(() -> invoices.invoiceOf(row.id()));
    assertThat(original.status()).isEqualTo("REVERSED");
    assertThat(original.reversalInvoiceId()).isEqualTo(credit.id());
    assertThat(original.reversedBy()).isEqualTo(actors.get("session").userId());
    assertThat(original.reversedAt()).isNotNull();

    var events = as(() -> invoices.events(row.id()));
    assertThat(events.stream().anyMatch(e -> "REVERSED".equals(e.eventType()))).isTrue();
  }

  @Test
  void 不允许孤立红冲() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    as(() -> invoices.issue(row.id(), actors.get("session")));

    // 直连 SQL 造一张没有原发票的贷项单 ⇒ DB 层就写不出来
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into finance_invoice(id,tenant_id,invoice_number,document_type,status,"
                              + " template_id,template_code,title,country_code,jurisdiction_code,"
                              + " jurisdiction_name,platform_code,shop_code,source_order_no,customer_name,"
                              + " currency,issue_date,subtotal_amount,tax_amount,total_amount,reason,"
                              + " created_at,updated_at)"
                              + " values(?,?,null,'CREDIT_NOTE','DRAFT',?,'DE-INV','示例','DE','EU-DE',"
                              + " '德国','AMAZON','','SO-1','买家','EUR',?::date,0,0,0,'',"
                              + " ?::timestamptz,?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          UUID.randomUUID(),
                          LocalDate.parse("2026-03-01"),
                          OffsetDateTime.now(ZoneOffset.UTC),
                          OffsetDateTime.now(ZoneOffset.UTC));
                      return null;
                    }))
        .hasStackTraceContaining("不允许孤立红冲");
  }

  @Test
  void 红冲金额不许手填_带不出来的数写不进去() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    as(() -> invoices.issue(row.id(), actors.get("session")));
    var credit = as(() -> invoices.reverse(row.id(), new FinanceInvoiceReverseInput(List.of(), "退货退款"), actors.get("session")));
    var originalLine = as(() -> invoices.linesOf(row.id())).get(0);

    // 手工插一条数量对不上的贷项行 ⇒ DB 镜像守卫挡住
    assertThatThrownBy(
            () ->
                as(
                    () -> {
                      sql.execute(
                          "insert into finance_invoice_line(id,tenant_id,invoice_id,line_no,sku_code,"
                              + " product_name,quantity,unit_price,tax_type,tax_code,tax_rate,"
                              + " tax_rate_id,original_line_id,original_line_amount,note,created_at)"
                              + " values(?,?,?,99,'SKU-X','手填的',?::numeric,?::numeric,'VAT','STANDARD',"
                              + " ?::numeric,?,?,?::numeric,'手填',?::timestamptz)",
                          UUID.randomUUID(),
                          tenant,
                          credit.id(),
                          new BigDecimal("-99"),
                          new BigDecimal("1"),
                          new BigDecimal("0.19"),
                          originalLine.taxRateId(),
                          originalLine.id(),
                          new BigDecimal("999.0000"),
                          OffsetDateTime.now(ZoneOffset.UTC));
                      return null;
                    }))
        .hasStackTraceContaining("不许手填");
  }

  @Test
  void 同一张原发票不允许重复红冲() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    as(() -> invoices.issue(row.id(), actors.get("session")));
    as(() -> invoices.reverse(row.id(), new FinanceInvoiceReverseInput(List.of(), "退货退款"), actors.get("session")));
    assertThatThrownBy(
            () -> as(() -> invoices.reverse(row.id(), new FinanceInvoiceReverseInput(List.of(), "再退一次"), actors.get("session"))))
        .hasStackTraceContaining("已有一张未终结的贷项通知单");
  }

  // ==========================================================================
  // 五、多币种：绝不相加
  // ==========================================================================
  @Test
  void 汇总按币种分组_没有跨币种的合计() throws Exception {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    template("GB-INV", "GB", "GB", "AMAZON", "GBP", "GB-INV");
    // 🔴 草稿不进合计（草稿不是对外凭据）⇒ 先开具
    var eurInvoice = draft("DE-INV", "EUR");
    var gbpInvoice = draft("GB-INV", "GBP");
    as(() -> invoices.issue(eurInvoice.id(), actors.get("session")));
    as(() -> invoices.issue(gbpInvoice.id(), actors.get("session")));

    var summary = as(() -> invoices.summary(null, "session"));
    assertThat(summary.byCurrency()).hasSize(2);
    var eur =
        summary.byCurrency().stream().filter(c -> "EUR".equals(c.currency())).findFirst().orElseThrow();
    var gbp =
        summary.byCurrency().stream().filter(c -> "GBP".equals(c.currency())).findFirst().orElseThrow();
    // EUR 税率 19% ⇒ 30.3450；GBP 税率 20% ⇒ 30.6000 —— **两个币种各算各的**
    assertThat(eur.total()).isEqualByComparingTo("30.3450");
    assertThat(gbp.total()).isEqualByComparingTo("30.6000");
    // 没请求本位币 ⇒ 没有折算值，也**没有** 1:1 兜底
    assertThat(eur.baseAmount()).isNull();
    assertThat(gbp.baseAmount()).isNull();
    assertThat(summary.exchangeRateAvailable()).isFalse();

    // HTTP 层再钉一道：响应里**没有**跨币种的合计字段
    var body =
        http.perform(get("/finance/invoices/summary").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var node = json.readTree(body);
    assertThat(node.has("total")).as("汇总响应不许有跨币种的合计").isFalse();
    assertThat(node.has("subtotal")).isFalse();
    assertThat(node.get("byCurrency")).hasSize(2);
  }

  @Test
  void 汇率不可得就如实报_不按1比1兜底() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var invoice = draft("DE-INV", "EUR");
    as(() -> invoices.issue(invoice.id(), actors.get("session")));
    ratePayload = null; // catalog 查不到 ⇒ 汇率不可得

    var summary = as(() -> invoices.summary("USD", "session"));
    var eur = summary.byCurrency().get(0);
    assertThat(eur.baseAmount()).as("汇率不可得时折算值必须是空的").isNull();
    assertThat(eur.exchangeRateNote()).contains("汇率不可得");
    assertThat(summary.exchangeRateAvailable()).isFalse();
    assertThat(summary.note()).contains("汇率不可得");
  }

  @Test
  void 汇率取得到就折算() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var invoice = draft("DE-INV", "EUR");
    as(() -> invoices.issue(invoice.id(), actors.get("session")));
    ratePayload =
        """
        {"id":"44444444-4444-4444-8444-444444444444","baseCurrency":"EUR","quoteCurrency":"USD",
         "rate":1.100000,"effectiveDate":"2026-03-01","sourceCode":"MANUAL","sourceName":"手工登记"}
        """;

    var summary = as(() -> invoices.summary("USD", "session"));
    var eur = summary.byCurrency().get(0);
    assertThat(eur.exchangeRate()).isEqualByComparingTo("1.1");
    assertThat(eur.baseAmount()).isEqualByComparingTo("33.3795");
    assertThat(summary.exchangeRateAvailable()).isTrue();
  }

  // ==========================================================================
  // 六、批量与平台开票对接
  // ==========================================================================
  @Test
  void 批量建单失败逐条说清原因() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var tpl =
        as(
            () ->
                sql.fetchOne(
                    "select id from finance_invoice_template where tenant_id = ? and template_code = ?",
                    tenant,
                    "DE-INV"));
    var result =
        as(
            () ->
                invoices.batchCreate(
                    new FinanceInvoiceBatchInput(
                        tpl.get("id", UUID.class),
                        List.of(UUID.fromString("22222222-2222-4222-8222-222222222222"),
                                UUID.fromString("99999999-9999-4999-8999-999999999999")),
                        LocalDate.parse("2026-03-01"),
                        "批量"),
                    actors.get("session"),
                    "session"));

    assertThat(result.created()).hasSize(1);
    assertThat(result.failed()).hasSize(1);
    assertThat(result.failed().get(0).reason()).contains("找不到这一条");
    // 批量建出来的单子挂上了上游需求
    assertThat(result.created().get(0).sourceReceivableId())
        .isEqualTo(UUID.fromString("22222222-2222-4222-8222-222222222222"));
  }

  @Test
  void 平台递交成功会回填回执号() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    as(() -> invoices.issue(row.id(), actors.get("session")));

    var result =
        as(
            () ->
                invoices.submitToPlatform(
                    new FinanceInvoicePlatformSubmitInput(List.of(row.id()), null),
                    actors.get("session"),
                    "session"));
    assertThat(result.failed()).isEmpty();
    assertThat(result.submitted()).hasSize(1);
    assertThat(result.submitted().get(0).platformRef()).isEqualTo("PLAT-001");

    var invoice = as(() -> invoices.invoiceOf(row.id()));
    assertThat(invoice.platformSyncStatus()).isEqualTo("SUBMITTED");
    assertThat(invoice.platformInvoiceRef()).isEqualTo("PLAT-001");
  }

  @Test
  void 平台递交失败也留痕_写上游原话() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    as(() -> invoices.issue(row.id(), actors.get("session")));
    platformSubmitStatus = 404; // channel 还没提供这个接口

    var result =
        as(
            () ->
                invoices.submitToPlatform(
                    new FinanceInvoicePlatformSubmitInput(List.of(row.id()), null),
                    actors.get("session"),
                    "session"));
    assertThat(result.submitted()).isEmpty();
    assertThat(result.failed()).hasSize(1);
    assertThat(result.failed().get(0).reason()).contains("尚未提供开票接口");
    assertThat(result.failed().get(0).reason()).contains("channel");

    // 失败也写进提交记录（失败不留痕等于没提交过）
    var submissions = as(() -> invoices.submissions(0, 50));
    assertThat(submissions.items()).hasSize(1);
    assertThat(submissions.items().get(0).status()).isEqualTo("FAILED");
    assertThat(submissions.items().get(0).errorMessage()).contains("尚未提供开票接口");

    var invoice = as(() -> invoices.invoiceOf(row.id()));
    assertThat(invoice.platformSyncStatus()).isEqualTo("FAILED");
    assertThat(invoice.platformInvoiceRef()).isEmpty();
  }

  @Test
  void 平台报文里金额是字符串_不走浮点() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    as(() -> invoices.issue(row.id(), actors.get("session")));

    var payload = as(() -> invoices.platformPayload(row.id()));
    assertThat(payload.totals().total()).isEqualTo("30.345");
    assertThat(payload.lines().get(0).lineAmount()).isEqualTo("20");
    assertThat(payload.totals().subtotal()).isEqualTo("25.5");
    // 摘要稳定：同一张发票两次生成，摘要必须一样
    assertThat(as(() -> invoices.platformPayload(row.id())).digest()).isEqualTo(payload.digest());
  }

  @Test
  void 平台回执要单独登记_受理不等于确认() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    as(() -> invoices.issue(row.id(), actors.get("session")));
    var result =
        as(
            () ->
                invoices.submitToPlatform(
                    new FinanceInvoicePlatformSubmitInput(List.of(row.id()), null),
                    actors.get("session"),
                    "session"));
    var submissionId = result.submitted().get(0).id();

    var acked =
        as(
            () ->
                invoices.acknowledge(
                    submissionId,
                    new FinanceInvoicePlatformAcknowledgeInput("PLAT-ACK-1", "ACKNOWLEDGED", "平台已确认"),
                    actors.get("session")));
    assertThat(acked.status()).isEqualTo("ACKNOWLEDGED");
    assertThat(acked.platformRef()).isEqualTo("PLAT-ACK-1");
    assertThat(acked.acknowledgedAt()).isNotNull();

    var invoice = as(() -> invoices.invoiceOf(row.id()));
    assertThat(invoice.platformSyncStatus()).isEqualTo("ACKNOWLEDGED");
  }

  // ==========================================================================
  // 七、能力声明：如实写清支持哪些、缺口归谁
  // ==========================================================================
  @Test
  void 能力声明逐个辖区说清支持哪些() {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var caps = as(() -> invoices.capabilities("session"));

    assertThat(caps.countryCoverage()).isNotEmpty();
    var de =
        caps.countryCoverage().stream()
            .filter(c -> "EU-DE".equals(c.jurisdictionCode()))
            .findFirst()
            .orElseThrow();
    assertThat(de.templateConfigured()).isTrue();
    assertThat(de.taxRateResolvable()).isTrue();

    // 没配模板的辖区要**说清**缺口，而不是假装支持
    var fr =
        caps.countryCoverage().stream()
            .filter(c -> "EU-FR".equals(c.jurisdictionCode()))
            .findFirst()
            .orElseThrow();
    assertThat(fr.templateConfigured()).isFalse();
    assertThat(fr.note()).contains("还没有配发票模板");

    // 平台开票的缺口归 channel
    assertThat(caps.downstreamGaps()).anyMatch(g -> g.ownerService().equals("channel"));
  }

  // ==========================================================================
  // 八、HTTP 层：鉴权 + 状态码
  // ==========================================================================
  @Test
  void 端点要登录() throws Exception {
    http.perform(get("/finance/invoices")).andExpect(status().isUnauthorized());
  }

  @Test
  void 列表与详情走真实数据() throws Exception {
    template("DE-INV", "DE", "EU-DE", "AMAZON", "EUR", "DE-INV");
    var row = draft("DE-INV", "EUR");
    as(() -> invoices.issue(row.id(), actors.get("session")));

    var page =
        json.readValue(
            http.perform(get("/finance/invoices").header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceInvoicePage.class);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).invoiceNumber()).startsWith("DE-INV-");

    http.perform(get("/finance/invoices/" + row.id()).header("Authorization", "session"))
        .andExpect(status().isOk());
    http.perform(
            get("/finance/invoices/" + UUID.randomUUID() + "/events")
                .header("Authorization", "session"))
        .andExpect(status().isNotFound());
  }
}
