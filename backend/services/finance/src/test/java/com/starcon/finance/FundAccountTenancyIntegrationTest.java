package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.FundAccountModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
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
 * 12.7.1.1 账户管理的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p><b>刻意跑在本模块自己的表与端点上</b>：断言直接跑在
 * {@code finance_fund_account} / {@code finance_fund_account_balance}
 * 与本模块的端点上，证明的是**真的要用的那两张表**被隔离了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FundAccountTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  /** 两个租户各自的法人（identity 侧按调用方成员关系返回哪一份，是 identity 的事）。 */
  static final UUID legalEntityA = UUID.randomUUID();
  static final UUID legalEntityB = UUID.randomUUID();

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-finance-fund-account-" + tenant + "-" + ordinal).getBytes());
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
      // identity 的法人档案（授权只读端口读的就是它）。
      // 这里对两个会话都返回同一份清单 —— 法人校验**不是**租户隔离的手段，
      // 租户隔离由本库的 RLS 负责（断言 1/2/3 证明的正是这件事）。
      identity.createContext(
          "/identity/legal-entities",
          e -> {
            byte[] body =
                ("["
                        + "{\"id\":\""
                        + legalEntityA
                        + "\",\"code\":\"LE_A\",\"legalName\":\"甲公司\",\"status\":\"ACTIVE\"},"
                        + "{\"id\":\""
                        + legalEntityB
                        + "\",\"code\":\"LE_B\",\"legalName\":\"乙公司\",\"status\":\"ACTIVE\"}"
                        + "]")
                    .getBytes();
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
   * 每个用例前清空业务表，再种入两租户各一个账户。
   *
   * <p><b>清空与播种必须在同一个方法里</b>：写成两个 {@code @BeforeEach} 时，JUnit 5 <b>不保证</b>它们的执行顺序。
   */
  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_fund_account_balance, finance_fund_account"
              + " restart identity cascade");
    }
    insertAccount(a, idFor(a, 1), "BANK-A", "租户 A 的账户", "USD", "100");
    insertAccount(b, idFor(b, 1), "BANK-B", "租户 B 的账户", "EUR", "500");
  }

  /** 在可信租户事务里插一个账户（连同它的建档初始余额历史）。 */
  void insertAccount(UUID tenant, UUID id, String no, String name, String currency, String balance) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_fund_account(id,tenant_id,account_no,account_name,account_type,"
                  + " currency,legal_entity_id,legal_entity_code,legal_entity_name,bank_name,"
                  + " account_number_masked,account_number_tail,account_holder,status,"
                  + " current_balance,current_balance_at,balance_record_count,note,version,"
                  + " created_at,updated_at)"
                  + " values (?,?,?,?,'BANK',?,?,?,'测试法人','测试银行','****5678','5678','',"
                  + " 'ACTIVE',0,?::timestamptz,0,'',0,?::timestamptz,?::timestamptz)",
              id,
              tenant,
              no,
              name,
              currency,
              legalEntityA,
              "LE_A",
              now,
              now,
              now);
          sql.execute(
              "insert into finance_fund_account_balance(id,tenant_id,account_id,currency,"
                  + " legal_entity_id,legal_entity_code,legal_entity_name,balance_amount,"
                  + " previous_amount,change_source,reason,source_system,source_reference,"
                  + " operator_user_id,recorded_at,created_at)"
                  + " values (?,?,?,?,?,?,'测试法人',?,0,'INITIAL','建档','','',?,?::timestamptz,"
                  + " ?::timestamptz)",
              idFor(tenant, 2),
              tenant,
              id,
              currency,
              legalEntityA,
              "LE_A",
              new BigDecimal(balance),
              UUID.randomUUID(),
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读账户编号。 */
  List<String> accountNosOf(UUID tenant) {
    return tenants.run(
        tenant, () -> sql.fetch("select account_no from finance_fund_account order by account_no")
            .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读余额历史的条数。 */
  long balanceRowsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select id from finance_fund_account_balance").size());
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from finance_fund_account").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的两张表强制rls而不是只启用() {
    for (String table : List.of("finance_fund_account", "finance_fund_account_balance")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V367 必须真的建出 " + table).isNotNull();
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
  void 断言1_两租户互不可见() {
    assertThat(accountNosOf(a)).containsExactly("BANK-A");
    assertThat(accountNosOf(b)).containsExactly("BANK-B");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch("select account_no from finance_fund_account where id = ?", idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 余额历史同样隔离。
    assertThat(balanceRowsOf(a)).isEqualTo(1);
    assertThat(balanceRowsOf(b)).isEqualTo(1);
    var balanceById =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select id from finance_fund_account_balance where id = ?", idFor(b, 2))
                    .size());
    assertThat(balanceById).as("按主键直查余额历史也必须被 RLS 挡住").isZero();
  }

  @Test
  void 断言1_账户端点只看得到本租户的数据() throws Exception {
    var aPage =
        json.readValue(
            http.perform(get("/finance/fund-accounts").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundAccountPage.class);
    assertThat(aPage.items()).extracting(FinanceFundAccountRow::accountNo).containsExactly("BANK-A");
    assertThat(aPage.total()).isEqualTo(1);

    var bPage =
        json.readValue(
            http.perform(get("/finance/fund-accounts").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundAccountPage.class);
    assertThat(bPage.items()).extracting(FinanceFundAccountRow::accountNo).containsExactly("BANK-B");
    assertThat(bPage.total()).isEqualTo(1);
  }

  @Test
  void 断言1_汇总也只统计本租户() throws Exception {
    var aSummary =
        json.readValue(
            http.perform(
                    get("/finance/fund-accounts/balances/summary")
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundCurrencyBalanceSummary.class);
    assertThat(aSummary.totals()).extracting(FinanceFundCurrencyBalanceTotal::currency)
        .containsExactly("USD");
    assertThat(aSummary.totals().get(0).totalBalance()).isEqualByComparingTo("100");

    var bSummary =
        json.readValue(
            http.perform(
                    get("/finance/fund-accounts/balances/summary")
                        .header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundCurrencyBalanceSummary.class);
    assertThat(bSummary.totals()).extracting(FinanceFundCurrencyBalanceTotal::currency)
        .containsExactly("EUR");
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
                    "insert into finance_fund_account(id,tenant_id,account_no,account_name,"
                        + " account_type,currency,legal_entity_id,legal_entity_code,legal_entity_name,"
                        + " bank_name,account_number_masked,account_number_tail,account_holder,"
                        + " status,current_balance,current_balance_at,balance_record_count,note,"
                        + " version,created_at,updated_at)"
                        + " values (?,?,'FORGED','伪造','BANK','USD',?,?,'伪造','银行','****0000',"
                        + " '0000','','ACTIVE',0,?::timestamptz,0,'',0,?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    legalEntityA,
                    "LE_A",
                    now,
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select account_no from finance_fund_account where account_no = 'FORGED'").size())
        .isZero();
  }

  // ==========================================================================
  // 断言 2 的另一半 —— 伪造租户**头**无效，且**行落在会话租户名下**
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var body =
        """
        {"accountName":"A 建的账户","accountType":"BANK","currency":"USD",
         "legalEntityId":"%s","bankName":"招商银行","initialBalance":250}
        """
            .formatted(legalEntityA);

    http.perform(
            post("/finance/fund-accounts")
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
            a, () -> sql.fetch("select account_no, tenant_id from finance_fund_account order by account_no"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    // ...且它们落在**会话租户** a 名下，**不是**调用方自称的 b。
    var created = rows.get(0);
    assertThat(created.get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它（它不属于 b）。
    assertThat(accountNosOf(b)).containsExactly("BANK-B");
  }

  @Test
  void 断言2_伪造租户头读不到别的租户的账户() throws Exception {
    var aPage =
        json.readValue(
            http.perform(
                    get("/finance/fund-accounts")
                        .header("Authorization", "session-a")
                        .header("X-Tenant-Id", b.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceFundAccountPage.class);
    // A 只看得到自己的那一笔。
    assertThat(aPage.items()).extracting(FinanceFundAccountRow::accountNo).containsExactly("BANK-A");
    assertThat(aPage.total()).isEqualTo(1);
  }

  @Test
  void 断言2_余额历史也按租户隔离() {
    // A 与 B 各自测一次：A 的余额历史只有 A 能看见。
    tenants.run(
        a,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_fund_account_balance(id,tenant_id,account_id,currency,"
                  + " legal_entity_id,legal_entity_code,legal_entity_name,balance_amount,"
                  + " previous_amount,change_source,reason,source_system,source_reference,"
                  + " operator_user_id,recorded_at,created_at)"
                  + " values (?,?,?,'USD',?,?,'甲公司',7,100,'MANUAL','A 的调整','','',?,"
                  + " ?::timestamptz,?::timestamptz)",
              idFor(a, 9),
              a,
              idFor(a, 1),
              legalEntityA,
              "LE_A",
              UUID.randomUUID(),
              now,
              now);
          return null;
        });
    assertThat(balanceRowsOf(a)).isEqualTo(2);
    assertThat(balanceRowsOf(b)).isEqualTo(1);
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后一次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      assertThat(accountNosOf(a)).as("第 %d 轮租户 A", i).containsExactly("BANK-A");
      assertThat(accountNosOf(b)).as("第 %d 轮租户 B", i).containsExactly("BANK-B");
      assertThat(balanceRowsOf(a)).as("第 %d 轮 A 的余额历史", i).isEqualTo(1);
      assertThat(balanceRowsOf(b)).as("第 %d 轮 B 的余额历史", i).isEqualTo(1);
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(accountNosOf(a)).containsExactly("BANK-A");
    assertThat(accountNosOf(b)).containsExactly("BANK-B");
  }
}
