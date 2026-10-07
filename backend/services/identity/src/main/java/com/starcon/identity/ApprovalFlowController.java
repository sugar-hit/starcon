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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * 17.4.1.1 审批流定义。
 *
 * <p>蓝图对本模块的界定很窄，这里严格照着做，边界写死在这段注释里：
 *
 * <blockquote>
 * 「定义审批流程：适用单据类型、审批节点（角色/岗位/指定人）、审批方式（串行/并行/条件分支）、
 * 金额阈值分支、超时升级规则。」<br>
 * 「<b>审批流按单据类型注册</b>，未注册的变更类操作默认可直接执行——需要显式定义才有管控。」
 * </blockquote>
 *
 * <p><b>本模块交付的是定义与校验，不是审批运行时。</b>这里<b>不</b>发起审批、<b>不</b>处理审批实例、
 * <b>不</b>出待办列表，也没有任何定时器会去看超时规则并真的放行或转办：那些是 17.4.1.2 与 17.4.2.1 的
 * 职责。因此 {@code condition_expression} 与 {@code amount_branch} 在这里<b>只被校验形状、不被解析执行</b>；
 * {@link ApprovalEscalationView} 上的 {@code AUTO_APPROVE} 也只是一条被定义出来的策略。
 * 下游真正要回答「这张单据该走哪条流程」，用的是 {@link #active} 这一个只读出口。
 *
 * <p>「按单据类型注册」落在两处：
 * <ol>
 *   <li>同一租户同一单据类型同时只能有一条 {@code ACTIVE} 流程（库上部分唯一索引兜底，
 *       {@link #rejectDuplicateActive} 先查一次给可读的 409）；
 *   <li>{@link #registrations()} 直接回答「哪些单据类型还没注册」——蓝图那句
 *       「未注册的变更类操作默认可直接执行」在这里是一个可查询的事实，不是注释。
 * </ol>
 *
 * <p>节点引用上游 17.3.1.1 的<b>真实 role 表</b>（复合外键 + 写入前查一次），不自造平行角色体系。
 * 「岗位」上游没有，按本模块职责内最小可用由 {@code approval_position} 承载，它不携带任何权限。
 */
@RestController
@RequestMapping("/identity")
public class ApprovalFlowController {

  /** 流程内 node_key 白名单：金额分支与超时规则都按它引用，字符集越宽引用越容易写错。 */
  private static final java.util.regex.Pattern NODE_KEY = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,32}");

  private static final String FLOW_READ = "identity.approval_flow.read";
  private static final String FLOW_CREATE = "identity.approval_flow.create";
  private static final String FLOW_UPDATE = "identity.approval_flow.update";
  private static final String FLOW_DELETE = "identity.approval_flow.delete";

  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;
  private final AuditLog audit;

  public ApprovalFlowController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.audit = new AuditLog(sql, identity);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  // ------------------------------------------------------------------
  // 契约类型
  // ------------------------------------------------------------------

  /**
   * 可注册审批的单据类型 + 本租户是否已注册生效流程。
   *
   * <p>{@code registered=false} 的那一行就是蓝图说的「未注册的变更类操作默认可直接执行」——
   * 配置页靠它一眼看出哪些单据类型目前<b>没有</b>管控。
   */
  public record ApprovalRegistrationView(
      String documentTypeCode,
      String documentTypeName,
      String description,
      boolean registered,
      @Schema(nullable = true) UUID activeFlowId,
      @Schema(nullable = true) String activeFlowName) {}

  /** 出厂单据类型码表（不含租户上下文）。 */
  public record ApprovalDocumentTypeView(String code, String name, String description) {}

  /** 流程定义概览（不含节点，列表页用）。 */
  public record ApprovalFlowView(
      UUID id,
      String documentTypeCode,
      String documentTypeName,
      String flowName,
      String status,
      String description,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      int nodeCount,
      int branchCount,
      int escalationCount) {}

  /** 流程定义详情：概览 + 节点 + 金额分支 + 超时升级规则。 */
  public record ApprovalFlowDetailView(
      ApprovalFlowView flow, List<ApprovalNodeView> nodes, List<ApprovalBranchView> branches,
      List<ApprovalEscalationView> escalations) {}

  /**
   * 审批节点。
   *
   * <p>三种审批人各认各的列，与库上 {@code approval_flow_node_approver_shape} 同口径：
   * {@code approverType='ROLE'} 用 {@code roleId}，{@code POSITION} 用 {@code positionId}，
   * {@code USER} 用 {@code membershipId}，其余两个必须为空。
   *
   * <p>{@code approverLabel} / {@code approverActive} 是写回时的联表结果：审批人后来被停用时，
   * 配置页不该还显示着一个看似有效的名字却配不出人来。
   */
  public record ApprovalNodeView(
      UUID id,
      String nodeKey,
      String nodeName,
      int nodeOrder,
      int stageIndex,
      String approverType,
      @Schema(nullable = true) UUID roleId,
      @Schema(nullable = true) String roleCode,
      @Schema(nullable = true) UUID positionId,
      @Schema(nullable = true) String positionName,
      @Schema(nullable = true) UUID membershipId,
      @Schema(nullable = true) String approverLabel,
      boolean approverExists,
      @Schema(nullable = true) Boolean approverActive,
      String approvalMode,
      @Schema(nullable = true) String conditionExpression,
      String description) {}

  /** 金额阈值分支：某币种下金额落进本区间就改走 {@code targetNodeKey}。 */
  public record ApprovalBranchView(
      UUID id,
      String branchName,
      String currencyCode,
      @Schema(nullable = true) BigDecimal minAmount,
      @Schema(nullable = true) BigDecimal maxAmount,
      String targetNodeKey,
      int priority,
      String description) {}

  /** 超时升级规则。本模块只定义它，不实现任何会去看它并放行的定时器。 */
  public record ApprovalEscalationView(
      UUID id,
      String nodeKey,
      String timeoutAction,
      int timeoutHours,
      @Schema(nullable = true) String escalateApproverType,
      @Schema(nullable = true) UUID escalateRoleId,
      @Schema(nullable = true) UUID escalatePositionId,
      @Schema(nullable = true) UUID escalateMembershipId,
      @Schema(nullable = true) String escalateLabel,
      String description) {}

  /** 岗位字典（供节点选审批人；岗位不携带权限，权限仍来自 role）。 */
  public record ApprovalPositionView(
      UUID id,
      String code,
      String name,
      String description,
      String status,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record ApprovalPositionInput(
      @NotBlank @Size(max = 32) String code,
      @NotBlank @Size(max = 100) String name,
      @Size(max = 300) String description,
      @Pattern(regexp = "ACTIVE|DISABLED") String status) {}

  public record ApprovalNodeInput(
      @NotBlank @Size(max = 32) String nodeKey,
      @NotBlank @Size(max = 100) String nodeName,
      @NotNull Integer nodeOrder,
      @NotNull Integer stageIndex,
      @NotNull @Pattern(regexp = "ROLE|POSITION|USER") String approverType,
      UUID roleId,
      UUID positionId,
      UUID membershipId,
      @NotNull @Pattern(regexp = "SERIAL|PARALLEL|CONDITIONAL") String approvalMode,
      @Size(max = 500) String conditionExpression,
      @Size(max = 300) String description) {}

  public record ApprovalBranchInput(
      @NotBlank @Size(max = 100) String branchName,
      @NotNull @Pattern(regexp = "[A-Z]{3}") String currencyCode,
      BigDecimal minAmount,
      BigDecimal maxAmount,
      @NotBlank @Size(max = 32) String targetNodeKey,
      Integer priority,
      @Size(max = 300) String description) {}

  public record ApprovalEscalationInput(
      @NotBlank @Size(max = 32) String nodeKey,
      @NotNull @Pattern(regexp = "ESCALATE|AUTO_APPROVE") String timeoutAction,
      @NotNull Integer timeoutHours,
      @Pattern(regexp = "ROLE|POSITION|USER") String escalateApproverType,
      UUID escalateRoleId,
      UUID escalatePositionId,
      UUID escalateMembershipId,
      @Size(max = 300) String description) {}

  /** 流程输入。{@code documentTypeCode} 建后不可改：换单据类型等于换一条流程。 */
  public record ApprovalFlowInput(
      @NotBlank @Size(max = 32) String documentTypeCode,
      @NotBlank @Size(max = 100) String flowName,
      @NotNull @Pattern(regexp = "DRAFT|ACTIVE|DISABLED") String status,
      @Size(max = 300) String description,
      @NotEmpty @Valid List<ApprovalNodeInput> nodes,
      @Valid List<ApprovalBranchInput> branches,
      @Valid List<ApprovalEscalationInput> escalations) {}

  /**
   * 结构体检的一条结论。
   *
   * <p>{@code severity=ERROR} 的是**配置期就不该存在**的结构（比如一个阶段里塞了两个串行节点、
   * 审批人已被停用导致这一节点无人可审）；{@code INFO} 的是提醒（比如该单据类型当前没有生效流程）。
   * 写入路径已经把这些挡掉，这个出口是让配置页在保存后仍能复核，而不是替代写入校验。
   */
  public record ApprovalFindingView(String severity, String code, String message) {}

  public record ApprovalValidationView(
      UUID flowId,
      String flowName,
      String status,
      int errorCount,
      int warningCount,
      boolean registered,
      List<ApprovalFindingView> findings) {}

  // ------------------------------------------------------------------
  // 调用骨架
  // ------------------------------------------------------------------

  /**
   * 与 {@code NumberRuleController.call} 同口径：解析会话 → 切到该会话的租户事务 →
   * 锁会话 → 二次校验租户未切换 → 校验权限码 → 组织变更要求 OWNER。
   *
   * <p>租户来自会话成员关系，{@code X-Tenant-Id} 头在这里从头到尾没有出现过。
   */
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
              a.grants().stream()
                  .anyMatch(g -> g.scopeMode().equals("ALL") && g.permissions().contains(permission)),
              "当前角色没有此操作权限");
          if (mutation) require(a.owner(), "审批流定义变更需由当前租户所有者执行");
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
    throw IdentityController.error(HttpStatus.NOT_FOUND, "审批流不存在或不属于当前租户");
  }

  private String hash(String auth) {
    return IdentityController.hash(identity.token(auth));
  }

  /**
   * 成员 id → 用户名。
   *
   * <p>刻意走 {@code member_names(hash)}（SECURITY DEFINER，以会话成员关系为条件）而不是直连
   * {@code app_user}：V1 明确「Service account cannot directly enumerate users」，
   * 直连会吃 42501。调用方没有 {@code identity.member.read} 时解析不出用户名是**正常**的，
   * 所以「审批人不存在」的判定绝不拿用户名是否为空来判（见 {@link #inspect}）。
   */
  private Map<UUID, String> memberNames(String auth) {
    var names = new LinkedHashMap<UUID, String>();
    for (Record r : sql.fetch("select id,username from member_names(?)", hash(auth)))
      names.put(r.get("id", UUID.class), r.get("username", String.class));
    return names;
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

  /**
   * 出厂单据类型码表（与本租户无关，全租户同一份）。
   *
   * <p>它和 17.5.1.2 的 {@code document_entity} <b>不是同一张表</b>：那张回答「用哪个码取号」，
   * 这张回答「这类单据允许挂审批流」。后者刻意多出 ROLE_GRANT / DATA_EXPORT 这类
   * 「没有编号、但恰恰该被显式管控」的变更类操作。
   */
  @GetMapping("/approval-flows/document-types")
  public List<ApprovalDocumentTypeView> documentTypes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        FLOW_READ,
        false,
        a ->
            sql.fetch(
                    "select code,name,description from approval_document_type"
                        + " where status='ACTIVE' order by sort_order,code")
                .stream()
                .map(
                    r ->
                        new ApprovalDocumentTypeView(
                            r.get("code", String.class),
                            r.get("name", String.class),
                            r.get("description", String.class)))
                .toList());
  }

  /**
   * 「哪些单据类型还没注册生效审批流」。
   *
   * <p>蓝图那句「未注册的变更类操作默认可直接执行」在这里变成一个可查询的清单：
   * {@code registered=false} 的行就是当前<b>没有</b>管控的单据类型，配置页据此提示补齐。
   * 查询走 RLS 保护的 {@code approval_flow}，所以「有没有注册」永远是本租户视角。
   */
  @GetMapping("/approval-flows/registrations")
  public List<ApprovalRegistrationView> registrations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        FLOW_READ,
        false,
        a ->
            sql.fetch(
                    "select r.code,r.name,r.flow_id,r.flow_name from approval_flow_no_registration r"
                        + " order by r.sort_order,r.code")
                .stream()
                .map(
                    r -> {
                      UUID id = r.get("flow_id", UUID.class);
                      return new ApprovalRegistrationView(
                          r.get("code", String.class),
                          r.get("name", String.class),
                          note(
                              sql.fetchOne(
                                      "select description from approval_document_type where code=?",
                                      r.get("code", String.class))
                                  .get("description", String.class)),
                          id != null,
                          id,
                          r.get("flow_name", String.class));
                    })
                .toList());
  }

  /**
   * 按单据类型查当前生效的流程（下游运行时的唯一入口）。
   *
   * <p>同一单据类型最多只有一条 {@code ACTIVE}（库上部分唯一索引保证），所以这个出口
   * <b>永远返回唯一结果</b>；还没注册就是 404，调用方据此知道该单据类型未被管控。
   */
  @GetMapping("/approval-flows/active")
  public ApprovalFlowDetailView active(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String documentTypeCode) {
    return call(
        auth,
        FLOW_READ,
        false,
        a -> {
          var row =
              sql.fetchOne(
                  selectFlow() + " where f.tenant_id=? and f.document_type_code=? and f.status='ACTIVE'",
                  a.tenantId(),
                  documentTypeCode.trim());
          if (row == null)
            throw IdentityController.error(
                HttpStatus.NOT_FOUND,
                "该单据类型「" + documentTypeCode.trim() + "」尚未注册生效审批流，按默认放行处理");
          return detail(a.tenantId(), row, memberNames(auth));
        });
  }

  /** 流程列表，可按单据类型与状态过滤。 */
  @GetMapping("/approval-flows")
  public List<ApprovalFlowView> flows(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String documentTypeCode,
      @RequestParam(required = false) String status) {
    return call(
        auth,
        FLOW_READ,
        false,
        a -> {
          var condition = new Condition(" where f.tenant_id=?");
          condition.bind(a.tenantId());
          if (documentTypeCode != null && !documentTypeCode.isBlank()) {
            condition.add(" and f.document_type_code=?");
            condition.bind(documentTypeCode.trim());
          }
          if (status != null && !status.isBlank()) {
            check(List.of("DRAFT", "ACTIVE", "DISABLED").contains(status), "状态只能是 DRAFT、ACTIVE 或 DISABLED");
            condition.add(" and f.status=?");
            condition.bind(status);
          }
          return sql.fetch(
                  selectFlow() + condition.sql() + " order by f.document_type_code,f.flow_name",
                  condition.bindings())
              .stream()
              .map(r -> toView(a.tenantId(), r))
              .toList();
        });
  }

  /** 单条流程详情（含节点 / 金额分支 / 超时规则）。跨租户的 id 与不存在的 id 都返回 404。 */
  @GetMapping("/approval-flows/{id}")
  public ApprovalFlowDetailView flow(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        FLOW_READ,
        false,
        a -> {
          var row = sql.fetchOne(selectFlow() + " where f.tenant_id=? and f.id=?", a.tenantId(), id);
          if (row == null) notFound();
          return detail(a.tenantId(), row, memberNames(auth));
        });
  }

  /**
   * 结构体检：把已保存的流程按「配置期就不该存在的结构」重新过一遍。
   *
   * <p>写入路径已经挡住了绝大多数问题（400 / 409），这里能查出来的是**事后才会暴露**的那类：
   * 比如审批人昨天还生效、今天被停用（外键只保证角色存在，不保证它还能审批人），
   * 于是某个节点配置上看着没问题，实际已经无人可审。
   */
  @GetMapping("/approval-flows/{id}/validation")
  public ApprovalValidationView validate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        FLOW_READ,
        false,
        a -> {
          var row = sql.fetchOne(selectFlow() + " where f.tenant_id=? and f.id=?", a.tenantId(), id);
          if (row == null) notFound();
          var nodes = nodes(a.tenantId(), id, memberNames(auth));
          var findings = inspect(row, nodes);
          int errors = (int) findings.stream().filter(f -> f.severity().equals("ERROR")).count();
          int warnings = (int) findings.stream().filter(f -> f.severity().equals("WARNING")).count();
          return new ApprovalValidationView(
              id,
              row.get("flow_name", String.class),
              row.get("status", String.class),
              errors,
              warnings,
              "ACTIVE".equals(row.get("status", String.class)),
              findings);
        });
  }

  // ------------------------------------------------------------------
  // 岗位字典
  // ------------------------------------------------------------------

  @GetMapping("/approval-flows/positions")
  public List<ApprovalPositionView> positions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        FLOW_READ,
        false,
        a ->
            sql.fetch(
                    "select id,code,name,description,status,created_at,updated_at"
                        + " from approval_position where tenant_id=? order by code",
                    a.tenantId())
                .stream()
                .map(ApprovalFlowController::toPosition)
                .toList());
  }

  /**
   * 新建岗位。
   *
   * <p>岗位只回答「这一步由哪个岗位的人审」，<b>不携带任何权限</b>——同租户 code 唯一，
   * 撞码返回 409（库上 {@code UNIQUE(tenant_id,code)} 兜底）。
   */
  @PostMapping("/approval-flows/positions")
  public UUID createPosition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ApprovalPositionInput input) {
    return call(
        auth,
        FLOW_CREATE,
        true,
        a -> {
          UUID id = Ids.next();
          sql.execute(
              "insert into approval_position(id,tenant_id,code,name,description,status)"
                  + " values(?,?,?,?,?,?)",
              id,
              a.tenantId(),
              input.code().trim(),
              input.name().trim(),
              note(input.description()),
              statusOf(input.status()));
          audit.record(
              a,
              auth,
              "identity.approval_flow",
              id,
              input.name().trim(),
              "ApprovalPositionCreated",
              null,
              Map.of("code", input.code().trim(), "name", input.name().trim()));
          return id;
        });
  }

  @PutMapping("/approval-flows/positions/{id}")
  public void updatePosition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ApprovalPositionInput input) {
    call(
        auth,
        FLOW_UPDATE,
        true,
        a -> {
          var before = positionRow(a.tenantId(), id);
          if (before == null) notFound();
          sql.execute(
              "update approval_position set code=?,name=?,description=?,status=?,updated_at=now()"
                  + " where tenant_id=? and id=?",
              input.code().trim(),
              input.name().trim(),
              note(input.description()),
              statusOf(input.status()),
              a.tenantId(),
              id);
          audit.record(
              a,
              auth,
              "identity.approval_flow",
              id,
              input.name().trim(),
              "ApprovalPositionUpdated",
              positionSnapshot(before),
              positionSnapshot(positionRow(a.tenantId(), id)));
          return null;
        });
  }

  /** 岗位被流程节点引用时拒删（409）：删掉会让那些节点的审批人凭空消失。 */
  @DeleteMapping("/approval-flows/positions/{id}")
  public void deletePosition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        FLOW_DELETE,
        true,
        a -> {
          var before = positionRow(a.tenantId(), id);
          if (before == null) notFound();
          var used =
              sql.fetchOne(
                  "select n.node_name from approval_flow_node n"
                      + " join approval_flow f on f.tenant_id=n.tenant_id and f.id=n.flow_id"
                      + " where n.tenant_id=? and n.position_id=? limit 1",
                  a.tenantId(),
                  id);
          if (used != null)
            reject("岗位已被审批流节点「" + used.get("node_name", String.class) + "」引用，请改用停用");
          sql.execute("delete from approval_position where tenant_id=? and id=?", a.tenantId(), id);
          audit.record(
              a,
              auth,
              "identity.approval_flow",
              id,
              before.get("name", String.class),
              "ApprovalPositionDeleted",
              positionSnapshot(before),
              null);
          return null;
        });
  }

  // ------------------------------------------------------------------
  // 写出口：流程定义
  // ------------------------------------------------------------------

  /**
   * 新建流程（含节点 / 金额分支 / 超时规则）。
   *
   * <p>整棵配置一次写完，校验在写入前跑完：节点审批人存在性、阶段串行冲突、
   * 金额区间交叠、分支与升级规则引用的节点必须存在、生效流程唯一性。
   */
  @PostMapping("/approval-flows")
  public UUID create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ApprovalFlowInput input) {
    return call(
        auth,
        FLOW_CREATE,
        true,
        a -> {
          documentType(input.documentTypeCode());
          var nodes = normalizeNodes(a.tenantId(), input.nodes());
          var branches = normalizeBranches(input.branches());
          var escalations = normalizeEscalations(a.tenantId(), input.escalations());
          crossCheck(nodes, branches, escalations);
          rejectDuplicateActive(a.tenantId(), input.documentTypeCode().trim(), null, input.status());
          UUID id = Ids.next();
          sql.execute(
              "insert into approval_flow(id,tenant_id,document_type_code,flow_name,status,description,"
                  + " created_by_membership_id) values(?,?,?,?,?,?,?)",
              id,
              a.tenantId(),
              input.documentTypeCode().trim(),
              input.flowName().trim(),
              input.status(),
              note(input.description()),
              a.membershipId());
          writeTree(a.tenantId(), id, nodes, branches, escalations);
          audit.record(
              a,
              auth,
              "identity.approval_flow",
              id,
              input.flowName().trim(),
              "ApprovalFlowCreated",
              null,
              snapshot(a.tenantId(), id, memberNames(auth)));
          return id;
        });
  }

  /**
   * 更新流程。节点 / 分支 / 升级规则整体替换（先删后插），不是逐条 diff：
   * {@code node_key} 被分支与升级规则引用，中间态会出现「分支指向还不存在的节点」。
   *
   * <p>{@code documentTypeCode} 不允许改：换单据类型等于换一条流程，请删除后新建。
   */
  @PutMapping("/approval-flows/{id}")
  public void update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ApprovalFlowInput input) {
    call(
        auth,
        FLOW_UPDATE,
        true,
        a -> {
          var row = sql.fetchOne("select * from approval_flow where tenant_id=? and id=?", a.tenantId(), id);
          if (row == null) notFound();
          check(
              input.documentTypeCode().trim().equals(row.get("document_type_code", String.class)),
              "不能把流程改到另一个单据类型，请删除后新建");
          documentType(input.documentTypeCode());
          var before = snapshot(a.tenantId(), id, memberNames(auth));
          var nodes = normalizeNodes(a.tenantId(), input.nodes());
          var branches = normalizeBranches(input.branches());
          var escalations = normalizeEscalations(a.tenantId(), input.escalations());
          crossCheck(nodes, branches, escalations);
          rejectDuplicateActive(a.tenantId(), input.documentTypeCode().trim(), id, input.status());
          sql.execute(
              "update approval_flow set flow_name=?,status=?,description=?,version=version+1,"
                  + " updated_at=now() where tenant_id=? and id=?",
              input.flowName().trim(),
              input.status(),
              note(input.description()),
              a.tenantId(),
              id);
          writeTree(a.tenantId(), id, nodes, branches, escalations);
          audit.record(
              a,
              auth,
              "identity.approval_flow",
              id,
              input.flowName().trim(),
              "ApprovalFlowUpdated",
              before,
              snapshot(a.tenantId(), id, memberNames(auth)));
          return null;
        });
  }

  /** 删除流程：节点 / 分支 / 升级规则随流程级联删除（都是它的配置，不是独立事实）。 */
  @DeleteMapping("/approval-flows/{id}")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        FLOW_DELETE,
        true,
        a -> {
          var row = sql.fetchOne("select * from approval_flow where tenant_id=? and id=?", a.tenantId(), id);
          if (row == null) notFound();
          var before = snapshot(a.tenantId(), id, memberNames(auth));
          sql.execute("delete from approval_flow where tenant_id=? and id=?", a.tenantId(), id);
          audit.record(
              a,
              auth,
              "identity.approval_flow",
              id,
              row.get("flow_name", String.class),
              "ApprovalFlowDeleted",
              before,
              null);
          return null;
        });
  }

  // ------------------------------------------------------------------
  // 写入校验
  // ------------------------------------------------------------------

  private static String statusOf(String status) {
    return status == null || status.isBlank() ? "ACTIVE" : status;
  }

  private void documentType(String code) {
    check(
        sql.fetchExists(
            sql.selectOne()
                .from("approval_document_type")
                .where("code=? and status='ACTIVE'", code.trim())),
        "单据类型不存在或已停用：" + code.trim());
  }

  /**
   * 同一租户同一单据类型只能有一条生效流程。
   *
   * <p>先查一次是为了给出可读的 409（带冲突流程名）；库上的
   * {@code approval_flow_active_document} 部分唯一索引是最后一道兜底，
   * 并发下两条同时提交也只会成功一条，那条会以 409 失败而不是产生两条生效流程。
   */
  private void rejectDuplicateActive(UUID tenantId, String documentTypeCode, UUID self, String status) {
    if (!"ACTIVE".equals(status)) return;
    var row =
        sql.fetchOne(
            "select id,flow_name from approval_flow"
                + " where tenant_id=? and document_type_code=? and status='ACTIVE' and id is distinct from ?",
            tenantId,
            documentTypeCode,
            self);
    if (row != null)
      reject("该单据类型已有生效流程「" + row.get("flow_name", String.class) + "」，请先停用它或把本流程设为草稿");
  }

  /** 节点校验：形状、审批人存在性、阶段串行唯一性。 */
  private List<ApprovalNodeInput> normalizeNodes(UUID tenantId, List<ApprovalNodeInput> inputs) {
    var keys = new HashSet<String>();
    var orders = new HashSet<Integer>();
    // 同一阶段最多一个串行节点：阶段之间本来就串行，阶段内再放串行节点等于自我矛盾。
    var serialStages = new HashSet<Integer>();
    for (var n : inputs) {
      String key = n.nodeKey().trim();
      check(NODE_KEY.matcher(key).matches(), "节点标识只能包含字母、数字、下划线与连字符（1-32 位）：" + key);
      check(keys.add(key), "节点标识重复：" + key);
      check(n.nodeOrder() != null && n.nodeOrder() >= 0, "节点顺序必须大于等于 0");
      check(n.stageIndex() != null && n.stageIndex() >= 0, "阶段序号必须大于等于 0");
      check(orders.add(n.nodeOrder()), "节点顺序重复：" + n.nodeOrder());
      if (n.approvalMode().equals("CONDITIONAL")) {
        String expression = blankToNull(n.conditionExpression());
        check(
            expression != null,
            "节点「" + n.nodeName() + "」是条件分支，必须填写条件表达式");
        check(
            expression.length() <= 500,
            "条件表达式过长（上限 500 字符）：" + n.nodeName());
      } else {
        check(
            blankToNull(n.conditionExpression()) == null,
            "只有条件分支节点能填条件表达式，节点「" + n.nodeName() + "」不是");
      }
      if (n.approvalMode().equals("SERIAL"))
        check(
            serialStages.add(n.stageIndex()),
            "阶段 " + n.stageIndex() + " 里有多个串行节点；一个阶段只能有一个串行节点，其余请用并行");
      resolveApprover(tenantId, n.approverType(), n.roleId(), n.positionId(), n.membershipId(), "节点「" + n.nodeName() + "」");
    }
    return inputs;
  }

  /**
   * 审批人解析：三种类型各认各的列，且被指向的对象必须真实存在于**本租户**。
   *
   * <p>角色指向 17.3.1.1 的真实 {@code role} 表——不自造平行角色体系。
   * 库里另有复合外键兜底，这里再查一次是为了把「角色不存在」变成可读的 400 而不是 409。
   */
  private void resolveApprover(
      UUID tenantId,
      String approverType,
      UUID roleId,
      UUID positionId,
      UUID membershipId,
      String where) {
    switch (approverType) {
      case "ROLE" -> {
        check(roleId != null, where + "按角色审批，必须指定 roleId");
        check(positionId == null && membershipId == null, where + "按角色审批时不能同时填岗位或指定人");
        check(
            sql.fetchExists(sql.selectOne().from("role").where("tenant_id=? and id=?", tenantId, roleId)),
            where + "引用的角色不存在于当前租户");
      }
      case "POSITION" -> {
        check(positionId != null, where + "按岗位审批，必须指定 positionId");
        check(roleId == null && membershipId == null, where + "按岗位审批时不能同时填角色或指定人");
        check(
            sql.fetchExists(
                sql.selectOne().from("approval_position").where("tenant_id=? and id=?", tenantId, positionId)),
            where + "引用的岗位不存在于当前租户");
      }
      case "USER" -> {
        check(membershipId != null, where + "按指定人审批，必须指定 membershipId");
        check(roleId == null && positionId == null, where + "按指定人审批时不能同时填角色或岗位");
        check(
            sql.fetchExists(
                sql.selectOne().from("membership").where("tenant_id=? and id=?", tenantId, membershipId)),
            where + "引用的成员不存在于当前租户");
      }
      default -> throw IdentityController.error(HttpStatus.BAD_REQUEST, "未知的审批人类型：" + approverType);
    }
  }

  /** 金额分支校验：端点、区间顺序、同币种内不交叠。 */
  private List<ApprovalBranchInput> normalizeBranches(List<ApprovalBranchInput> inputs) {
    var list = inputs == null ? List.<ApprovalBranchInput>of() : inputs;
    var names = new HashSet<String>();
    for (var b : list) {
      String name = b.branchName().trim();
      check(names.add(name), "金额分支名称重复：" + name);
      check(
          b.minAmount() != null || b.maxAmount() != null,
          "金额分支「" + name + "」至少要有一端区间；上下限都空表示全金额都走这里，那应当是默认路径");
      if (b.minAmount() != null) check(b.minAmount().signum() >= 0, "金额下限不能为负：" + name);
      if (b.maxAmount() != null) check(b.maxAmount().signum() >= 0, "金额上限不能为负：" + name);
      if (b.minAmount() != null && b.maxAmount() != null)
        check(
            b.maxAmount().compareTo(b.minAmount()) > 0,
            "金额分支「" + name + "」的上下限必须构成正区间");
    }
    rejectOverlappingBranches(list);
    return list;
  }

  /**
   * 同币种内金额区间不允许交叠。
   *
   * <p>交叠意味着某个金额落进两条分支时「该走哪个节点」没有唯一答案，必须在配置期就拒。
   * 半开区间 {@code [min,max)}：上一条的上限等于下一条的下限不算交叠，那是正常接续。
   * 不同币种之间允许重合——那本来就是两笔不同币种的金额。
   */
  private void rejectOverlappingBranches(List<ApprovalBranchInput> list) {
    var byCurrency = new LinkedHashMap<String, List<ApprovalBranchInput>>();
    for (var b : list)
      byCurrency.computeIfAbsent(b.currencyCode(), k -> new ArrayList<>()).add(b);
    for (var entry : byCurrency.entrySet()) {
      var group = new ArrayList<>(entry.getValue());
      group.sort(
          Comparator.comparing(
              b -> b.minAmount() == null ? new BigDecimal("-1") : b.minAmount()));
      for (int i = 0; i < group.size(); i++)
        for (int j = i + 1; j < group.size(); j++) {
          var a = group.get(i);
          var b = group.get(j);
          // a 排在前面：a.maxAmount 为 null 表示无上界，与后面任何一条都交叠。
          if (a.maxAmount() == null)
            reject(
                "金额分支「"
                    + a.branchName()
                    + "」没有上限（覆盖该币种全部金额），与「"
                    + b.branchName()
                    + "」交叠；全金额都走一条分支时应设为默认路径而不是分支");
          if (b.minAmount() != null && a.maxAmount().compareTo(b.minAmount()) > 0)
            reject(
                "金额分支「"
                    + a.branchName()
                    + "」与「"
                    + b.branchName()
                    + "」在币种 "
                    + entry.getKey()
                    + " 上区间交叠，同一笔金额走哪条节点没有唯一答案");
        }
    }
  }

  /** 超时规则校验：时限、形状、目标审批人存在性。 */
  private List<ApprovalEscalationInput> normalizeEscalations(
      UUID tenantId, List<ApprovalEscalationInput> inputs) {
    var list = inputs == null ? List.<ApprovalEscalationInput>of() : inputs;
    var seen = new HashSet<String>();
    for (var e : list) {
      check(e.timeoutHours() != null && e.timeoutHours() > 0, "超时时限必须大于 0 小时");
      String signature = e.nodeKey().trim() + '#' + e.timeoutHours();
      check(seen.add(signature), "同一节点的同一超时时限只能配一条规则：" + signature);
      if (e.timeoutAction().equals("ESCALATE"))
        resolveApprover(
            tenantId,
            e.escalateApproverType() == null ? "" : e.escalateApproverType(),
            e.escalateRoleId(),
            e.escalatePositionId(),
            e.escalateMembershipId(),
            "超时规则（节点 " + e.nodeKey() + "）");
      else
        check(
            blankToNull(e.escalateApproverType()) == null
                && e.escalateRoleId() == null
                && e.escalatePositionId() == null
                && e.escalateMembershipId() == null,
            "超时视同通过（AUTO_APPROVE）不需要升级对象，请把升级审批人清空");
    }
    return list;
  }

  /**
   * 交叉校验：金额分支与超时规则引用的节点必须存在于本次提交的节点集合里。
   *
   * <p>库的复合外键也会兜住「跨流程引用」，但那是 409；这里先查是为了给出指明是哪一个
   * {@code nodeKey} 写错的 400。
   */
  private void crossCheck(
      List<ApprovalNodeInput> nodes,
      List<ApprovalBranchInput> branches,
      List<ApprovalEscalationInput> escalations) {
    var keys = new HashSet<String>();
    for (var n : nodes) keys.add(n.nodeKey().trim());
    for (var b : branches)
      check(
          keys.contains(b.targetNodeKey().trim()),
          "金额分支「" + b.branchName().trim() + "」指向的节点 " + b.targetNodeKey() + " 不在本流程内");
    for (var e : escalations)
      check(
          keys.contains(e.nodeKey().trim()),
          "超时规则指向的节点 " + e.nodeKey() + " 不在本流程内");
  }

  // ------------------------------------------------------------------
  // 落库
  // ------------------------------------------------------------------

  /**
   * 整棵配置替换：先删子表再删节点，最后插新的。
   *
   * <p>删除顺序是「升级规则 → 金额分支 → 节点」，因为前两者的复合外键都指向节点。
   */
  private void writeTree(
      UUID tenantId,
      UUID flowId,
      List<ApprovalNodeInput> nodes,
      List<ApprovalBranchInput> branches,
      List<ApprovalEscalationInput> escalations) {
    sql.execute("delete from approval_flow_escalation where tenant_id=? and flow_id=?", tenantId, flowId);
    sql.execute("delete from approval_flow_amount_branch where tenant_id=? and flow_id=?", tenantId, flowId);
    sql.execute("delete from approval_flow_node where tenant_id=? and flow_id=?", tenantId, flowId);
    for (var n : nodes)
      sql.execute(
          "insert into approval_flow_node(id,tenant_id,flow_id,node_key,node_name,node_order,stage_index,"
              + " approver_type,role_id,position_id,membership_id,approval_mode,condition_expression,description)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          Ids.next(),
          tenantId,
          flowId,
          n.nodeKey().trim(),
          n.nodeName().trim(),
          n.nodeOrder(),
          n.stageIndex(),
          n.approverType(),
          n.approverType().equals("ROLE") ? n.roleId() : null,
          n.approverType().equals("POSITION") ? n.positionId() : null,
          n.approverType().equals("USER") ? n.membershipId() : null,
          n.approvalMode(),
          n.approvalMode().equals("CONDITIONAL") ? blankToNull(n.conditionExpression()) : null,
          note(n.description()));
    for (var b : branches)
      sql.execute(
          "insert into approval_flow_amount_branch(id,tenant_id,flow_id,branch_name,currency_code,"
              + " min_amount,max_amount,target_node_key,priority,description) values(?,?,?,?,?,?,?,?,?,?)",
          Ids.next(),
          tenantId,
          flowId,
          b.branchName().trim(),
          b.currencyCode(),
          b.minAmount(),
          b.maxAmount(),
          b.targetNodeKey().trim(),
          b.priority() == null ? 0 : b.priority(),
          note(b.description()));
    for (var e : escalations)
      sql.execute(
          "insert into approval_flow_escalation(id,tenant_id,flow_id,node_key,timeout_action,timeout_hours,"
              + " escalate_approver_type,escalate_role_id,escalate_position_id,escalate_membership_id,description)"
              + " values(?,?,?,?,?,?,?,?,?,?,?)",
          Ids.next(),
          tenantId,
          flowId,
          e.nodeKey().trim(),
          e.timeoutAction(),
          e.timeoutHours(),
          e.timeoutAction().equals("ESCALATE") ? e.escalateApproverType() : null,
          e.timeoutAction().equals("ESCALATE") ? e.escalateRoleId() : null,
          e.timeoutAction().equals("ESCALATE") ? e.escalatePositionId() : null,
          e.timeoutAction().equals("ESCALATE") ? e.escalateMembershipId() : null,
          note(e.description()));
  }

  // ------------------------------------------------------------------
  // 读模型
  // ------------------------------------------------------------------

  private static String selectFlow() {
    return "select f.id,f.document_type_code,d.name as document_type_name,f.flow_name,f.status,"
        + " f.description,f.version,f.created_at,f.updated_at"
        + " from approval_flow f"
        + " join approval_document_type d on d.code=f.document_type_code";
  }

  private ApprovalFlowView toView(UUID tenantId, Record row) {
    UUID id = row.get("id", UUID.class);
    return new ApprovalFlowView(
        id,
        row.get("document_type_code", String.class),
        row.get("document_type_name", String.class),
        row.get("flow_name", String.class),
        row.get("status", String.class),
        row.get("description", String.class),
        row.get("version", Long.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class),
        sql.fetchOne("select count(*) as n from approval_flow_node where tenant_id=? and flow_id=?", tenantId, id)
            .get("n", Long.class)
            .intValue(),
        sql.fetchOne(
                "select count(*) as n from approval_flow_amount_branch where tenant_id=? and flow_id=?",
                tenantId,
                id)
            .get("n", Long.class)
            .intValue(),
        sql.fetchOne(
                "select count(*) as n from approval_flow_escalation where tenant_id=? and flow_id=?",
                tenantId,
                id)
            .get("n", Long.class)
            .intValue());
  }

  private ApprovalFlowDetailView detail(UUID tenantId, Record row, Map<UUID, String> memberNames) {
    UUID id = row.get("id", UUID.class);
    return new ApprovalFlowDetailView(
        toView(tenantId, row),
        nodes(tenantId, id, memberNames),
        branches(tenantId, id),
        escalations(tenantId, id, memberNames));
  }

  /**
   * 节点 + 审批人可读名。
   *
   * <p>用 left join 把角色 / 岗位 / 成员的名称与状态一次带出来：审批人被停用是
   * 「外键还在、但已经没有人能审」的典型形态，必须让配置页看得见。
   */
  private List<ApprovalNodeView> nodes(UUID tenantId, UUID flowId, Map<UUID, String> memberNames) {
    return sql.fetch(
            "select n.id,n.node_key,n.node_name,n.node_order,n.stage_index,n.approver_type,"
                + " n.role_id,r.code as role_code,n.position_id,p.name as position_name,n.membership_id,"
                + " n.approval_mode,n.condition_expression,n.description,"
                + " r.status as role_status,p.status as position_status,m.status as membership_status,"
                + " p.id as position_row,m.id as membership_row"
                + " from approval_flow_node n"
                + " left join role r on r.tenant_id=n.tenant_id and r.id=n.role_id"
                + " left join approval_position p on p.tenant_id=n.tenant_id and p.id=n.position_id"
                + " left join membership m on m.tenant_id=n.tenant_id and m.id=n.membership_id"
                + " where n.tenant_id=? and n.flow_id=?"
                + " order by n.stage_index,n.node_order",
            tenantId,
            flowId)
        .stream()
        .map(
            r -> {
              String type = r.get("approver_type", String.class);
              String label = null;
              boolean exists;
              boolean active;
              if (type.equals("ROLE")) {
                label = r.get("role_code", String.class);
                exists = label != null;
                active = "ACTIVE".equals(r.get("role_status", String.class));
              } else if (type.equals("POSITION")) {
                label = r.get("position_name", String.class);
                exists = r.get("position_row", UUID.class) != null;
                active = "ACTIVE".equals(r.get("position_status", String.class));
              } else {
                label = memberNames.get(r.get("membership_id", UUID.class));
                exists = r.get("membership_row", UUID.class) != null;
                active = "ACTIVE".equals(r.get("membership_status", String.class));
              }
              return new ApprovalNodeView(
                  r.get("id", UUID.class),
                  r.get("node_key", String.class),
                  r.get("node_name", String.class),
                  r.get("node_order", Integer.class),
                  r.get("stage_index", Integer.class),
                  type,
                  r.get("role_id", UUID.class),
                  r.get("role_code", String.class),
                  r.get("position_id", UUID.class),
                  r.get("position_name", String.class),
                  r.get("membership_id", UUID.class),
                  label,
                  exists,
                  active,
                  r.get("approval_mode", String.class),
                  r.get("condition_expression", String.class),
                  r.get("description", String.class));
            })
        .toList();
  }

  private List<ApprovalBranchView> branches(UUID tenantId, UUID flowId) {
    return sql.fetch(
            "select id,branch_name,currency_code,min_amount,max_amount,target_node_key,priority,description"
                + " from approval_flow_amount_branch where tenant_id=? and flow_id=?"
                + " order by currency_code,min_amount nulls first,priority",
            tenantId,
            flowId)
        .stream()
        .map(
            r ->
                new ApprovalBranchView(
                    r.get("id", UUID.class),
                    r.get("branch_name", String.class),
                    r.get("currency_code", String.class),
                    r.get("min_amount", BigDecimal.class),
                    r.get("max_amount", BigDecimal.class),
                    r.get("target_node_key", String.class),
                    r.get("priority", Integer.class),
                    r.get("description", String.class)))
        .toList();
  }

  private List<ApprovalEscalationView> escalations(
      UUID tenantId, UUID flowId, Map<UUID, String> memberNames) {
    return sql.fetch(
            "select e.id,e.node_key,e.timeout_action,e.timeout_hours,e.escalate_approver_type,"
                + " e.escalate_role_id,e.escalate_position_id,e.escalate_membership_id,e.description,"
                + " r.code as role_code,p.name as position_name"
                + " from approval_flow_escalation e"
                + " left join role r on r.tenant_id=e.tenant_id and r.id=e.escalate_role_id"
                + " left join approval_position p on p.tenant_id=e.tenant_id and p.id=e.escalate_position_id"
                + " where e.tenant_id=? and e.flow_id=?"
                + " order by e.node_key,e.timeout_hours",
            tenantId,
            flowId)
        .stream()
        .map(
            r -> {
              String type = r.get("escalate_approver_type", String.class);
              String label = null;
              if ("ROLE".equals(type)) label = r.get("role_code", String.class);
              else if ("POSITION".equals(type)) label = r.get("position_name", String.class);
              else if ("USER".equals(type))
        label = memberNames.get(r.get("escalate_membership_id", UUID.class));
              return new ApprovalEscalationView(
                  r.get("id", UUID.class),
                  r.get("node_key", String.class),
                  r.get("timeout_action", String.class),
                  r.get("timeout_hours", Integer.class),
                  type,
                  r.get("escalate_role_id", UUID.class),
                  r.get("escalate_position_id", UUID.class),
                  r.get("escalate_membership_id", UUID.class),
                  label,
                  r.get("description", String.class));
            })
        .toList();
  }

  /**
   * 结构体检。
   *
   * <p>能查出来的问题都是<b>写入时看不到、事后才暴露</b>的：审批人被停用导致节点无人可审，
   * 以及流程本身还没生效（此时该单据类型按蓝图「未注册则默认可直接执行」放行）。
   */
  private List<ApprovalFindingView> inspect(Record flow, List<ApprovalNodeView> nodes) {
    var findings = new ArrayList<ApprovalFindingView>();
    boolean active = "ACTIVE".equals(flow.get("status", String.class));
    if (!active)
      findings.add(
          new ApprovalFindingView(
              "INFO",
              "NOT_REGISTERED",
              "该流程当前状态为 "
                  + flow.get("status", String.class)
                  + "，该单据类型尚未注册生效审批流；按蓝图约定，未注册的变更类操作默认可直接执行"));
    if (active && nodes.isEmpty())
      findings.add(
          new ApprovalFindingView("ERROR", "NO_NODE", "生效流程必须至少有一个审批节点，否则单据提交后无人可审"));
    var serialStages = new HashSet<Integer>();
    for (var n : nodes) {
      if (n.approvalMode().equals("SERIAL") && !serialStages.add(n.stageIndex()))
        findings.add(
            new ApprovalFindingView(
                "ERROR",
                "MULTI_SERIAL",
                "阶段 " + n.stageIndex() + " 里出现多个串行节点（节点「" + n.nodeName() + "」）"));
      if (n.approverExists() && !n.approverActive())
        findings.add(
            new ApprovalFindingView(
                active ? "ERROR" : "WARNING",
                "APPROVER_INACTIVE",
                "节点「" + n.nodeName() + "」的审批人（" + labelOf(n) + "）已停用，该节点实际无人可审"));
      // 「审批人已不存在」只认审批人**行**真的没了（left join 出来是 null）。
      // 不能拿显示名判空：用户名要走 member_names() 解析，调用方没有 identity.member.read
      // 时解析不出来是正常的，那不代表这个人没了。
      if (!n.approverExists())
        findings.add(
            new ApprovalFindingView(
                active ? "ERROR" : "WARNING",
                "APPROVER_MISSING",
                "节点「" + n.nodeName() + "」的审批人已不存在，该节点实际无人可审"));
    }
    return findings;
  }

  private static String labelOf(ApprovalNodeView n) {
    return switch (n.approverType()) {
      case "ROLE" -> "角色 " + (n.roleCode() == null ? n.roleId() : n.roleCode());
      case "POSITION" -> "岗位 " + (n.positionName() == null ? n.positionId() : n.positionName());
      default -> "成员 " + (n.approverLabel() == null ? n.membershipId() : n.approverLabel());
    };
  }

  // ------------------------------------------------------------------
  // 岗位 / 快照
  // ------------------------------------------------------------------

  private Record positionRow(UUID tenantId, UUID id) {
    return sql.fetchOne("select * from approval_position where tenant_id=? and id=?", tenantId, id);
  }

  private static ApprovalPositionView toPosition(Record r) {
    return new ApprovalPositionView(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("description", String.class),
        r.get("status", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private Map<String, Object> positionSnapshot(Record r) {
    if (r == null) return null;
    var snap = new LinkedHashMap<String, Object>();
    snap.put("code", r.get("code", String.class));
    snap.put("name", r.get("name", String.class));
    snap.put("status", r.get("status", String.class));
    snap.put("description", r.get("description", String.class));
    return snap;
  }

  private Map<String, Object> snapshot(UUID tenantId, UUID id, Map<UUID, String> memberNames) {
    var row = sql.fetchOne("select * from approval_flow where tenant_id=? and id=?", tenantId, id);
    if (row == null) return null;
    var snap = new LinkedHashMap<String, Object>();
    snap.put("documentTypeCode", row.get("document_type_code", String.class));
    snap.put("flowName", row.get("flow_name", String.class));
    snap.put("status", row.get("status", String.class));
    snap.put("description", row.get("description", String.class));
    snap.put("version", row.get("version", Long.class));
    var nodeList = new ArrayList<Map<String, Object>>();
    for (var n : nodes(tenantId, id, memberNames)) {
      var node = new LinkedHashMap<String, Object>();
      node.put("nodeKey", n.nodeKey());
      node.put("nodeName", n.nodeName());
      node.put("stageIndex", n.stageIndex());
      node.put("nodeOrder", n.nodeOrder());
      node.put("approverType", n.approverType());
      node.put("approvalMode", n.approvalMode());
      nodeList.add(node);
    }
    snap.put("nodes", nodeList);
    var branchList = new ArrayList<Map<String, Object>>();
    for (var b : branches(tenantId, id)) {
      var branch = new LinkedHashMap<String, Object>();
      branch.put("branchName", b.branchName());
      branch.put("currencyCode", b.currencyCode());
      branch.put("minAmount", b.minAmount());
      branch.put("maxAmount", b.maxAmount());
      branch.put("targetNodeKey", b.targetNodeKey());
      branchList.add(branch);
    }
    snap.put("branches", branchList);
    var escalationList = new ArrayList<Map<String, Object>>();
    for (var e : escalations(tenantId, id, memberNames)) {
      var escalation = new LinkedHashMap<String, Object>();
      escalation.put("nodeKey", e.nodeKey());
      escalation.put("timeoutAction", e.timeoutAction());
      escalation.put("timeoutHours", e.timeoutHours());
      escalationList.add(escalation);
    }
    snap.put("escalations", escalationList);
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