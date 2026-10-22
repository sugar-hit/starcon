package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;

import com.starcon.crm.EmailRuleModels.CrmEmailRuleEvaluateResult;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleInput;
import com.starcon.crm.EmailRuleModels.CrmEmailRulePreviewResult;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleResult;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleRow;
import com.starcon.crm.EmailRuleModels.CrmEmailTagAssignmentInput;
import com.starcon.crm.EmailRuleModels.CrmEmailTicketTagInput;
import com.starcon.tenancy.TenantTransactions;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 15.1.2.2 邮件规则的<b>规则语义</b>测试（服务层直调，不走 HTTP）。
 *
 * <p>蓝图职责句点名三个条件维度与三种动作，所以本类专门盯住规则这一层：
 *
 * <ul>
 *   <li><b>三个维度各自可判</b>：发件人（邮箱 / 域名）、关键词、工单标签。</li>
 *   <li><b>命中方式真的起作用</b>：ALL = 该维度的值必须**全部**命中，ANY = 任一命中。</li>
 *   <li><b>没配的维度不参与判定</b>：只配了关键词的规则，不该因为「发件人不匹配」而不命中。</li>
 *   <li><b>顺序显式</b>：多条规则都命中时，顺序小的赢。</li>
 *   <li><b>被引用的东西删不掉</b>：规则条件引用的工单标签、打标记录引用的标签 ——
 *       删了它们，那些规则就永远命中不了。</li>
 *   <li><b>自动分配结果只能向前走</b>：取消必须写理由，取消后不可复活。</li>
 * </ul>
 */
@SpringBootTest
class EmailRuleRulesTest {

  static final UUID tenant = UUID.randomUUID();
  static final UUID actorId = UUID.randomUUID();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", EmailRuleTestSupport.PG::getJdbcUrl);
    r.add("DB_USER", () -> "crm_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", EmailRuleTestSupport.PG::getUsername);
    r.add("DB_MIGRATION_PASSWORD", EmailRuleTestSupport.PG::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + EmailRuleTestSupport.identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired EmailRuleService rules;

  @BeforeEach
  void reset() {
    EmailRuleTestSupport.truncate();
  }

  // ================================================================ 夹具

  UUID insertEmail(String buyerEmail, String subject, String body) {
    UUID id = UUID.randomUUID();
    EmailRuleTestSupport.insertEmail(
        sql, tenants, tenant, id, buyerEmail, subject, body, null, OffsetDateTime.now(ZoneOffset.UTC));
    return id;
  }

  /**
   * 在**绑定了租户**的事务里调服务层。
   *
   * <p>⚠️ 直调服务层时不会自动绑租户（那是控制器里 {@code access.run} 的职责），
   * 而 RLS 只认事务内的 {@code app.current_tenant} —— 不绑就是「写入被策略挡掉」。
   */
  <T> T asTenant(java.util.function.Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  CrmEmailRuleResult createRule(CrmEmailRuleInput input) {
    return asTenant(() -> rules.createRule(input, actorId));
  }

  static CrmEmailRuleInput input(
      String code,
      String action,
      int order,
      List<String> keywords,
      List<String> senderEmails,
      List<String> senderDomains,
      List<String> tagCodes,
      String matchMode) {
    return new CrmEmailRuleInput(
        code,
        "规则 " + code,
        order,
        true,
        action,
        senderEmails,
        senderDomains,
        keywords,
        tagCodes,
        matchMode,
        "TEAM",
        "GROUP-A",
        "T-REPLY",
        "标题",
        "正文",
        List.of(),
        "");
  }

  CrmEmailRuleEvaluateResult evaluate(UUID emailId) {
    return asTenant(
        () -> rules.evaluate(new EmailRuleModels.CrmEmailRuleEvaluateInput(emailId, null), actorId));
  }

  // ==========================================================================
  // 三个条件维度
  // ==========================================================================

  @Test
  void 发件人域名维度可判() {
    createRule(
        input(
            "DOMAIN",
            "AUTO_ASSIGN",
            10,
            List.of(),
            List.of(),
            List.of("example.com"),
            List.of(),
            "ANY"));
    UUID email = insertEmail("someone@example.com", "你好", "你好");
    CrmEmailRuleEvaluateResult result = evaluate(email);
    assertThat(result.matched()).isTrue();
    assertThat(result.ruleCode()).isEqualTo("DOMAIN");
  }

  @Test
  void 工单标签维度可判_打标后命中() {
    var tag =
        asTenant(() -> rules.createTag(new CrmEmailTicketTagInput("VIP", "VIP 客户", null, ""), actorId));
    UUID email = insertEmail("vip@example.com", "你好", "你好");
    asTenant(() -> rules.assignTag(email, new CrmEmailTagAssignmentInput("VIP", null), actorId));

    createRule(
        input(
            "TAGGED",
            "AUTO_ASSIGN",
            10,
            List.of(),
            List.of(),
            List.of(),
            List.of("VIP"),
            "ANY"));
    CrmEmailRuleEvaluateResult result = evaluate(email);
    assertThat(result.matched()).isTrue();
    assertThat(result.ruleCode()).isEqualTo("TAGGED");
    // usageCount 是**读时**聚合出来的（打标后才有 1）。
    assertThat(tag.tag().usageCount()).isZero();
    assertThat(asTenant(() -> rules.requireTag(tag.tag().id())).usageCount()).isEqualTo(1);
  }

  @Test
  void 命中方式ALL要求该维度的值全部命中() {
    createRule(
        input(
            "ALLKW",
            "AUTO_ASSIGN",
            10,
            List.of("退款", "发票"),
            List.of(),
            List.of(),
            List.of(),
            "ALL"));
    // 只命中一个 ⇒ 不命中。
    UUID email = insertEmail("buyer@example.com", "退款", "我要退款");
    assertThat(evaluate(email).matched()).as("ALL 模式：没全部命中就不算命中").isFalse();

    // 两个都命中 ⇒ 命中。
    UUID email2 = insertEmail("buyer@example.com", "退款与发票", "我要退款和发票");
    assertThat(evaluate(email2).matched()).isTrue();
  }

  @Test
  void 没配的维度不参与判定() {
    createRule(
        input(
            "ONLYKW",
            "AUTO_ASSIGN",
            10,
            List.of("退款"),
            List.of(),
            List.of(),
            List.of(),
            "ANY"));
    // 发件人一个都没配：发件人是谁都不影响命中。
    UUID email = insertEmail("whoever@example.com", "退款", "退款");
    assertThat(evaluate(email).matched()).isTrue();
  }

  @Test
  void 顺序显式_顺序小的赢() {
    createRule(
        input(
            "FIRST",
            "AUTO_ASSIGN",
            10,
            List.of("退款"),
            List.of(),
            List.of(),
            List.of(),
            "ANY"));
    createRule(
        input(
            "SECOND",
            "AUTO_ASSIGN",
            20,
            List.of("退款"),
            List.of(),
            List.of(),
            List.of(),
            "ANY"));
    UUID email = insertEmail("buyer@example.com", "退款", "退款");
    assertThat(evaluate(email).ruleCode()).isEqualTo("FIRST");
  }

  // ==========================================================================
  // 引用守卫
  // ==========================================================================

  @Test
  void 被规则条件引用的工单标签删不掉() {
    asTenant(() -> rules.createTag(new CrmEmailTicketTagInput("KEEP", "要留着", null, ""), actorId));
    createRule(
        input(
            "USES-KEEP",
            "AUTO_ASSIGN",
            10,
            List.of(),
            List.of(),
            List.of(),
            List.of("KEEP"),
            "ANY"));
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rules.deleteTag(
                            asTenant(() -> rules.tags("", null, 0, 50)).items().get(0).id())))
        .as("被规则引用的标签删不掉：删了那条规则就永远命中不了")
        .hasMessageContaining("删不掉");
  }

  @Test
  void 被打标记录引用的工单标签删不掉() {
    asTenant(() -> rules.createTag(new CrmEmailTicketTagInput("USED", "用过", null, ""), actorId));
    UUID email = insertEmail("buyer@example.com", "你好", "你好");
    asTenant(() -> rules.assignTag(email, new CrmEmailTagAssignmentInput("USED", null), actorId));
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rules.deleteTag(
                            asTenant(() -> rules.tags("", null, 0, 50)).items().get(0).id())))
        .as("被打标记录引用的标签删不掉")
        .hasMessageContaining("删不掉");
  }

  @Test
  void 规则编码建后不可改_由db层钉死() {
    CrmEmailRuleResult created =
        createRule(
            input(
                "STABLE",
                "AUTO_ASSIGN",
                10,
                List.of("退款"),
                List.of(),
                List.of(),
                List.of(),
                "ANY"));
    assertThatThrownBy(
            () ->
                EmailRuleTestSupport.asMigrationUser(
                    "update crm_email_rule set rule_code = 'CHANGED' where id = '"
                        + created.rule().id()
                        + "'"))
        .as("历史命中记录里存的是编码，改了就对不上")
        .hasMessageContaining("不可改");
  }

  // ==========================================================================
  // 自动分配结果的状态机
  // ==========================================================================

  @Test
  void 自动分配结果取消必须写理由_取消后不可复活() {
    createRule(
        input(
            "ASSIGN",
            "AUTO_ASSIGN",
            10,
            List.of("退款"),
            List.of(),
            List.of(),
            List.of(),
            "ANY"));
    UUID email = insertEmail("buyer@example.com", "退款", "退款");
    CrmEmailRuleEvaluateResult result = evaluate(email);
    UUID assignmentId = result.assignmentId();
    assertThat(assignmentId).isNotNull();

    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        rules.cancelAssignment(
                            assignmentId,
                            new EmailRuleModels.CrmEmailRuleCancelAssignmentInput("  "),
                            actorId)))
        .as("取消必须写理由")
        .hasMessageContaining("理由");

    var cancelled =
        asTenant(
            () ->
                rules.cancelAssignment(
                    assignmentId,
                    new EmailRuleModels.CrmEmailRuleCancelAssignmentInput("分错了组"),
                    actorId));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(cancelled.cancelReason()).isEqualTo("分错了组");

    assertThatThrownBy(
            () ->
                EmailRuleTestSupport.asMigrationUser(
                    "update crm_email_rule_assignment set status = 'PENDING_DISPATCH' where id = '"
                        + assignmentId
                        + "'"))
        .as("已取消的不可复活：取消就是取消")
        .hasMessageContaining("不可复活");
  }

  // ==========================================================================
  // 试算
  // ==========================================================================

  @Test
  void 试算不写命中记录() {
    CrmEmailRuleResult created =
        createRule(
            input(
                "PREVIEW",
                "AUTO_ASSIGN",
                10,
                List.of("退款"),
                List.of(),
                List.of(),
                List.of(),
                "ANY"));
    UUID email = insertEmail("buyer@example.com", "退款", "退款");
    CrmEmailRulePreviewResult preview = asTenant(() -> rules.preview(created.rule().id(), email));
    assertThat(preview.matched()).isTrue();
    assertThat(preview.conditions().keywords().matchedKeywords()).containsExactly("退款");

    long hits =
        tenants.run(
            tenant, () -> sql.fetch("select id from crm_email_rule_hit").size());
    assertThat(hits).as("试算**不写**命中记录").isZero();
  }

  // ==========================================================================
  // 规则版本
  // ==========================================================================

  @Test
  void 改规则版本加一_且停用即不参与命中() {
    CrmEmailRuleResult created =
        createRule(
            input(
                "VER",
                "AUTO_ASSIGN",
                10,
                List.of("退款"),
                List.of(),
                List.of(),
                List.of(),
                "ANY"));
    CrmEmailRuleRow before = created.rule();
    assertThat(before.version()).isEqualTo(1);

    CrmEmailRuleResult updated =
        asTenant(() -> rules.updateRule(
            before.id(),
            new CrmEmailRuleInput(
                before.ruleCode(),
                before.ruleName(),
                before.priorityOrder(),
                false,
                before.action(),
                before.senderEmails(),
                before.senderDomains(),
                before.keywords(),
                before.ticketTagCodes(),
                before.matchMode(),
                before.assignTargetType(),
                before.assignTargetCode(),
                before.replyTemplateCode(),
                before.replySubject(),
                before.replyBody(),
                before.importantMarkers(),
                before.note()),
            actorId));
    assertThat(updated.rule().version()).isEqualTo(2);
    assertThat(updated.rule().enabled()).isFalse();

    UUID email = insertEmail("buyer@example.com", "退款", "退款");
    assertThat(evaluate(email).matched()).as("停用的规则不参与命中").isFalse();
  }
}
