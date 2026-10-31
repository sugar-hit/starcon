package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
 * 18.1.3.1「导入导出任务」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p>🔴 <b>文件本体也在断言范围内</b>：这是本模块唯一把数据「送出去」的通道 ——
 * 串租户意味着「A 的导出文件内容出现在 B 的下载里」，那是最严重的一类越界。
 * 本测试把两份**真文件**写到存储上，再断言各自只拿得到自己的那份。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImportExportTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的四张表。 */
  static final List<String> TABLES =
      List.of("platform_io_task", "platform_io_file", "platform_io_row_error", "platform_io_template");

  static final List<String> PERMISSIONS =
      List.of("platform.io.read", "platform.io.create", "platform.io.execute", "platform.io.download");

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-io-" + tenant + "-" + ordinal).getBytes());
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
    r.add("PLATFORM_IO_STORAGE_ROOT", () -> "target/platform-io-store-tenancy");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ImportExportStorage storage;

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
          "truncate table platform_io_row_error, platform_io_file, platform_io_template,"
              + " platform_io_task restart identity cascade");
      s.execute("alter sequence platform_io_task_no_seq restart with 1");
    }
    seedTenant(a, "tenant-a");
    seedTenant(b, "tenant-b");
  }

  /**
   * 在可信租户事务里插一个任务 + 一个文件 + 一条失败行 + 一个模板。
   *
   * <p>🔴 文件那一行带**真内容**（写到存储上）—— 串租户意味着「A 的导出文件内容
   * 出现在 B 的下载里」，这是本模块最严重的一类越界，必须被断言挡住。
   */
  void seedTenant(UUID tenant, String marker) throws Exception {
    var taskId = idFor(tenant, 1);
    var fileId = idFor(tenant, 2);
    var content = ("﻿应用编码,应用名称\n" + marker + "-app," + marker + "的应用\n").getBytes(StandardCharsets.UTF_8);
    var key = tenant.toString().replace("-", "") + "/" + marker + "/result.csv";
    storage.put(key, content);
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_io_task(id,tenant_id,task_no,direction,domain_code,domain_label,"
                  + " format,status,scope,total_rows,processed_rows,succeeded_rows,failed_rows,"
                  + " result_file_id,actor_id,requested_at,started_at,finished_at,expires_at,created_at,"
                  + " updated_at) values(?,?,?,'EXPORT','PLATFORM_APP','平台应用与凭证','CSV','SUCCEEDED',"
                  + " '{}'::jsonb,1,1,1,0,?,?,?::timestamptz,?::timestamptz,?::timestamptz,"
                  + " ?::timestamptz,?::timestamptz,?::timestamptz)",
              taskId,
              tenant,
              "IO-" + marker,
              fileId,
              UUID.randomUUID(),
              now,
              now,
              now,
              now.plusHours(24),
              now,
              now);
          sql.execute(
              "insert into platform_io_file(id,tenant_id,task_id,role,filename,storage_key,content_type,"
                  + " content_sha256,byte_size,row_count,expires_at,created_at)"
                  + " values(?,?,?,'RESULT',?,?,'text/csv; charset=UTF-8',?,?::numeric,1,?::timestamptz,"
                  + " ?::timestamptz)",
              fileId,
              tenant,
              taskId,
              marker + "-result.csv",
              key,
              "0".repeat(64),
              new java.math.BigDecimal(content.length),
              now.plusHours(24),
              now);
          sql.execute(
              "insert into platform_io_row_error(id,tenant_id,task_id,row_no,column_name,raw_value,"
                  + " error_code,message,severity,created_at)"
                  + " values(?,?,?,3,'','x','APP_NOT_FOUND','没有这个应用','ERROR',?::timestamptz)",
              idFor(tenant, 3),
              tenant,
              taskId,
              now);
          sql.execute(
              "insert into platform_io_template(id,tenant_id,domain_code,code,name,columns,sample_row,"
                  + " builtin,enabled,remark,created_at,updated_at)"
                  + " values(?,?,'PLATFORM_SUBSCRIPTION','SUBSCRIPTION_STANDARD',?,'[]'::jsonb,"
                  + " '{}'::jsonb,true,true,'',?::timestamptz,?::timestamptz)",
              idFor(tenant, 4),
              tenant,
              marker + "-模板",
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读任务号。 */
  List<String> taskNosOf(UUID tenant) {
    return tenants.run(
        tenant,
        () -> sql.fetch("select task_no from platform_io_task order by task_no").map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读存储键。 */
  List<String> storageKeysOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select storage_key from platform_io_file order by storage_key")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读失败行的原因码。 */
  List<String> errorCodesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select error_code from platform_io_row_error order by error_code")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_io_task").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的四张表强制rls而不是只启用() {
    for (var table : TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V415 必须真的建出 " + table).isNotNull();
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
    assertThat(taskNosOf(a)).containsExactly("IO-tenant-a");
    assertThat(taskNosOf(b)).containsExactly("IO-tenant-b");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () -> sql.fetch("select id from platform_io_task where id = ?", idFor(b, 1)).size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 文件 / 失败行 / 模板同理。
    assertThat(storageKeysOf(a)).hasSize(1);
    assertThat(storageKeysOf(a).get(0)).contains("tenant-a").doesNotContain("tenant-b");
    assertThat(storageKeysOf(b).get(0)).contains("tenant-b").doesNotContain("tenant-a");
    assertThat(errorCodesOf(a)).containsExactly("APP_NOT_FOUND");
    assertThat(errorCodesOf(b)).containsExactly("APP_NOT_FOUND");
    assertThat(
            tenants.run(
                a,
                () -> sql.fetch("select id from platform_io_row_error where id = ?", idFor(b, 3)).size()))
        .as("按主键直查对方的失败行也必须被挡")
        .isZero();
    assertThat(
            tenants.run(
                a,
                () -> sql.fetch("select id from platform_io_template where id = ?", idFor(b, 4)).size()))
        .as("按主键直查对方的模板也必须被挡")
        .isZero();

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aTasks =
        json.readTree(
                http.perform(get("/platform/io/tasks").header("Authorization", "session-a"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("items");
    assertThat(aTasks).hasSize(1);
    assertThat(aTasks.get(0).get("taskNo").asText()).isEqualTo("IO-tenant-a");

    var bTasks =
        json.readTree(
                http.perform(get("/platform/io/tasks").header("Authorization", "session-b"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("items");
    assertThat(bTasks).hasSize(1);
    assertThat(bTasks.get(0).get("taskNo").asText()).isEqualTo("IO-tenant-b");

    // 🔴 A 直接按 B 的任务 id 取详情 ⇒ 取不到（B 的内容一个字都不许回给 A）。
    var crossTenant =
        http.perform(
                get("/platform/io/tasks/" + idFor(b, 1)).header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(crossTenant.getStatus()).isBetween(400, 499);
    assertThat(crossTenant.getContentAsString()).doesNotContain("tenant-b").doesNotContain("IO-tenant-b");
  }

  @Test
  void 断言1_下载到的文件内容也不串租户() throws Exception {
    var aFile =
        http.perform(
                get("/platform/io/tasks/" + idFor(a, 1) + "/file").header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(aFile).contains("tenant-a-app").doesNotContain("tenant-b");

    var bFile =
        http.perform(
                get("/platform/io/tasks/" + idFor(b, 1) + "/file").header("Authorization", "session-b"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(bFile).contains("tenant-b-app").doesNotContain("tenant-a");
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
                    "insert into platform_io_task(id,tenant_id,task_no,direction,domain_code,domain_label,"
                        + " format,status,scope,actor_id,requested_at,created_at,updated_at)"
                        + " values(?,?,'forged','EXPORT','PLATFORM_APP','平台应用与凭证','CSV','PENDING',"
                        + " '{}'::jsonb,?,?::timestamptz,?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    UUID.randomUUID(),
                    now,
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select task_no from platform_io_task where task_no = 'forged'").size())
        .isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var body = json.writeValueAsString(Map.of("domainCode", "PLATFORM_APP"));

    http.perform(
            post("/platform/io/tasks")
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
                    "select task_no, tenant_id from platform_io_task where task_no like 'IO-%'"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    // ...且新行落在**会话租户** a 名下，**不是**调用方自称的 b。
    var created =
        rows.stream()
            .filter(r -> !"IO-tenant-a".equals(r.get("task_no", String.class)))
            .findFirst()
            .orElseThrow();
    assertThat(created.get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它，b 的数据仍然是 b 自己的。
    assertThat(taskNosOf(b)).containsExactly("IO-tenant-b");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (var round = 0; round < 5; round++) {
      assertThat(taskNosOf(a)).as("第 " + round + " 轮：A 只能看见 A 的任务").containsExactly("IO-tenant-a");
      assertThat(taskNosOf(b)).as("第 " + round + " 轮：B 只能看见 B 的任务").containsExactly("IO-tenant-b");
      assertThat(storageKeysOf(a)).as("第 " + round + " 轮：A 只能看见 A 的文件").hasSize(1);
      assertThat(storageKeysOf(b)).as("第 " + round + " 轮：B 只能看见 B 的文件").hasSize(1);
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(taskNosOf(a)).containsExactly("IO-tenant-a");
    assertThat(taskNosOf(b)).containsExactly("IO-tenant-b");
  }
}
