package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AiDiagnosisModels.*;
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
 * 18.4.2.3「异常诊断」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p><b>刻意跑在本模块自己的四张表与本模块的端点上</b>：
 * {@code platform_diagnosis_event} / {@code platform_diagnosis_run} /
 * {@code platform_diagnosis_conclusion} / {@code platform_diagnosis_evidence}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiDiagnosisTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的四张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_diagnosis_event",
          "platform_diagnosis_run",
          "platform_diagnosis_conclusion",
          "platform_diagnosis_evidence");

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-diagnosis-" + tenant + "-" + ordinal).getBytes());
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
            "platform.ai_diagnosis.read",
            "platform.ai_diagnosis.create",
            "platform.ai_diagnosis.update");
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
    // 上游读不到（隔离测试不关心归因内容，但**不能**让上游调用把请求拖垮）。
    r.add("REPORTING_URL", () -> "http://127.0.0.1:1");
    r.add("WAREHOUSE_URL", () -> "http://127.0.0.1:1");
    r.add("TRADE_URL", () -> "http://127.0.0.1:1");
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:1");
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
          "truncate table platform_diagnosis_evidence, platform_diagnosis_conclusion,"
              + " platform_diagnosis_run, platform_diagnosis_event restart identity cascade");
      s.execute("alter sequence platform_diagnosis_event_no_seq restart with 1");
      s.execute("alter sequence platform_diagnosis_run_no_seq restart with 1");
    }
    seedTenant(a, "evt-a");
    seedTenant(b, "evt-b");
  }

  /** 在可信租户事务里插一个异常事件 + 一次运行 + 一条结论 + 一条依据。 */
  void seedTenant(UUID tenant, String marker) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          var eventId = idFor(tenant, 1);
          sql.execute(
              "insert into platform_diagnosis_event(id,tenant_id,event_no,anomaly_type,subject_type,"
                  + " subject_ref,subject_label,occurred_at,detected_at,source,source_ref,severity,"
                  + " status,note,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,'SUBMITTED','',"
                  + " 'WARNING','OPEN','',?::timestamptz,?::timestamptz)",
              eventId,
              tenant,
              "EVT-" + marker,
              "INVENTORY",
              "SKU",
              marker + "-sku",
              marker,
              now,
              now,
              now,
              now);
          var runId = idFor(tenant, 2);
          sql.execute(
              "insert into platform_diagnosis_run(id,tenant_id,run_no,event_id,status,rule_version,"
                  + " conclusion_count,evidence_count,sources_read,sources_unavailable,note,started_at,"
                  + " finished_at) values(?,?,?,?,'COMPLETED','v1',1,1,'warehouse','','',"
                  + "?::timestamptz,?::timestamptz)",
              runId,
              tenant,
              "DG-" + marker,
              eventId,
              now,
              now);
          var conclusionId = idFor(tenant, 3);
          sql.execute(
              "insert into platform_diagnosis_conclusion(id,tenant_id,run_id,event_id,rule_code,"
                  + " rule_label,anomaly_type,severity,subject_ref,summary,detail,evidence_level,"
                  + " impact,impact_unit,impact_text,recommendation,action_ref,action_owner,created_at)"
                  + " values(?,?,?,?,?,?,'INVENTORY','WARNING',?,?,'', 'STRONG',0,'件','',"
                  + " '核对库存','', '7 库存',?::timestamptz)",
              conclusionId,
              tenant,
              runId,
              eventId,
              "INVENTORY_STOCKOUT",
              "可用库存归零",
              marker + "-sku",
              marker + " 的可用库存为 0",
              now);
          sql.execute(
              "insert into platform_diagnosis_evidence(id,tenant_id,conclusion_id,run_id,event_id,"
                  + " fact_service,fact_table,fact_field,fact_value,fact_value_text,fact_unit,"
                  + " fact_ref,snapshot_at,explanation,created_at)"
                  + " values(?,?,?,?,?,'warehouse','inventory_balance','available',0,'','件',?,"
                  + "?::timestamptz,'夹具',?::timestamptz)",
              idFor(tenant, 4),
              tenant,
              conclusionId,
              runId,
              eventId,
              marker + "-balance",
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读异常事件号。 */
  List<String> eventsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select event_no from platform_diagnosis_event order by event_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读依据引用的余额 id。 */
  List<String> evidenceRefsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select fact_ref from platform_diagnosis_evidence order by fact_ref")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select event_no from platform_diagnosis_event").size();
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
      assertThat(row).as("V429 必须真的建出 " + table).isNotNull();
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
      // 每张表都必须**真的**有 isolation 策略（不是只开了 RLS 却没策略）。
      var policies =
          sql.fetch(
                  "select policyname from pg_policies where tablename = ? and policyname = 'isolation'",
                  table)
              .size();
      assertThat(policies).as(table + " 必须有 isolation 策略").isEqualTo(1);
    }
  }

  @Test
  void 依据表要求事实表字段与数值三者至少有一() {
    // 🔴 **两个都空的「依据」什么也证明不了** ⇒ 结构上必须挡住。
    var defs =
        sql.fetch(
                "select pg_get_constraintdef(oid) as def from pg_constraint"
                    + " where conrelid = 'platform_diagnosis_evidence'::regclass and contype = 'c'")
            .map(r -> r.get("def", String.class));
    assertThat(defs).isNotEmpty();
    assertThat(defs.stream().anyMatch(d -> d.contains("fact_value IS NOT NULL")))
        .as("依据表必须要求数值或文字至少有一个")
        .isTrue();
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(eventsOf(a)).containsExactly("EVT-evt-a");
    assertThat(eventsOf(b)).containsExactly("EVT-evt-b");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select event_no from platform_diagnosis_event where id = ?", idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 结论与依据同理。
    assertThat(
            tenants.run(
                a,
                () ->
                    sql.fetch(
                            "select id from platform_diagnosis_conclusion where id = ?", idFor(b, 3))
                        .size()))
        .as("按主键直查对方租户的结论也必须被挡")
        .isZero();
    assertThat(
            tenants.run(
                a,
                () ->
                    sql.fetch(
                            "select id from platform_diagnosis_evidence where id = ?", idFor(b, 4))
                        .size()))
        .as("按主键直查对方租户的依据也必须被挡")
        .isZero();

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aPage =
        json.readValue(
            http.perform(get("/platform/ai/diagnosis/events").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiDiagnosisEventPage.class);
    assertThat(aPage.items()).hasSize(1);
    assertThat(aPage.items().get(0).eventNo()).isEqualTo("EVT-evt-a");

    var bPage =
        json.readValue(
            http.perform(get("/platform/ai/diagnosis/events").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiDiagnosisEventPage.class);
    assertThat(bPage.items()).hasSize(1);
    assertThat(bPage.items().get(0).eventNo()).isEqualTo("EVT-evt-b");
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() {
    // 事务外没有任何租户上下文：伪造一个 tenant_id 往里写，触发器直接拒绝。
    UUID forged = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into platform_diagnosis_event(id,tenant_id,event_no,anomaly_type,"
                        + " subject_type,subject_ref,occurred_at,detected_at,source,severity,status,"
                        + " created_at,updated_at)"
                        + " values(?,?,'EVT-forged','INVENTORY','SKU','forged-sku',"
                        + "?::timestamptz,?::timestamptz,'SUBMITTED','WARNING','OPEN',"
                        + "?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    now,
                    now,
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因在 cause 里。
        .hasStackTraceContaining("缺少租户上下文");

    // 伪造的行一条都没落库。
    assertThat(
            sql.fetch("select event_no from platform_diagnosis_event where event_no = 'EVT-forged'")
                .size())
        .isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var body =
        """
        {"anomalyType":"PROFIT","subjectType":"SKU","subjectRef":"forged-header"}
        """;

    http.perform(
            post("/platform/ai/diagnosis/events")
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
                    "select subject_ref, tenant_id from platform_diagnosis_event"
                        + " order by subject_ref"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    var forged =
        rows.stream()
            .filter(r -> "forged-header".equals(r.get("subject_ref", String.class)))
            .findFirst()
            .orElse(null);
    assertThat(forged).as("新建的那一行必须存在").isNotNull();
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(forged.get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它，b 的数据仍然是 b 自己的。
    assertThat(eventsOf(b)).containsExactly("EVT-evt-b");
    var aEvents = eventsOf(a);
    assertThat(aEvents).as("A 只多出自己那一条").hasSize(2);
    assertThat(aEvents).contains("EVT-evt-a");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(eventsOf(a)).as("第 " + round + " 轮：A 只能看见 A 的事件").containsExactly("EVT-evt-a");
      assertThat(eventsOf(b)).as("第 " + round + " 轮：B 只能看见 B 的事件").containsExactly("EVT-evt-b");
      assertThat(evidenceRefsOf(a)).as("第 " + round + " 轮：A 只能看见 A 的依据")
          .containsExactly("evt-a-balance");
      assertThat(evidenceRefsOf(b)).as("第 " + round + " 轮：B 只能看见 B 的依据")
          .containsExactly("evt-b-balance");
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(eventsOf(a)).containsExactly("EVT-evt-a");
    assertThat(eventsOf(b)).containsExactly("EVT-evt-b");
  }
}
