package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AiUsageQuotaModels.*;
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
 * 18.4.3.1「使用与配额」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * {@code platform_ai_model} / {@code platform_ai_usage_record} / {@code platform_ai_quota} /
 * {@code platform_ai_quota_usage} / {@code platform_ai_sensitive_policy}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiUsageQuotaTenancyIntegrationTest {
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
          "platform_ai_model",
          "platform_ai_usage_record",
          "platform_ai_quota",
          "platform_ai_quota_usage",
          "platform_ai_sensitive_policy");

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-ai-usage-" + tenant + "-" + ordinal).getBytes());
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
        List.of("platform.ai-usage.read", "platform.ai-usage.record", "platform.ai-usage.update");
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
          "truncate table platform_ai_sensitive_policy, platform_ai_quota_usage, platform_ai_quota,"
              + " platform_ai_usage_record, platform_ai_model, platform_app_audit_event,"
              + " platform_app_quota_usage, platform_app_quota, platform_app_ip_allowlist,"
              + " platform_app_scope, platform_app_credential, platform_app restart identity cascade");
    }
    seedTenant(a, "model-a");
    seedTenant(b, "model-b");
  }

  /** 在可信租户事务里插一个模型 + 一条用量 + 一条配额 + 一条策略。 */
  void seedTenant(UUID tenant, String marker) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_ai_model(id,tenant_id,model_code,model_name,provider,unit,"
                  + " unit_cost,currency,context_limit,enabled,note,created_at,updated_at)"
                  + " values(?,?,?,?,'','REQUEST',0.5,'USD',0,true,'',?::timestamptz,?::timestamptz)",
              idFor(tenant, 1),
              tenant,
              marker + "-model",
              marker + "-name",
              now,
              now);
          sql.execute(
              "insert into platform_ai_usage_record(id,tenant_id,membership_id,capability_code,"
                  + " feature_code,model_id,model_code,unit,quantity,unit_cost,cost,currency,"
                  + " request_ref,occurred_at,created_at)"
                  + " values(?,?,?,'TEXT_TRANSLATION','',?,?,'REQUEST',1,0.5,0.5,'USD','',"
                  + " ?::timestamptz,?::timestamptz)",
              idFor(tenant, 2),
              tenant,
              memberA,
              idFor(tenant, 1),
              marker + "-model",
              now,
              now);
          sql.execute(
              "insert into platform_ai_quota(id,tenant_id,scope,membership_id,capability_code,"
                  + " limit_count,limit_cost,period,enabled,note,created_at,updated_at)"
                  + " values(?,?,'TENANT',null,'TEXT_TRANSLATION',10,null,'DAY',true,'',"
                  + " ?::timestamptz,?::timestamptz)",
              idFor(tenant, 3),
              tenant,
              now,
              now);
          sql.execute(
              "insert into platform_ai_sensitive_policy(id,tenant_id,capability_code,model_code,"
                  + " action,enabled,note,created_at,updated_at)"
                  + " values(?,?,'TEXT_TRANSLATION',?,'BLOCK',true,'',?::timestamptz,?::timestamptz)",
              idFor(tenant, 4),
              tenant,
              marker + "-model",
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读模型目录。 */
  List<String> modelsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select model_code from platform_ai_model order by model_code")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读用量台账。 */
  List<String> recordsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select request_ref from platform_ai_usage_record order by request_ref")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读策略。 */
  List<String> policiesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select model_code from platform_ai_sensitive_policy order by model_code")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select model_code from platform_ai_model").size();
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
      assertThat(row).as("V425 必须真的建出 " + table).isNotNull();
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
  void 用量台账只授读写不授改删() {
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('platform_app','platform_ai_usage_record','UPDATE')")
                .get(0, Boolean.class))
        .as("只追加台账不该给 UPDATE")
        .isFalse();
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('platform_app','platform_ai_usage_record','DELETE')")
                .get(0, Boolean.class))
        .as("只追加台账不该给 DELETE")
        .isFalse();
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('platform_app','platform_ai_usage_record','INSERT')")
                .get(0, Boolean.class))
        .as("台账必须能写")
        .isTrue();
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(modelsOf(a)).containsExactly("model-a-model");
    assertThat(modelsOf(b)).containsExactly("model-b-model");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select model_code from platform_ai_model where id = ?", idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 台账 / 配额 / 策略同理。
    assertThat(recordsOf(a)).hasSize(1);
    assertThat(recordsOf(b)).hasSize(1);
    assertThat(policiesOf(a)).containsExactly("model-a-model");
    assertThat(policiesOf(b)).containsExactly("model-b-model");
    assertThat(tenants.run(a, () -> sql.fetch("select id from platform_ai_quota")).size())
        .isEqualTo(1);

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aPage =
        json.readValue(
            http.perform(get("/platform/ai/usage/models").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiModelRow[].class);
    assertThat(aPage).hasSize(1);
    assertThat(aPage[0].modelCode()).isEqualTo("model-a-model");

    var bPage =
        json.readValue(
            http.perform(get("/platform/ai/usage/models").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiModelRow[].class);
    assertThat(bPage).hasSize(1);
    assertThat(bPage[0].modelCode()).isEqualTo("model-b-model");
  }

  @Test
  void 断言1_配额计数也按租户隔离() {
    var aCount =
        tenants.run(
            a,
            () ->
                sql.fetchOne("select count(*) from platform_ai_quota_usage").get(0, Long.class));
    var bCount =
        tenants.run(
            b,
            () ->
                sql.fetchOne("select count(*) from platform_ai_quota_usage").get(0, Long.class));
    assertThat(aCount).isZero();
    assertThat(bCount).isZero();
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
                    "insert into platform_ai_model(id,tenant_id,model_code,model_name,provider,unit,"
                        + " unit_cost,currency,context_limit,enabled,note,created_at,updated_at)"
                        + " values(?,?,'forged','Forged','','REQUEST',1,'USD',0,true,'',"
                        + " ?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select model_code from platform_ai_model where model_code = 'forged'").size())
        .isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    http.perform(
            post("/platform/ai/usage/records")
                .header("Authorization", "session-a")
                // 伪造的租户头与内部键：代理与服务层都必须忽略它们。
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"capabilityCode":"TEXT_TRANSLATION","modelCode":"model-a-model","quantity":"1",
                     "requestRef":"forged-header"}
                    """))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉）。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select request_ref, tenant_id, membership_id from platform_ai_usage_record"
                        + " order by request_ref"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    var forged =
        rows.stream()
            .filter(r -> "forged-header".equals(r.get("request_ref", String.class)))
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
    assertThat(recordsOf(b)).hasSize(1);
    assertThat(modelsOf(b)).containsExactly("model-b-model");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(modelsOf(a)).as("第 " + round + " 轮：A 只能看见 A 的模型").containsExactly("model-a-model");
      assertThat(modelsOf(b)).as("第 " + round + " 轮：B 只能看见 B 的模型").containsExactly("model-b-model");
      assertThat(recordsOf(a)).as("第 " + round + " 轮：A 只能看见 A 的台账").hasSize(1);
      assertThat(recordsOf(b)).as("第 " + round + " 轮：B 只能看见 B 的台账").hasSize(1);
      assertThat(policiesOf(a)).as("第 " + round + " 轮：A 只能看见 A 的策略").containsExactly("model-a-model");
      assertThat(policiesOf(b)).as("第 " + round + " 轮：B 只能看见 B 的策略").containsExactly("model-b-model");
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(modelsOf(a)).containsExactly("model-a-model");
    assertThat(modelsOf(b)).containsExactly("model-b-model");
  }
}
