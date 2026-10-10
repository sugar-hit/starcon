package com.starcon.identity;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 17.4.1.2 审批规则（触发规则 + 免审规则）。
 *
 * <p>蓝图对本模块的界定：
 *
 * <blockquote>
 * 「配置审批触发规则：金额区间、跨法人、跨部门、新供应商、高风险商品等条件触发不同流程，
 * 支持免审配置（小额免审）。」<br>
 * 「输入：触发条件配置；免审规则。输出：规则集；生效策略。」<br>
 * 「<b>免审要设上限金额</b>，否则"小额免审"会被用来绕过大额管控。」
 * </blockquote>
 *
 * <p><b>与上游 17.4.1.1 的分工</b>：17.4.1.1 回答「这张单据谁审」，本模块回答「这次提交会不会触发审批」。
 * 因此本模块<b>不定义流程</b>：{@code targetFlowId} 只能指向 17.4.1.1 已存在的<b>生效</b>流程，
 * 这里既不另造节点，也不重做金额阈值分支。
 *
 * <p><b>蓝图那句警告落在两处库级不变量上</b>（{@code approval_trigger_rule_decision_shape}）：
 * 免审规则<b>必须</b>有币种与<b>上限金额</b>（没有上限的免审等于取消管控），
 * 且<b>不允许</b>把跨法人 / 跨部门 / 新供应商 / 高风险商品标成 REQUIRED
 * ——否则「小额免审」就能顺手把跨法人大额一起免掉，那正是它要防的绕法。Java 侧只把同样两条规则
 * 翻译成人能读懂的 400，不靠应用层自律。
 *
 * <p><b>本模块不产生审批实例。</b>{@link #evaluate} 是对<b>已保存配置</b>的试算与只读决策面：
 * 它按 priority 取第一条命中的规则并给出结论，不建审批单、不出待办、不发通知、不起定时器 ——
 * 那些是 17.4.2.1 的职责。
 */
@RestController
@RequestMapping("/identity")
public class ApprovalRuleController {

  private static final String RULE_READ = "identity.approval_rule.read";
  private static final String RULE_CREATE = "identity.approval_rule.create";
  private static final String RULE_UPDATE = "identity.approval_rule.update";
  private static final String RULE_DELETE = "identity.approval_rule.delete";

  private static final String ANY = "ANY";
  private static final String REQUIRED = "REQUIRED";
  private static final String FORBIDDEN = "FORBIDDEN";
  private static final String DIMENSIONS =
      "ANY|REQUIRED|FORBIDDEN";

  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;
  private final AuditLog audit;

  public ApprovalRuleController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.audit = new AuditLog(sql, identity);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  // ------------------------------------------------------------------
  // 契约类型
  // ------------------------------------------------------------------

  /** 规则集概览（不含规则，列表页用）。 */
  public record ApprovalRuleSetView(
      UUID id,
      String documentTypeCode,
      String documentTypeName,
      String ruleSetName,
      String status,
      String defaultAction,
      String description,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      int ruleCount,
      int exemptionCount,
      int submitCount) {}

  /** 单条触发规则 / 免审规则（读模型：带出目标流程与可读名）。 */
  public record ApprovalTriggerRuleView(
      UUID id,
      String ruleName,
      int priority,
      @Schema(nullable = true) String amountCurrencyCode,
      @Schema(nullable = true) BigDecimal amountMinAmount,
      @Schema(nullable = true) BigDecimal amountMaxAmount,
      String crossLegalEntity,
      String crossDepartment,
      String newVendor,
      String highRiskProduct,
      String decision,
      @Schema(nullable = true) UUID targetFlowId,
      @Schema(nullable = true) String targetFlowName,
      boolean targetFlowActive,
      String status,
      String description) {}

  /** 规则集详情：概览 + 有序规则。 */
  public record ApprovalRuleSetDetailView(ApprovalRuleSetView ruleSet, List<ApprovalTriggerRuleView> rules) {}

  /** 结构体检的一条结论（与 17.4.1.1 的 findings 同口径）。 */
  public record ApprovalRuleFindingView(String severity, String code, String message) {}

  public record ApprovalRuleValidationView(
      UUID ruleSetId,
      String ruleSetName,
      String status,
      int errorCount,
      int warningCount,
      List<ApprovalRuleFindingView> findings) {}

  /**
   * 生效策略：某单据类型当前「这次提交要不要走审批」的完整答案。
   *
   * <p>{@code controlled=false} 就是蓝图那句「未注册的变更类操作默认可直接执行」——本租户既没有
   * 生效流程也没有生效规则集，下游据此直接放行，而不是把「没配」当成「配错了」。
   *
   * <p>{@code exemptionCeilings} 把当前生效规则集里所有免审额度列出来：免审是本模块最需要被盯住的
   * 一处，配置页与审计都靠它回答「现在到底有哪些金额是被免掉的」。
   */
  public record ApprovalEffectivePolicyView(
      String documentTypeCode,
      String documentTypeName,
      boolean controlled,
      @Schema(nullable = true) UUID activeRuleSetId,
      @Schema(nullable = true) String activeRuleSetName,
      @Schema(nullable = true) String defaultAction,
      @Schema(nullable = true) UUID activeFlowId,
      @Schema(nullable = true) String activeFlowName,
      int activeRuleCount,
      List<ApprovalExemptionCeilingView> exemptionCeilings) {}

  /** 一条免审额度：这个币种下多少金额以内免审。 */
  public record ApprovalExemptionCeilingView(
      UUID ruleId,
      String ruleName,
      int priority,
      String currencyCode,
      BigDecimal maxAmount,
      @Schema(nullable = true) BigDecimal minAmount,
      String crossLegalEntity,
      String crossDepartment,
      String newVendor,
      String highRiskProduct) {}

  /** 试算 / 只读决策的入参：一次假想提交的客观事实。 */
  public record ApprovalEvaluationRequest(
      @NotBlank @Size(max = 32) String documentTypeCode,
      @Size(max = 3) String currencyCode,
      BigDecimal amount,
      Boolean crossLegalEntity,
      Boolean crossDepartment,
      Boolean newVendor,
      Boolean highRiskProduct) {}

  /**
   * 试算 / 只读决策的结果。
   *
   * <p>{@code source=RULE} 表示命中某条规则，{@code source=DEFAULT} 表示一条都没命中、
   * 走了规则集的 {@code defaultAction}，{@code source=UNCONTROLLED} 表示该单据类型在本租户
   * 既无生效规则集也无生效流程（默认放行）。
   */
  public record ApprovalEvaluationView(
      String documentTypeCode,
      String source,
      String decision,
      @Schema(nullable = true) UUID matchedRuleId,
      @Schema(nullable = true) String matchedRuleName,
      @Schema(nullable = true) Integer matchedPriority,
      @Schema(nullable = true) UUID targetFlowId,
      @Schema(nullable = true) String targetFlowName,
      @Schema(nullable = true) BigDecimal exemptionCeilingAmount,
      @Schema(nullable = true) String exemptionCeilingCurrency,
      String reason) {}

  /** 规则输入。{@code documentTypeCode} 建后不可改：换单据类型等于换一套规则。 */
  public record ApprovalTriggerRuleInput(
      @NotBlank @Size(max = 100) String ruleName,
      @NotNull Integer priority,
      @Pattern(regexp = "[A-Z]{3}") String amountCurrencyCode,
      BigDecimal amountMinAmount,
      BigDecimal amountMaxAmount,
      @NotNull @Pattern(regexp = DIMENSIONS) String crossLegalEntity,
      @NotNull @Pattern(regexp = DIMENSIONS) String crossDepartment,
      @NotNull @Pattern(regexp = DIMENSIONS) String newVendor,
      @NotNull @Pattern(regexp = DIMENSIONS) String highRiskProduct,
      @NotNull @Pattern(regexp = "SUBMIT_FOR_APPROVAL|EXEMPT") String decision,
      UUID targetFlowId,
      @NotNull @Pattern(regexp = "ACTIVE|DISABLED") String status,
      @Size(max = 300) String description) {}

  public record ApprovalRuleSetInput(
      @NotBlank @Size(max = 32) String documentTypeCode,
      @NotBlank @Size(max = 100) String ruleSetName,
      @NotNull @Pattern(regexp = "DRAFT|ACTIVE|DISABLED") String status,
      @NotNull @Pattern(regexp = "SUBMIT_FOR_APPROVAL|EXEMPT") String defaultAction,
      @Size(max = 300) String description,
      @Valid @NotEmpty List<ApprovalTriggerRuleInput> rules) {}

  // ------------------------------------------------------------------
  // 调用骨架（与 ApprovalFlowController 同口径）
  // ------------------------------------------------------------------

  private <T> T call(
      String auth, String permission, boolean mutation, Function<IdentityController.Access, T> work) {
    var initial = identity.resolve(auth);
    return tenants.run(
        initial.tenantId(),
        () -> {
          sql.fetch("select lock_session(?)", hash(auth));
          sql.fetch(
              "select pg_advisory_xact_lock(hashtextextended(?,0))", initial.tenantId().toString());
          var a = identity.resolve(auth);
          require(a.tenantId().equals(initial.tenantId()), "租户已切换，请重试");
          require(
              a.grants()
                  .stream()
                  .anyMatch(
                      g -> g.scopeMode().equals("ALL") && g.permissions().contains(permission)),
              "当前角色没有此操作权限");
          if (mutation) require(a.owner(), "审批规则变更需由当前租户所有者执行");
          return work.apply(a);
        });
  }

  private static void require(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.FORBIDDEN, message);
  }

  private static void check(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.BAD_REQUEST, message);
  }

  private static void reject(String message) {
    throw IdentityController.error(HttpStatus.CONFLICT, message);
  }

  private static void notFound() {
    throw IdentityController.error(HttpStatus.NOT_FOUND, "审批规则集不存在或不属于当前租户");
  }

  private String hash(String auth) {
    return IdentityController.hash(identity.token(auth));
  }

  private static String note(String value) {
    return value == null ? "" : value.trim();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  // ------------------------------------------------------------------
  // 读出口
  // ------------------------------------------------------------------

  /** 规则集列表，可按单据类型与状态过滤。 */
  @GetMapping("/approval-rules")
  public List<ApprovalRuleSetView> ruleSets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String documentTypeCode,
      @RequestParam(required = false) String status) {
    return call(
        auth,
        RULE_READ,
        false,
        a -> {
          var condition = new Condition(" where s.tenant_id=?");
          condition.bind(a.tenantId());
          if (documentTypeCode != null && !documentTypeCode.isBlank()) {
            condition.add(" and s.document_type_code=?");
            condition.bind(documentTypeCode.trim());
          }
          if (status != null && !status.isBlank()) {
            check(
                List.of("DRAFT", "ACTIVE", "DISABLED").contains(status),
                "状态只能是 DRAFT、ACTIVE 或 DISABLED");
            condition.add(" and s.status=?");
            condition.bind(status);
          }
          return sql.fetch(
                  selectSet() + condition.sql() + " order by s.document_type_code,s.rule_set_name",
                  condition.bindings())
              .stream()
              .map(r -> toView(a.tenantId(), r))
              .toList();
        });
  }

  /** 单个规则集详情（概览 + 有序规则）。跨租户 id 与不存在的 id 都返回 404。 */
  @GetMapping("/approval-rules/{id}")
  public ApprovalRuleSetDetailView ruleSet(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        RULE_READ,
        false,
        a -> {
          var row = sql.fetchOne(selectSet() + " where s.tenant_id=? and s.id=?", a.tenantId(), id);
          if (row == null) notFound();
          return new ApprovalRuleSetDetailView(toView(a.tenantId(), row), rules(a.tenantId(), id));
        });
  }

  /**
   * 生效策略：某单据类型当前按什么策略判。
   *
   * <p>这是蓝图「输出：规则集；生效策略」里「生效策略」的只读面，也是下游业务系统提交动作前
   * 判断「要不要送审」的依据。本出口只读不写，更不产生任何审批实例。
   */
  @GetMapping("/approval-rules/effective-policy")
  public ApprovalEffectivePolicyView effectivePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String documentTypeCode) {
    return call(
        auth,
        RULE_READ,
        false,
        a -> {
          String code = documentTypeCode.trim();
          var type =
              sql.fetchOne(
                  "select name from approval_document_type where code=? and status='ACTIVE'", code);
          if (type == null)
            throw IdentityController.error(
                HttpStatus.NOT_FOUND, "单据类型不存在或已停用：" + code);
          var set =
              sql.fetchOne(
                  "select id,rule_set_name,default_action from approval_rule_set"
                      + " where tenant_id=? and document_type_code=? and status='ACTIVE'",
                  a.tenantId(),
                  code);
          var flow =
              sql.fetchOne(
                  "select id,flow_name from approval_flow"
                      + " where tenant_id=? and document_type_code=? and status='ACTIVE'",
                  a.tenantId(),
                  code);
          UUID setId = set == null ? null : set.get("id", UUID.class);
          // 「受管控」= 本租户给它配了生效规则集；流程是否存在是另一件事（规则集里的规则指向流程）。
          boolean controlled = setId != null;
          return new ApprovalEffectivePolicyView(
              code,
              type.get("name", String.class),
              controlled,
              setId,
              set == null ? null : set.get("rule_set_name", String.class),
              set == null ? null : set.get("default_action", String.class),
              flow == null ? null : flow.get("id", UUID.class),
              flow == null ? null : flow.get("flow_name", String.class),
              controlled ? activeRuleCount(a.tenantId(), setId) : 0,
              controlled ? exemptionCeilings(a.tenantId(), setId) : List.of());
        });
  }

  /**
   * 结构体检：把已保存的规则集按「配置期就不该存在的结构」重新过一遍。
   *
   * <p>写入路径挡住了绝大多数问题（400 / 409），这里能查出来的是<b>事后才会暴露</b>的那类：
   * 比如规则集是 ACTIVE 但单据类型的生效流程被停用/删掉了，于是「送审」指向一条不存在的流程。
   */
  @GetMapping("/approval-rules/{id}/validation")
  public ApprovalRuleValidationView validate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        RULE_READ,
        false,
        a -> {
          var row = sql.fetchOne(selectSet() + " where s.tenant_id=? and s.id=?", a.tenantId(), id);
          if (row == null) notFound();
          var rules = rules(a.tenantId(), id);
          var findings = inspect(a.tenantId(), row, rules);
          int errors = (int) findings.stream().filter(f -> f.severity().equals("ERROR")).count();
          int warnings =
              (int) findings.stream().filter(f -> f.severity().equals("WARNING")).count();
          return new ApprovalRuleValidationView(
              id,
              row.get("rule_set_name", String.class),
              row.get("status", String.class),
              errors,
              warnings,
              findings);
        });
  }

  /**
   * 试算：给定一次假想提交的事实，回答「会不会触发审批、命中哪条规则」。
   *
   * <p>判定口径与运行时完全一致（{@link #matches} 是同一个方法），所以配置期试算出来的结论
   * 就是下游提交动作会得到的结论，不会出现「页面上看着免审、真提交时却要审批」。
   *
   * <p><b>它不产生审批实例</b>：不建单、不出待办、不发通知、不起定时器。真正的运行时在 17.4.2.1。
   */
  @PostMapping("/approval-rules/evaluate")
  public ApprovalEvaluationView evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ApprovalEvaluationRequest input) {
    return call(
        auth,
        RULE_READ,
        false,
        a -> {
          String code = input.documentTypeCode().trim();
          var type =
              sql.fetchOne(
                  "select name from approval_document_type where code=? and status='ACTIVE'", code);
          if (type == null)
            throw IdentityController.error(
                HttpStatus.NOT_FOUND, "单据类型不存在或已停用：" + code);
          var set =
              sql.fetchOne(
                  "select id,rule_set_name,default_action from approval_rule_set"
                      + " where tenant_id=? and document_type_code=? and status='ACTIVE'",
                  a.tenantId(),
                  code);
          if (set == null)
            return new ApprovalEvaluationView(
                code,
                "UNCONTROLLED",
                "EXEMPT",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "本租户未为单据类型「"
                    + code
                    + "」配置生效规则集，按默认放行处理（与未注册审批流同一语义）");
          UUID setId = set.get("id", UUID.class);
          for (var rule : rules(a.tenantId(), setId)) {
            if (!rule.status().equals("ACTIVE")) continue;
            if (!matches(rule, input)) continue;
            boolean exempt = rule.decision().equals("EXEMPT");
            return new ApprovalEvaluationView(
                code,
                "RULE",
                rule.decision(),
                rule.id(),
                rule.ruleName(),
                rule.priority(),
                rule.targetFlowId(),
                rule.targetFlowName(),
                exempt ? rule.amountMaxAmount() : null,
                exempt ? rule.amountCurrencyCode() : null,
                exempt
                    ? "命中免审规则「"
                        + rule.ruleName()
                        + "」："
                        + rule.amountCurrencyCode()
                        + " "
                        + rule.amountMaxAmount()
                        + " 以内免审"
                    : "命中送审规则「" + rule.ruleName() + "」，走流程「"
                        + (rule.targetFlowName() == null ? rule.targetFlowId() : rule.targetFlowName())
                        + "」");
          }
          String fallback = set.get("default_action", String.class);
          var flow =
              sql.fetchOne(
                  "select id,flow_name from approval_flow"
                      + " where tenant_id=? and document_type_code=? and status='ACTIVE'",
                  a.tenantId(),
                  code);
          return new ApprovalEvaluationView(
              code,
              "DEFAULT",
              fallback,
              null,
              null,
              null,
              flow == null ? null : flow.get("id", UUID.class),
              flow == null ? null : flow.get("flow_name", String.class),
              null,
              null,
              fallback.equals("EXEMPT")
                  ? "没有任何规则命中，按规则集「"
                      + set.get("rule_set_name", String.class)
                      + "」的生效策略免审"
                  : "没有任何规则命中，按规则集「"
                      + set.get("rule_set_name", String.class)
                      + "」的生效策略送审");
        });
  }

  // ------------------------------------------------------------------
  // 写出口
  // ------------------------------------------------------------------

  /**
   * 新建规则集（含全部触发/免审规则）。
   *
   * <p>整套配置一次写完，校验在写入前跑完：规则形状、免审上限、免审不得豁免高风险条件、
   * 送审必须指向本单据类型的生效流程、同单据类型生效唯一性、默认策略与规则的相容性。
   */
  @PostMapping("/approval-rules")
  public UUID create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ApprovalRuleSetInput input) {
    return call(
        auth,
        RULE_CREATE,
        true,
        a -> {
          documentType(input.documentTypeCode());
          var rules = normalizeRules(a.tenantId(), input.documentTypeCode().trim(), input.rules());
          checkDefaultAction(input.defaultAction(), rules);
          rejectDuplicateActive(a.tenantId(), input.documentTypeCode().trim(), null, input.status());
          if ("ACTIVE".equals(input.status())) requireActiveFlow(a.tenantId(), input.documentTypeCode().trim());
          UUID id = Ids.next();
          sql.execute(
              "insert into approval_rule_set(id,tenant_id,document_type_code,rule_set_name,status,"
                  + " default_action,description,created_by_membership_id)"
                  + " values(?,?,?,?,?,?,?,?)",
              id,
              a.tenantId(),
              input.documentTypeCode().trim(),
              input.ruleSetName().trim(),
              input.status(),
              input.defaultAction(),
              note(input.description()),
              a.membershipId());
          writeRules(a.tenantId(), id, rules);
          audit.record(
              a,
              auth,
              "identity.approval_rule",
              id,
              input.ruleSetName().trim(),
              "ApprovalRuleSetCreated",
              null,
              snapshot(a.tenantId(), id));
          return id;
        });
  }

  /**
   * 更新规则集。规则整体替换（先删后插）。
   *
   * <p>{@code documentTypeCode} 不允许改：换单据类型等于换一套规则，请删除后新建。
   */
  @PutMapping("/approval-rules/{id}")
  public void update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ApprovalRuleSetInput input) {
    call(
        auth,
        RULE_UPDATE,
        true,
        a -> {
          var row =
              sql.fetchOne("select * from approval_rule_set where tenant_id=? and id=?", a.tenantId(), id);
          if (row == null) notFound();
          check(
              input.documentTypeCode().trim().equals(row.get("document_type_code", String.class)),
              "不能把规则集改到另一个单据类型，请删除后新建");
          documentType(input.documentTypeCode());
          var before = snapshot(a.tenantId(), id);
          var rules = normalizeRules(a.tenantId(), input.documentTypeCode().trim(), input.rules());
          checkDefaultAction(input.defaultAction(), rules);
          rejectDuplicateActive(a.tenantId(), input.documentTypeCode().trim(), id, input.status());
          if ("ACTIVE".equals(input.status())) requireActiveFlow(a.tenantId(), input.documentTypeCode().trim());
          sql.execute(
              "update approval_rule_set set rule_set_name=?,status=?,default_action=?,description=?,"
                  + " version=version+1,updated_at=now() where tenant_id=? and id=?",
              input.ruleSetName().trim(),
              input.status(),
              input.defaultAction(),
              note(input.description()),
              a.tenantId(),
              id);
          writeRules(a.tenantId(), id, rules);
          audit.record(
              a,
              auth,
              "identity.approval_rule",
              id,
              input.ruleSetName().trim(),
              "ApprovalRuleSetUpdated",
              before,
              snapshot(a.tenantId(), id));
          return null;
        });
  }

  /** 删除规则集：其下规则随规则集级联删除（是它的配置，不是独立事实）。 */
  @DeleteMapping("/approval-rules/{id}")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        RULE_DELETE,
        true,
        a -> {
          var row =
              sql.fetchOne("select * from approval_rule_set where tenant_id=? and id=?", a.tenantId(), id);
          if (row == null) notFound();
          var before = snapshot(a.tenantId(), id);
          sql.execute("delete from approval_rule_set where tenant_id=? and id=?", a.tenantId(), id);
          audit.record(
              a,
              auth,
              "identity.approval_rule",
              id,
              row.get("rule_set_name", String.class),
              "ApprovalRuleSetDeleted",
              before,
              null);
          return null;
        });
  }

  // ------------------------------------------------------------------
  // 写入校验
  // ------------------------------------------------------------------

  private void documentType(String code) {
    check(
        sql.fetchExists(
            sql.selectOne().from("approval_document_type").where("code=? and status='ACTIVE'", code.trim())),
        "单据类型不存在或已停用：" + code.trim());
  }

  /** 同一租户同一单据类型只能有一条生效规则集。库上部分唯一索引是最后一道兜底。 */
  private void rejectDuplicateActive(UUID tenantId, String documentTypeCode, UUID self, String status) {
    if (!"ACTIVE".equals(status)) return;
    var row =
        sql.fetchOne(
            "select id,rule_set_name from approval_rule_set"
                + " where tenant_id=? and document_type_code=? and status='ACTIVE' and id is distinct from ?",
            tenantId,
            documentTypeCode,
            self);
    if (row != null)
      reject(
          "该单据类型已有生效规则集「"
              + row.get("rule_set_name", String.class)
              + "」，请先停用它或把本规则集设为草稿");
  }

  /**
   * 规则集设为生效时，单据类型必须已有生效流程。
   *
   * <p>否则「送审」就没有可走的流程，等于配了一套永远走不通的管控。留成写入前的 400 而不是
   * 运行时的意外，是为了让人在配置页就知道顺序：先注册流程，再配规则。
   */
  private void requireActiveFlow(UUID tenantId, String documentTypeCode) {
    check(
        sql.fetchExists(
            sql.selectOne()
                .from("approval_flow")
                .where(
                    "tenant_id=? and document_type_code=? and status='ACTIVE'",
                    tenantId,
                    documentTypeCode)),
        "单据类型「"
            + documentTypeCode
            + "」还没有生效审批流，请先在 17.4.1.1 审批流定义里注册并生效一条流程");
  }

  /**
   * 规则校验：形状、免审上限、免审不得豁免高风险条件、送审必须指向本单据类型的生效流程。
   *
   * <p>这些与库上 {@code approval_trigger_rule_decision_shape} 同口径；先查一次是为了把
   * 「数据库会拒绝」变成指明是哪条规则写错的 400。
   */
  private List<ApprovalTriggerRuleInput> normalizeRules(
      UUID tenantId, String documentTypeCode, List<ApprovalTriggerRuleInput> inputs) {
    var names = new java.util.HashSet<String>();
    var priorities = new java.util.HashSet<Integer>();
    for (var r : inputs) {
      String name = r.ruleName().trim();
      check(names.add(name), "规则名称重复：" + name);
      check(r.priority() != null && r.priority() >= 0, "规则优先级必须大于等于 0");
      check(priorities.add(r.priority()), "规则优先级重复：" + r.priority() + "（先命中先生效，同序没有唯一答案）");
      String currency = blankToNull(r.amountCurrencyCode());
      BigDecimal min = r.amountMinAmount();
      BigDecimal max = r.amountMaxAmount();
      if (min == null && max == null)
        check(
            currency == null,
            "规则「"
                + name
                + "」填了金额区间端点就必须给币种：区间只在同一币种内有意义");
      else
        check(
            currency != null,
            "规则「" + name + "」要约束金额就必须给出币种，不能只给一个不分币种的金额");
      if (min != null) check(min.signum() >= 0, "规则「" + name + "」的金额下限不能为负");
      if (max != null) check(max.signum() >= 0, "规则「" + name + "」的金额上限不能为负");
      if (min != null && max != null)
        check(max.compareTo(min) > 0, "规则「" + name + "」的金额上下限必须构成正区间");

      if (r.decision().equals("EXEMPT")) {
        // 蓝图那句「免审要设上限金额」的第一道闸：没有上限的免审等于取消管控。
        check(
            max != null,
            "免审规则「"
                + name
                + "」必须设置上限金额。免审一旦没有上限，小额免审就会被用来绕过大额管控");
        check(
            max != null && max.signum() > 0,
            "免审规则「" + name + "」的上限金额必须大于 0");
        check(
            r.targetFlowId() == null,
            "免审规则「" + name + "」不指向流程：免审就是不走审批");
        // 第二道闸：免审不得豁免蓝图点名的四个高风险条件。
        rejectExemptionWaiver(r, "crossLegalEntity", "跨法人");
        rejectExemptionWaiver(r, "crossDepartment", "跨部门");
        rejectExemptionWaiver(r, "newVendor", "新供应商");
        rejectExemptionWaiver(r, "highRiskProduct", "高风险商品");
      } else {
        check(
            r.targetFlowId() != null,
            "送审规则「" + name + "」必须指定走哪条审批流（targetFlowId）");
        // 送审必须指向**本单据类型**的生效流程：跨单据类型送审是一笔糊涂账，
        // 而且它会让「这条规则到底受哪个流程管」没有答案。
        var flow =
            sql.fetchOne(
                "select id,flow_name,status,document_type_code from approval_flow"
                    + " where tenant_id=? and id=?",
                tenantId,
                r.targetFlowId());
        if (flow == null)
          check(false, "送审规则「" + name + "」引用的审批流不存在于当前租户");
        check(
            flow.get("document_type_code", String.class).equals(documentTypeCode),
            "送审规则「"
                + name
                + "」指向的审批流属于单据类型 "
                + flow.get("document_type_code", String.class)
                + "，与本规则集的 "
                + documentTypeCode
                + " 不一致");
        check(
            "ACTIVE".equals(flow.get("status", String.class)),
            "送审规则「"
                + name
                + "」指向的审批流「"
                + flow.get("flow_name", String.class)
                + "」当前不是生效状态（"
                + flow.get("status", String.class)
                + "），请先在 17.4.1.1 里生效它");
      }
    }
    return inputs;
  }

  /** 免审规则不得把高风险条件标成 REQUIRED —— 那是「小额免审」绕过大额管控的第二种走法。 */
  private static void rejectExemptionWaiver(
      ApprovalTriggerRuleInput r, String field, String label) {
    String value =
        switch (field) {
          case "crossLegalEntity" -> r.crossLegalEntity();
          case "crossDepartment" -> r.crossDepartment();
          case "newVendor" -> r.newVendor();
          default -> r.highRiskProduct();
        };
    check(
        !REQUIRED.equals(value),
        "免审规则「"
            + r.ruleName().trim()
            + "」不能要求"
            + label
            + "：免审只允许「该条件不成立时才免审」或「不约束」，"
            + "否则小额免审会被用来一并免掉"
            + label
            + "的大额提交");
  }

  /**
   * 生效策略与规则集内容必须相容。
   *
   * <p>「默认免审 + 存在送审规则」是一个真实的静默漏洞：配规则的人以为大额会被送审，
   * 实际上一条都没命中的单子直接放行。所以在这里拒掉，不留给运行时变成意外。
   */
  private static void checkDefaultAction(String defaultAction, List<ApprovalTriggerRuleInput> rules) {
    if (!defaultAction.equals("EXEMPT")) return;
    var submits =
        rules.stream()
            .filter(r -> r.decision().equals("SUBMIT_FOR_APPROVAL"))
            .map(r -> r.ruleName().trim())
            .toList();
    check(
        submits.isEmpty(),
        "生效策略选了「一条都没命中就免审」，但规则集里还有送审规则（"
            + String.join("、", submits)
            + "）：没命中规则的提交会被静默放行。存在送审规则时默认策略只能是送审");
  }

  // ------------------------------------------------------------------
  // 落库
  // ------------------------------------------------------------------

  private void writeRules(UUID tenantId, UUID ruleSetId, List<ApprovalTriggerRuleInput> rules) {
    sql.execute("delete from approval_trigger_rule where tenant_id=? and rule_set_id=?", tenantId, ruleSetId);
    var sorted = new ArrayList<>(rules);
    sorted.sort(Comparator.comparingInt(ApprovalTriggerRuleInput::priority));
    for (var r : sorted) {
      boolean exempt = r.decision().equals("EXEMPT");
      sql.execute(
          "insert into approval_trigger_rule(id,tenant_id,rule_set_id,rule_name,priority,"
              + " amount_currency_code,amount_min_amount,amount_max_amount,cross_legal_entity,"
              + " cross_department,new_vendor,high_risk_product,decision,target_flow_id,status,description)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          Ids.next(),
          tenantId,
          ruleSetId,
          r.ruleName().trim(),
          r.priority(),
          blankToNull(r.amountCurrencyCode()),
          r.amountMinAmount(),
          r.amountMaxAmount(),
          r.crossLegalEntity(),
          r.crossDepartment(),
          r.newVendor(),
          r.highRiskProduct(),
          r.decision(),
          exempt ? null : r.targetFlowId(),
          r.status(),
          note(r.description()));
    }
  }

  // ------------------------------------------------------------------
  // 读模型
  // ------------------------------------------------------------------

  private static String selectSet() {
    return "select s.id,s.document_type_code,d.name as document_type_name,s.rule_set_name,s.status,"
        + " s.default_action,s.description,s.version,s.created_at,s.updated_at"
        + " from approval_rule_set s"
        + " join approval_document_type d on d.code=s.document_type_code";
  }

  private ApprovalRuleSetView toView(UUID tenantId, Record row) {
    UUID id = row.get("id", UUID.class);
    var counts =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where decision='EXEMPT') as exempt,"
                + " count(*) filter (where decision='SUBMIT_FOR_APPROVAL') as submit"
                + " from approval_trigger_rule where tenant_id=? and rule_set_id=?",
            tenantId,
            id);
    return new ApprovalRuleSetView(
        id,
        row.get("document_type_code", String.class),
        row.get("document_type_name", String.class),
        row.get("rule_set_name", String.class),
        row.get("status", String.class),
        row.get("default_action", String.class),
        row.get("description", String.class),
        row.get("version", Long.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class),
        counts.get("total", Long.class).intValue(),
        counts.get("exempt", Long.class).intValue(),
        counts.get("submit", Long.class).intValue());
  }

  /** 规则列表按 priority 升序 —— 这就是判定顺序，页面上看到的顺序就是生效顺序。 */
  private List<ApprovalTriggerRuleView> rules(UUID tenantId, UUID ruleSetId) {
    return sql.fetch(
            "select r.id,r.rule_name,r.priority,r.amount_currency_code,r.amount_min_amount,"
                + " r.amount_max_amount,r.cross_legal_entity,r.cross_department,r.new_vendor,"
                + " r.high_risk_product,r.decision,r.target_flow_id,r.status,r.description,"
                + " f.flow_name as target_flow_name,f.status as target_flow_status"
                + " from approval_trigger_rule r"
                + " left join approval_flow f on f.tenant_id=r.tenant_id and f.id=r.target_flow_id"
                + " where r.tenant_id=? and r.rule_set_id=?"
                + " order by r.priority,r.rule_name",
            tenantId,
            ruleSetId)
        .stream()
        .map(
            r ->
                new ApprovalTriggerRuleView(
                    r.get("id", UUID.class),
                    r.get("rule_name", String.class),
                    r.get("priority", Integer.class),
                    r.get("amount_currency_code", String.class),
                    r.get("amount_min_amount", BigDecimal.class),
                    r.get("amount_max_amount", BigDecimal.class),
                    r.get("cross_legal_entity", String.class),
                    r.get("cross_department", String.class),
                    r.get("new_vendor", String.class),
                    r.get("high_risk_product", String.class),
                    r.get("decision", String.class),
                    r.get("target_flow_id", UUID.class),
                    r.get("target_flow_name", String.class),
                    "ACTIVE".equals(r.get("target_flow_status", String.class)),
                    r.get("status", String.class),
                    r.get("description", String.class)))
        .toList();
  }

  private int activeRuleCount(UUID tenantId, UUID ruleSetId) {
    return sql.fetchOne(
            "select count(*) as n from approval_trigger_rule"
                + " where tenant_id=? and rule_set_id=? and status='ACTIVE'",
            tenantId,
            ruleSetId)
        .get("n", Long.class)
        .intValue();
  }

  /** 当前生效规则集里的全部免审额度：谁免、免到多少、在什么附加条件下免。 */
  private List<ApprovalExemptionCeilingView> exemptionCeilings(UUID tenantId, UUID ruleSetId) {
    return sql.fetch(
            "select id,rule_name,priority,amount_currency_code,amount_min_amount,amount_max_amount,"
                + " cross_legal_entity,cross_department,new_vendor,high_risk_product"
                + " from approval_trigger_rule"
                + " where tenant_id=? and rule_set_id=? and status='ACTIVE' and decision='EXEMPT'"
                + " order by priority",
            tenantId,
            ruleSetId)
        .stream()
        .map(
            r ->
                new ApprovalExemptionCeilingView(
                    r.get("id", UUID.class),
                    r.get("rule_name", String.class),
                    r.get("priority", Integer.class),
                    r.get("amount_currency_code", String.class),
                    r.get("amount_max_amount", BigDecimal.class),
                    r.get("amount_min_amount", BigDecimal.class),
                    r.get("cross_legal_entity", String.class),
                    r.get("cross_department", String.class),
                    r.get("new_vendor", String.class),
                    r.get("high_risk_product", String.class)))
        .toList();
  }

  // ------------------------------------------------------------------
  // 判定：配置期试算与运行时判定共用同一段逻辑
  // ------------------------------------------------------------------

  /**
   * 一条规则是否命中这次提交。
   *
   * <p>金额区间是半开区间 {@code [min, max)}：上一条上限等于下一条下限是正常接续（同一笔金额
   * 不会同时落进两条规则）。端点为 null 表示该侧无界，但**免审规则的上限永远有界**
   * ——那正是蓝图那句警告要保住的性质。
   */
  private static boolean matches(ApprovalTriggerRuleView rule, ApprovalEvaluationRequest input) {
    String currency = blankToNull(input.currencyCode());
    if (rule.amountCurrencyCode() != null) {
      if (currency == null || !currency.equalsIgnoreCase(rule.amountCurrencyCode())) return false;
      BigDecimal amount = input.amount();
      if (amount != null) {
        if (amount.signum() < 0) return false;
        if (rule.amountMinAmount() != null && amount.compareTo(rule.amountMinAmount()) < 0) return false;
        if (rule.amountMaxAmount() != null && amount.compareTo(rule.amountMaxAmount()) >= 0) return false;
      }
    }
    return dimension(rule.crossLegalEntity(), truth(input.crossLegalEntity()))
        && dimension(rule.crossDepartment(), truth(input.crossDepartment()))
        && dimension(rule.newVendor(), truth(input.newVendor()))
        && dimension(rule.highRiskProduct(), truth(input.highRiskProduct()));
  }

  private static boolean dimension(String mode, boolean fact) {
    return switch (mode) {
      case REQUIRED -> fact;
      case FORBIDDEN -> !fact;
      default -> true;
    };
  }

  private static boolean truth(Boolean value) {
    return Boolean.TRUE.equals(value);
  }

  // ------------------------------------------------------------------
  // 结构体检 / 快照
  // ------------------------------------------------------------------

  private List<ApprovalRuleFindingView> inspect(
      UUID tenantId, Record set, List<ApprovalTriggerRuleView> rules) {
    var findings = new ArrayList<ApprovalRuleFindingView>();
    boolean active = "ACTIVE".equals(set.get("status", String.class));
    String code = set.get("document_type_code", String.class);
    if (!active)
      findings.add(
          new ApprovalRuleFindingView(
              "INFO",
              "NOT_ACTIVE",
              "该规则集当前状态为 "
                  + set.get("status", String.class)
                  + "，本次提交按「未配置生效规则」处理（默认放行）"));
    boolean anyActive = rules.stream().anyMatch(r -> r.status().equals("ACTIVE"));
    if (active && !anyActive)
      findings.add(
          new ApprovalRuleFindingView(
              "ERROR",
              "NO_ACTIVE_RULE",
              "生效规则集里没有任何启用中的规则，所有提交都会落到生效策略上；"
                  + "生效策略为免审时这等于该单据类型完全没有管控"));
    if (active && "EXEMPT".equals(set.get("default_action", String.class)))
      findings.add(
          new ApprovalRuleFindingView(
              "WARNING",
              "DEFAULT_EXEMPT",
              "生效策略是「一条都没命中就免审」：任何没被规则覆盖到的组合都会被直接放行"));
    for (var r : rules) {
      if (!r.status().equals("ACTIVE")) continue;
      if (r.decision().equals("SUBMIT_FOR_APPROVAL") && !r.targetFlowActive())
        findings.add(
            new ApprovalRuleFindingView(
                active ? "ERROR" : "WARNING",
                "TARGET_FLOW_INACTIVE",
                "规则「"
                    + r.ruleName()
                    + "」指向的审批流「"
                    + (r.targetFlowName() == null ? r.targetFlowId() : r.targetFlowName())
                    + "」已不是生效状态，该规则实际上送不进审批"));
      if (r.decision().equals("EXEMPT"))
        findings.add(
            new ApprovalRuleFindingView(
                "INFO",
                "EXEMPTION_CEILING",
                "规则「"
                    + r.ruleName()
                    + "」免审至 "
                    + r.amountCurrencyCode()
                    + " "
                    + r.amountMaxAmount()
                    + " 以内"
                    + extraGuards(r)
                    + "，超过部分不适用本规则"));
    }
    // 生效规则集却找不到生效流程：送审规则将无处可送。这是写入顺序问题（先注册流程再配规则）
    // 在事后才暴露的形态，所以体检要能查出来。
    if (active
        && !sql.fetchExists(
            sql.selectOne()
                .from("approval_flow")
                .where("tenant_id=? and document_type_code=? and status='ACTIVE'", tenantId, code)))
      findings.add(
          new ApprovalRuleFindingView(
              "ERROR",
              "NO_ACTIVE_FLOW",
              "单据类型「"
                  + code
                  + "」当前没有生效审批流，送审规则将无处可送；请先在 17.4.1.1 注册并生效流程"));
    return findings;
  }

  private static String extraGuards(ApprovalTriggerRuleView r) {
    var guards = new ArrayList<String>();
    if (FORBIDDEN.equals(r.crossLegalEntity())) guards.add("不跨法人");
    if (FORBIDDEN.equals(r.crossDepartment())) guards.add("不跨部门");
    if (FORBIDDEN.equals(r.newVendor())) guards.add("非新供应商");
    if (FORBIDDEN.equals(r.highRiskProduct())) guards.add("非高风险商品");
    return guards.isEmpty() ? "" : "（且" + String.join("、", guards) + "）";
  }

  private Map<String, Object> snapshot(UUID tenantId, UUID id) {
    var row = sql.fetchOne("select * from approval_rule_set where tenant_id=? and id=?", tenantId, id);
    if (row == null) return null;
    var snap = new LinkedHashMap<String, Object>();
    snap.put("documentTypeCode", row.get("document_type_code", String.class));
    snap.put("ruleSetName", row.get("rule_set_name", String.class));
    snap.put("status", row.get("status", String.class));
    snap.put("defaultAction", row.get("default_action", String.class));
    snap.put("description", row.get("description", String.class));
    snap.put("version", row.get("version", Long.class));
    var list = new ArrayList<Map<String, Object>>();
    for (var r : rules(tenantId, id)) {
      var item = new LinkedHashMap<String, Object>();
      item.put("ruleName", r.ruleName());
      item.put("priority", r.priority());
      item.put("decision", r.decision());
      item.put("amountCurrencyCode", r.amountCurrencyCode());
      item.put("amountMinAmount", r.amountMinAmount());
      item.put("amountMaxAmount", r.amountMaxAmount());
      item.put("crossLegalEntity", r.crossLegalEntity());
      item.put("crossDepartment", r.crossDepartment());
      item.put("newVendor", r.newVendor());
      item.put("highRiskProduct", r.highRiskProduct());
      item.put("targetFlowId", r.targetFlowId());
      list.add(item);
    }
    snap.put("rules", list);
    return snap;
  }

  /** 拼 where 片段的小工具，绑定值与片段顺序严格对应。 */
  private static final class Condition {
    private final List<String> clauses = new ArrayList<>();
    private final List<Object> bindings = new ArrayList<>();

    Condition(String first) {
      clauses.add(first);
    }

    void add(String clause) {
      clauses.add(clause);
    }

    void bind(Object value) {
      bindings.add(value);
    }

    String sql() {
      return String.join(" ", clauses);
    }

    Object[] bindings() {
      return bindings.toArray();
    }
  }
}