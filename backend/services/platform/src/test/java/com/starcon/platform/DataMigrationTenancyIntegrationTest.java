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
 * 18.1.3.2「数据迁移」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p>🔴 <b>核对报告也在断言范围内</b>：这是本模块唯一把数据「送出去」的通道 ——
 * 串租户意味着「A 的核对报告出现在 B 的下载里」，那是最严重的一类越界。
 * 本测试把两份**真文件**写到存储上，再断言各自只拿得到自己的那份。
 *
 * <p>🔴 <b>「逐行结论」也在断言范围内</b>：逐行结论里带着**上游记录 id**
 * （回滚的依据）—— 串租户意味着 A 能看到 B 建出来的商品 id，
 * 那不只是「看到了别人的数据」，还等于**拿到了回滚别人数据的钥匙**。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DataMigrationTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的四张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_migration_mapping",
          "platform_migration_run",
          "platform_migration_file",
          "platform_migration_row");

  static final List<String> PERMISSIONS =
      List.of(
          "platform.migration.read",
          "platform.migration.create",
          "platform.migration.execute",
          "platform.migration.download");

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-migration-" + tenant + "-" + ordinal).getBytes());
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
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("PLATFORM_IO_STORAGE_ROOT", () -> "target/platform-migration-store-tenancy");
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
          "truncate table platform_migration_row, platform_migration_file, platform_migration_run,"
              + " platform_migration_mapping restart identity cascade");
      s.execute("alter sequence platform_migration_run_no_seq restart with 1");
    }
    seedTenant(a, "tenant-a");
    seedTenant(b, "tenant-b");
  }

  /**
   * 在可信租户事务里插一份完整的迁移事实：映射 + 任务 + 核对报告文件 + 逐行结论。
   *
   * <p>🔴 报告文件那一行带**真内容**（写到存储上）—— 串租户意味着「A 的核对报告
   * 出现在 B 的下载里」，这是本模块最严重的一类越界，必须被断言挡住。
   *
   * <p>🔴 逐行结论带**上游记录 id** —— 那是回滚的依据；串租户等于把
   * 「回滚别人数据」的钥匙交出去。
   */
  void seedTenant(UUID tenant, String marker) throws Exception {
    var mappingId = idFor(tenant, 1);
    var runId = idFor(tenant, 2);
    var fileId = idFor(tenant, 3);
    var rowId = idFor(tenant, 4);
    var upstreamId = idFor(tenant, 5);
    var content =
        ("﻿行号,结论,目标类型,目标编码\n2,成功,SPU," + marker + "-SPU-001\n").getBytes(StandardCharsets.UTF_8);
    var key = tenant.toString().replace("-", "") + "/migration/" + marker + "/report.csv";
    storage.put(key, content);
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_migration_mapping(id,tenant_id,code,name,source_system,"
                  + " target_model,fields,enabled,remark,created_by,created_at,updated_at)"
                  + " values(?,?,?,?,'聚水潭','SPU','[]'::jsonb,true,'',?,?::timestamptz,"
                  + " ?::timestamptz)",
              mappingId,
              tenant,
              "MIG-" + marker,
              marker + " 的映射",
              UUID.randomUUID(),
              now,
              now);
          sql.execute(
              "insert into platform_migration_run(id,tenant_id,run_no,mode,target_model,mapping_id,"
                  + " mapping_code,mapping_snapshot,source_filename,source_sha256,status,"
                  + " rollback_status,total_rows,processed_rows,succeeded_rows,failed_rows,"
                  + " skipped_rows,report_file_id,actor_id,requested_at,started_at,finished_at,"
                  + " created_at,updated_at)"
                  + " values(?,?,?,'APPLY','SPU',?,?,'{}'::jsonb,?,'','SUCCEEDED','NONE',1,1,1,0,0,"
                  + " ?,?,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz)",
              runId,
              tenant,
              "MIG-" + marker,
              mappingId,
              "MIG-" + marker,
              marker + ".csv",
              fileId,
              UUID.randomUUID(),
              now,
              now,
              now,
              now,
              now);
          sql.execute(
              "insert into platform_migration_file(id,tenant_id,run_id,role,filename,storage_key,"
                  + " content_type,content_sha256,byte_size,row_count,expires_at,created_at)"
                  + " values(?,?,?,'REPORT',?,?,'text/csv; charset=UTF-8',?,?::numeric,1,"
                  + " ?::timestamptz,?::timestamptz)",
              fileId,
              tenant,
              runId,
              marker + "-report.csv",
              key,
              "0".repeat(64),
              new java.math.BigDecimal(content.length),
              now.plusHours(24),
              now);
          sql.execute(
              "insert into platform_migration_row(id,tenant_id,run_id,row_no,outcome,target_kind,"
                  + " target_code,upstream_id,reason_code,message,raw_value,rollback_status,"
                  + " rollback_message,created_at,updated_at)"
                  + " values(?,?,?,2,'SUCCEEDED','SPU',?,?,'','已写入目标库（草稿）','',"
                  + " 'NONE','',?::timestamptz,?::timestamptz)",
              rowId,
              tenant,
              runId,
              marker + "-SPU-001",
              upstreamId,
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读映射编码。 */
  List<String> mappingCodesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select code from platform_migration_mapping order by code")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读任务单号。 */
  List<String> runNosOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select run_no from platform_migration_run order by run_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读存储键。 */
  List<String> storageKeysOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select storage_key from platform_migration_file order by storage_key")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读逐行结论的上游记录 id。 */
  List<UUID> upstreamIdsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select upstream_id from platform_migration_row where upstream_id is not null")
                .map(r -> r.get(0, UUID.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_migration_run").size();
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
      assertThat(row).as("V417 必须真的建出 " + table).isNotNull();
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
    assertThat(mappingCodesOf(a)).containsExactly("MIG-tenant-a");
    assertThat(mappingCodesOf(b)).containsExactly("MIG-tenant-b");
    assertThat(runNosOf(a)).containsExactly("MIG-tenant-a");
    assertThat(runNosOf(b)).containsExactly("MIG-tenant-b");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a, () -> sql.fetch("select id from platform_migration_run where id = ?", idFor(b, 2)).size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();
    assertThat(
            tenants.run(
                a,
                () ->
                    sql.fetch("select id from platform_migration_row where id = ?", idFor(b, 4)).size()))
        .as("按主键直查对方的逐行结论也必须被挡")
        .isZero();
    assertThat(
            tenants.run(
                a,
                () ->
                    sql.fetch("select id from platform_migration_file where id = ?", idFor(b, 3)).size()))
        .as("按主键直查对方的报告文件也必须被挡")
        .isZero();

    // 文件键带租户段：A 只看得见自己的键。
    assertThat(storageKeysOf(a)).hasSize(1);
    assertThat(storageKeysOf(a).get(0)).contains("tenant-a").doesNotContain("tenant-b");
    assertThat(storageKeysOf(b).get(0)).contains("tenant-b").doesNotContain("tenant-a");

    // 🔴 **上游记录 id 也必须互不可见** —— 它是回滚的依据，
    //   看得见别人的就等于**拿到了回滚别人数据的钥匙**。
    assertThat(upstreamIdsOf(a)).containsExactly(idFor(a, 5)).doesNotContain(idFor(b, 5));
    assertThat(upstreamIdsOf(b)).containsExactly(idFor(b, 5)).doesNotContain(idFor(a, 5));

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aRuns =
        json.readTree(
                http.perform(get("/platform/data-migration/runs").header("Authorization", "session-a"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("items");
    assertThat(aRuns).hasSize(1);
    assertThat(aRuns.get(0).get("runNo").asText()).isEqualTo("MIG-tenant-a");

    var bRuns =
        json.readTree(
                http.perform(get("/platform/data-migration/runs").header("Authorization", "session-b"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("items");
    assertThat(bRuns).hasSize(1);
    assertThat(bRuns.get(0).get("runNo").asText()).isEqualTo("MIG-tenant-b");

    // 🔴 A 直接按 B 的任务 id 取详情 ⇒ 取不到（B 的内容一个字都不许回给 A）。
    var crossTenant =
        http.perform(
                get("/platform/data-migration/runs/" + idFor(b, 2)).header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(crossTenant.getStatus()).isBetween(400, 499);
    assertThat(crossTenant.getContentAsString()).doesNotContain("tenant-b").doesNotContain("MIG-tenant-b");
  }

  @Test
  void 断言1_下载到的核对报告也不串租户() throws Exception {
    var aFile =
        http.perform(
                get("/platform/data-migration/runs/" + idFor(a, 2) + "/report")
                    .header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(aFile).contains("tenant-a-SPU-001").doesNotContain("tenant-b");

    var bFile =
        http.perform(
                get("/platform/data-migration/runs/" + idFor(b, 2) + "/report")
                    .header("Authorization", "session-b"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(bFile).contains("tenant-b-SPU-001").doesNotContain("tenant-a");
  }

  @Test
  void 断言1_逐行结论端点不串租户() throws Exception {
    var aRows =
        json.readTree(
                http.perform(
                        get("/platform/data-migration/runs/" + idFor(a, 2) + "/rows")
                            .header("Authorization", "session-a"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("items");
    assertThat(aRows).hasSize(1);
    assertThat(aRows.get(0).get("upstreamId").asText()).isEqualTo(idFor(a, 5).toString());
    assertThat(aRows.get(0).get("upstreamId").asText()).isNotEqualTo(idFor(b, 5).toString());

    // B 的逐行结论在 A 这里必须整个拿不到。
    var crossTenant =
        http.perform(
                get("/platform/data-migration/runs/" + idFor(b, 2) + "/rows")
                    .header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(crossTenant.getStatus()).isBetween(400, 499);
    assertThat(crossTenant.getContentAsString()).doesNotContain(idFor(b, 5).toString());
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
                    "insert into platform_migration_mapping(id,tenant_id,code,name,source_system,"
                        + " target_model,fields,enabled,remark,created_by,created_at,updated_at)"
                        + " values(?,?,'forged','伪造的映射','聚水潭','SPU','[]'::jsonb,true,'',?,"
                        + " ?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    UUID.randomUUID(),
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select code from platform_migration_mapping where code = 'forged'").size())
        .isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var body =
        json.writeValueAsString(
            Map.of(
                "code",
                "FORGED_MAPPING",
                "name",
                "伪造租户头的映射",
                "sourceSystem",
                "聚水潭",
                "targetModel",
                "SPU",
                "fields",
                List.of(
                    Map.of(
                        "source", "商品编码",
                        "target", "code",
                        "required", true,
                        "transform", "TRIM",
                        "defaultValue", ""),
                    Map.of(
                        "source", "商品名称",
                        "target", "name",
                        "required", true,
                        "transform", "TRIM",
                        "defaultValue", ""),
                    Map.of(
                        "source", "类目",
                        "target", "category",
                        "required", true,
                        "transform", "TRIM",
                        "defaultValue", "")),
                "remark",
                ""));

    http.perform(
            post("/platform/data-migration/mappings")
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
                    "select code, tenant_id from platform_migration_mapping where code like 'MIG-%'"
                        + " or code = 'FORGED_MAPPING'"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    // ...且新行落在**会话租户** a 名下，**不是**调用方自称的 b。
    var created =
        rows.stream()
            .filter(r -> "FORGED_MAPPING".equals(r.get("code", String.class)))
            .findFirst()
            .orElseThrow();
    assertThat(created.get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它，b 的数据仍然是 b 自己的。
    assertThat(mappingCodesOf(b)).containsExactly("MIG-tenant-b");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (var round = 0; round < 5; round++) {
      assertThat(mappingCodesOf(a)).as("第 " + round + " 轮：A 只能看见 A 的映射").containsExactly("MIG-tenant-a");
      assertThat(mappingCodesOf(b)).as("第 " + round + " 轮：B 只能看见 B 的映射").containsExactly("MIG-tenant-b");
      assertThat(runNosOf(a)).as("第 " + round + " 轮：A 只能看见 A 的任务").containsExactly("MIG-tenant-a");
      assertThat(runNosOf(b)).as("第 " + round + " 轮：B 只能看见 B 的任务").containsExactly("MIG-tenant-b");
      assertThat(storageKeysOf(a)).as("第 " + round + " 轮：A 只能看见 A 的文件").hasSize(1);
      assertThat(storageKeysOf(b)).as("第 " + round + " 轮：B 只能看见 B 的文件").hasSize(1);
      assertThat(upstreamIdsOf(a)).as("第 " + round + " 轮：A 只能看见 A 的上游 id").containsExactly(idFor(a, 5));
      assertThat(upstreamIdsOf(b)).as("第 " + round + " 轮：B 只能看见 B 的上游 id").containsExactly(idFor(b, 5));
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(runNosOf(a)).containsExactly("MIG-tenant-a");
    assertThat(runNosOf(b)).containsExactly("MIG-tenant-b");
  }
}
