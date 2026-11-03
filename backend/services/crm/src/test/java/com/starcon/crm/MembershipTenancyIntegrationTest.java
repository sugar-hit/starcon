package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
 * crm 服务的**租户隔离**证明（15.6.1.1 会员与等级车道），跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p>刻意跑在**真实业务表**上（本车道新建的四张表），不建占位探针表 ——
 * 证明的是**真的要用的那张表**被隔离了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MembershipTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role crm_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    List<String> permissions = List.of("crm.membership.read", "crm.membership.update");
    actors.put(
        "session-a",
        new AccessService.Access(
            userA,
            a,
            "Test",
            UUID.randomUUID(),
            null,
            permissions,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions))));
    actors.put(
        "session-b",
        new AccessService.Access(
            UUID.randomUUID(),
            b,
            "Test",
            UUID.randomUUID(),
            null,
            permissions,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions))));
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
    r.add("DB_USER", () -> "crm_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  /** 在可信租户事务里插一位会员 + 一条等级规则（走**真实业务表**）。 */
  void insertMember(UUID tenant, UUID id, String customerRef, String levelCode) {
    tenants.run(
        tenant,
        () -> {
          OffsetDateTime moment = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into crm_membership_profile"
                  + "(id,tenant_id,member_key,customer_ref,source_shop,customer_name,member_count,"
                  + " source_shops,members,order_count,total_spend,spend_currency,amounts,"
                  + " repurchase_rate,first_order_at,last_order_at,level_code,level_rank,level_origin,"
                  + " level_reason,level_evaluated_at,registered_at,created_at,updated_at)"
                  + " values (?,?,?,?,'AMAZON','客户',1,'[\"AMAZON\"]'::jsonb,'[]'::jsonb,3,120.00,"
                  + " 'USD','[{\"currency\":\"USD\",\"orderCount\":3,\"totalSpend\":120.00}]'::jsonb,"
                  + " 0.666667,?::timestamptz,?::timestamptz,?,?,'RULE','测试用判定理由',"
                  + " ?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz)",
              id,
              tenant,
              "AMAZON " + customerRef,
              customerRef,
              moment,
              moment,
              levelCode,
              "GOLD".equals(levelCode) ? 2 : 0,
              moment,
              moment,
              moment,
              moment);
          sql.execute(
              "insert into crm_membership_level_change"
                  + "(id,tenant_id,member_key,customer_ref,source_shop,customer_name,from_level_code,"
                  + " to_level_code,from_level_rank,to_level_rank,change_kind,origin,reason,basis,"
                  + " changed_at,created_at)"
                  + " values (?,?,?,?,'AMAZON','客户','NONE',?,0,2,'REGISTERED','RULE','测试用变更理由',"
                  + " '{}'::jsonb,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              "AMAZON " + customerRef,
              customerRef,
              levelCode,
              moment,
              moment);
          sql.execute(
              "insert into crm_membership_level_rule"
                  + "(id,tenant_id,level_code,level_name,level_rank,min_total_spend,"
                  + " min_total_spend_currency,min_order_count,enabled,effective_from,note,"
                  + " created_at,updated_at)"
                  + " values (?,?,'GOLD','黄金会员',2,100.00,'USD',1,true,?::timestamptz,'测试用等级规则',"
                  + " ?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              moment,
              moment,
              moment);
          return null;
        });
  }

  /** 以指定租户身份读会员标识。 */
  List<String> memberKeysOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select member_key from crm_membership_profile order by member_key")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select member_key from crm_membership_profile").size();
  }

  String rawTenantSetting() {
    return sql.fetch("select current_setting('app.current_tenant', true)").stream()
        .findFirst()
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  /** RLS 策略**实际**使用的值：空串与 NULL 都归一成 null，也就是「没有租户」。 */
  UUID effectiveTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_membership_level_rule, crm_membership_profile,"
              + " crm_membership_level_change, crm_membership_run cascade");
    }
    insertMember(a, UUID.randomUUID(), "CUST-A", "GOLD");
    insertMember(b, UUID.randomUUID(), "CUST-B", "NONE");
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 业务表强制rls而不是只启用() {
    for (String table :
        List.of(
            "crm_membership_level_rule",
            "crm_membership_profile",
            "crm_membership_level_change",
            "crm_membership_run")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V440 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('crm_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("crm_app 必须真的能读 " + table)
          .isTrue();
    }
    // 序列单独授权（GRANT ... ON ALL TABLES 不覆盖序列，漏了会在第一次重算上炸）。
    assertThat(
            sql.fetchOne(
                    "select has_sequence_privilege('crm_app','crm_membership_run_no_seq','USAGE')")
                .get(0, Boolean.class))
        .as("crm_app 必须真的能用重算运行号序列")
        .isTrue();
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(memberKeysOf(a)).containsExactly("AMAZON CUST-A");
    assertThat(memberKeysOf(b)).containsExactly("AMAZON CUST-B");
    // 更强的一格：A 明确按标识查 B 的行也拿不到（不只是列表过滤）。
    var byKey =
        tenants.run(
            a,
            () ->
                sql.fetch("select member_key from crm_membership_profile where member_key = 'AMAZON CUST-B'")
                    .size());
    assertThat(byKey).as("按条件直查也必须被 RLS 挡住").isZero();

    // 等级规则与等级变更记录同样隔离。
    var rules =
        tenants.run(a, () -> sql.fetch("select id from crm_membership_level_rule").size());
    assertThat(rules).as("等级规则也必须按租户隔离").isEqualTo(1);
    var changes = tenants.run(a, () -> sql.fetch("select id from crm_membership_level_change").size());
    assertThat(changes).as("等级变更记录也必须按租户隔离").isEqualTo(1);
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户头无效，且**行落在自称的租户名下**
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(effectiveTenant()).as("未绑定租户时策略看到的租户必须是 null").isNull();
    assertThat(visibleWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    var bound =
        tenants.run(
            b,
            () -> {
              // ⚠️ 刻意**不**在这里再调 tenants.run（那是嵌套事务）：
              //   这里要证明的是「事务内只看得到本租户」，直接查一次即可。
              var visible =
                  sql.fetch("select member_key from crm_membership_profile order by member_key")
                      .map(r -> r.get(0, String.class));
              assertThat(visible).as("事务内只看得到本租户").containsExactly("AMAZON CUST-B");
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(b.toString());
    // ⚠️ 这里断言的是「**没有租户上下文**」，不是某个字面量：
    //   事务局部 GUC 在提交后会被 PostgreSQL 复位成**空串**，而 RLS 策略用的是
    //   nullif(current_setting(...),'')::uuid —— 空串与 NULL **同义**，都是「没有租户」。
    assertThat(rawTenantSetting()).as("提交后租户上下文必须已清空").isNullOrEmpty();
    assertThat(effectiveTenant()).as("提交后策略看到的租户必须是 null").isNull();
    assertThat(visibleWithoutTenant()).as("提交后事务外不应看到任何行").isZero();

    // 🔴 伪造租户头的请求：走真实端点（读列表 + 建等级规则）。
    //   规则是**写**动作，走它才能证明「写入归属也不受头影响」。
    String body =
        http.perform(
                post("/crm/customer-membership/levels")
                    .header("Authorization", "session-a")
                    .header("X-Tenant-Id", b.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"levelCode\":\"FORGED\",\"levelName\":\"伪造头建的等级\",\"levelRank\":9,"
                            + "\"effectiveFrom\":\"2026-01-01T00:00:00Z\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).contains("FORGED");

    // 写入必须落在**会话租户 a** 名下，而不是伪造头里的 b。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select id, tenant_id, level_code from crm_membership_level_rule"
                        + " where level_code = 'FORGED'"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(1);
    assertThat(rows.get(0).get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它（它不属于 b）。
    assertThat(
            tenants.run(
                b,
                () ->
                    sql.fetch(
                            "select id from crm_membership_level_rule where level_code = 'FORGED'")
                        .size()))
        .as("这一行不属于 b，b 必须看不到")
        .isZero();

    // 🔴 HTTP 层：带伪造的 X-Tenant-Id 头读会员档案，看到的**仍然只有会话租户 a 的数据**。
    String members =
        http.perform(
                get("/crm/customer-membership/members")
                    .header("Authorization", "session-a")
                    .header("X-Tenant-Id", b.toString())
                    .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(members).contains("CUST-A").doesNotContain("CUST-B");
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后一次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      assertThat(memberKeysOf(a)).as("第 %d 轮租户 A", i).containsExactly("AMAZON CUST-A");
      assertThat(memberKeysOf(b)).as("第 %d 轮租户 B", i).containsExactly("AMAZON CUST-B");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(memberKeysOf(a)).containsExactly("AMAZON CUST-A");
    assertThat(memberKeysOf(b)).containsExactly("AMAZON CUST-B");
  }
}
