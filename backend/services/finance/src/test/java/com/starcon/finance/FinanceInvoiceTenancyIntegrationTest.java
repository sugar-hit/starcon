package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.InvoiceModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
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
 * 12.6.2.1 发票管理的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，
 *       不是只断言 A 看不见（只断言「看不见」的话，一句「写入被全表拒掉」也能骗过它）；
 *   <li>连接池归还后不串租户。
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>刻意跑在本模块自己的六张表与本模块的端点上</b>：
 * {@code finance_invoice_template} / {@code finance_invoice_sequence} /
 * {@code finance_invoice} / {@code finance_invoice_line} /
 * {@code finance_invoice_event} / {@code finance_invoice_platform_submission}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FinanceInvoiceTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer channel;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-finance-invoice-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<String> permissions) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    var permissions =
        List.of("finance.invoice.read", "finance.invoice.create", "finance.invoice.update");
    actors.put("session-a", actor(UUID.randomUUID(), a, permissions));
    actors.put("session-b", actor(UUID.randomUUID(), b, permissions));
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
          "/channel/tax-registration/jurisdictions",
          e -> {
            var payload =
                """
                {"items":[{"code":"EU-DE","name":"德国","region":"EUROPEAN_UNION","countryCode":"DE",
                           "subdivisionCode":"","taxRegime":"VAT","vatRequired":true,"requiresEpr":true,
                           "defaultFilingCycle":"MONTHLY","derivationScope":"COUNTRY"}],
                 "total":1,"note":"channel 16.3.1.1"}
                """;
            byte[] body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_invoice_event, finance_invoice_platform_submission,"
              + " finance_invoice_line, finance_invoice, finance_invoice_sequence,"
              + " finance_invoice_template restart identity cascade");
    }
    seedInvoice(a, idFor(a, 1), "A 的发票", "INV-A-000001");
    seedInvoice(b, idFor(b, 1), "B 的发票", "INV-B-000001");
  }

  /** 在可信租户事务里插一张发票（模板 + 发票 + 明细行 + 留痕）。 */
  void seedInvoice(UUID tenant, UUID id, String note, String number) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          var templateId = idFor(tenant, 90);
          sql.execute(
              "insert into finance_invoice_template(id,tenant_id,template_code,template_name,country_code,"
                  + " jurisdiction_code,jurisdiction_name,platform_code,document_type,tax_type,tax_code,"
                  + " currency,title,sequence_prefix,sequence_scope,sequence_scope_value,sequence_period,"
                  + " effective_from,effective_to,status,note,created_at,updated_at)"
                  + " values(?,?,?,'测试模板','DE','EU-DE','德国','AMAZON','INVOICE','VAT','STANDARD',"
                  + " 'EUR','示例','INV','COUNTRY','DE','MONTHLY',?::timestamptz,null,'ACTIVE','',"
                  + " ?::timestamptz,?::timestamptz)",
              templateId,
              tenant,
              "TPL-" + tenant.toString().substring(0, 4),
              OffsetDateTime.parse("2026-01-01T00:00:00Z"),
              now,
              now);
          sql.execute(
              "insert into finance_invoice(id,tenant_id,invoice_number,document_type,status,template_id,"
                  + " template_code,title,country_code,jurisdiction_code,jurisdiction_name,platform_code,"
                  + " shop_code,source_order_no,customer_name,currency,issue_date,subtotal_amount,"
                  + " tax_amount,total_amount,reason,issued_by,issued_at,created_by,created_at,updated_at)"
                  + " values(?,?,?,'INVOICE','ISSUED',?,'TPL','示例','DE','EU-DE','德国','AMAZON',"
                  + " '','SO-1','买家','EUR',?::date,10.0000,1.9000,11.9000,'',?,?::timestamptz,?,"
                  + " ?::timestamptz,?::timestamptz)",
              id,
              tenant,
              number,
              templateId,
              LocalDate.parse("2026-03-01"),
              UUID.randomUUID(),
              now,
              UUID.randomUUID(),
              now,
              now);
          sql.execute(
              "insert into finance_invoice_line(id,tenant_id,invoice_id,line_no,sku_code,product_name,"
                  + " quantity,unit_price,tax_type,tax_code,tax_rate,tax_rate_id,note,created_at)"
                  + " values(?,?,?,1,'SKU-1','商品',1.000000,10.0000,'VAT','STANDARD',0.190000,?,?,"
                  + " ?::timestamptz)",
              idFor(tenant, 2),
              tenant,
              id,
              UUID.randomUUID(),
              note,
              now);
          sql.execute(
              "insert into finance_invoice_event(id,tenant_id,invoice_id,event_type,from_status,to_status,"
                  + " reason,actor_id,occurred_at,detail,created_at)"
                  + " values(?,?,?,'ISSUED','DRAFT','ISSUED','',?,?::timestamptz,'{}'::jsonb,?::timestamptz)",
              idFor(tenant, 3),
              tenant,
              id,
              UUID.randomUUID(),
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读发票备注。 */
  List<String> notesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () -> sql.fetch("select note from finance_invoice_line order by note").map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读发票号。 */
  List<String> numbersOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select invoice_number from finance_invoice order by invoice_number")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select note from finance_invoice_line").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的六张表强制rls而不是只启用() {
    for (String table :
        List.of(
            "finance_invoice_template",
            "finance_invoice_sequence",
            "finance_invoice",
            "finance_invoice_line",
            "finance_invoice_event",
            "finance_invoice_platform_submission")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V366 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('finance_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("finance_app 必须真的能读 " + table)
          .isTrue();
    }
  }

  @Test
  void 状态记录表是append_only_运行角色改不动() {
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('finance_app','finance_invoice_event','UPDATE')")
                .get(0, Boolean.class))
        .as("留痕表的 UPDATE 必须不授予运行角色")
        .isFalse();
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('finance_app','finance_invoice_event','DELETE')")
                .get(0, Boolean.class))
        .as("留痕表的 DELETE 必须不授予运行角色")
        .isFalse();
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(notesOf(a)).containsExactly("A 的发票");
    assertThat(notesOf(b)).containsExactly("B 的发票");
    assertThat(numbersOf(a)).containsExactly("INV-A-000001");
    assertThat(numbersOf(b)).containsExactly("INV-B-000001");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch("select note from finance_invoice_line where id = ?", idFor(b, 2)).size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_发票端点只看得到本租户的数据() throws Exception {
    var aPage =
        json.readValue(
            http.perform(get("/finance/invoices").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceInvoicePage.class);
    assertThat(aPage.items()).hasSize(1);
    assertThat(aPage.items().get(0).invoiceNumber()).isEqualTo("INV-A-000001");

    var bPage =
        json.readValue(
            http.perform(get("/finance/invoices").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceInvoicePage.class);
    assertThat(bPage.items()).hasSize(1);
    assertThat(bPage.items().get(0).invoiceNumber()).isEqualTo("INV-B-000001");

    // A 的会话按 id 查 B 的发票 ⇒ 404（不是 403：不该存在的就不该被看见）
    http.perform(get("/finance/invoices/" + idFor(b, 1)).header("Authorization", "session-a"))
        .andExpect(status().isNotFound());
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() {
    // 事务外没有任何租户上下文：伪造一个 tenant_id 往里写，策略直接拒绝。
    UUID forged = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    // ⚠️ 用 **finance_invoice_template**（没有业务守卫的那张表）做这次断言：
    //   明细行表还有业务守卫（"明细行必须挂在一张本租户的发票上"）会**先**拒掉写入 ——
    //   那样这条断言就变成「被业务规则挡住」，证明不了 RLS 生效。
    //   模板表上唯一能拒掉它的就是 RLS 策略本身。
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into finance_invoice_template(id,tenant_id,template_code,template_name,"
                        + " country_code,jurisdiction_code,jurisdiction_name,platform_code,document_type,"
                        + " tax_type,tax_code,currency,title,sequence_prefix,sequence_scope,"
                        + " sequence_scope_value,sequence_period,effective_from,effective_to,status,note,"
                        + " created_at,updated_at)"
                        + " values(?,?,?,'伪造','DE','EU-DE','德国','AMAZON','INVOICE','VAT','STANDARD',"
                        + " 'EUR','示例','FORGED','COUNTRY','DE','MONTHLY',?::timestamptz,null,'ACTIVE','',"
                        + " ?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    "FORGED-" + forged.toString().substring(0, 4),
                    OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select template_code from finance_invoice_template").size()).isZero();

    // 明细行表同样被挡住（有业务守卫先挡，但结论一样：伪造的租户写不进去）。
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into finance_invoice_line(id,tenant_id,invoice_id,line_no,sku_code,"
                        + " product_name,quantity,unit_price,tax_type,tax_code,tax_rate,tax_rate_id,"
                        + " note,created_at)"
                        + " values(?,?,?,1,'SKU-X','伪造',1.000000,10.0000,'VAT','STANDARD',0.190000,?,"
                        + " '伪造',?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    idFor(a, 1),
                    UUID.randomUUID(),
                    now))
        .as("没有可信租户上下文时，明细行也写不进去");
    assertThat(sql.fetch("select note from finance_invoice_line where note = '伪造'").size()).isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var body =
        """
        {"templateCode":"FORGED","templateName":"伪造的模板","countryCode":"DE",
         "jurisdictionCode":"EU-DE","platformCode":"SHOPIFY","documentType":"INVOICE",
         "taxType":"VAT","taxCode":"STANDARD","currency":"EUR","title":"示例",
         "sequencePrefix":"FORGED","sequenceScope":"COUNTRY","sequencePeriod":"MONTHLY",
         "effectiveFrom":"2026-02-01T00:00:00Z"}
        """;

    http.perform(
            post("/finance/invoices/templates")
                .header("Authorization", "session-a")
                // 伪造的租户头与内部键：代理与服务层都必须忽略它们。
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉）：夹具那张 + 伪造头建出来的那张。
    var rows =
        tenants.run(
            a,
            () -> sql.fetch("select template_code, tenant_id from finance_invoice_template order by template_code"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    // ...且新那张落在**会话租户** a 名下，**不是**调用方自称的 b。
    var forgedRow =
        rows.stream()
            .filter(r -> "FORGED".equals(r.get("template_code", String.class)))
            .findFirst()
            .orElseThrow();
    assertThat(forgedRow.get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它（b 只看得见自己夹具里那一张）。
    var bTemplates =
        tenants.run(
            b,
            () ->
                sql.fetch("select template_code from finance_invoice_template").map(r -> r.get(0, String.class)));
    assertThat(bTemplates).as("B 看不见 A 新建的模板").doesNotContain("FORGED");
    assertThat(bTemplates).as("B 只看得见自己那一张").hasSize(1);
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(notesOf(a)).as("第 " + round + " 轮：A 只能看见 A 的发票").containsExactly("A 的发票");
      assertThat(notesOf(b)).as("第 " + round + " 轮：B 只能看见 B 的发票").containsExactly("B 的发票");
      assertThat(numbersOf(a)).as("第 " + round + " 轮：A 只能看见 A 的发票号").containsExactly("INV-A-000001");
      assertThat(numbersOf(b)).as("第 " + round + " 轮：B 只能看见 B 的发票号").containsExactly("INV-B-000001");
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(notesOf(a)).containsExactly("A 的发票");
  }
}
