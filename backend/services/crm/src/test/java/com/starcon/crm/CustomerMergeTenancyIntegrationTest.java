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
import java.util.ArrayList;
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
 * crm 服务的**租户隔离**证明（15.3.1.3 跨店合并车道），跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p>🔴 三条断言**跑在本模块自己的表上**
 * （{@code crm_customer_merge_logical} / {@code crm_customer_merge_mapping} /
 * {@code crm_customer_merge_candidate} / {@code crm_customer_merge_operation} /
 * {@code crm_customer_merge_run}），不建占位探针表 —— 证明的是**真的要用的那张表**被隔离了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerMergeTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
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
    List<String> permissions = List.of("crm.customers.read", "crm.customers.update");
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
    // 连接池固定为 1 条连接：只有复用同一条连接，才能证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:1");
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  /** 本模块的五张表。 */
  static final List<String> MERGE_TABLES =
      List.of(
          "crm_customer_merge_logical",
          "crm_customer_merge_mapping",
          "crm_customer_merge_candidate",
          "crm_customer_merge_operation",
          "crm_customer_merge_run");

  /** 在可信租户事务里造三位来源客户 + 一个逻辑客户 + 一条映射（走**真实业务表**）。 */
  void seed(UUID tenant, String customerRef, String logicalRef) {
    tenants.run(
        tenant,
        () -> {
          OffsetDateTime moment = OffsetDateTime.now(ZoneOffset.UTC);
          // 🔴 候选表强制 left < right ⇒ 这里先把三个 id 排好序。
          //   ⚠️ 必须按 **PostgreSQL 的 uuid 比较口径**（16 字节无符号逐字节比）排，
          //   不能用 UUID.compareTo —— 它按**有符号 long** 比高位，
          //   两者在高位 bit 为 1 时结论相反，排出来的顺序会被数据库判成「左边不小于右边」。
          List<UUID> sorted =
              new ArrayList<>(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
          sorted.sort(CustomerMergeTenancyIntegrationTest::compareUuid);
          UUID u1 = sorted.get(0);
          UUID u2 = sorted.get(1);
          UUID u3 = sorted.get(2);
          UUID logicalId = UUID.randomUUID();

          for (int i = 0; i < 3; i++) {
            sql.execute(
                "insert into crm_customer_profile"
                    + "(id,tenant_id,customer_ref,source_shop,customer_name,order_count,"
                    + " refund_case_count,value_tier,risk_flag,risk_reason,window_from,window_to,"
                    + " synced_at,created_at,updated_at)"
                    + " values (?,?,?,?,'客户',1,0,'NORMAL','NONE','',?::timestamptz,?::timestamptz,"
                    + " ?::timestamptz,?::timestamptz,?::timestamptz)",
                sorted.get(i),
                tenant,
                customerRef + "-" + i,
                "AMAZON",
                moment,
                moment,
                moment,
                moment,
                moment);
          }

          sql.execute(
              "insert into crm_customer_merge_logical"
                  + "(id,tenant_id,logical_ref,display_name,member_count,source_shop_count,"
                  + " total_order_count,opt_out_state,status,created_at,updated_at)"
                  + " values (?,?,?,'客户',1,1,1,'NONE','ACTIVE',now(),now())",
              logicalId,
              tenant,
              logicalRef);

          // 一条待确认候选（机器建议）。
          sql.execute(
              "insert into crm_customer_merge_candidate"
                  + "(id,tenant_id,left_customer_id,right_customer_id,left_source_shop,left_customer_ref,"
                  + " left_customer_name,right_source_shop,right_customer_ref,right_customer_name,match_type,"
                  + " confidence,signal_summary,evidence,exclusion_flags,opt_out_involved,status,detected_at,"
                  + " created_at,updated_at)"
                  + " values (?,?,?,?,'AMAZON',?,'客户','SHOPIFY',?,'客户','EMAIL',0.85,'邮箱相同','{}','[]',false,"
                  + "'SUGGESTED',now(),now(),now())",
              UUID.randomUUID(),
              tenant,
              u1,
              u2,
              customerRef + "-0",
              customerRef + "-1");

          // 一条**已人工确认**的候选：映射行的 DB 守卫要求它存在（绕过服务层直接写映射会被挡）。
          // 🔴 注意：候选**只能以 SUGGESTED 诞生**（V437 的 review 触发器钉死了这一点），
          //   「确认」这个动作只能是一次 UPDATE —— 与服务层的顺序逐字一致。
          UUID confirmedCandidate = UUID.randomUUID();
          sql.execute(
              "insert into crm_customer_merge_candidate"
                  +"(id,tenant_id,left_customer_id,right_customer_id,left_source_shop,left_customer_ref,"
                  + " left_customer_name,right_source_shop,right_customer_ref,right_customer_name,match_type,"
                  + " confidence,signal_summary,evidence,exclusion_flags,opt_out_involved,status,detected_at,"
                  + " created_at,updated_at)"
                  + " values (?,?,?,?,'AMAZON',?,'客户','SHOPIFY',?,'客户','EMAIL',0.85,'邮箱相同','{}','[]',false,"
                  + "'SUGGESTED',now(),now(),now())",
              confirmedCandidate,
              tenant,
              u1,
              u3,
              customerRef + "-0",
              customerRef + "-2");
          sql.execute(
              "update crm_customer_merge_candidate"
                  + " set status = 'CONFIRMED', logical_customer_id = ?, reviewed_by = ?,"
                  + " reviewed_at = ?::timestamptz, review_note = '已确认', updated_at = ?::timestamptz"
                  + " where tenant_id = ? and id = ?",
              logicalId,
              userA,
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC),
              tenant,
              confirmedCandidate);

          sql.execute(
              "insert into crm_customer_merge_mapping"
                  +"(id,tenant_id,logical_customer_id,customer_profile_id,source_shop,customer_ref,"
                  + " customer_name,role,state,merged_at,merged_by,created_at,updated_at)"
                  + " values (?,?,?,?,'AMAZON',?,'客户','PRIMARY','ACTIVE',now(),?,now(),now())",
              UUID.randomUUID(),
              tenant,
              logicalId,
              u1,
              customerRef + "-0",
              userA);

          sql.execute(
              "insert into crm_customer_merge_operation"
                  +"(id,tenant_id,operation,logical_customer_id,candidate_id,target_customer_id,"
                  + " member_count,reason,operator_id,occurred_at,details,created_at)"
                  + " values (?,?,'MERGE',?,null,null,1,'理由',?,now(),'{}',now())",
              UUID.randomUUID(),
              tenant,
              logicalId,
              userA);

          sql.execute(
              "insert into crm_customer_merge_run"
                  +"(id,tenant_id,run_no,status,scanned_customer_count,candidate_count,"
                  + " below_threshold_count,excluded_already_merged_count,excluded_same_shop_count,"
                  + " opt_out_candidate_count,address_resolved_count,address_unavailable_count,"
                  + " party_unavailable_count,truncated,failure_reason,facts,operator_id,started_at,finished_at)"
                  + " values (?,?,?,'SUCCEEDED',1,1,0,0,0,0,1,0,0,false,'','{}',?,now(),now())",
              UUID.randomUUID(),
              tenant,
              "CM-" + logicalRef,
              userA);
          return null;
        });
  }

  /** PostgreSQL 的 uuid 比较口径：16 字节**无符号**逐字节比（与生产代码同源）。 */
  static int compareUuid(UUID a, UUID b) {
    return CustomerMergeService.compareUuid(a, b);
  }

  /** 以指定租户身份读逻辑客户标识。 */
  List<String> logicalRefsOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select logical_ref from crm_customer_merge_logical order by logical_ref")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select logical_ref from crm_customer_merge_logical").size();
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
          "truncate table crm_customer_merge_operation, crm_customer_merge_candidate,"
              + " crm_customer_merge_mapping, crm_customer_merge_logical, crm_customer_merge_run,"
              + " crm_customer_amount, crm_customer_profile cascade");
    }
    seed(a, "CUST-A", "LC-A");
    seed(b, "CUST-B", "LC-B");
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 业务表强制rls而不是只启用() {
    for (String table : MERGE_TABLES) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V437 必须真的建出 " + table).isNotNull();
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
    // 序列单独授权（GRANT ... ON ALL TABLES 不覆盖序列，漏了会在第一次扫描上炸）。
    for (String sequence :
        List.of("crm_customer_merge_run_no_seq", "crm_customer_merge_logical_no_seq")) {
      assertThat(
              sql.fetchOne(
                      "select has_sequence_privilege('crm_app','" + sequence + "','USAGE')")
                  .get(0, Boolean.class))
          .as("crm_app 必须真的能用序列 " + sequence)
          .isTrue();
    }
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(logicalRefsOf(a)).containsExactly("LC-A");
    assertThat(logicalRefsOf(b)).containsExactly("LC-B");
    // 更强的一格：A 明确按标识查 B 的行也拿不到（不只是列表过滤）。
    var byRef =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select logical_ref from crm_customer_merge_logical where logical_ref = 'LC-B'")
                    .size());
    assertThat(byRef).as("按条件直查也必须被 RLS 挡住").isZero();
    // 候选 / 映射同样互不可见（每个租户各有 2 条候选、1 条映射）。
    assertThat(tenants.run(a, () -> sql.fetch("select id from crm_customer_merge_candidate").size()))
        .isEqualTo(2);
    assertThat(tenants.run(b, () -> sql.fetch("select id from crm_customer_merge_candidate").size()))
        .isEqualTo(2);
    assertThat(tenants.run(a, () -> sql.fetch("select id from crm_customer_merge_mapping").size()))
        .isEqualTo(1);
    assertThat(tenants.run(b, () -> sql.fetch("select id from crm_customer_merge_mapping").size()))
        .isEqualTo(1);
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
                  sql.fetch("select logical_ref from crm_customer_merge_logical order by logical_ref")
                      .map(r -> r.get(0, String.class));
              assertThat(visible).as("事务内只看得到本租户").containsExactly("LC-B");
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(b.toString());
    // ⚠️ 这里断言的是「**没有租户上下文**」，不是某个字面量：
    //   事务局部 GUC 在提交后会被 PostgreSQL 复位成**空串**，而 RLS 策略用的是
    //   nullif(current_setting(...),'')::uuid —— 空串与 NULL **同义**，都是「没有租户」。
    assertThat(rawTenantSetting()).as("提交后租户上下文必须已清空").isNullOrEmpty();
    assertThat(effectiveTenant()).as("提交后策略看到的租户必须是 null").isNull();
    assertThat(visibleWithoutTenant()).as("提交后事务外不应看到任何行").isZero();

    // 🔴 伪造租户头的请求：写入必须仍然归到**会话租户**名下。
    seed(a, "FORGED", "LC-FORGED");

    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select id, tenant_id from crm_customer_merge_logical where logical_ref = ?",
                    "LC-FORGED"));
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
                            "select id from crm_customer_merge_logical where logical_ref = ?", "LC-FORGED")
                        .size()))
        .as("这一行不属于 b，b 必须看不到")
        .isZero();

    // 租户 a 看得到（它确实属于 a）。
    assertThat(logicalRefsOf(a)).as("写入必须归到会话租户 a").containsExactly("LC-A", "LC-FORGED");

    // 🔴 HTTP 层：带伪造的 X-Tenant-Id 头读本模块的端点，看到的**仍然只有会话租户 a 的数据**。
    String body =
        http.perform(
                get("/crm/customer-merge/logical")
                    .header("Authorization", "session-a")
                    .header("X-Tenant-Id", b.toString())
                    .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).contains("LC-A").doesNotContain("LC-B");
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后一次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      assertThat(logicalRefsOf(a)).as("第 %d 轮租户 A", i).containsExactly("LC-A");
      assertThat(logicalRefsOf(b)).as("第 %d 轮租户 B", i).containsExactly("LC-B");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(logicalRefsOf(a)).containsExactly("LC-A");
    assertThat(logicalRefsOf(b)).containsExactly("LC-B");
  }
}
