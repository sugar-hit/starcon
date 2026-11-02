package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.SmartQueryModels.SmartQueryResult;
import com.starcon.tenancy.TenantTransactions;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
 * 18.4.1.2「智能查询」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，
 *       不是只断言 A 看不见；
 *   <li>连接池归还后不串租户。
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p>🔴 **语义层词表本身也是租户隔离的**：它按租户播种（每个租户一份），
 * 所以 A 租户看不到 B 租户登记的词表行。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SmartQueryTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static SmartQueryTestStub stub;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的四张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_smart_query_metric",
          "platform_smart_query_dimension",
          "platform_smart_query",
          "platform_smart_query_evidence");

  static AccessService.Access actor(UUID tenant) {
    var permissions = SmartQueryTestStub.permissions();
    return new AccessService.Access(
        UUID.randomUUID(),
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
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      stub = new SmartQueryTestStub();
      stub.start();
      stub.actor("session-a", actor(a));
      stub.actor("session-b", actor(b));
      // 🔴 上游按 bearer 返回**各自租户**的事实 ⇒ 「结果隔离」是可证伪的。
      stub.facts(
          "session-a",
          new SmartQueryTestStub.TenantFacts(
              "aaaaaaaa-0000-0000-0000-000000000001",
              "北美一店",
              "aaaaaaaa-0000-0000-0000-000000000002",
              "北美二店",
              "aaaaaaaa-0000-0000-0000-000000000003",
              "北美三店",
              "USD",
              "120.50",
              "80.25"));
      stub.facts(
          "session-b",
          new SmartQueryTestStub.TenantFacts(
              "bbbbbbbb-0000-0000-0000-000000000001",
              "欧洲一店",
              "bbbbbbbb-0000-0000-0000-000000000002",
              "欧洲二店",
              "bbbbbbbb-0000-0000-0000-000000000003",
              "欧洲三店",
              "EUR",
              "999.99",
              "111.11"));
      stub.registered("session-a", SmartQueryTestStub.standardMetrics());
      stub.registered("session-b", SmartQueryTestStub.standardMetrics());
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "platform_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    // 连接池压到 1：只有复用同一条连接，才能真正证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> stub.url());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("REPORTING_URL", () -> stub.url());
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_smart_query_evidence, platform_smart_query,"
              + " platform_smart_query_dimension, platform_smart_query_metric,"
              + " platform_app_audit_event, platform_app_quota_usage, platform_app_quota,"
              + " platform_app_ip_allowlist, platform_app_scope, platform_app_credential,"
              + " platform_app restart identity cascade");
    }
  }

  // ================================================================ 夹具

  private SmartQueryResult ask(String bearer, String body) throws Exception {
    var response =
        http.perform(
                post("/platform/smart-query/query")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, SmartQueryResult.class);
  }

  /** 以指定租户身份读查询台账的问句。 */
  List<String> questionsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select question_text from platform_smart_query order by created_at")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读明细依据的事实引用。 */
  List<String> factRefsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select fact_ref from platform_smart_query_evidence order by seq")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_smart_query").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的四张表强制rls而不是只启用() {
    for (String table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V441 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('platform_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("platform_app 必须真的能读 " + table)
          .isTrue();
      var nullable =
          sql.fetchOne(
                  "select is_nullable from information_schema.columns where table_name = ? and column_name = 'tenant_id'",
                  table)
              .get(0, String.class);
      assertThat(nullable).as(table + " 的 tenant_id 必须是 NOT NULL").isEqualTo("NO");
    }
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() throws Exception {
    ask("session-a", "{\"question\":\"上周各店铺的销售额排名\"}");
    ask("session-b", "{\"question\":\"上周各店铺的销售额排名\"}");

    assertThat(questionsOf(a)).hasSize(1);
    assertThat(questionsOf(b)).hasSize(1);

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var bQueryId =
        tenants.run(
            b, () -> sql.fetchOne("select id from platform_smart_query").get(0, UUID.class));
    var byId =
        tenants.run(
            a,
            () -> sql.fetch("select id from platform_smart_query where id = ?", bQueryId).size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 明细依据同理。
    assertThat(factRefsOf(a)).isNotEmpty();
    assertThat(factRefsOf(b)).isNotEmpty();
    assertThat(factRefsOf(a)).doesNotContainAnyElementsOf(factRefsOf(b));

    // 语义层词表也是**按租户分**的：两边各一份，互不可见。
    assertThat(
            tenants.run(
                a, () -> sql.fetch("select metric_code from platform_smart_query_metric").size()))
        .isEqualTo(SmartQueryTestStub.standardMetrics().size());
    assertThat(
            tenants.run(
                b, () -> sql.fetch("select metric_code from platform_smart_query_metric").size()))
        .isEqualTo(SmartQueryTestStub.standardMetrics().size());

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_结果本身也按租户隔离() throws Exception {
    var resultA = ask("session-a", "{\"question\":\"上周各店铺的销售额排名\"}");
    var resultB = ask("session-b", "{\"question\":\"上周各店铺的销售额排名\"}");

    assertThat(resultA.evidence()).isNotEmpty();
    for (var evidence : resultA.evidence()) {
      assertThat(evidence.factRef()).contains("aaaaaaaa-");
    }
    for (var evidence : resultB.evidence()) {
      assertThat(evidence.factRef()).contains("bbbbbbbb-");
    }
    // 币种也不串（A 是 USD、B 是 EUR）。
    assertThat(resultA.evidence().get(0).metricUnit()).isEqualTo("USD");
    assertThat(resultB.evidence().get(0).metricUnit()).isEqualTo("EUR");
  }

  @Test
  void 断言1_端点只看得到本租户的查询() throws Exception {
    var resultA = ask("session-a", "{\"question\":\"上周各店铺的销售额排名\"}");
    ask("session-b", "{\"question\":\"上周各店铺的销售额排名\"}");

    var own =
        http.perform(
                get("/platform/smart-query/queries/" + resultA.queryId())
                    .header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(json.readValue(own, SmartQueryModels.SmartQueryQueryView.class).queryId())
        .isEqualTo(resultA.queryId());

    // 🔴 **B 拿 A 的查询 id 也读不到**（RLS 直接挡住，不是 403 也不是空列表）。
    http.perform(
            get("/platform/smart-query/queries/" + resultA.queryId())
                .header("Authorization", "session-b"))
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
                    "insert into platform_smart_query(id,tenant_id,owner_user_id,question_text,"
                        + "match_mode,period_code,period_from,period_to,created_at,updated_at)"
                        + " values(?,?,?,?,?,?,current_date,current_date,?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    UUID.randomUUID(),
                    "伪造的租户",
                    "KEYWORD",
                    "LAST_30_DAYS",
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因在 cause 里。
        .satisfiesAnyOf(
            e -> assertThat(e).hasStackTraceContaining("row-level security"),
            e -> assertThat(e).hasStackTraceContaining("缺少租户上下文"));

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select id from platform_smart_query").size()).isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var result =
        http.perform(
                post("/platform/smart-query/query")
                    .header("Authorization", "session-a")
                    // 伪造的租户头与内部键：代理与服务层都必须忽略它们。
                    .header("X-Tenant-Id", b.toString())
                    .header("X-Internal-Key", "forged")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"上周各店铺的销售额排名\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var parsed = json.readValue(result, SmartQueryResult.class);

    // 行确实存在（不是被全表拒掉）。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select question_text, tenant_id from platform_smart_query order by created_at"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(1);
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(rows.get(0).get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);
    assertThat(rows.get(0).get("question_text", String.class)).isEqualTo("上周各店铺的销售额排名");

    // 租户 b 看不到它。
    assertThat(questionsOf(b)).isEmpty();
    assertThat(questionsOf(a)).hasSize(1);

    // 🔴 结果本身也仍然落在 a 的事实口径上（伪造头没有把结果切到 b 的数据）。
    assertThat(parsed.evidence()).isNotEmpty();
    for (var evidence : parsed.evidence()) {
      assertThat(evidence.factRef()).contains("aaaaaaaa-");
    }
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    ask("session-a", "{\"question\":\"上周各店铺的销售额排名\"}");
    ask("session-b", "{\"question\":\"上周各店铺的销售额排名\"}");

    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(questionsOf(a)).as("第 " + round + " 轮：A 只能看见 A 的查询").hasSize(1);
      assertThat(questionsOf(b)).as("第 " + round + " 轮：B 只能看见 B 的查询").hasSize(1);
      assertThat(factRefsOf(a)).as("第 " + round + " 轮：A 只能看见 A 的依据")
          .allMatch(ref -> ref.contains("aaaaaaaa-"));
      assertThat(factRefsOf(b)).as("第 " + round + " 轮：B 只能看见 B 的依据")
          .allMatch(ref -> ref.contains("bbbbbbbb-"));
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(questionsOf(a)).hasSize(1);
    assertThat(questionsOf(b)).hasSize(1);
  }
}
