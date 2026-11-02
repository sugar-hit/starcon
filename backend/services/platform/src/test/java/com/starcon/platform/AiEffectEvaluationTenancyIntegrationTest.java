package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AiEffectEvaluationModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
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
 * 18.4.3.2「效果评估」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p><b>刻意跑在本模块自己的五张表与本模块的端点上</b>：
 * {@code platform_ai_effect_adoption} / {@code platform_ai_effect_feedback} /
 * {@code platform_ai_effect_backtest} / {@code platform_ai_effect_threshold} /
 * {@code platform_ai_effect_suggestion}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiEffectEvaluationTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID memberA = UUID.randomUUID(), memberB = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的五张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_ai_effect_adoption",
          "platform_ai_effect_feedback",
          "platform_ai_effect_backtest",
          "platform_ai_effect_threshold",
          "platform_ai_effect_suggestion");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-ai-effect-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(UUID uid, UUID tenant, UUID membership, List<String> permissions) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        membership,
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
    var permissions =
        List.of("platform.ai-effect.read", "platform.ai-effect.record", "platform.ai-effect.update");
    actors.put("session-a", actor(UUID.randomUUID(), a, memberA, permissions));
    actors.put("session-b", actor(UUID.randomUUID(), b, memberB, permissions));
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
      identity.setExecutor(Executors.newFixedThreadPool(4));
      identity.start();
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
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
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
          "truncate table platform_ai_effect_suggestion, platform_ai_effect_backtest,"
              + " platform_ai_effect_feedback, platform_ai_effect_adoption,"
              + " platform_ai_effect_threshold, platform_ai_usage_record, platform_ai_model,"
              + " platform_ai_quota_usage, platform_ai_quota, platform_ai_sensitive_policy,"
              + " platform_app_audit_event, platform_app_quota_usage, platform_app_quota,"
              + " platform_app_ip_allowlist, platform_app_scope, platform_app_credential,"
              + " platform_app restart identity cascade");
    }
    seedTenant(a, "a");
    seedTenant(b, "b");
  }

  /** 在可信租户事务里插一条采纳记录 + 一条有用性反馈 + 一条阈值。 */
  void seedTenant(UUID tenant, String marker) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_ai_effect_adoption(id,tenant_id,capability_code,feature_code,"
                  + " decision,artifact_type,artifact_id,artifact_version,artifact_ref,"
                  + " membership_id,edit_note,reject_reason,status,occurred_at,created_at)"
                  + " values(?,?,'TEXT_TRANSLATION','','ACCEPTED','ASSISTANT',?,1,?,?,'','',"
                  + " 'ACTIVE',?::timestamptz,?::timestamptz)",
              idFor(tenant, 1),
              tenant,
              UUID.randomUUID(),
              marker + "-ref",
              UUID.randomUUID(),
              now,
              now);
          sql.execute(
              "insert into platform_ai_effect_feedback(id,tenant_id,capability_code,feature_code,"
                  + " usefulness,reason_code,reason_text,artifact_type,artifact_ref,membership_id,"
                  + " status,occurred_at,created_at)"
                  + " values(?,?,'TEXT_TRANSLATION','','USEFUL','','','ASSISTANT',?,?,"
                  + " 'ACTIVE',?::timestamptz,?::timestamptz)",
              idFor(tenant, 2),
              tenant,
              marker + "-ref",
              UUID.randomUUID(),
              now,
              now);
          sql.execute(
              "insert into platform_ai_effect_threshold(id,tenant_id,min_calls,low_adoption_rate,"
                  + " min_feedback_count,low_useful_rate,max_mape,updated_at)"
                  + " values(?,?,20,0.2,5,0.5,0.3,?::timestamptz)",
              idFor(tenant, 3),
              tenant,
              now);
          return null;
        });
  }

  /** 以指定租户身份读采纳记录。 */
  List<String> adoptionsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select artifact_ref from platform_ai_effect_adoption order by artifact_ref")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读有用性反馈。 */
  List<String> feedbacksOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select artifact_ref from platform_ai_effect_feedback order by artifact_ref")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读阈值行数。 */
  long thresholdsOf(UUID tenant) {
    return tenants.run(
        tenant, () -> sql.fetch("select id from platform_ai_effect_threshold").size());
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select artifact_ref from platform_ai_effect_adoption").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的五张表强制rls而不是只启用() {
    for (String table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V430 必须真的建出 " + table).isNotNull();
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

  @Test
  void 事实台账不授删除() {
    for (String table : List.of("platform_ai_effect_adoption", "platform_ai_effect_feedback")) {
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('platform_app','" + table + "','DELETE')")
                  .get(0, Boolean.class))
          .as(table + " 是事实台账，不该给 DELETE")
          .isFalse();
      assertThat(
              sql.fetchOne(
                      "select has_table_privilege('platform_app','" + table + "','INSERT')")
                  .get(0, Boolean.class))
          .as(table + " 必须能写")
          .isTrue();
    }
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(adoptionsOf(a)).containsExactly("a-ref");
    assertThat(adoptionsOf(b)).containsExactly("b-ref");
    assertThat(feedbacksOf(a)).containsExactly("a-ref");
    assertThat(feedbacksOf(b)).containsExactly("b-ref");
    assertThat(thresholdsOf(a)).isEqualTo(1L);
    assertThat(thresholdsOf(b)).isEqualTo(1L);

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select artifact_ref from platform_ai_effect_adoption where id = ?",
                        idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aPage =
        json.readValue(
            http.perform(get("/platform/ai/effects/adoptions").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiAdoptionPage.class);
    assertThat(aPage.items()).hasSize(1);
    assertThat(aPage.items().get(0).artifactRef()).isEqualTo("a-ref");

    var bPage =
        json.readValue(
            http.perform(get("/platform/ai/effects/adoptions").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiAdoptionPage.class);
    assertThat(bPage.items()).hasSize(1);
    assertThat(bPage.items().get(0).artifactRef()).isEqualTo("b-ref");
  }

  @Test
  void 断言1_阈值也按租户隔离() throws Exception {
    var aThresholds =
        json.readValue(
            http.perform(get("/platform/ai/effects/thresholds").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiEffectThresholdRow.class);
    assertThat(aThresholds.configured()).as("A 登记了自己的阈值行").isTrue();

    var bThresholds =
        json.readValue(
            http.perform(get("/platform/ai/effects/thresholds").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiEffectThresholdRow.class);
    assertThat(bThresholds.configured()).isTrue();
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
                    "insert into platform_ai_effect_adoption(id,tenant_id,capability_code,"
                        + " feature_code,decision,artifact_type,artifact_ref,membership_id,status,"
                        + " occurred_at,created_at)"
                        + " values(?,?,'TEXT_TRANSLATION','','ACCEPTED','ASSISTANT','forged',"
                        + " ?::uuid,'ACTIVE',?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    UUID.randomUUID(),
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因在 cause 里。
        // 🔴 本模块的 V430 除了 RLS 之外**还有一道租户触发器兜底**（直连 SQL 绕过策略也写不进去），
        //   所以这里报的是触发器的「缺少租户上下文」而不是 RLS 的
        //   「row-level security」—— 两道闸都在，拒得更早、错报得更准。
        .hasStackTraceContaining("租户上下文");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select artifact_ref from platform_ai_effect_adoption where artifact_ref = 'forged'").size())
        .isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    http.perform(
            post("/platform/ai/effects/adoptions")
                .header("Authorization", "session-a")
                // 伪造的租户头与内部键：代理与服务层都必须忽略它们。
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"capabilityCode":"TEXT_TRANSLATION","featureCode":"","decision":"ACCEPTED",
                     "artifactType":"ASSISTANT","artifactRef":"forged-header"}
                    """
                        .replace("\n", "")))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉）。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select artifact_ref, tenant_id, membership_id from platform_ai_effect_adoption"
                        + " order by artifact_ref"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    var forged =
        rows.stream()
            .filter(r -> "forged-header".equals(r.get("artifact_ref", String.class)))
            .findFirst()
            .orElse(null);
    assertThat(forged).as("新建的那一行必须存在").isNotNull();
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(forged.get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);
    // 主体成员也来自会话，不是客户端自称的。
    assertThat(forged.get("membership_id", UUID.class)).isEqualTo(memberA);

    // 租户 b 看不到它，b 的数据仍然是 b 自己的。
    assertThat(adoptionsOf(b)).containsExactly("b-ref");
    assertThat(feedbacksOf(b)).containsExactly("b-ref");
  }

  @Test
  void 断言2_跨租户的引用挂不上() {
    // A 的上下文里写一条引用 B 的用量记录 —— 复合 FK 兜底，当场拒。
    assertThatThrownBy(
            () ->
                tenants.run(
                    a,
                    () ->
                        sql.execute(
                            "insert into platform_ai_effect_adoption(id,tenant_id,capability_code,"
                                + " feature_code,decision,artifact_type,artifact_ref,usage_record_id,"
                                + " membership_id,status,occurred_at,created_at)"
                                + " values(?,?,'TEXT_TRANSLATION','','ACCEPTED','ASSISTANT','x',"
                                + " ?::uuid,?::uuid,'ACTIVE',?::timestamptz,?::timestamptz)",
                            UUID.randomUUID(),
                            a,
                            idFor(b, 99),
                            memberA,
                            OffsetDateTime.now(ZoneOffset.UTC),
                            OffsetDateTime.now(ZoneOffset.UTC))))
        .as("引用不到本租户的调用记录 ⇒ DB 复合 FK 当场拒")
        .hasStackTraceContaining("foreign key");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(adoptionsOf(a)).as("第 " + round + " 轮：A 只能看见 A 的采纳记录").containsExactly("a-ref");
      assertThat(adoptionsOf(b)).as("第 " + round + " 轮：B 只能看见 B 的采纳记录").containsExactly("b-ref");
      assertThat(feedbacksOf(a)).as("第 " + round + " 轮：A 只能看见 A 的反馈").containsExactly("a-ref");
      assertThat(feedbacksOf(b)).as("第 " + round + " 轮：B 只能看见 B 的反馈").containsExactly("b-ref");
      assertThat(thresholdsOf(a)).as("第 " + round + " 轮：A 只能看见 A 的阈值").isEqualTo(1L);
      assertThat(thresholdsOf(b)).as("第 " + round + " 轮：B 只能看见 B 的阈值").isEqualTo(1L);
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(adoptionsOf(a)).containsExactly("a-ref");
    assertThat(adoptionsOf(b)).containsExactly("b-ref");
  }
}
