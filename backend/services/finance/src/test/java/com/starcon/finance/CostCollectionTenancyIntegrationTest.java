package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.CostCollectionModels.*;
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
 * 12.4.2.1 费用归集的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * {@code finance_cost_collection_rule} / {@code finance_cost_collection_pool} /
 * {@code finance_cost_collection_entry} / {@code finance_cost_collection_basis}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CostCollectionTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-finance-cost-collection-" + tenant + "-" + ordinal).getBytes());
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
        List.of("finance.costcollection.read", "finance.costcollection.create", "finance.costcollection.update");
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

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_cost_collection_basis, finance_cost_collection_entry,"
              + " finance_cost_collection_pool, finance_cost_collection_rule"
              + " restart identity cascade");
    }
    seedRule(a, idFor(a, 1), "R-A", "A 的平台费规则");
    seedRule(b, idFor(b, 1), "R-B", "B 的平台费规则");
  }

  /** 在可信租户事务里插一条归集规则（平台费 / 按月 / 自然月）。 */
  void seedRule(UUID tenant, UUID id, String code, String name) {
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_cost_collection_rule(id,tenant_id,rule_code,rule_name,fee_source,"
                  + " fee_category,target_dimension,period_basis,pool_key,pool_name,effective_from,"
                  + " status,created_at,updated_at)"
                  + " values(?,?,?,?,'PLATFORM_SETTLEMENT','PLATFORM_FEE','MONTH','CALENDAR_MONTH',"
                  + " 'PLATFORM_FEE','平台费',?::date,'ACTIVE',?::timestamptz,?::timestamptz)",
              id,
              tenant,
              code,
              name,
              LocalDate.of(2026, 1, 1),
              now,
              now);
          return null;
        });
  }

  /** 以指定租户身份读规则名。 */
  List<String> ruleNamesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select rule_name from finance_cost_collection_rule order by rule_name")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读条目金额。 */
  List<BigDecimal> entryAmountsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select amount from finance_cost_collection_entry order by amount")
                .map(r -> r.get(0, BigDecimal.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select rule_name from finance_cost_collection_rule").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的四张表强制rls而不是只启用() {
    for (String table :
        List.of(
            "finance_cost_collection_rule",
            "finance_cost_collection_pool",
            "finance_cost_collection_entry",
            "finance_cost_collection_basis")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V351 必须真的建出 " + table).isNotNull();
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
    assertThat(ruleNamesOf(a)).containsExactly("A 的平台费规则");
    assertThat(ruleNamesOf(b)).containsExactly("B 的平台费规则");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select rule_name from finance_cost_collection_rule where id = ?",
                        idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_报表端点只看得到本租户的数据() throws Exception {
    List<FinanceCostCollectionRule> aRules =
        json.readValue(
            http.perform(
                    get("/finance/cost-collections/rules").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            json.getTypeFactory().constructCollectionType(List.class, FinanceCostCollectionRule.class));
    assertThat(aRules)
        .extracting(FinanceCostCollectionRule::ruleName)
        .containsExactly("A 的平台费规则");

    List<FinanceCostCollectionRule> bRules =
        json.readValue(
            http.perform(
                    get("/finance/cost-collections/rules").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            json.getTypeFactory().constructCollectionType(List.class, FinanceCostCollectionRule.class));
    assertThat(bRules)
        .extracting(FinanceCostCollectionRule::ruleName)
        .containsExactly("B 的平台费规则");
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
                    "insert into finance_cost_collection_rule(id,tenant_id,rule_code,rule_name,"
                        + " fee_source,fee_category,target_dimension,period_basis,pool_key,"
                        + " effective_from,status,created_at,updated_at)"
                        + " values(?,?,'FORGED','伪造','PLATFORM_SETTLEMENT','PLATFORM_FEE','MONTH',"
                        + " 'CALENDAR_MONTH','PLATFORM_FEE',?::date,'ACTIVE',?::timestamptz,?::timestamptz)",
                    UUID.randomUUID(),
                    forged,
                    LocalDate.of(2026, 1, 1),
                    now,
                    now))
        .as("没有可信租户上下文时，写入必须被 RLS 挡住 —— 租户不是客户端自称的")
        // ⚠️ 用 hasStackTraceContaining 而不是 hasMessageContaining：
        //   jOOQ 把 PG 的报错包成「bad SQL grammar」，真正的原因（RLS 策略拒绝）在 cause 里。
        .hasStackTraceContaining("row-level security");

    // 伪造的行一条都没落库。
    assertThat(sql.fetch("select rule_name from finance_cost_collection_rule where rule_name = '伪造'").size())
        .isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var body =
        """
        {"ruleCode":"R-FORGED","ruleName":"伪造头建的规则","feeSource":"SUBSCRIPTION",
         "feeCategory":"SUBSCRIPTION_FEE","targetDimension":"MONTH","periodBasis":"CALENDAR_MONTH",
         "poolKey":"SUBSCRIPTION_FEE","poolName":"订阅费","effectiveFrom":"2026-01-01"}
        """;

    http.perform(
            post("/finance/cost-collections/rules")
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
                    "select rule_name, tenant_id from finance_cost_collection_rule order by rule_name"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(2);
    var forged =
        rows.stream()
            .filter(r -> "伪造头建的规则".equals(r.get("rule_name", String.class)))
            .findFirst()
            .orElseThrow();
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(forged.get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它，b 的规则仍然是 b 自己的。
    assertThat(ruleNamesOf(b)).containsExactly("B 的平台费规则");
  }

  @Test
  void 断言2_伪造租户头读不到别的租户的归集明细() throws Exception {
    var aEntries =
        json.readValue(
            http.perform(
                    get("/finance/cost-collections/entries")
                        .header("Authorization", "session-a")
                        .header("X-Tenant-Id", b.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceCostCollectionEntryPage.class);
    assertThat(aEntries.items()).isEmpty();
    assertThat(aEntries.total()).isZero();
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(ruleNamesOf(a)).as("第 " + round + " 轮：A 只能看见 A 的规则").containsExactly("A 的平台费规则");
      assertThat(ruleNamesOf(b)).as("第 " + round + " 轮：B 只能看见 B 的规则").containsExactly("B 的平台费规则");
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(ruleNamesOf(a)).containsExactly("A 的平台费规则");
  }

  @Test
  void 断言3_归集条目也按租户隔离() {
    // A 在自己的事务里建一条条目，B 必须看不见。
    var ruleId = idFor(a, 1);
    var poolId = idFor(a, 2);
    var entryId = idFor(a, 3);
    var basisId = idFor(a, 4);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    tenants.run(
        a,
        () -> {
          sql.execute(
              "insert into finance_cost_collection_pool(id,tenant_id,pool_key,pool_name,dimension,"
                  + " period_start,period_end,currency,status,total_amount,entry_count,"
                  + " allocation_available,allocation_owner_module,created_at,updated_at)"
                  + " values(?,?,'PLATFORM_FEE','平台费','MONTH',?::date,?::date,'USD','OPEN',0,0,"
                  + " false,'12.4.2.2',?::timestamptz,?::timestamptz)",
              poolId,
              a,
              LocalDate.of(2026, 3, 1),
              LocalDate.of(2026, 3, 31),
              now,
              now);
          sql.execute(
              "insert into finance_cost_collection_entry(id,tenant_id,pool_id,rule_id,fee_source,"
                  + " fee_category,target_dimension,base_date,currency,amount,source_ref_type,"
                  + " source_ref_key,collected_at,created_at,updated_at)"
                  + " values(?,?,?,?,'PLATFORM_SETTLEMENT','PLATFORM_FEE','MONTH',?::date,'USD',"
                  + " '12.3400','SETTLEMENT_LINE','SETTLEMENT_LINE:1',?::timestamptz,?::timestamptz,?::timestamptz)",
              entryId,
              a,
              poolId,
              ruleId,
              LocalDate.of(2026, 3, 18),
              now,
              now,
              now);
          sql.execute(
              "insert into finance_cost_collection_basis(id,tenant_id,entry_id,basis_type,basis_code,"
                  + " basis_currency,basis_amount,created_at)"
                  + " values(?,?,?,'SETTLEMENT_LINE','ST-A','USD','12.3400',?::timestamptz)",
              basisId,
              a,
              entryId,
              now);
          return null;
        });

    assertThat(entryAmountsOf(a)).containsExactly(new BigDecimal("12.3400"));
    assertThat(entryAmountsOf(b)).as("B 看不见 A 的条目").isEmpty();

    // 池合计也被触发器算成了 12.34（只在 A 的租户事务里可见）。
    var poolTotal =
        tenants.run(
            a,
            () -> sql.fetchOne("select total_amount from finance_cost_collection_pool").get(0, BigDecimal.class));
    assertThat(poolTotal).isEqualByComparingTo("12.3400");
  }
}
