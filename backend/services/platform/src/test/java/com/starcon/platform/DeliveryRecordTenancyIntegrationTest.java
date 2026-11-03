package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.DeliveryRecordModels.*;
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
 * 18.1.2.2「投递记录」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * {@code platform_delivery_record} / {@code platform_delivery_attempt} /
 * {@code platform_delivery_dead_letter} / {@code platform_delivery_replay_task}。
 *
 * <p>🔴 <b>投递记录是租户隔离的重点对象</b>：目标地址、事件载荷、响应体片段
 * 全是租户私有事实 —— 跨租户可见等于把别人的业务数据与外部出口交出去。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeliveryRecordTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的四张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_delivery_record",
          "platform_delivery_attempt",
          "platform_delivery_dead_letter",
          "platform_delivery_replay_task");

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-delivery-" + tenant + "-" + ordinal).getBytes());
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
            "platform.webhook.delivery.read",
            "platform.webhook.delivery.publish",
            "platform.webhook.delivery.replay");
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
          "truncate table platform_delivery_replay_task, platform_delivery_dead_letter,"
              + " platform_delivery_attempt, platform_delivery_record,"
              + " platform_webhook_audit_event, platform_webhook_policy,"
              + " platform_webhook_signing_secret, platform_webhook_subscription_event,"
              + " platform_webhook_subscription, platform_webhook_event_type, platform_app"
              + " restart identity cascade");
    }
    seed(a, "app-a", "http://127.0.0.1:1/hooks-a", "event-a");
    seed(b, "app-b", "http://127.0.0.1:1/hooks-b", "event-b");
  }

  /** 在可信租户事务里插一套夹具：应用 + 事件定义 + 订阅 + 过滤 + 签名密钥 + 策略 + 投递记录全套。 */
  void seed(UUID tenant, String appCode, String targetUrl, String eventType) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_app(id,tenant_id,app_code,app_name,app_type,description,status,"
                  + " created_at,updated_at) values(?,?,?,'夹具应用','THIRD_PARTY','','ACTIVE',"
                  + " ?::timestamptz,?::timestamptz)",
              idFor(tenant, 1),
              tenant,
              appCode,
              now,
              now);
          sql.execute(
              "insert into platform_webhook_event_type(id,tenant_id,event_type,schema_version,status,"
                  + " payload_schema,source_service,source_artifact,registered_at)"
                  + " values(?,?,?,1,'ACTIVE','{\"type\":\"object\"}'::jsonb,'catalog','a.java:1',"
                  + " ?::timestamptz)",
              idFor(tenant, 2),
              tenant,
              eventType,
              now);
          sql.execute(
              "insert into platform_webhook_subscription(id,tenant_id,app_id,name,target_url,status,"
                  + " description,created_at,updated_at)"
                  + " values(?,?,?,?,?,'ACTIVE','',?::timestamptz,?::timestamptz)",
              idFor(tenant, 3),
              tenant,
              idFor(tenant, 1),
              "夹具订阅",
              targetUrl,
              now,
              now);
          sql.execute(
              "insert into platform_webhook_subscription_event(id,tenant_id,subscription_id,event_type,"
                  + " schema_version,filter,enabled,created_at)"
                  + " values(?,?,?,?,1,'{}'::jsonb,true,?::timestamptz)",
              idFor(tenant, 4),
              tenant,
              idFor(tenant, 3),
              eventType,
              now);
          sql.execute(
              "insert into platform_webhook_signing_secret(id,tenant_id,subscription_id,version,status,"
                  + " secret_ciphertext,secret_hint,created_at)"
                  + " values(?,?,?,1,'ACTIVE',?,'aaaa',?::timestamptz)",
              idFor(tenant, 5),
              tenant,
              idFor(tenant, 3),
              // 🔴 **真密文**：平台要解密它才能算签名 —— 假的密文会让投递路径直接炸。
              new WebhookSigningSecretCipher("test-key").seal("fixture-plain-secret"),
              now);
          sql.execute(
              "insert into platform_webhook_policy(id,tenant_id,subscription_id,max_attempts,"
                  + " initial_backoff_seconds,backoff_multiplier,max_backoff_seconds,"
                  + " request_timeout_seconds,dead_letter_retention_days,created_at,updated_at)"
                  + " values(?,?,?,5,30,2.00,3600,10,30,?::timestamptz,?::timestamptz)",
              idFor(tenant, 6),
              tenant,
              idFor(tenant, 3),
              now,
              now);
          // 投递记录 + 投递日志 + 死信 + 重放任务：四张表各一行。
          sql.execute(
              "insert into platform_delivery_record(id,tenant_id,event_id,event_type,schema_version,"
                  + " subscription_id,app_id,target_url,status,status_code,attempt_count,replay_count,"
                  + " event_payload,body_bytes,last_error,created_at,updated_at)"
                  + " values(?,?,?,?,1,?,?,?,'DELIVERED',200,1,0,'{}'::jsonb,?,'',"
                  + " ?::timestamptz,?::timestamptz)",
              idFor(tenant, 7),
              tenant,
              UUID.nameUUIDFromBytes(("event-" + tenant).getBytes()),
              eventType,
              idFor(tenant, 3),
              idFor(tenant, 1),
              targetUrl,
              java.util.Base64.getEncoder().encodeToString("{}".getBytes()),
              now,
              now);
          sql.execute(
              "insert into platform_delivery_attempt(id,tenant_id,delivery_id,attempt_no,"
                  + " status_code,success,attempt_source,duration_ms,error,response_snippet,occurred_at)"
                  + " values(?,?,?,1,200,true,'INITIAL',7,'','ok',?::timestamptz)",
              idFor(tenant, 8),
              tenant,
              idFor(tenant, 7),
              now);
          sql.execute(
              "insert into platform_delivery_dead_letter(id,tenant_id,delivery_id,reason,"
                  + " failed_attempt_no,status,expires_at,created_at)"
                  + " values(?,?,?,'夹具死信',3,'PENDING',?::timestamptz,?::timestamptz)",
              idFor(tenant, 9),
              tenant,
              idFor(tenant, 7),
              now,
              now);
          sql.execute(
              "insert into platform_delivery_replay_task(id,tenant_id,delivery_id,trigger_type,status,"
                  + " result_status_code,result_message,reason,requested_by,created_at)"
                  + " values(?,?,?,'MANUAL','SUCCEEDED',200,'ok','夹具重放',?::uuid,?::timestamptz)",
              idFor(tenant, 10),
              tenant,
              idFor(tenant, 7),
              UUID.randomUUID(),
              now);
          return null;
        });
  }

  /** 以指定租户身份读投递目标地址。 */
  List<String> targetUrlsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select target_url from platform_delivery_record order by target_url")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读事件类型。 */
  List<String> eventTypesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select event_type from platform_delivery_record order by event_type")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份数四张表各有多少行。 */
  List<Integer> countsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            List.of(
                sql.fetch("select id from platform_delivery_record").size(),
                sql.fetch("select id from platform_delivery_attempt").size(),
                sql.fetch("select id from platform_delivery_dead_letter").size(),
                sql.fetch("select id from platform_delivery_replay_task").size()));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_delivery_record").size();
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
      assertThat(row).as("V413 必须真的建出 " + table).isNotNull();
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
  void 断言1_两租户互不可见() {
    assertThat(targetUrlsOf(a)).containsExactly("http://127.0.0.1:1/hooks-a");
    assertThat(targetUrlsOf(b)).containsExactly("http://127.0.0.1:1/hooks-b");
    assertThat(eventTypesOf(a)).containsExactly("event-a");
    assertThat(eventTypesOf(b)).containsExactly("event-b");

    // 四张表各自只看得到自己那一行。
    assertThat(countsOf(a)).containsExactly(1, 1, 1, 1);
    assertThat(countsOf(b)).containsExactly(1, 1, 1, 1);

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select id from platform_delivery_record where id = ?", idFor(b, 7))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 投递日志 / 死信 / 重放任务同理。
    assertThat(
            tenants.run(
                    a,
                    () ->
                        sql.fetch(
                                "select id from platform_delivery_attempt where id = ?",
                                idFor(b, 8))
                            .size()))
        .as("按主键直查对方租户的投递日志也必须被挡")
        .isZero();
    assertThat(
            tenants.run(
                    a,
                    () ->
                        sql.fetch(
                                "select id from platform_delivery_dead_letter where id = ?", idFor(b, 9))
                            .size()))
        .isZero();
    assertThat(
            tenants.run(
                    a,
                    () ->
                        sql.fetch(
                                "select id from platform_delivery_replay_task where id = ?", idFor(b, 10))
                            .size()))
        .isZero();

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aPage =
        json.readValue(
            http.perform(get("/platform/deliveries").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            DeliveryRecordPage.class);
    assertThat(aPage.items()).hasSize(1);
    assertThat(aPage.items().get(0).targetUrl()).isEqualTo("http://127.0.0.1:1/hooks-a");

    var bPage =
        json.readValue(
            http.perform(get("/platform/deliveries").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            DeliveryRecordPage.class);
    assertThat(bPage.items()).hasSize(1);
    assertThat(bPage.items().get(0).targetUrl()).isEqualTo("http://127.0.0.1:1/hooks-b");

    // A 按 B 的投递记录 id 直查详情也拿不到。
    http.perform(
            get("/platform/deliveries/" + idFor(b, 7)).header("Authorization", "session-a"))
        .andExpect(status().isNotFound());

    // A 读不到 B 的投递日志。
    http.perform(
            get("/platform/deliveries/" + idFor(b, 7) + "/attempts").header("Authorization", "session-a"))
        .andExpect(status().isNotFound());

    // A 看不到 B 的死信与重放任务。
    var aDeadLetters =
        json.readValue(
            http.perform(get("/platform/deliveries/dead-letters").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            DeliveryRecordDeadLetterPage.class);
    assertThat(aDeadLetters.total()).isEqualTo(1);
    assertThat(aDeadLetters.items().get(0).targetUrl()).isEqualTo("http://127.0.0.1:1/hooks-a");

    var aReplayTasks =
        json.readValue(
            http.perform(get("/platform/deliveries/replay-tasks").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            DeliveryRecordReplayTaskPage.class);
    assertThat(aReplayTasks.total()).isEqualTo(1);
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
                    "insert into platform_delivery_record(id,tenant_id,event_id,event_type,schema_version,"
                        + " subscription_id,app_id,target_url,status,status_code,attempt_count,replay_count,"
                        + " event_payload,body_bytes,last_error,created_at,updated_at)"
                        + " values(?,?,?, 'forged',1,?,?,'https://forged.example.com/hooks','PENDING',0,0,0,"
                        + " '{}'::jsonb,?,'',?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    UUID.randomUUID(),
                    idFor(a, 3),
                    idFor(a, 1),
                    java.util.Base64.getEncoder().encodeToString("{}".getBytes()),
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(
            sql.fetch("select event_type from platform_delivery_record where event_type = 'forged'")
                .size())
        .isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    // ⚠️ payload 是**一段 JSON 文本**（平台服务既有契约口径：JSON 字段一律 String）。
    var body =
        json.writeValueAsString(
            java.util.Map.of(
                "eventType", "event-a",
                "schemaVersion", 1,
                "payload", "{\"k\":\"v\"}"));

    http.perform(
            post("/platform/deliveries/publish")
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
                    "select event_type, tenant_id from platform_delivery_record order by created_at"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    var forged =
        rows.stream()
            .filter(r -> r.get("event_type", String.class).equals("event-a"))
            .findFirst()
            .orElse(null);
    assertThat(forged).as("新建的那一行必须存在").isNotNull();
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(forged.get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它，b 的数据仍然是 b 自己的。
    assertThat(targetUrlsOf(b)).containsExactly("http://127.0.0.1:1/hooks-b");
    assertThat(
            tenants.run(
                    b,
                    () ->
                        sql.fetch(
                                "select id from platform_delivery_record"
                                    + " where event_type = 'event-a' and tenant_id <> ?::uuid",
                                b))
                .size())
        .as("B 看不见 A 伪造头写入的那一行")
        .isZero();
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(targetUrlsOf(a)).as("第 " + round + " 轮：A 只能看见 A 的投递记录")
          .containsExactly("http://127.0.0.1:1/hooks-a");
      assertThat(targetUrlsOf(b)).as("第 " + round + " 轮：B 只能看见 B 的投递记录")
          .containsExactly("http://127.0.0.1:1/hooks-b");
      assertThat(eventTypesOf(a)).as("第 " + round + " 轮：A 只能看见 A 的事件")
          .containsExactly("event-a");
      assertThat(eventTypesOf(b)).as("第 " + round + " 轮：B 只能看见 B 的事件")
          .containsExactly("event-b");
      assertThat(countsOf(a)).as("第 " + round + " 轮：A 的四张表各 1 行")
          .containsExactly(1, 1, 1, 1);
      assertThat(countsOf(b)).as("第 " + round + " 轮：B 的四张表各 1 行")
          .containsExactly(1, 1, 1, 1);
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(targetUrlsOf(a)).containsExactly("http://127.0.0.1:1/hooks-a");
    assertThat(targetUrlsOf(b)).containsExactly("http://127.0.0.1:1/hooks-b");
  }
}
