package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.finance.CostAllocationModels.*;
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
 * 12.4.2.2 分摊规则的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * {@code finance_cost_allocation_rule} / {@code finance_cost_allocation_target} /
 * {@code finance_cost_allocation_batch} / {@code finance_cost_allocation_result}。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CostAllocationTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-finance-cost-allocation-" + tenant + "-" + ordinal).getBytes());
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
        List.of(
            "finance.costallocation.read",
            "finance.costallocation.create",
            "finance.costallocation.update");
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
          "truncate table finance_cost_allocation_result, finance_cost_allocation_batch,"
              + " finance_cost_allocation_target, finance_cost_allocation_rule,"
              + " finance_cost_collection_basis, finance_cost_collection_entry,"
              + " finance_cost_collection_pool, finance_cost_collection_rule"
              + " restart identity cascade");
    }
    seedTenant(a, idFor(a, 1), "A 的分摊规则");
    seedTenant(b, idFor(b, 1), "B 的分摊规则");
  }

  /**
   * 在可信租户事务里落一整套分摊事实：归集规则 → 费用池 → 归集条目 →
   * 分摊规则 → 分摊对象 → 分摊批次 → 分摊结果。
   */
  void seedTenant(UUID tenant, UUID ruleId, String ruleName) {
    var poolId = idFor(tenant, 2);
    var collectionRuleId = idFor(tenant, 3);
    var entryId = idFor(tenant, 4);
    var targetId = idFor(tenant, 5);
    var batchId = idFor(tenant, 6);
    var resultId = idFor(tenant, 7);
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into finance_cost_collection_rule(id,tenant_id,rule_code,rule_name,fee_source,"
                  + " fee_category,target_dimension,period_basis,pool_key,pool_name,effective_from,"
                  + " status,created_at,updated_at)"
                  + " values(?,?,?,?,'PLATFORM_SETTLEMENT','PLATFORM_FEE','MONTH','CALENDAR_MONTH',"
                  + " 'PLATFORM_FEE','平台费',?::date,'ACTIVE',?::timestamptz,?::timestamptz)",
              collectionRuleId,
              tenant,
              "CR-" + tenant,
              "归集规则",
              LocalDate.of(2026, 1, 1),
              now,
              now);
          sql.execute(
              "insert into finance_cost_collection_pool(id,tenant_id,pool_key,pool_name,dimension,"
                  + " period_start,period_end,currency,status,total_amount,entry_count,"
                  + " allocation_available,allocation_owner_module,created_at,updated_at)"
                  + " values(?,?,'PLATFORM_FEE','平台费','MONTH',?::date,?::date,'USD','OPEN',0,0,"
                  + " false,'12.4.2.2',?::timestamptz,?::timestamptz)",
              poolId,
              tenant,
              LocalDate.of(2026, 3, 1),
              LocalDate.of(2026, 3, 31),
              now,
              now);
          sql.execute(
              "insert into finance_cost_collection_entry(id,tenant_id,pool_id,rule_id,fee_source,"
                  + " fee_category,target_dimension,base_date,currency,amount,source_ref_type,"
                  + " source_ref_key,collected_at,created_at,updated_at)"
                  + " values(?,?,?,?,'PLATFORM_SETTLEMENT','PLATFORM_FEE','MONTH',?::date,'USD',"
                  + " '10.0000','MANUAL_DOCUMENT',?,?::timestamptz,?::timestamptz,?::timestamptz)",
              entryId,
              tenant,
              poolId,
              collectionRuleId,
              LocalDate.of(2026, 3, 18),
              "E-" + tenant,
              now,
              now,
              now);
          sql.execute(
              "insert into finance_cost_allocation_rule(id,tenant_id,rule_code,rule_name,"
                  + " target_dimension,allocation_basis,pool_key,priority,rounding_policy,"
                  + " effective_from,status,note,created_at,updated_at)"
                  + " values(?,?,?,?,'ORDER','AMOUNT','*',10,'LARGEST_REMAINDER',?::date,'ACTIVE','',"
                  + " ?::timestamptz,?::timestamptz)",
              ruleId,
              tenant,
              "R-" + tenant,
              ruleName,
              LocalDate.of(2026, 1, 1),
              now,
              now);
          sql.execute(
              "insert into finance_cost_allocation_target(id,tenant_id,pool_id,target_dimension,"
                  + " target_type,target_code,target_name,basis_amount,basis_source,target_date,"
                  + " collected_at,created_at)"
                  + " values(?,?,?,'ORDER','ORDER','SO-001','订单一','10.0000','TRADE_PLATFORM_ORDER',"
                  + " ?::date,?::timestamptz,?::timestamptz)",
              targetId,
              tenant,
              poolId,
              LocalDate.of(2026, 3, 18),
              now,
              now);
          sql.execute(
              "insert into finance_cost_allocation_batch(id,tenant_id,pool_id,rule_id,currency,"
                  + " source_total,allocated_total,rounding_difference,rounding_target_type,"
                  + " rounding_target_code,target_count,basis_unavailable_count,status,allocated_at,"
                  + " note,created_at,updated_at)"
                  + " values(?,?,?,?,'USD','10.0000','10.0000',0,'ORDER','SO-001',1,0,'ALLOCATED',"
                  + " ?::timestamptz,'',?::timestamptz,?::timestamptz)",
              batchId,
              tenant,
              poolId,
              ruleId,
              now,
              now,
              now);
          sql.execute(
              "insert into finance_cost_allocation_result(id,tenant_id,batch_id,target_dimension,"
                  + " target_code,target_name,basis_value,basis_available,share_ratio,exact_amount,"
                  + " allocated_amount,rounding_adjustment,is_rounding_target,basis_note,created_at)"
                  + " values(?,?,?,'ORDER','SO-001','订单一','10.000000',true,'1.0000000000',"
                  + " '10.0000000000','10.0000',0,true,'',?::timestamptz)",
              resultId,
              tenant,
              batchId,
              now);
          return null;
        });
  }

  /** 以指定租户身份读规则名。 */
  List<String> ruleNamesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select rule_name from finance_cost_allocation_rule order by rule_name")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读分摊结果的目标编码。 */
  List<String> resultCodesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select target_code from finance_cost_allocation_result order by target_code")
                .map(r -> r.get(0, String.class)));
  }

  /** 以指定租户身份读分摊批次数。 */
  long batchCountOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetchOne("select count(*) from finance_cost_allocation_batch").get(0, Long.class));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select rule_name from finance_cost_allocation_rule").size();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 本模块的四张表强制rls而不是只启用() {
    for (String table :
        List.of(
            "finance_cost_allocation_rule",
            "finance_cost_allocation_target",
            "finance_cost_allocation_batch",
            "finance_cost_allocation_result")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V354 必须真的建出 " + table).isNotNull();
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
  // 断言 1 —— 两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(ruleNamesOf(a)).containsExactly("A 的分摊规则");
    assertThat(ruleNamesOf(b)).containsExactly("B 的分摊规则");

    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select rule_name from finance_cost_allocation_rule where id = ?",
                        idFor(b, 1))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();

    // 分摊批次与分摊结果同样隔离。
    assertThat(batchCountOf(a)).isEqualTo(1);
    assertThat(batchCountOf(b)).isEqualTo(1);
    assertThat(resultCodesOf(a)).containsExactly("SO-001");
    assertThat(resultCodesOf(b)).containsExactly("SO-001");

    // 没有租户上下文时一行都看不见。
    assertThat(visibleWithoutTenant()).as("事务外必须一行都看不见").isZero();
  }

  @Test
  void 断言1_分摊端点只看得到本租户的数据() throws Exception {
    List<FinanceCostAllocationRule> aRules =
        json.readValue(
            http.perform(
                    get("/finance/cost-allocations/rules").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            json.getTypeFactory().constructCollectionType(List.class, FinanceCostAllocationRule.class));
    assertThat(aRules)
        .extracting(FinanceCostAllocationRule::ruleName)
        .containsExactly("A 的分摊规则");

    List<FinanceCostAllocationRule> bRules =
        json.readValue(
            http.perform(
                    get("/finance/cost-allocations/rules").header("Authorization", "session-b"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            json.getTypeFactory().constructCollectionType(List.class, FinanceCostAllocationRule.class));
    assertThat(bRules)
        .extracting(FinanceCostAllocationRule::ruleName)
        .containsExactly("B 的分摊规则");

    // A 按 B 的批次 id 查分摊结果 ⇒ 拿不到。
    var results =
        json.readValue(
            http.perform(
                    get("/finance/cost-allocations/results?batchId=" + idFor(b, 6))
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceCostAllocationResultPage.class);
    assertThat(results.items()).isEmpty();
    assertThat(results.total()).isZero();
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
                    "insert into finance_cost_allocation_rule(id,tenant_id,rule_code,rule_name,"
                        + " target_dimension,allocation_basis,pool_key,priority,rounding_policy,"
                        + " effective_from,status,created_at,updated_at)"
                        + " values(?,?,'FORGED','伪造','ORDER','AMOUNT','*',10,'LARGEST_REMAINDER',"
                        + " ?::date,'ACTIVE',?::timestamptz,?::timestamptz)",
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
    assertThat(sql.fetch("select rule_name from finance_cost_allocation_rule where rule_name = '伪造'").size())
        .isZero();
  }

  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    var body =
        """
        {"ruleCode":"R-FORGED","ruleName":"伪造头建的规则","targetDimension":"ORDER",
         "allocationBasis":"AMOUNT","effectiveFrom":"2026-01-01"}
        """;

    http.perform(
            post("/finance/cost-allocations/rules")
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
                    "select rule_name, tenant_id from finance_cost_allocation_rule order by rule_name"));
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
    assertThat(ruleNamesOf(b)).containsExactly("B 的分摊规则");
  }

  @Test
  void 断言2_伪造租户头读不到别的租户的分摊结果() throws Exception {
    var aResults =
        json.readValue(
            http.perform(
                    get("/finance/cost-allocations/results")
                        .header("Authorization", "session-a")
                        .header("X-Tenant-Id", b.toString()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            FinanceCostAllocationResultPage.class);
    // A 只能看见自己那一条（伪造的头不能把 B 的结果端过来）。
    assertThat(aResults.total()).isEqualTo(1);
    assertThat(aResults.totalAmount()).isEqualByComparingTo("10.0000");
  }

  // ==========================================================================
  // 断言 3 —— 连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 交替使用同一批连接（A → B → A → B …），每次都必须只看见自己租户的行。
    for (int round = 0; round < 5; round++) {
      assertThat(ruleNamesOf(a)).as("第 " + round + " 轮：A 只能看见 A 的规则")
          .containsExactly("A 的分摊规则");
      assertThat(ruleNamesOf(b)).as("第 " + round + " 轮：B 只能看见 B 的规则")
          .containsExactly("B 的分摊规则");
    }
    // 事务外读（连接已归还池中）：仍然一行都看不见。
    assertThat(visibleWithoutTenant()).as("连接归还后不留租户上下文").isZero();
    // 再来一轮，确认归还的那条连接没有残留 GUC。
    assertThat(ruleNamesOf(a)).containsExactly("A 的分摊规则");
  }

  @Test
  void 断言3_分摊对象与结果也按租户隔离() {
    var aTargets =
        tenants.run(
            a,
            () ->
                sql.fetch("select target_code from finance_cost_allocation_target")
                    .map(r -> r.get(0, String.class)));
    assertThat(aTargets).containsExactly("SO-001");
    var bTargets =
        tenants.run(
            b,
            () ->
                sql.fetch("select target_code from finance_cost_allocation_target")
                    .map(r -> r.get(0, String.class)));
    assertThat(bTargets).containsExactly("SO-001");

    // A 看不到 B 的池（上游 12.4.2.1 的池同样按租户隔离）。
    var aPools =
        tenants.run(
            a, () -> sql.fetchOne("select count(*) from finance_cost_collection_pool").get(0, Long.class));
    assertThat(aPools).isEqualTo(1);
  }
}
