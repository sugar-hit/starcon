package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.FinanceSoftwareModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
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
 * 18.2.1.1「财务软件」的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * {@code platform_finance_account} / {@code platform_finance_subject_map} /
 * {@code platform_finance_master_map} / {@code platform_finance_voucher_file} /
 * {@code platform_finance_reconcile_task} / {@code platform_finance_reconcile_balance} /
 * {@code platform_finance_reconcile_diff}。
 *
 * <p>🔴 **凭证导出记录也在断言范围内**：它是本模块唯一带**文件正文与 sha256** 的表 ——
 * 串租户意味着「A 的法定凭证文件内容出现在 B 的界面里」，那是最严重的一类越界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FinanceSoftwareTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 本模块的七张表。 */
  static final List<String> TABLES =
      List.of(
          "platform_finance_account",
          "platform_finance_subject_map",
          "platform_finance_master_map",
          "platform_finance_voucher_file",
          "platform_finance_reconcile_task",
          "platform_finance_reconcile_balance",
          "platform_finance_reconcile_diff");

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-platform-finance-" + tenant + "-" + ordinal).getBytes());
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
    var permissions =
        List.of(
            "platform.finance.read",
            "platform.finance.create",
            "platform.finance.update",
            "platform.finance.export",
            "platform.finance.reconcile",
            "platform.finance.push");
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
    r.add("FINANCE_URL", () -> "http://127.0.0.1:1");
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
          "truncate table platform_finance_reconcile_diff, platform_finance_reconcile_balance,"
              + " platform_finance_reconcile_task, platform_finance_voucher_file,"
              + " platform_finance_master_map, platform_finance_subject_map, platform_finance_account,"
              + " platform_app_audit_event, platform_app_quota_usage,"
              + " platform_app_quota, platform_app_ip_allowlist, platform_app_scope,"
              + " platform_app_credential, platform_app restart identity cascade");
      s.execute("alter sequence platform_finance_voucher_file_no_seq restart with 1");
      s.execute("alter sequence platform_finance_reconcile_no_seq restart with 1");
    }
    seedTenant(a, "tenant-a");
    seedTenant(b, "tenant-b");
  }

  /**
   * 在可信租户事务里插一个连接档案 + 一条科目映射 + 一条主数据映射 + 一条**凭证文件导出记录**。
   *
   * <p>🔴 凭证文件那一行带**真内容与 sha256** —— 串租户意味着「A 的法定凭证文件正文
   * 出现在 B 的界面里」，这是本模块最严重的一类越界，必须被断言挡住。
   */
  void seedTenant(UUID tenant, String marker) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_finance_account(id,tenant_id,name,vendor,book_code,company_name,"
                  + " currency,fiscal_start_month,status,remark,created_by,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
              idFor(tenant, 1),
              tenant,
              "连接-" + marker,
              "KINGDEE",
              "BOOK-" + marker,
              marker,
              "USD",
              1,
              "ACTIVE",
              "",
              null,
              now,
              now);
          sql.execute(
              "insert into platform_finance_subject_map(id,tenant_id,account_id,local_subject_code,"
                  + " local_subject_name,external_subject_code,external_subject_name,tax_code,direction,"
                  + " enabled,remark,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
              idFor(tenant, 2),
              tenant,
              idFor(tenant, 1),
              "1122",
              "应收账款",
              "EX-" + marker,
              "外部应收",
              "",
              "",
              true,
              "",
              now,
              now);
          sql.execute(
              "insert into platform_finance_master_map(id,tenant_id,account_id,master_type,local_code,"
                  + " local_name,external_code,external_name,enabled,remark,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
              idFor(tenant, 3),
              tenant,
              idFor(tenant, 1),
              "CUSTOMER",
              "C-" + marker,
              marker,
              "CUST-" + marker,
              "",
              true,
              "",
              now,
              now);
          var content = "﻿凭证号,借方金额\n" + marker + ",100\n";
          sql.execute(
              "insert into platform_finance_voucher_file(id,tenant_id,account_id,file_no,format,filename,"
                  + " content,content_sha256,content_bytes,voucher_count,line_count,unmapped_line_count,"
                  + " total_debit,total_credit,currency,date_from,date_to,created_by,created_at)"
                  + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
              idFor(tenant, 4),
              tenant,
              idFor(tenant, 1),
              "FS-" + marker + "-000001",
              "CSV",
              "vouchers-" + marker + ".csv",
              content,
              "0".repeat(64),
              content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
              1,
              1,
              0,
              new java.math.BigDecimal("100.00"),
              new java.math.BigDecimal("100.00"),
              "USD",
              LocalDate.of(2026, 1, 1),
              LocalDate.of(2026, 1, 31),
              null,
              now);
          return null;
        });
  }

  /** 以指定租户身份读科目映射的外部科目编码。 */
  List<String> subjectCodesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch(
                    "select external_subject_code from platform_finance_subject_map"
                        + " order by external_subject_code")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读凭证导出记录的文件号。 */
  List<String> fileNosOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select file_no from platform_finance_voucher_file order by file_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读凭证文件的**正文**（串租户意味着正文也会串）。 */
  List<String> fileContentsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select content from platform_finance_voucher_file order by file_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from platform_finance_subject_map").size();
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
      assertThat(row).as("V382 必须真的建出 " + table).isNotNull();
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
    assertThat(subjectCodesOf(a)).containsExactly("EX-tenant-a");
    assertThat(subjectCodesOf(b)).containsExactly("EX-tenant-b");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select external_subject_code from platform_finance_subject_map where id = ?",
                        idFor(b, 2))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 🔴 凭证导出记录同理：A 看不见 B 的导出留痕。
    assertThat(fileNosOf(a)).containsExactly("FS-tenant-a-000001");
    assertThat(fileNosOf(b)).containsExactly("FS-tenant-b-000001");
    assertThat(
            tenants.run(
                a,
                () ->
                    sql.fetch(
                            "select id from platform_finance_voucher_file where id = ?",
                            idFor(b, 4))
                        .size()))
        .as("按主键直查对方的凭证文件记录也必须被挡")
        .isZero();

    // 主数据映射同理。
    assertThat(tenants.run(a, () -> sql.fetch("select id from platform_finance_master_map")).size())
        .isEqualTo(1);
    assertThat(tenants.run(b, () -> sql.fetch("select id from platform_finance_master_map")).size())
        .isEqualTo(1);

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aMaps =
        json.readValue(
            http.perform(get("/platform/finance-software/subject-maps").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceSoftwareSubjectMapRow[].class);
    assertThat(aMaps).hasSize(1);
    assertThat(aMaps[0].externalSubjectCode()).isEqualTo("EX-tenant-a");

    var bMaps =
        json.readValue(
            http.perform(get("/platform/finance-software/subject-maps").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceSoftwareSubjectMapRow[].class);
    assertThat(bMaps).hasSize(1);
    assertThat(bMaps[0].externalSubjectCode()).isEqualTo("EX-tenant-b");

    // 凭证文件列表同理。
    var aFiles =
        json.readValue(
            http.perform(get("/platform/finance-software/voucher-files").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceSoftwareVoucherFileRow[].class);
    assertThat(aFiles).hasSize(1);
    assertThat(aFiles[0].fileNo()).isEqualTo("FS-tenant-a-000001");

    // 🔴 A 直接按 B 的凭证文件 id 取详情 ⇒ 取不到（B 的正文一个字都不许回给 A）。
    //   状态码是 4xx 即可（本服务的「不存在」走 check() 惯例，报 400 而不是 404）——
    //   真正要钉住的是**响应里没有 B 的内容**。
    var crossTenant =
        http.perform(
                get("/platform/finance-software/voucher-files/" + idFor(b, 4))
                    .header("Authorization", "session-a"))
            .andReturn()
            .getResponse();
    assertThat(crossTenant.getStatus()).isBetween(400, 499);
    assertThat(crossTenant.getContentAsString()).doesNotContain("tenant-b").doesNotContain("FS-tenant-b");
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
                    "insert into platform_finance_account(id,tenant_id,name,vendor,book_code,company_name,"
                        + " currency,fiscal_start_month,status,remark,created_by,created_at,updated_at)"
                        // ⚠️ 这一句的**列与值必须一一对应**：created_by 是 uuid，
                        //   不能给它套 ::timestamptz（那会先撞类型错、掩盖掉 RLS 的拒绝）。
                        + " values(?,?,'forged','KINGDEE','B1','','USD',1,'ACTIVE','',"
                        + " ?,?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    null,
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select name from platform_finance_account where name = 'forged'").size())
        .isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var body =
        json.writeValueAsString(
            new FinanceSoftwareSubjectMapInput(
                idFor(a, 1), "2202", "其他应收", "EX-FORGED", "伪造", "", "", true, ""));

    http.perform(
            post("/platform/finance-software/subject-maps")
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
                    "select external_subject_code, tenant_id from platform_finance_subject_map"
                        + " order by external_subject_code"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    var forged =
        rows.stream()
            .filter(r -> "EX-FORGED".equals(r.get("external_subject_code", String.class)))
            .findFirst()
            .orElse(null);
    assertThat(forged).as("新建的那一行必须存在").isNotNull();
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(forged.get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它，b 的数据仍然是 b 自己的。
    assertThat(subjectCodesOf(b)).containsExactly("EX-tenant-b");
    assertThat(fileNosOf(b)).containsExactly("FS-tenant-b-000001");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(subjectCodesOf(a)).as("第 " + round + " 轮：A 只能看见 A 的科目映射")
          .containsExactly("EX-tenant-a");
      assertThat(subjectCodesOf(b)).as("第 " + round + " 轮：B 只能看见 B 的科目映射")
          .containsExactly("EX-tenant-b");
      assertThat(fileNosOf(a)).as("第 " + round + " 轮：A 只能看见 A 的凭证导出记录")
          .containsExactly("FS-tenant-a-000001");
      assertThat(fileNosOf(b)).as("第 " + round + " 轮：B 只能看见 B 的凭证导出记录")
          .containsExactly("FS-tenant-b-000001");
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(subjectCodesOf(a)).containsExactly("EX-tenant-a");
    assertThat(subjectCodesOf(b)).containsExactly("EX-tenant-b");
  }

  @Test
  void 断言3_凭证文件正文也不串租户() {
    // 🔴 正文与 sha256 是本模块最敏感的数据（法定凭证文件）——
    //   列表不串还不够，正文也必须不串。
    for (int round = 0; round < 3; round++) {
      assertThat(fileContentsOf(a)).as("第 " + round + " 轮：A 只看见自己的文件正文")
          .allSatisfy(c -> assertThat(c).contains("tenant-a").doesNotContain("tenant-b"));
      assertThat(fileContentsOf(b)).as("第 " + round + " 轮：B 只看见自己的文件正文")
          .allSatisfy(c -> assertThat(c).contains("tenant-b").doesNotContain("tenant-a"));
    }
  }
}
