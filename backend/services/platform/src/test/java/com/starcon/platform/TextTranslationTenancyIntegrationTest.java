package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.TextTranslationModels.*;
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
 * 18.4.2.1「文本与翻译」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p><b>刻意跑在本模块自己的七张表与本模块的端点上</b>：
 * {@code platform_text_glossary} / {@code platform_text_banned_term} / {@code platform_text_rule} /
 * {@code platform_text_template} / {@code platform_text_task} / {@code platform_text_draft} /
 * {@code platform_text_check}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TextTranslationTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的七张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_text_glossary",
          "platform_text_banned_term",
          "platform_text_rule",
          "platform_text_template",
          "platform_text_task",
          "platform_text_draft",
          "platform_text_check");

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-text-" + tenant + "-" + ordinal).getBytes());
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
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    var permissions =
        List.of(
            "platform.text.read",
            "platform.text.create",
            "platform.text.update",
            "platform.text.confirm",
            "platform.text.publish",
            "platform.text.reject");
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
          "truncate table platform_text_check, platform_text_draft, platform_text_task,"
              + " platform_text_template, platform_text_rule, platform_text_banned_term,"
              + " platform_text_glossary, platform_app_audit_event, platform_app_quota_usage,"
              + " platform_app_quota, platform_app_ip_allowlist, platform_app_scope,"
              + " platform_app_credential, platform_app restart identity cascade");
      s.execute("alter sequence platform_text_task_no_seq restart with 1");
    }
    seedTenant(a, "glossary-a");
    seedTenant(b, "glossary-b");
  }

  /** 在可信租户事务里插一条术语 + 一条违禁词 + 一条任务。 */
  void seedTenant(UUID tenant, String marker) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_text_glossary(id,tenant_id,source_term,target_term,term_type,"
                  + " target_locale,case_sensitive,enabled,note,created_at,updated_at)"
                  + " values(?,?,?,'Term','BRAND','',false,true,'',?::timestamptz,?::timestamptz)",
              idFor(tenant, 1),
              tenant,
              marker,
              now,
              now);
          sql.execute(
              "insert into platform_text_banned_term(id,tenant_id,term,category,severity,target_locale,"
                  + " enabled,note,created_at,updated_at)"
                  + " values(?,?,?,'ABSOLUTE','BLOCK','',true,'',?::timestamptz,?::timestamptz)",
              idFor(tenant, 2),
              tenant,
              marker + "-banned",
              now,
              now);
          sql.execute(
              "insert into platform_text_task(id,tenant_id,task_no,content_type,source_text,"
                  + " source_locale,target_locale,style_requirement,upstream_ref_type,status,"
                  + " created_at,updated_at)"
                  + " values(?,?,?,'DESCRIPTION',?,'en-US','de','','','PENDING',"
                  + " ?::timestamptz,?::timestamptz)",
              idFor(tenant, 3),
              tenant,
              "TX-" + tenant.toString().substring(0, 8) + "-900001",
              marker + "-source",
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读术语表。 */
  List<String> glossaryOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select source_term from platform_text_glossary order by source_term")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读任务单号。 */
  List<String> taskNosOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select task_no from platform_text_task order by task_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select source_term from platform_text_glossary").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的七张表强制rls而不是只启用() {
    for (String table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V380 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('platform_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("platform_app 必须真的能读 " + table)
          .isTrue();
      // 每张表都必须有 tenant_id NOT NULL。
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
  void 断言1_两租户互不可见() {
    assertThat(glossaryOf(a)).containsExactly("glossary-a");
    assertThat(glossaryOf(b)).containsExactly("glossary-b");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select source_term from platform_text_glossary where id = ?", idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 违禁词 / 任务同理。
    assertThat(tenants.run(a, () -> sql.fetch("select id from platform_text_banned_term")).size())
        .isEqualTo(1);
    assertThat(tenants.run(a, () -> sql.fetch("select id from platform_text_task")).size()).isEqualTo(1);
    assertThat(
            tenants.run(
                a,
                () ->
                    sql.fetch("select id from platform_text_task where id = ?", idFor(b, 3)).size()))
        .as("按主键直查对方租户的任务也必须被挡")
        .isZero();

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aPage =
        json.readValue(
            http.perform(get("/platform/text/glossary").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextGlossaryRow[].class);
    assertThat(aPage).hasSize(1);
    assertThat(aPage[0].sourceTerm()).isEqualTo("glossary-a");

    var bPage =
        json.readValue(
            http.perform(get("/platform/text/glossary").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            TextGlossaryRow[].class);
    assertThat(bPage).hasSize(1);
    assertThat(bPage[0].sourceTerm()).isEqualTo("glossary-b");
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
                    "insert into platform_text_glossary(id,tenant_id,source_term,target_term,"
                        + " term_type,target_locale,case_sensitive,enabled,note,created_at,updated_at)"
                        + " values(?,?,'forged','Forged','BRAND','',false,true,'',"
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
    assertThat(sql.fetch("select source_term from platform_text_glossary where source_term = 'forged'").size())
        .isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var body =
        """
        {"contentType":"DESCRIPTION","sourceText":"forged-header","sourceLocale":"en-US","targetLocale":"de"}
        """;

    http.perform(
            post("/platform/text/tasks")
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
            () ->
                sql.fetch(
                    "select task_no, source_text, tenant_id from platform_text_task"
                        + " order by task_no"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    var forged =
        rows.stream()
            .filter(r -> "forged-header".equals(r.get("source_text", String.class)))
            .findFirst()
            .orElse(null);
    assertThat(forged).as("新建的那一行必须存在").isNotNull();
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(forged.get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它，b 的数据仍然是 b 自己的。
    assertThat(taskNosOf(b)).hasSize(1);
    assertThat(glossaryOf(b)).containsExactly("glossary-b");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(glossaryOf(a)).as("第 " + round + " 轮：A 只能看见 A 的术语").containsExactly("glossary-a");
      assertThat(glossaryOf(b)).as("第 " + round + " 轮：B 只能看见 B 的术语").containsExactly("glossary-b");
      assertThat(tenants.run(a, () -> sql.fetch("select id from platform_text_task")).size())
          .as("第 " + round + " 轮：A 只能看见 A 的任务")
          .isEqualTo(1);
      assertThat(tenants.run(b, () -> sql.fetch("select id from platform_text_task")).size())
          .as("第 " + round + " 轮：B 只能看见 B 的任务")
          .isEqualTo(1);
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(glossaryOf(a)).containsExactly("glossary-a");
    assertThat(glossaryOf(b)).containsExactly("glossary-b");
  }
}
