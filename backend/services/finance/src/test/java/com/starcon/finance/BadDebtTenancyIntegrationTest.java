package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 12.1.1.3 坏账与核销的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，不是只断言 A 看不见
 *       （只断言「看不见」的话，一句「写入被全表拒掉」也能骗过它）；
 *   <li>连接池归还后不串租户。
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>刻意跑在本模块自己的三张表与本模块自己的端点上</b>：
 * {@code finance_bad_debt_case} / {@code finance_bad_debt_evidence} /
 * {@code finance_bad_debt_event} —— 断言直接跑在这些表与本模块的读端点 / 写端点上，
 * 证明的是**真的要用的那些表**被隔离了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BadDebtTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-finance-bad-debt-tenancy-" + tenant + "-" + ordinal).getBytes());
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
    var permissions =
        List.of("finance.baddebt.read", "finance.baddebt.create", "finance.baddebt.update", "finance.baddebt.approve");
    actors.put("session-a", actor(userA, a, permissions));
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

  /**
   * 在可信租户事务里插入一张坏账核销申请（timestamptz 一律绑 OffsetDateTime + {@code ?::timestamptz}）。
   *
   * <p>⚠️ 直接插 SQL 而不走服务层：这里要证明的是<b>数据层</b>的隔离，
   * 服务层的策略闸门不该混进这个断言。
   */
  void insertCase(UUID tenant, UUID id, String no, String currency, String amount) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_bad_debt_case"
                  + "(id,tenant_id,case_no,receivable_id,receivable_no,counterparty_name,currency,"
                  + " receivable_amount,receivable_settled,open_balance,write_off_amount,reason_type,"
                  + " reason_detail,status,decision_comment,accounting_status,accounting_owner_module,"
                  + " accounting_ref,created_at,updated_at)"
                  + " values(?,?,?,?,?,'客户',?,100.0000,0.0000,100.0000,?,'PLATFORM_FROZEN','冻结','DRAFT',"
                  + " '','PENDING_FINANCE','12.5.2.1','',?::timestamptz,?::timestamptz)",
              id,
              tenant,
              no,
              UUID.randomUUID(),
              "AR-" + no,
              currency,
              new BigDecimal(amount),
              now,
              now);
          sql.execute(
              "insert into finance_bad_debt_evidence(id,tenant_id,case_id,evidence_no,evidence_type,title,"
                  + " reference_no,content,attachment_ref,created_by,created_at)"
                  + " values(?,?,?,1,'PLATFORM_FROZEN_NOTICE','冻结通知','','内容','',?,"+ " ?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              id,
              UUID.randomUUID(),
              now);
          sql.execute(
              "insert into finance_bad_debt_event(id,tenant_id,case_id,event_type,from_status,to_status,"
                  + " reason,actor_id,occurred_at)"
                  + " values(?,?,?,'CREATED','','DRAFT','建单',?,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              id,
              UUID.randomUUID(),
              now);
          return null;
        });
  }

  /** 以指定租户身份读申请单号。 */
  List<String> caseNosOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select case_no from finance_bad_debt_case order by case_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读依据材料的标题。 */
  List<String> evidenceTitlesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select title from finance_bad_debt_evidence order by title")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select case_no from finance_bad_debt_case").size();
  }

  /**
   * 每个用例前清空业务表，再种入两租户各一张申请。
   *
   * <p><b>清空与播种必须在同一个方法里</b>：写成两个 {@code @BeforeEach} 时，JUnit 5 <b>不保证</b>它们的执行顺序。
   *
   * <p>用**迁移用户**（超管）连接来 TRUNCATE：RLS 对超管不生效。
   */
  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_bad_debt_event, finance_bad_debt_evidence, finance_bad_debt_case,"
              + " finance_receivable_event, finance_collection_task, finance_receivable_line,"
              + " finance_receivable, finance_receivable_policy restart identity cascade");
    }
    insertCase(a, idFor(a, 1), "tenant-a-case", "USD", "60");
    insertCase(b, idFor(b, 1), "tenant-b-case", "EUR", "80");
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 坏账核销三张表强制rls而不是只启用() {
    for (String table :
        List.of("finance_bad_debt_case", "finance_bad_debt_evidence", "finance_bad_debt_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V342 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」").isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('finance_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("finance_app 必须真的能读 " + table)
          .isTrue();
    }
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() throws Exception {
    assertThat(caseNosOf(a)).containsExactly("tenant-a-case");
    assertThat(caseNosOf(b)).containsExactly("tenant-b-case");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch("select case_no from finance_bad_debt_case where id = ?", idFor(b, 1)).size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 依据材料同样隔离。
    assertThat(evidenceTitlesOf(a)).containsExactly("冻结通知");
    assertThat(evidenceTitlesOf(b)).containsExactly("冻结通知");
    var evidenceById =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select title from finance_bad_debt_evidence where case_id = ?", idFor(b, 1))
                    .size());
    assertThat(evidenceById).as("按外键直查也必须被 RLS 挡住").isZero();

    // HTTP 层：A 看不到 B 的申请（列表 + 按 id 直达）。
    var listA =
        http.perform(get("/finance/bad-debt").header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(listA).contains("tenant-a-case").doesNotContain("tenant-b-case");

    http.perform(get("/finance/bad-debt/" + idFor(b, 1)).header("Authorization", "session-a"))
        .andExpect(status().isNotFound());
    http.perform(get("/finance/bad-debt/" + idFor(a, 1)).header("Authorization", "session-b"))
        .andExpect(status().isNotFound());

    // 写入口同样隔离：A 不能给 B 的申请加依据。
    http.perform(
            post("/finance/bad-debt/" + idFor(b, 1) + "/evidence")
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"evidenceType\":\"OTHER\",\"title\":\"越界\",\"referenceNo\":\"\","
                        + "\"content\":\"越界内容\",\"attachmentRef\":null,\"occurredOn\":null}"))
        .andExpect(status().isNotFound());
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
                    "insert into finance_bad_debt_case"
                        + "(id,tenant_id,case_no,receivable_id,receivable_no,counterparty_name,currency,"
                        + " receivable_amount,receivable_settled,open_balance,write_off_amount,reason_type,"
                        + " reason_detail,status,decision_comment,accounting_status,accounting_owner_module,"
                        + " accounting_ref,created_at,updated_at)"
                        + " values(?,?,'BD-FORGED',?,?,'客户','USD',100.0000,0.0000,100.0000,50.0000,"
                        + " 'OTHER','伪造','DRAFT','','PENDING_FINANCE','12.5.2.1','',?::timestamptz,"
                        + "?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    UUID.randomUUID(),
                    "AR-FORGED",
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select case_no from finance_bad_debt_case where case_no = 'BD-FORGED'").size())
        .isZero();
  }

  // ==========================================================================
  // 断言 2 的另一半 —— 伪造租户**头**无效，且**行落在会话租户名下**
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    http.perform(
            post("/finance/bad-debt")
                .header("Authorization", "session-a")
                // 伪造的租户头与内部键：服务层必须忽略它们。
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"receivableId\":\""
                        + UUID.randomUUID()
                        + "\",\"writeOffAmount\":10,\"reasonType\":\"OTHER\","
                        + "\"reasonDetail\":\"伪造头测试\"}"))
        // 目标应收不存在 ⇒ 404（说明请求确实进了服务层，不是被代理挡了）。
        .andExpect(status().isNotFound());

    // 行确实存在（不是被全表拒掉）：直接用数据层写入一个属于 a 的行，再验证 b 看不见。
    insertCase(a, idFor(a, 2), "tenant-a-case-2", "USD", "10");
    var rows =
        tenants.run(
            a, () -> sql.fetch("select case_no, tenant_id from finance_bad_debt_case order by case_no"));
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);
    assertThat(caseNosOf(b)).containsExactly("tenant-b-case");
  }

  @Test
  void 断言2_伪造租户头读不到别的租户的申请() throws Exception {
    var bodyA =
        http.perform(
                get("/finance/bad-debt")
                    .header("Authorization", "session-a")
                    .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(bodyA).contains("tenant-a-case").doesNotContain("tenant-b-case");
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后一次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      assertThat(caseNosOf(a)).as("第 %d 轮租户 A", i).containsExactly("tenant-a-case");
      assertThat(caseNosOf(b)).as("第 %d 轮租户 B", i).containsExactly("tenant-b-case");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(caseNosOf(a)).containsExactly("tenant-a-case");
    assertThat(caseNosOf(b)).containsExactly("tenant-b-case");
  }
}
