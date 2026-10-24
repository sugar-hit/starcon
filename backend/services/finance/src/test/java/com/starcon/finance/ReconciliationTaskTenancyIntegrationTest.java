package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.finance.ReconciliationTaskModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
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
 * 12.3.2.1 对账任务的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；</li>
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，
 *       不是只断言 A 看不见（只断言「看不见」的话，一句「写入被全表拒掉」也能骗过它）；</li>
 *   <li>连接池归还后不串租户。</li>
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p>⚠️ <b>刻意跑在本模块自己的四张表上</b>：断言直接跑在
 * {@code finance_reconciliation_task} / {@code finance_reconciliation_match} /
 * {@code finance_reconciliation_difference} / {@code finance_reconciliation_event}
 * 与本模块的端点上，证明的是**真的要用的那几张表**被隔离了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationTaskTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-finance-recon-task-tenancy-" + tenant + "-" + ordinal).getBytes());
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
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    var permissions = List.of("finance.receivable.read", "finance.receivable.update");
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
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  /**
   * 每个用例前清空业务表，再种入两租户各一个账户 + 一个对账任务。
   *
   * <p><b>清空与播种必须在同一个方法里</b>：写成两个 {@code @BeforeEach} 时，JUnit 5 <b>不保证</b>它们的执行顺序。
   */
  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_reconciliation_event, finance_reconciliation_difference,"
              + " finance_reconciliation_match, finance_reconciliation_task,"
              + " finance_fund_account_balance, finance_fund_account restart identity cascade");
    }
    seed(a, "FA-A", "租户 A 的账户", "USD", "RT-A");
    seed(b, "FA-B", "租户 B 的账户", "EUR", "RT-B");
  }

  /** 在可信租户事务里插账户 + 对账任务。 */
  void seed(UUID tenant, String accountNo, String accountName, String currency, String taskNo) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
          var accountId = idFor(tenant, 1);
          sql.execute(
              "insert into finance_fund_account(id,tenant_id,account_no,account_name,account_type,"
                  + " currency,legal_entity_id,legal_entity_code,legal_entity_name,bank_name,"
                  + " account_number_masked,account_number_tail,account_holder,status,"
                  + " current_balance,current_balance_at,balance_record_count,note,version,"
                  + " created_at,updated_at)"
                  + " values (?,?,?,?,'BANK',?,?,'LE-A','测试法人','测试银行','****5678','5678','',"
                  + " 'ACTIVE',0,?::timestamptz,0,'',0,?::timestamptz,?::timestamptz)",
              accountId,
              tenant,
              accountNo,
              accountName,
              currency,
              idFor(tenant, 9),
              now,
              now,
              now);
          sql.execute(
              "insert into finance_reconciliation_task(id,tenant_id,task_no,account_id,account_no,"
                  + " account_name,currency,legal_entity_id,legal_entity_code,legal_entity_name,"
                  + " shop_id,shop_code,shop_name,platform_code,period_start,period_end,"
                  + " book_sources,date_window_days,amount_tolerance,status,external_line_count,"
                  + " matched_group_count,matched_line_count,difference_count,matched_at,matched_by,"
                  + " note,version,created_by,created_at,updated_at)"
                  + " values (?,?,?,?,?,?,?,?,'LE-A','测试法人',null,'','','','2026-10-01',"
                  + " '2026-10-31',?::text[],7,0,'DRAFT',0,0,0,0,null,null,'',0,?,"
                  + " ?::timestamptz,?::timestamptz)",
              idFor(tenant, 2),
              tenant,
              taskNo,
              accountId,
              accountNo,
              accountName,
              currency,
              idFor(tenant, 9),
              new String[] {"RECEIVABLE"},
              UUID.randomUUID(),
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读任务号。 */
  List<String> taskNosOf(UUID tenant) {
    return tenants.run(
        tenant, () -> sql.fetch("select task_no from finance_reconciliation_task order by task_no")
            .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读匹配结果行数。 */
  long matchRowsOf(UUID tenant) {
    return tenants.run(
        tenant, () -> sql.fetch("select id from finance_reconciliation_match").size());
  }

  /** 以指定租户身份读差异行数。 */
  long differenceRowsOf(UUID tenant) {
    return tenants.run(
        tenant, () -> sql.fetch("select id from finance_reconciliation_difference").size());
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from finance_reconciliation_task").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的四张表强制rls而不是只启用() {
    for (String table :
        List.of(
            "finance_reconciliation_task",
            "finance_reconciliation_match",
            "finance_reconciliation_difference",
            "finance_reconciliation_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V370 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('finance_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("finance_app 必须真的能读 " + table)
          .isTrue();
    }
  }

  @Test
  void 匹配结果在运行角色手里是只追加的() {
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('finance_app','finance_reconciliation_match','UPDATE')")
                .get(0, Boolean.class))
        .as("匹配结果必须不可改写 ⇒ 运行角色不能 UPDATE")
        .isFalse();
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('finance_app','finance_reconciliation_match','DELETE')")
                .get(0, Boolean.class))
        .as("匹配结果必须不可改写 ⇒ 运行角色不能 DELETE")
        .isFalse();
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(taskNosOf(a)).containsExactly("RT-A");
    assertThat(taskNosOf(b)).containsExactly("RT-B");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select task_no from finance_reconciliation_task where id = ?",
                        idFor(b, 2))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();
    assertThat(matchRowsOf(a)).isZero();
    assertThat(matchRowsOf(b)).isZero();
    assertThat(differenceRowsOf(a)).isZero();
    assertThat(differenceRowsOf(b)).isZero();
  }

  @Test
  void 断言1_端点只看得到本租户的数据() throws Exception {
    var aPage =
        json.readValue(
            http.perform(get("/finance/reconciliation-tasks").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceReconTaskPage.class);
    assertThat(aPage.items()).extracting(FinanceReconTaskRow::taskNo).containsExactly("RT-A");
    assertThat(aPage.total()).isEqualTo(1);

    var bPage =
        json.readValue(
            http.perform(get("/finance/reconciliation-tasks").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceReconTaskPage.class);
    assertThat(bPage.items()).extracting(FinanceReconTaskRow::taskNo).containsExactly("RT-B");
    assertThat(bPage.total()).isEqualTo(1);
  }

  @Test
  void 断言1_跨租户按主键直查端点也拿不到() throws Exception {
    http.perform(
            get("/finance/reconciliation-tasks/" + idFor(b, 2))
                .header("Authorization", "session-a"))
        .andExpect(status().isNotFound());
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() {
    // 事务外没有任何租户上下文：伪造一个 tenant_id 往里写，策略直接拒绝。
    UUID forged = UUID.randomUUID();
    var now = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into finance_reconciliation_task(id,tenant_id,task_no,account_id,"
                        + " account_no,account_name,currency,legal_entity_id,legal_entity_code,"
                        + " legal_entity_name,shop_id,shop_code,shop_name,platform_code,"
                        + " period_start,period_end,book_sources,date_window_days,amount_tolerance,"
                        + " status,external_line_count,matched_group_count,matched_line_count,"
                        + " difference_count,matched_at,matched_by,note,version,created_by,"
                        + " created_at,updated_at)"
                        + " values (?,?,'FORGED',?,'FA-X','伪造','USD',?,'LE-X','伪造法人',null,"
                        + " '','','','2026-10-01','2026-10-31','{RECEIVABLE}',7,0,'DRAFT',0,0,0,"
                        + " 0,null,null,'',0,?,?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select task_no from finance_reconciliation_task where task_no = 'FORGED'").size())
        .isZero();
  }

  // ==========================================================================
  // 断言 2 的另一半 —— 伪造租户**头**无效，且**行落在会话租户名下**
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var body =
        """
        {"accountId":"%s","periodStart":"2026-10-01","periodEnd":"2026-10-31","bookSources":["RECEIVABLE"]}
        """
            .formatted(idFor(a, 1));

    http.perform(
            post("/finance/reconciliation-tasks")
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
                    "select task_no, tenant_id from finance_reconciliation_task order by task_no"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    // ...且它们落在**会话租户** a 名下，**不是**调用方自称的 b。
    var created = rows.get(0);
    assertThat(created.get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它（它不属于 b）。
    assertThat(taskNosOf(b)).containsExactly("RT-B");
  }

  @Test
  void 断言2_伪造租户头读不到别的租户的任务() throws Exception {
    var aPage =
        json.readValue(
            http.perform(
                    get("/finance/reconciliation-tasks")
                        .header("Authorization", "session-a")
                        .header("X-Tenant-Id", b.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceReconTaskPage.class);
    assertThat(aPage.items()).extracting(FinanceReconTaskRow::taskNo).containsExactly("RT-A");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小 = 1 ⇒ 每一次读都复用**同一条连接** ⇒ 「没串」才是真的没串。
    for (int round = 0; round < 5; round++) {
      assertThat(taskNosOf(a)).containsExactly("RT-A");
      assertThat(taskNosOf(b)).containsExactly("RT-B");
      assertThat(taskNosOf(a)).containsExactly("RT-A");
      assertThat(taskNosOf(b)).containsExactly("RT-B");
    }
    // 事务外（没有租户上下文）一行都读不到。
    assertThat(visibleWithoutTenant()).isZero();
  }

  @Test
  void 断言3_写入也按会话租户归属_不按连接上一次是谁() {
    // 先用 B 的身份读一次（把连接归还），再用 A 的身份写一行 ⇒ 必须落在 A 名下。
    taskNosOf(b);
    var now = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    var id = UUID.randomUUID();
    tenants.run(
        a,
        () -> {
          sql.execute(
              "insert into finance_reconciliation_task(id,tenant_id,task_no,account_id,account_no,"
                  + " account_name,currency,legal_entity_id,legal_entity_code,legal_entity_name,"
                  + " shop_id,shop_code,shop_name,platform_code,period_start,period_end,"
                  + " book_sources,date_window_days,amount_tolerance,status,external_line_count,"
                  + " matched_group_count,matched_line_count,difference_count,matched_at,matched_by,"
                  + " note,version,created_by,created_at,updated_at)"
                  + " values (?,?,'RT-AFTER-B',?,'FA-A','租户 A 的账户','USD',?,'LE-A','测试法人',"
                  + " null,'','','','2026-10-01','2026-10-31','{RECEIVABLE}',7,0,'DRAFT',0,0,0,"
                  + " 0,null,null,'',0,?,?::timestamptz,?::timestamptz)",
              id,
              a,
              idFor(a, 1),
              idFor(a, 9),
              UUID.randomUUID(),
              now,
              now);
          return null;
        });
    var owner =
        tenants.run(
            a,
            () ->
                sql.fetchOne(
                        "select tenant_id from finance_reconciliation_task where id = ?", id)
                    .get("tenant_id", UUID.class));
    assertThat(owner).isEqualTo(a);
    assertThat(taskNosOf(b)).containsExactly("RT-B");
  }
}
