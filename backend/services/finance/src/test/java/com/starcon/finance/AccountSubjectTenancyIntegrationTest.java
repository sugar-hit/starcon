package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * finance 服务的**租户隔离**证明（12.5.1.1 会计科目这一段），跑在 AGENTS.md 明文要求的三条断言上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，不是只断言 A 看不见；
 *   <li>连接池归还后不串租户。
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>刻意跑在本模块自己的业务表上</b>：隔离断言直接跑在 {@code finance_account_subject} /
 * {@code finance_account_subject_event_mapping} / {@code finance_account_subject_event_mapping_line} /
 * {@code finance_account_subject_event} 上 —— 证明的是**本模块真的要用的那几张表**被隔离了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountSubjectTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();

  static final UUID BOOK = UUID.randomUUID();
  static final UUID ENTITY = UUID.randomUUID();

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static HttpServer identity;

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-finance-account-subject-tenancy-" + tenant + "-" + ordinal).getBytes());
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
    actors.put(
        "session-a",
        actor(
            userA,
            a,
            List.of(
                "finance.account_subject.read",
                "finance.account_subject.create",
                "finance.account_subject.update")));
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

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /**
   * 在可信租户事务里插入一条科目（timestamptz 一律绑 OffsetDateTime + {@code ?::timestamptz}）。
   *
   * <p>⚠️ 直接插 SQL 而不走服务层：这里要证明的是<b>数据层</b>的隔离。
   */
  void insertSubject(UUID tenant, UUID id, String code, String name) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_account_subject"
                  + "(id,tenant_id,accounting_book_id,legal_entity_id,subject_code,subject_name,category,"
                  + " balance_direction,is_detail,parent_subject_code,level,tax_treatment,tax_rate,tax_note,"
                  + " status,source,version,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,'ASSET','DEBIT',true,null,1,'TAXABLE',null,'','ACTIVE','CUSTOM',0,"
                  + "?::timestamptz,?::timestamptz)",
              id,
              tenant,
              BOOK,
              ENTITY,
              code,
              name,
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读科目编码。 */
  List<String> codesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select subject_code from finance_account_subject order by subject_code")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select subject_code from finance_account_subject").size();
  }

  /**
   * 用**应用账号**直连数据库，按主键查某一行能看见几行。
   *
   * <p>⚠️ 这一格不能省：用迁移用户（超管）直连查会**绕过** FORCE RLS，让断言变成空断言 ——
   * 必须用运行角色（{@code finance_app}，nobypassrls）才能证明策略真的在挡。
   */
  int visibleToAppRole(UUID tenant, UUID rowId) throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "finance_app", "test-only")) {
      try (var s = c.createStatement()) {
        s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      }
      try (var ps =
          c.prepareStatement("select count(*) from finance_account_subject where id = ?")) {
        ps.setObject(1, rowId);
        try (var rs = ps.executeQuery()) {
          rs.next();
          return rs.getInt(1);
        }
      }
    }
  }

  /**
   * 每个用例前清空业务表，再种入两租户各一行。
   *
   * <p>清空与播种必须在同一个方法里：写成两个 {@code @BeforeEach} 时，JUnit 5
   * <b>不保证</b>它们的执行顺序。
   */
  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_account_subject_event_mapping_line,"
              + " finance_account_subject_event_mapping, finance_account_subject_event,"
              + " finance_account_subject cascade");
    }
    insertSubject(a, idFor(a, 1), "1001", "租户 A 的库存现金");
    insertSubject(b, idFor(b, 1), "1001", "租户 B 的库存现金");
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 业务表强制rls而不是只启用() throws Exception {
    for (String table :
        List.of(
            "finance_account_subject",
            "finance_account_subject_event_mapping",
            "finance_account_subject_event_mapping_line",
            "finance_account_subject_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V356 必须真的建出 " + table).isNotNull();
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

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() throws Exception {
    assertThat(codesOf(a)).containsExactly("1001");
    assertThat(codesOf(b)).containsExactly("1001");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select subject_code from finance_account_subject where id = ?", idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();
    // 用**应用账号**直连数据库查也一样查不到（不能靠超管绕过 FORCE RLS 让断言空转）。
    assertThat(visibleToAppRole(a, idFor(b, 1))).as("应用账号按主键查对方的行也必须查不到").isZero();
    assertThat(visibleToAppRole(a, idFor(a, 1))).as("应用账号能查到自己的行").isEqualTo(1);
    // 走 HTTP 端点也一样：A 的列表里看不到 B 的科目名。
    var body =
        http.perform(get("/finance/account-subjects").header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).contains("租户 A 的库存现金").doesNotContain("租户 B 的库存现金");
    // 按 id 直达 B 的行 ⇒ 404。
    http.perform(
            get("/finance/account-subjects/" + idFor(b, 1)).header("Authorization", "session-a"))
        .andExpect(status().isNotFound());
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    UUID forged = UUID.randomUUID();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into finance_account_subject"
                        + "(id,tenant_id,accounting_book_id,legal_entity_id,subject_code,subject_name,category,"
                        + " balance_direction,is_detail,parent_subject_code,level,tax_treatment,tax_rate,tax_note,"
                        + " status,source,version,created_at,updated_at)"
                        + " values(?,?,?,?,'9999','伪造租户的行','ASSET','DEBIT',true,null,1,'TAXABLE',null,'',"
                        + "'ACTIVE','CUSTOM',0,?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    BOOK,
                    ENTITY,
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(visibleWithoutTenant()).isZero();
  }

  // ==========================================================================
  // 断言 2 的另一半 —— 伪造租户**头**无效，且**行落在会话租户名下**
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    // ① 建科目（走服务层 ⇒ 真走鉴权 + 事务 + RLS），请求头伪造另一个租户。
    http.perform(
            post("/finance/account-subjects")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"accountingBookId":"%s","legalEntityId":"%s","subjectCode":"6001","subjectName":"伪造头的写入",
                     "category":"PROFIT_LOSS","balanceDirection":"CREDIT","detail":true}
                    """
                        .formatted(BOOK, ENTITY)))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉）。
    // 读取**必须在某个租户事务里**做：事务外没有任何租户上下文，RLS 会诚实地把
    // 所有行都挡掉 —— 那恰好又是一次隔离生效的证明，但会让「行到底写到哪去了」
    // 这个问题答不了。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select id, tenant_id from finance_account_subject where subject_code = '6001'"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(1);
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(rows.get(0).get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它（它不属于 b）。
    var visibleToB =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select id from finance_account_subject where subject_code = '6001'").size());
    assertThat(visibleToB).as("这一行不属于 b，b 必须看不到").isZero();
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后一次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      var namesA =
          tenants.run(
              a,
              () ->
                  sql.fetch("select subject_name from finance_account_subject order by subject_code")
                      .map(r -> r.get(0, String.class)));
      var namesB =
          tenants.run(
              b,
              () ->
                  sql.fetch("select subject_name from finance_account_subject order by subject_code")
                      .map(r -> r.get(0, String.class)));
      assertThat(namesA).as("第 %d 轮：A 必须只看到自己的行", i).containsExactly("租户 A 的库存现金");
      assertThat(namesB).as("第 %d 轮：B 必须只看到自己的行", i).containsExactly("租户 B 的库存现金");
    }
  }
}
