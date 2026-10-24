package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.TaxRateModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
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
 * 12.6.1.1 税率配置的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p><b>刻意跑在本模块自己的两张表与本模块的端点上</b>：
 * {@code finance_tax_rate} / {@code finance_tax_shop_binding}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaxRateTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID shopA = UUID.randomUUID(), shopB = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer channel;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-finance-tax-rate-" + tenant + "-" + ordinal).getBytes());
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
        List.of(
            "finance.tax_rate.read", "finance.tax_rate.create", "finance.tax_rate.update");
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
      channel.createContext(
          "/channel/shops",
          e -> {
            byte[] body = "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
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
          "truncate table finance_tax_shop_binding, finance_tax_rate restart identity cascade");
    }
    seedRate(a, idFor(a, 1), "0.19", "A 的税率");
    seedRate(b, idFor(b, 1), "0.20", "B 的税率");
    seedBinding(a, idFor(a, 2), shopA, idFor(a, 1), "A 的绑定");
    seedBinding(b, idFor(b, 2), shopB, idFor(b, 1), "B 的绑定");
  }

  /** 在可信租户事务里插一版税率（第一版）。 */
  void seedRate(UUID tenant, UUID id, String rate, String note) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_tax_rate(id,tenant_id,tax_type,jurisdiction_code,jurisdiction_name,"
                  + " country_code,tax_code,rate,effective_from,effective_to,status,version,note,"
                  + " created_at,updated_at)"
                  + " values(?,?,'VAT','EU-DE','德国','DE','STANDARD',?::numeric,"
                  + " ?::timestamptz,null,'ACTIVE',1,?,?::timestamptz,?::timestamptz)",
              id,
              tenant,
              new BigDecimal(rate),
              OffsetDateTime.parse("2026-01-01T00:00:00Z"),
              note,
              now,
              now);
          return null;
        });
  }

  /** 在可信租户事务里插一条店铺绑定（第一版）。 */
  void seedBinding(UUID tenant, UUID id, UUID shop, UUID rateId, String note) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_tax_shop_binding(id,tenant_id,shop_id,shop_code,shop_name,tax_type,"
                  + " jurisdiction_code,tax_code,effective_from,effective_to,status,version,note,"
                  + " created_at,updated_at)"
                  + " values(?,?,?,'SHOP','测试店','VAT','EU-DE','STANDARD',"
                  + " ?::timestamptz,null,'ACTIVE',1,?,?::timestamptz,?::timestamptz)",
              id,
              tenant,
              shop,
              OffsetDateTime.parse("2026-01-01T00:00:00Z"),
              note,
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读税率备注。 */
  List<String> rateNotesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select note from finance_tax_rate order by note")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读绑定备注。 */
  List<String> bindingNotesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select note from finance_tax_shop_binding order by note")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select note from finance_tax_rate").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的两张表强制rls而不是只启用() {
    for (String table : List.of("finance_tax_rate", "finance_tax_shop_binding")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V361 必须真的建出 " + table).isNotNull();
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

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(rateNotesOf(a)).containsExactly("A 的税率");
    assertThat(rateNotesOf(b)).containsExactly("B 的税率");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch("select note from finance_tax_rate where id = ?", idFor(b, 1)).size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 绑定同理。
    assertThat(bindingNotesOf(a)).containsExactly("A 的绑定");
    assertThat(bindingNotesOf(b)).containsExactly("B 的绑定");

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_税率端点只看得到本租户的数据() throws Exception {
    var aPage =
        json.readValue(
            http.perform(get("/finance/tax-rates/rates").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceTaxRatePage.class);
    assertThat(aPage.items()).hasSize(1);
    assertThat(aPage.items().get(0).note()).isEqualTo("A 的税率");

    var bPage =
        json.readValue(
            http.perform(get("/finance/tax-rates/rates").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceTaxRatePage.class);
    assertThat(bPage.items()).hasSize(1);
    assertThat(bPage.items().get(0).note()).isEqualTo("B 的税率");

    // 绑定端点同理。
    var aBindings =
        json.readValue(
            http.perform(get("/finance/tax-rates/bindings").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceTaxBindingPage.class);
    assertThat(aBindings.items()).hasSize(1);
    assertThat(aBindings.items().get(0).note()).isEqualTo("A 的绑定");
  }

  @Test
  void 断言1_取税率时按生效时间只能命中本租户的那一版() throws Exception {
    // A 的店铺在 A 的租户下能取到 0.19。
    var hit =
        json.readValue(
            http.perform(
                    get("/finance/tax-rates/resolve")
                        .header("Authorization", "session-a")
                        .param("taxType", "VAT")
                        .param("shopId", shopA.toString())
                        .param("jurisdictionCode", "EU-DE")
                        .param("asOf", "2026-03-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceTaxResolution.class);
    assertThat(hit.rate()).isEqualByComparingTo("0.19");

    // A 的会话查 B 的店铺 ⇒ 命中不到（B 的绑定不在 A 的租户下）。
    http.perform(
            get("/finance/tax-rates/resolve")
                .header("Authorization", "session-a")
                .param("taxType", "VAT")
                .param("shopId", shopB.toString())
                .param("jurisdictionCode", "EU-DE")
                .param("asOf", "2026-03-01T00:00:00Z"))
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
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into finance_tax_rate(id,tenant_id,tax_type,jurisdiction_code,jurisdiction_name,"
                        + " country_code,tax_code,rate,effective_from,effective_to,status,version,note,"
                        + " created_at,updated_at)"
                        + " values(?,?,'VAT','EU-DE','德国','DE','STANDARD',0.19,"
                        + " ?::timestamptz,null,'ACTIVE',1,'伪造',?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select note from finance_tax_rate where note = '伪造'").size()).isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var body =
        """
        {"taxType":"VAT","jurisdictionCode":"EU-DE","taxCode":"STANDARD","rate":0.19,
         "effectiveFrom":"2026-02-01T00:00:00Z"}
        """;

    http.perform(
            post("/finance/tax-rates/rates")
                .header("Authorization", "session-a")
                // 伪造的租户头与内部键：代理与服务层都必须忽略它们。
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉）。
    var rows =
        tenants.run(
            a,
            () -> sql.fetch("select note, tenant_id from finance_tax_rate order by note"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    var forged =
        rows.stream()
            .filter(r -> r.get("note", String.class).startsWith("测试") || r.get("note", String.class).isEmpty())
            .findFirst()
            .orElse(null);
    assertThat(forged).as("新建的那一行必须存在").isNotNull();
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(forged.get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它，b 的数据仍然是 b 自己的。
    assertThat(rateNotesOf(b)).containsExactly("B 的税率");
  }

  @Test
  void 断言2_绑定也按租户隔离() {
    var aBindings =
        tenants.run(
            a,
            () ->
                sql.fetch("select note from finance_tax_shop_binding")
                    .map(r -> r.get(0, String.class)));
    assertThat(aBindings).containsExactly("A 的绑定");

    var bBindings =
        tenants.run(
            b,
            () ->
                sql.fetch("select note from finance_tax_shop_binding")
                    .map(r -> r.get(0, String.class)));
    assertThat(bBindings).as("B 看不见 A 的绑定").containsExactly("B 的绑定");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(rateNotesOf(a)).as("第 " + round + " 轮：A 只能看见 A 的税率").containsExactly("A 的税率");
      assertThat(rateNotesOf(b)).as("第 " + round + " 轮：B 只能看见 B 的税率").containsExactly("B 的税率");
      assertThat(bindingNotesOf(a)).as("第 " + round + " 轮：A 只能看见 A 的绑定").containsExactly("A 的绑定");
      assertThat(bindingNotesOf(b)).as("第 " + round + " 轮：B 只能看见 B 的绑定").containsExactly("B 的绑定");
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(rateNotesOf(a)).containsExactly("A 的税率");
  }
}
