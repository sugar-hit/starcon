package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.ExternalIntegrationModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
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
 * 18.2.3.1「其他对接」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p>🔴 本测试刻意跑在「外部通道未接入」的真实实现上（不注入任何测试替身）：
 * 未接入 ⇒ 状态 NOT_CONNECTED、<b>不写成功记录</b> —— 这条「不写」也必须在租户维度上钉住。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExternalIntegrationTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的三张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_external_connection",
          "platform_external_sync_task",
          "platform_external_sync_record");

  static final List<String> PERMISSIONS =
      List.of(
          "platform.external.read",
          "platform.external.create",
          "platform.external.update",
          "platform.external.sync");

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-external-" + tenant + "-" + ordinal).getBytes());
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

  static void respond(HttpExchange e, int status, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
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
    actors.put("session-a", actor(UUID.randomUUID(), a, PERMISSIONS));
    actors.put("session-b", actor(UUID.randomUUID(), b, PERMISSIONS));
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
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:1");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stopContainers() {
    if (identity != null) identity.stop(0);
    if (pg.isRunning()) pg.stop();
  }

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_external_sync_record, platform_external_sync_task,"
              + " platform_external_connection restart identity cascade");
    }
    seedTenant(a, "tenant-a");
    seedTenant(b, "tenant-b");
  }

  /**
   * 在可信租户事务里插一个连接 + 一个同步任务 + 一条同步记录。
   *
   * <p>🔴 同步记录带**真条数** —— 串租户意味着「A 的条数出现在 B 的界面上」，
   * 这是本模块最严重的一类越界。
   */
  void seedTenant(UUID tenant, String marker) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_external_connection(id,tenant_id,category,adapter_code,name,base_url,"
                  + " credential_ref,credential_ref_lookup,credential_hint,channel_platform_code,"
                  + " channel_shop_id,status,health_status,timeout_seconds,max_attempts,remark,"
                  + " created_by,created_at,updated_at)"
                  + " values(?,?,'SUPPLY_SOURCE','ALIBABA_1688',?,'',?,?,?,'AMAZON',null,"
                  + " 'ACTIVE','HEALTHY',15,3,?,?::uuid,?::timestamptz,?::timestamptz)",
              idFor(tenant, 1),
              tenant,
              "连接-" + marker,
              "secret://external/1688/" + marker,
              ExternalIntegrationService.sha256Hex("secret://external/1688/" + marker),
              "9f2a",
              marker,
              null,
              now,
              now);
          sql.execute(
              "insert into platform_external_sync_task(id,tenant_id,connection_id,task_type,direction,"
                  + " name,resource_ref,schedule_kind,schedule_expression,interval_seconds,enabled,status,"
                  + " last_run_status,remark,created_by,created_at,updated_at)"
                  + " values(?,?,?,'CATALOG_SYNC','PULL',?,'SHOP-1','MANUAL','',null,true,'ACTIVE',"
                  + " 'SUCCEEDED',?,?::uuid,?::timestamptz,?::timestamptz)",
              idFor(tenant, 2),
              tenant,
              idFor(tenant, 1),
              "任务-" + marker,
              marker,
              null,
              now,
              now);
          sql.execute(
              "insert into platform_external_sync_record(id,tenant_id,connection_id,task_id,run_no,status,"
                  + " source,records_total,records_succeeded,records_failed,external_ref,failure_reason,"
                  + " failure_class,provider_notified,provider_notice,started_at,finished_at,created_by,created_at)"
                  + " values(?,?,?,?,1,'SUCCEEDED','EXTERNAL',?,?,0,?,'','',false,?,"
                  + " ?::timestamptz,?::timestamptz,?::uuid,?::timestamptz)",
              idFor(tenant, 3),
              tenant,
              idFor(tenant, 1),
              idFor(tenant, 2),
              new BigDecimal("120.000000"),
              new BigDecimal("118.000000"),
              "BATCH-" + marker,
              "外部侧已完成，人工登记进本系统台账；本系统**未**与外部系统核对过",
              now,
              now,
              null,
              now);
          return null;
        });
  }

  /** 以指定租户身份读连接名（串租户意味着名字也会串）。 */
  List<String> connectionNamesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select name from platform_external_connection order by name")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读同步记录的条数。 */
  List<BigDecimal> recordTotalsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select records_total from platform_external_sync_record order by run_no")
                .map(r -> r.get(0, BigDecimal.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_external_sync_record").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的三张表强制rls而不是只启用() throws Exception {
    for (String table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V418 必须真的建出 " + table).isNotNull();
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
  // 断言 1 —— 两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() throws Exception {
    assertThat(connectionNamesOf(a)).containsExactly("连接-tenant-a");
    assertThat(connectionNamesOf(b)).containsExactly("连接-tenant-b");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select name from platform_external_connection where id = ?", idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 同步任务同理。
    assertThat(
            tenants.run(
                a, () -> sql.fetch("select id from platform_external_sync_task").size()))
        .isEqualTo(1);
    assertThat(
            tenants.run(
                a,
                () ->
                    sql.fetch("select id from platform_external_sync_task where id = ?", idFor(b, 2))
                        .size()))
        .as("按主键直查对方的同步任务也必须被挡")
        .isZero();

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aConnections =
        json.readValue(
            http.perform(
                    MockMvcRequestBuilders.get("/platform/external-integrations/connections")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ExternalConnectionRow[].class);
    assertThat(aConnections).hasSize(1);
    assertThat(aConnections[0].name()).isEqualTo("连接-tenant-a");

    var bConnections =
        json.readValue(
            http.perform(
                    MockMvcRequestBuilders.get("/platform/external-integrations/connections")
                        .header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            ExternalConnectionRow[].class);
    assertThat(bConnections).hasSize(1);
    assertThat(bConnections[0].name()).isEqualTo("连接-tenant-b");

    // 🔴 A 直接按 B 的连接 id 取详情 ⇒ 取不到（B 的名字一个字都不许回给 A）。
    var crossTenant =
        http.perform(
                MockMvcRequestBuilders.get(
                        "/platform/external-integrations/connections/" + idFor(b, 1))
                    .header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(crossTenant.getStatus()).isBetween(400, 499);
    assertThat(crossTenant.getContentAsString()).doesNotContain("tenant-b");

    // 🔴 A 直接按 B 的连接 id 取健康状态 ⇒ 同样取不到。
    var crossHealth =
        http.perform(
                MockMvcRequestBuilders.get(
                        "/platform/external-integrations/connections/" + idFor(b, 1) + "/health")
                    .header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(crossHealth.getStatus()).isBetween(400, 499);
    assertThat(crossHealth.getContentAsString()).doesNotContain("tenant-b");
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    // 事务外没有任何租户上下文：伪造一个 tenant_id 往里写，策略直接拒绝。
    UUID forged = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into platform_external_connection(id,tenant_id,category,adapter_code,name,"
                        + " base_url,credential_ref,credential_ref_lookup,credential_hint,"
                        + " channel_platform_code,channel_shop_id,status,health_status,timeout_seconds,"
                        + " max_attempts,remark,created_by,created_at,updated_at)"
                        + " values(?,?,'SUPPLY_SOURCE','ALIBABA_1688','forged','','','','','',null,"
                        + " 'ACTIVE','UNKNOWN',15,3,'',null,?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select name from platform_external_connection where name = 'forged'").size())
        .isZero();
  }

  @Test
  void 断言2_伪造的租户头改不动会话租户() throws Exception {
    // 伪造的 X-Tenant-Id 头 + 真实会话 ⇒ 行必须落在**会话租户**名下。
    var response =
        http.perform(
                MockMvcRequestBuilders.post("/platform/external-integrations/sync-records")
                    .header("Authorization", "session-a")
                    .header("X-Tenant-Id", b.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "taskId", idFor(a, 2).toString(),
                                "status", "SUCCEEDED",
                                "recordsTotal", "7",
                                "recordsSucceeded", "7",
                                "recordsFailed", "0"))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);

    // 行落在 A 名下（不是伪造的 B）。
    assertThat(
            tenants.run(
                a, () -> sql.fetch("select id from platform_external_sync_record").size()))
        .as("行必须落在**会话租户**名下")
        .isEqualTo(2);
    assertThat(
            tenants.run(
                b, () -> sql.fetch("select id from platform_external_sync_record").size()))
        .as("伪造的头不能把行写进别的租户")
        .isEqualTo(1);
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    // 连接池固定为 1 条连接 ⇒ 每一次读写都复用**同一条**连接。
    for (int i = 0; i < 5; i++) {
      assertThat(connectionNamesOf(a)).containsExactly("连接-tenant-a");
      assertThat(connectionNamesOf(b)).containsExactly("连接-tenant-b");
      assertThat(recordTotalsOf(a)).containsExactly(new BigDecimal("120.000000"));
      assertThat(recordTotalsOf(b)).containsExactly(new BigDecimal("120.000000"));
    }
    // 交替读：连续两次读不同租户，中间不夹任何事务边界。
    assertThat(connectionNamesOf(a)).containsExactly("连接-tenant-a");
    assertThat(connectionNamesOf(b)).containsExactly("连接-tenant-b");
    assertThat(connectionNamesOf(a)).containsExactly("连接-tenant-a");
  }

  // ==========================================================================
  // 附：未接入的外部通道不得在任何租户名下留下成功痕迹
  // ==========================================================================
  @Test
  void 未接入的外部通道在两个租户名下都不留成功记录() throws Exception {
    for (var session : List.of("session-a", "session-b")) {
      var tenant = session.equals("session-a") ? a : b;
      var response =
          http.perform(
                  MockMvcRequestBuilders.post(
                          "/platform/external-integrations/sync-tasks/" + idFor(tenant, 2) + "/run")
                      .header("Authorization", session)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{}"))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus()).as("未接入 ⇒ 409").isEqualTo(409);
    }
    // 🔴 两个租户名下都**多了一条「未接入」记录**（条数 0，run_no = 2），
    //    原来那条人工登记的 EXTERNAL 记录还在（run_no = 1）——
    //    留痕照留，但**没有**新增任何成功记录。
    assertThat(recordTotalsOf(a))
        .containsExactly(new BigDecimal("120.000000"), new BigDecimal("0.000000"));
    assertThat(recordTotalsOf(b))
        .containsExactly(new BigDecimal("120.000000"), new BigDecimal("0.000000"));
    for (var tenant : List.of(a, b)) {
      var succeeded =
          tenants.run(
              tenant,
              () ->
                  sql.fetch(
                          "select id from platform_external_sync_record where status = 'SUCCEEDED'"
                              + " and source = 'DIRECT'")
                      .size());
      assertThat(succeeded).as("未接入不许写 DIRECT 成功记录").isZero();
      // 🔴 新增的那一条必须如实标成「未接入」且「没核对过」。
      var notConnected =
          tenants.run(
              tenant,
              () ->
                  sql.fetch(
                          "select status, provider_notified from platform_external_sync_record"
                              + " where status = 'NOT_CONNECTED'")
                      .size());
      assertThat(notConnected).as("未接入 ⇒ 如实记一条 NOT_CONNECTED").isEqualTo(1);
    }
  }
}
