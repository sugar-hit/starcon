package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AssistantModels.AssistantAnswer;
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
 * 18.4.1.1「对话助手」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p><b>刻意跑在本模块自己的四张表与本模块的端点上</b>：
 * {@code platform_assistant_intent} / {@code platform_assistant_session} /
 * {@code platform_assistant_turn} / {@code platform_assistant_evidence}。
 *
 * <p>🔴 <b>答案本身也按租户隔离</b>：A 租户的问句查到的必须是 A 租户的事实
 * （上游按调用方自己的 bearer 返回各自的数据），B 租户既拿不到 A 的问答台账，
 * 也不可能在 A 的答案里看到 B 的事实。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssistantTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static AssistantTestStub stub;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的四张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_assistant_intent",
          "platform_assistant_session",
          "platform_assistant_turn",
          "platform_assistant_evidence");

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
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      stub = new AssistantTestStub();
      stub.start();
      stub.actor("session-a", actor(UUID.randomUUID(), a, AssistantTestStub.permissions()));
      stub.actor("session-b", actor(UUID.randomUUID(), b, AssistantTestStub.permissions()));
      // 🔴 上游按 bearer 返回**各自租户**的事实 ⇒ 「答案隔离」是可证伪的。
      stub.facts(
          "session-a",
          new AssistantTestStub.TenantFacts(
              "SHOP-A", "北美一店", "SKU-A", "蓝牙耳机", "CUST-A", "安琪", "SO-A"));
      stub.facts(
          "session-b",
          new AssistantTestStub.TenantFacts(
              "SHOP-B", "欧洲二店", "SKU-B", "咖啡机", "CUST-B", "博尔", "SO-B"));
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
    r.add("FINANCE_URL", () -> stub.url());
    r.add("TRADE_URL", () -> stub.url());
    r.add("CRM_URL", () -> stub.url());
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_assistant_evidence, platform_assistant_turn,"
              + " platform_assistant_session, platform_assistant_intent,"
              + " platform_app_audit_event, platform_app_quota_usage, platform_app_quota,"
              + " platform_app_ip_allowlist, platform_app_scope, platform_app_credential,"
              + " platform_app restart identity cascade");
    }
  }

  // ================================================================ 夹具

  private AssistantAnswer ask(String bearer, String body) throws Exception {
    var response =
        http.perform(
                post("/platform/assistant/ask")
                    .header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(response, AssistantAnswer.class);
  }

  /** 以指定租户身份读问答台账的问句。 */
  List<String> questionsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select question_text from platform_assistant_turn order by turn_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读明细依据的事实引用。 */
  List<String> factRefsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select fact_ref from platform_assistant_evidence order by seq")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_assistant_turn").size();
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
      assertThat(row).as("V438 必须真的建出 " + table).isNotNull();
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
    ask("session-a", "{\"question\":\"上周哪个店利润最差\"}");
    ask("session-b", "{\"question\":\"上周哪个店利润最差\"}");

    assertThat(questionsOf(a)).hasSize(1);
    assertThat(questionsOf(b)).hasSize(1);

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var bTurnId =
        tenants.run(
            b, () -> sql.fetchOne("select id from platform_assistant_turn").get(0, UUID.class));
    var byId =
        tenants.run(
            a,
            () -> sql.fetch("select id from platform_assistant_turn where id = ?", bTurnId).size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 明细依据同理。
    assertThat(factRefsOf(a)).isNotEmpty();
    assertThat(factRefsOf(b)).isNotEmpty();
    assertThat(factRefsOf(a)).doesNotContainAnyElementsOf(factRefsOf(b));

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的会话() throws Exception {
    var answerA = ask("session-a", "{\"question\":\"上周哪个店利润最差\"}");
    ask("session-b", "{\"question\":\"上周哪个店利润最差\"}");

    // A 读自己的会话：只有 A 的一轮。
    var own =
        http.perform(
                get("/platform/assistant/sessions/" + answerA.sessionId())
                    .header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var sessionA = json.readValue(own, AssistantModels.AssistantSessionView.class);
    assertThat(sessionA.turns()).hasSize(1);

    // 🔴 **B 拿 A 的会话 id 也读不到**（RLS 直接挡住，不是 403 也不是空列表）。
    http.perform(
            get("/platform/assistant/sessions/" + answerA.sessionId())
                .header("Authorization", "session-b"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 断言1_答案本身也按租户隔离() throws Exception {
    var answerA = ask("session-a", "{\"question\":\"上周哪个店利润最差\"}");
    var answerB = ask("session-b", "{\"question\":\"上周哪个店利润最差\"}");

    // A 的答案里只可能出现 A 租户的事实引用。
    assertThat(answerA.evidence()).isNotEmpty();
    for (var evidence : answerA.evidence()) {
      assertThat(evidence.factRef()).startsWith("SHOP-A");
      assertThat(evidence.factLabel()).contains("北美一店");
    }
    for (var evidence : answerB.evidence()) {
      assertThat(evidence.factRef()).startsWith("SHOP-B");
      assertThat(evidence.factLabel()).contains("欧洲二店");
    }
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
                    "insert into platform_assistant_session(id,tenant_id,intent_code,shop_code,"
                        + "scope_type,period_code,period_from,period_to,last_question,turn_count,"
                        + "created_at,updated_at)"
                        + " values(?,?,'FORGED','','COMPANY','LAST_30_DAYS',current_date,current_date,'',0,"
                        + "?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因在 cause 里。
        // ⚠️ 两种说法都算数：租户守卫触发器（BEFORE INSERT）先于 RLS 策略触发，
        //   所以报出来的可能是触发器那句「缺少租户上下文」，也可能是策略那句 row-level security。
        .satisfiesAnyOf(
            e -> assertThat(e).hasStackTraceContaining("row-level security"),
            e -> assertThat(e).hasStackTraceContaining("缺少租户上下文"));

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select id from platform_assistant_session").size()).isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var answer =
        http.perform(
                post("/platform/assistant/ask")
                    .header("Authorization", "session-a")
                    // 伪造的租户头与内部键：代理与服务层都必须忽略它们。
                    .header("X-Tenant-Id", b.toString())
                    .header("X-Internal-Key", "forged")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"上周哪个店利润最差\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var parsed = json.readValue(answer, AssistantAnswer.class);

    // 行确实存在（不是被全表拒掉）。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select question_text, tenant_id from platform_assistant_turn order by turn_no"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(1);
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(rows.get(0).get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);
    assertThat(rows.get(0).get("question_text", String.class)).isEqualTo("上周哪个店利润最差");

    // 租户 b 看不到它。
    assertThat(questionsOf(b)).isEmpty();
    assertThat(questionsOf(a)).hasSize(1);

    // 🔴 答案本身也仍然落在 a 的事实口径上（伪造头没有把答案切到 b 的数据）。
    assertThat(parsed.evidence()).isNotEmpty();
    for (var evidence : parsed.evidence()) {
      assertThat(evidence.factRef()).startsWith("SHOP-A");
    }
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    ask("session-a", "{\"question\":\"上周哪个店利润最差\"}");
    ask("session-b", "{\"question\":\"上周哪个店利润最差\"}");

    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(questionsOf(a)).as("第 " + round + " 轮：A 只能看见 A 的问答").hasSize(1);
      assertThat(questionsOf(b)).as("第 " + round + " 轮：B 只能看见 B 的问答").hasSize(1);
      assertThat(factRefsOf(a)).as("第 " + round + " 轮：A 只能看见 A 的依据").allMatch(r -> r.startsWith("SHOP-A"));
      assertThat(factRefsOf(b)).as("第 " + round + " 轮：B 只能看见 B 的依据").allMatch(r -> r.startsWith("SHOP-B"));
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(questionsOf(a)).hasSize(1);
    assertThat(questionsOf(b)).hasSize(1);
  }
}
