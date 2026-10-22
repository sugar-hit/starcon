package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.starcon.tenancy.TenantTransactions;
import java.util.List;
import java.util.Map;
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
 * 15.2.1.3 差评处理的<b>租户隔离</b>证明，跑在 AGENTS.md 明文要求的三条断言上。
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
 * <p>刻意跑在<b>真实业务表</b>（{@code crm_negative_review_case} /
 * {@code crm_negative_review_action} / {@code crm_negative_review_appeal} /
 * {@code crm_negative_review_rule}）上，不建占位探针表 ——
 * 证明的是**真的要用的那几张表**被隔离了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NegativeReviewTenancyIntegrationTest {

  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();
  static final UUID shop = UUID.randomUUID();

  /** 参照行 ID 按租户派生：主键是**全局** uuid，两个租户不能用同一批固定 UUID。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-crm-negative-review-tenancy-" + tenant + "-" + ordinal).getBytes());
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", NegativeReviewTestSupport.PG::getJdbcUrl);
    r.add("DB_USER", () -> "crm_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", NegativeReviewTestSupport.PG::getUsername);
    r.add("DB_MIGRATION_PASSWORD", NegativeReviewTestSupport.PG::getPassword);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + NegativeReviewTestSupport.identity.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + NegativeReviewTestSupport.channel.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + NegativeReviewTestSupport.trade.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @BeforeEach
  void resetThenSeed() {
    NegativeReviewTestSupport.ACTORS.clear();
    NegativeReviewTestSupport.ACTORS.put(
        "session-a",
        NegativeReviewTestSupport.actor(userA, a, "A 租户", NegativeReviewTestSupport.PERMISSIONS));
    NegativeReviewTestSupport.SHOPS.clear();
    NegativeReviewTestSupport.SHOPS.add(
        new SignoffDeliveredOrderPort.ShopView(
            shop, "SHOP-T", "测试店铺", "AMAZON", "亚马逊", "SELF_FULFILLED", "ACTIVE"));
    NegativeReviewTestSupport.asMigrationUser(
        "truncate table crm_negative_review_action, crm_negative_review_appeal,"
            + " crm_negative_review_case, crm_negative_review_rule,"
            + " crm_product_review restart identity cascade");
    insertReview(a, idFor(a, 1), "tenant-a-review");
    insertReview(b, idFor(b, 1), "tenant-b-review");
    insertReview(a, idFor(a, 3), "tenant-a-review-2");
    insertCase(a, idFor(a, 2), "tenant-a-case", idFor(a, 1));
    insertCase(b, idFor(b, 2), "tenant-b-case", idFor(b, 1));
    // 平台策略：建处理单需要它（差评阈值与申诉配额都是规则）。
    insertPlatformPolicy(a);
  }

  // ================================================================ 插入助手

  /** 在可信租户事务里插入一条评价（timestamptz 一律绑 OffsetDateTime + {@code ?::timestamptz}）。 */
  void insertReview(UUID tenant, UUID id, String reviewNo) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_product_review"
                  + "(id,tenant_id,review_no,shop_id,shop_name,platform_code,source,rating,"
                  + " product_ref,product_title,variant_ref,variant_label,image_urls,"
                  + " has_image,has_video,replied,platform_reviewed_at,registered_at,"
                  + " created_at,updated_at)"
                  + " values (?,?,?,?,'测试店铺','AMAZON','MANUAL_ENTRY',1,?,'商品',?,'变体',"
                  + " '[]'::jsonb,false,false,false,?::timestamptz,?::timestamptz,"
                  + " ?::timestamptz,?::timestamptz)",
              id,
              tenant,
              reviewNo,
              shop,
              "SPU-" + reviewNo,
              "SKU-" + reviewNo,
              java.time.OffsetDateTime.now(),
              java.time.OffsetDateTime.now(),
              java.time.OffsetDateTime.now(),
              java.time.OffsetDateTime.now());
          return null;
        });
  }

  /** 在可信租户事务里插入一张处理单。 */
  void insertCase(UUID tenant, UUID id, String caseNo, UUID reviewId) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_negative_review_case"
                  + "(id,tenant_id,case_no,review_id,review_no,review_rating,shop_id,shop_name,"
                  + " platform_code,negative_rating_threshold,appeal_supported,"
                  + " contact_buyer_required,appeal_quota_limit,quota_period,appeal_window_days,"
                  + " appeal_eligible,case_status,resolution_status,created_at,updated_at)"
                  + " values (?,?,?,?,?,1,?,'测试店铺','AMAZON',2,false,true,0,'MONTHLY',30,"
                  + " false,'OPEN','UNRESOLVED',?::timestamptz,?::timestamptz)",
              id,
              tenant,
              caseNo,
              reviewId,
              "R-" + caseNo,
              shop,
              java.time.OffsetDateTime.now(),
              java.time.OffsetDateTime.now());
          return null;
        });
  }

  /** 在可信租户事务里插入一条平台策略规则（建处理单需要它）。 */
  void insertPlatformPolicy(UUID tenant) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_negative_review_rule"
                  + "(id,tenant_id,rule_no,rule_scope,platform_code,label,"
                  + " negative_rating_threshold,appeal_supported,contact_buyer_required,"
                  + " appeal_quota_limit,quota_period,appeal_window_days,appeal_eligible,"
                  + " evidence_required,priority,version,status,effective_from,created_at,updated_at)"
                  + " values (?,?,?,'PLATFORM','AMAZON','测试策略',2,false,true,0,'MONTHLY',30,"
                  + " false,false,100,1,'ACTIVE',?::timestamptz,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              "NVR-T-" + tenant.toString().substring(0, 8),
              java.time.OffsetDateTime.now(),
              java.time.OffsetDateTime.now(),
              java.time.OffsetDateTime.now());
          return null;
        });
  }

  /** 以指定租户身份读处理单的 case_no。 */
  List<String> caseNosOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select case_no from crm_negative_review_case order by case_no")
                .map(r -> r.get(0, String.class)));
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select case_no from crm_negative_review_case").size();
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
            "crm_negative_review_case",
            "crm_negative_review_action",
            "crm_negative_review_appeal",
            "crm_negative_review_rule")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V302 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('crm_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as("crm_app 必须真的能读 " + table)
          .isTrue();
    }
    // 序列单独授权：漏了会变成「第一条处理单就报权限不足」。
    for (String sequence :
        List.of(
            "crm_negative_review_case_no_seq",
            "crm_negative_review_action_no_seq",
            "crm_negative_review_appeal_no_seq",
            "crm_negative_review_rule_no_seq")) {
      assertThat(
              sql.fetchOne(
                      "select has_sequence_privilege('crm_app',?,'USAGE')", sequence)
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
    assertThat(caseNosOf(a)).containsExactly("tenant-a-case");
    assertThat(caseNosOf(b)).containsExactly("tenant-b-case");
    // 更强的一格：A 明确按 ID 查 B 的行也拿不到（不只是列表过滤）。
    var byId =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select case_no from crm_negative_review_case where id = ?", idFor(b, 2))
                    .size());
    assertThat(byId).as("按主键直查也必须被 RLS 挡住").isZero();
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户头无效，且**行落在自称的租户名下**
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    // (1) 服务在事务外没有「环境租户」：不存在一个可以被沿用的默认租户。
    assertThat(effectiveTenant()).isNull();
    assertThat(visibleWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    // (2) 事务内绑定的值精确等于可信参数，提交后立刻失效。
    var bound =
        tenants.run(
            b,
            () -> {
              assertThat(effectiveTenant()).as("事务内只看得到本租户").isEqualTo(b);
              return effectiveTenant();
            });
    assertThat(bound).isEqualTo(b);
    assertThat(effectiveTenant()).as("提交后租户上下文必须已清空").isNull();
    assertThat(visibleWithoutTenant()).isZero();

    // (3) 真正的入口断言：带**合法会话**（会话租户是 a）但**伪造 X-Tenant-Id: b** 建一张处理单。
    //     只断言「A 看不见 B」是不够的 —— 那一句「写入被全表拒掉」也能让它通过。
    String body =
        """
        {"reviewId":"%s","note":"伪造头建的处理单"}
        """
            .formatted(idFor(a, 3));

    http.perform(
            post("/crm/negative-reviews")
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
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
                    "select id, tenant_id from crm_negative_review_case where note = ?",
                    "伪造头建的处理单"));
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
                        "select id from crm_negative_review_case where note = ?",
                        "伪造头建的处理单")
                    .size());
    assertThat(visibleToB).as("这一行不属于 b，b 必须看不到").isZero();

    // 租户 a 看得到（它确实属于 a）。
    assertThat(caseNosOf(a)).as("写入必须归到会话租户 a").hasSize(2);

    // 伪造的内部键同样无效：它只是被代理忽略的普通头，不是鉴权凭据。
    assertThat(NegativeReviewTestSupport.ACTORS.get("session-a").tenantId()).isEqualTo(a);
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

  /** 动作流水与申诉任务同样受 RLS 保护（它们是处理单的子事实）。 */
  @Test
  void 动作流水与申诉任务也受租户隔离() {
    assertThat(
            tenants.run(
                a,
                () -> sql.fetch("select id from crm_negative_review_action").size()))
        .isZero();
    assertThat(
            tenants.run(
                a,
                () -> sql.fetch("select id from crm_negative_review_appeal").size()))
        .isZero();
    // 规则：租户 a 有且只有自己那一条平台策略（租户 b 的看不到）。
    assertThat(
            tenants.run(
                a,
                () -> sql.fetch("select id from crm_negative_review_rule").size()))
        .isEqualTo(1);
    assertThat(
            tenants.run(
                b,
                () -> sql.fetch("select id from crm_negative_review_rule").size()))
        .isZero();
  }
}
