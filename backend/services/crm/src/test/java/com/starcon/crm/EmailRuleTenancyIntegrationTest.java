package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.tenancy.TenantTransactions;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * crm 服务的<b>租户隔离</b>证明（15.1.2.2 邮件规则车道），跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p>刻意跑在**真实业务表**上（{@code crm_email_rule} / {@code crm_email_ticket_tag} /
 * {@code crm_email_ticket_tag_assignment} / {@code crm_email_rule_hit} /
 * {@code crm_email_rule_assignment}），不建占位探针表 ——
 * 证明的是**真的要用的那张表**被隔离了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmailRuleTenancyIntegrationTest {

  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", EmailRuleTestSupport.PG::getJdbcUrl);
    r.add("DB_USER", () -> "crm_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", EmailRuleTestSupport.PG::getUsername);
    r.add("DB_MIGRATION_PASSWORD", EmailRuleTestSupport.PG::getPassword);
    // 池大小为 1：下面每次读写都复用同一条连接。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + EmailRuleTestSupport.identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @BeforeEach
  void resetThenSeed() {
    EmailRuleTestSupport.truncate();
    EmailRuleTestSupport.ACTORS.put(
        "session-a", EmailRuleTestSupport.actor(userA, a, "A", EmailRuleTestSupport.PERMISSIONS));
    insertRule(a, UUID.randomUUID(), "RULE-A");
    insertRule(b, UUID.randomUUID(), "RULE-B");
  }

  // ================================================================ 夹具

  /** 在可信租户事务里插一条规则。 */
  void insertRule(UUID tenant, UUID id, String code) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_email_rule"
                  + "(id,tenant_id,rule_code,rule_name,priority_order,enabled,action,"
                  + " sender_emails,sender_domains,keywords,ticket_tag_codes,match_mode,"
                  + " assign_target_type,assign_target_code,reply_template_code,reply_subject,"
                  + " reply_body,important_markers,version,note,created_at,updated_at)"
                  + " values (?,?,?,'规则',10,true,'SILENCE','','','spam','','ANY',"
                  + " 'TEAM','','','','','',1,'',?::timestamptz,?::timestamptz)",
              id,
              tenant,
              code,
              OffsetDateTime.now(ZoneOffset.UTC),
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
  }

  /** 以指定租户身份读规则编码。 */
  List<String> rulesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select rule_code from crm_email_rule order by rule_code")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select rule_code from crm_email_rule").size();
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

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 业务表强制rls而不是只启用() {
    for (String table :
        List.of(
            "crm_email_rule",
            "crm_email_ticket_tag",
            "crm_email_ticket_tag_assignment",
            "crm_email_rule_hit",
            "crm_email_rule_assignment")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V305 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住").isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('crm_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("crm_app 必须真的能读 " + table)
          .isTrue();
    }
    // 🔴 序列单独授权：GRANT ... ON ALL TABLES 不覆盖序列。
    assertThat(
            sql.fetchOne(
                    "select has_sequence_privilege('crm_app','crm_email_rule_hit_no_seq','USAGE')")
                .get(0, Boolean.class))
        .as("命中号序列必须单独授权，否则第一条命中就报权限不足")
        .isTrue();
    assertThat(
            sql.fetchOne(
                    "select has_sequence_privilege('crm_app','crm_email_rule_assignment_no_seq','USAGE')")
                .get(0, Boolean.class))
        .as("分配号序列必须单独授权")
        .isTrue();
  }

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(rulesOf(a)).containsExactly("RULE-A");
    assertThat(rulesOf(b)).containsExactly("RULE-B");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch("select rule_code from crm_email_rule where rule_code = 'RULE-B'").size());
    assertThat(byId).as("按条件直查也必须被 RLS 挡住").isZero();
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
                  sql.fetch("select rule_code from crm_email_rule order by rule_code")
                      .map(r -> r.get(0, String.class));
              assertThat(visible).as("事务内只看得到本租户").containsExactly("RULE-B");
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(b.toString());
    assertThat(rawTenantSetting()).as("提交后租户上下文必须已清空").isNullOrEmpty();
    assertThat(effectiveTenant()).as("提交后策略看到的租户必须是 null").isNull();
    assertThat(visibleWithoutTenant()).as("提交后事务外不应看到任何行").isZero();

    String content =
        """
        {"ruleCode":"FORGED-001","ruleName":"伪造头建的规则","priorityOrder":5,
         "action":"SILENCE","keywords":["forged"],"matchMode":"ANY","enabled":true}
        """;

    http.perform(
            post("/crm/email-rules/rules")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(content))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉），且**落在会话租户 a 名下**。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select id, tenant_id from crm_email_rule where rule_code = ?", "FORGED-001"));
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
                    sql.fetch("select id from crm_email_rule where rule_code = ?", "FORGED-001")
                        .size()))
        .as("这一行不属于 b，b 必须看不到")
        .isZero();

    // 租户 a 看得到（它确实属于 a）。
    assertThat(rulesOf(a)).as("写入必须归到会话租户 a").containsExactly("FORGED-001", "RULE-A");
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后一次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      assertThat(rulesOf(a)).as("第 %d 轮租户 A", i).containsExactly("RULE-A");
      assertThat(rulesOf(b)).as("第 %d 轮租户 B", i).containsExactly("RULE-B");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(rulesOf(a)).containsExactly("RULE-A");
    assertThat(rulesOf(b)).containsExactly("RULE-B");
  }
}
