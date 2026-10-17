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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 17.4.2.1 待我审批。
 *
 * <blockquote>
 *
 * 「展示当前用户的待审批列表：单据类型、金额、申请人、停留时长、当前节点，支持批量审批与转交。」<br>
 * 「上游 = 各业务系统提交；下游 = 1 工作台待办。<b>审批动作在 17 执行</b>，工作台只做跳转，避免两处审批逻辑不一致。」
 *
 * </blockquote>
 *
 * <p><b>本模块是 approval_instance 的唯一写入所有者</b>（V160）。集成头上 17.4.1.1 只交付了流程定义、17.4.1.2 只交付了触发规则，
 * 两者都不产生「有一张单子在等人审」这个事实，所以这张表在本服务里第一次被写出来。
 *
 * <p><b>边界三处，写死在这里：</b>
 *
 * <ol>
 *   <li><b>不实现审批记录查询。</b>本模块只写 {@code approval_instance_action}——执行一次审批动作却不记录谁在什么时刻
 *       做了什么，这个动作就不可追责，转交更是连「现在该谁审」都说不清。17.4.2.2 做全链路检索、统计与导出，
 *       本模块一个记录查询端点都不提供。
 *   <li><b>不在工作台重复实现审批。</b>蓝图那句「审批动作在 17 执行」在这里是字面事实：1 工作台待办只跳转到本页面，
 *       不持有第二套审批逻辑。
 *   <li><b>不假装各业务系统已经对接。</b>{@link #capabilities} 如实报出每个来源系统的归属模块与 {@code
 *       delivered=false}，并附 DB 里真实存在的按来源计数——未交付就是未交付。
 * </ol>
 *
 * <p><b>权限判定的形状：</b>租户与操作人**全部**来自已认证会话的成员关系，{@code X-Tenant-Id} 头从头到尾没有出现过，
 * 请求体里也没有任何一处接受调用方指定的租户或用户 id。待审批列表按 {@code role_assignment} + 节点审批人类型判定，
 * 页面权限与节点判定是**两件独立的事**：权限决定「能不能用这个页面」，节点决定「哪些单子归我」，
 * 分开才不会让一个拿到页面的人审批所有单据。
 *
 * <p><b>已知限制（刻意不掩盖）：</b>{@code CONDITIONAL} 条件节点的路由**不执行**。V83 明确条件表达式只做形状校验、不解析，
 * 而本模块也没有一个能安全执行任意表达式的求值器。所以解析到条件节点时返回 409 并说明原因，而不是猜一个节点把单子送过去。
 */
@RestController
@RequestMapping("/identity")
public class ApprovalInboxController {

  private static final String INBOX_READ = "identity.approval_inbox.read";
  private static final String INBOX_APPROVE = "identity.approval_inbox.approve";
  private static final String INBOX_TRANSFER = "identity.approval_inbox.transfer";

  /** 批量一次最多处理多少条。单条一个事务，200 条已经足够点完一屏。 */
  private static final int MAX_BATCH = 200;

  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;
  private final AuditLog audit;
  private final String internalKey;

  public ApprovalInboxController(
      DSLContext sql,
      IdentityController identity,
      PlatformTransactionManager manager,
      @Value("${INTERNAL_KEY}") String internalKey) {
    this.sql = sql;
    this.identity = identity;
    this.audit = new AuditLog(sql, identity);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
    this.internalKey = internalKey;
  }

  // ------------------------------------------------------------------
  // 契约类型
  // ------------------------------------------------------------------

  /**
   * 待审批列表的一行。
   *
   * <p>{@code dwellSeconds} 是**在当前节点停了多久**（由 DB 侧 {@code approval_dwell_seconds} 算，不是 Java 算），
   * {@code totalSeconds} 是**从提交到今多久**。两个都给人看：前者回答「这单压在谁手里」，后者回答「申请人等了多久」。
   *
   * <p>{@code applicantName} 可为 null：解析成员名要走 {@code member_names}，而它要求 {@code
   * identity.member.read}。审批人没有成员查看权限是正常的，此时 {@code applicantId} 仍然可信。
   */
  public record ApprovalInboxItem(
      UUID id,
      String instanceNo,
      String documentTypeCode,
      String documentTypeName,
      String flowName,
      String subjectTitle,
      @Schema(nullable = true) BigDecimal amount,
      @Schema(nullable = true) String currencyCode,
      UUID applicantId,
      @Schema(nullable = true) String applicantName,
      @Schema(nullable = true) UUID assignedToId,
      String currentNodeKey,
      String currentNodeName,
      int currentNodeOrder,
      int currentStageIndex,
      String approverType,
      String sourceSystem,
      String sourceReferenceId,
      Instant submittedAt,
      Instant nodeEnteredAt,
      long dwellSeconds,
      long totalSeconds,
      String status) {}

  /** 单条详情。与列表同一套字段来源，额外补上转交指派对象的可读名。 */
  public record ApprovalInboxDetail(ApprovalInboxItem instance, @Schema(nullable = true) String assignedToName) {}

  /**
   * 表头汇总。
   *
   * <p>{@code pendingTotal} 是**当前用户**未过滤的待办总数（不是当前筛选命中的数），页面用它显示「筛选后 3 / 共 17」；
   * {@code longestDwellSeconds} 用来提示最久的一单已经压了多久。
   */
  public record ApprovalInboxSummary(long pendingTotal, long longestDwellSeconds) {}

  /**
   * 当前无人可审的节点。
   *
   * <p>来源是停在 {@code POSITION} 节点上的待办：V83 的 {@code approval_position} 是岗位字典，**不带成员绑定**，
   * 所以这类待办解析不出具体的人。本模块不发明岗位↔成员的绑定关系（那会给 17.4.1.1 造第二个权威），
   * 如实列出来让人去配，而不是让这些单子安静地消失。
   */
  public record ApprovalUnclaimedNodeView(String nodeKey, String nodeName, String flowName, long pendingCount) {}

  /** 分页信封：total 来自真实 count，不是前端猜的。 */
  public record ApprovalInboxPage(
      List<ApprovalInboxItem> items,
      long total,
      int page,
      int size,
      ApprovalInboxSummary summary,
      List<ApprovalUnclaimedNodeView> unclaimedNodes) {}

  /** 单条审批。驳回必须带理由（库上 CHECK 同口径）。 */
  public record ApprovalDecideInput(
      @NotNull @Pattern(regexp = "APPROVE|REJECT") String decision, @Size(max = 500) String comment) {}

  /** 转交。{@code toMembershipId} 为空表示「交回节点配置的审批人」（撤销这次指派）。 */
  public record ApprovalTransferInput(UUID toMembershipId, @Size(max = 500) String comment) {}

  /** 批量条目。comment / toMembershipId 为空时回落到批量级的默认值。 */
  public record ApprovalBatchItemInput(
      @NotNull UUID instanceId, @Size(max = 500) String comment, UUID toMembershipId) {}

  /**
   * 批量审批 / 转交请求。
   *
   * <p>审批动作**不是**「要么全成要么全败」：一条驳回不该让同一批里那 19 条已经通过的采购单重新回到待办，
   * 那种整体回滚对审批人是不可用的。所以每条一个独立事务，逐条回报。
   */
  public record ApprovalBatchInput(
      @NotNull @Pattern(regexp = "APPROVE|REJECT|TRANSFER") String action,
      @NotEmpty @Size(max = 200) List<@Valid ApprovalBatchItemInput> items,
      @Size(max = 500) String comment,
      UUID toMembershipId) {}

  /**
   * 批量结果里的一条。
   *
   * <p>{@code code} 是**机器可判定**的结果码（页面据此标红哪一行、是否可重试），{@code message} 是同一结论的人话。
   * 两者的区别是有意的：只给人话的话，前端只能靠字符串匹配来分类。
   */
  public record ApprovalBatchItemResult(
      UUID instanceId, boolean ok, String code, String message, @Schema(nullable = true) String status) {}

  /** 批量结果与请求**同序同长**：调用方按下标回填表格，不需要按 id 反查。 */
  public record ApprovalBatchResult(
      String action, int total, int succeeded, int failed, List<ApprovalBatchItemResult> results) {}

  /** 提交入口的请求体。注意：**没有** tenantId、没有 applicantId —— 两者都来自已认证会话。 */
  public record ApprovalSubmitInput(
      @NotNull UUID flowId,
      @NotBlank @Size(max = 32) String sourceSystem,
      @NotBlank @Size(max = 64) String sourceReferenceId,
      @NotBlank @Size(max = 200) String subjectTitle,
      @Pattern(regexp = "[A-Z]{3}") String currencyCode,
      BigDecimal amount) {}

  /** 提交结果：实例已在本服务落库，停在起始节点上等人审。 */
  public record ApprovalSubmitResult(
      UUID id,
      String instanceNo,
      UUID flowId,
      String currentNodeKey,
      String currentNodeName,
      UUID applicantId,
      String routedBy,
      Instant submittedAt) {}

  /** 一个来源系统的对接声明。delivered=false 是**如实**的，不是占位。 */
  public record ApprovalSourceIntegration(
      String sourceSystem, String owningSystem, String owningModule, boolean delivered, String note) {}

  /** DB 里真实存在的按来源计数 —— 让「有没有接上」这件事有真实数据佐证。 */
  public record ApprovalSourceCount(String sourceSystem, long instanceCount, long pendingCount) {}

  /**
   * 对接能力声明。
   *
   * <p>{@code intakeContract} 说的是**本服务这一侧**已交付的事实（授权提交 API 已实现）；
   * {@code sourceSystems} 说的是**各业务系统那一侧**是否已调用它 —— 目前全部 false，
   * 每条都指明归属模块，由那条车道自己去接。
   */
  public record ApprovalInboxCapabilities(
      String intakeContract,
      String approvedBy,
      List<ApprovalSourceIntegration> sourceSystems,
      List<ApprovalSourceCount> observed) {}

  // ------------------------------------------------------------------
  // 调用骨架
  // ------------------------------------------------------------------

  /**
   * 与 {@code ApprovalFlowController.call} 同口径：解析会话 → 切到该会话的租户事务 → 锁会话 → 二次校验租户未切换 →
   * 校验权限码。
   *
   * <p>{@code permission} 传 null 表示「内部调用，不要求页面权限码」—— 只有提交入口用它：
   * 提交人是业务系统的终端用户，他不需要「待我审批」页面的读权限才能发起一张要审批的单。
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
          if (permission != null)
            require(
                a.grants().stream()
                    .anyMatch(
                        g ->
                            g.scopeMode().equals("ALL")
                                && g.permissions().contains(permission)),
                "当前角色没有此操作权限");
          if (mutation) require(activeMembership(a), "成员已停用，无法执行审批动作");
          return work.apply(a);
        });
  }

  private boolean activeMembership(IdentityController.Access a) {
    var row =
        sql.fetchOne(
            "select status from membership where id=? and tenant_id=?", a.membershipId(), a.tenantId());
    return row != null && "ACTIVE".equals(row.get("status", String.class));
  }

  private static void require(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.FORBIDDEN, message);
  }

  private static void check(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.BAD_REQUEST, message);
  }

  private static void conflict(String message) {
    throw IdentityController.error(HttpStatus.CONFLICT, message);
  }

  private String hash(String auth) {
    return IdentityController.hash(identity.token(auth));
  }

  /**
   * 成员 id → 用户名。
   *
   * <p>刻意走 {@code member_names(hash)}（SECURITY DEFINER，以会话成员关系为条件）而不是直连 {@code app_user}：
   * V1 明确「Service account cannot directly enumerate users」，直连会吃 42501。
   * 没有 {@code identity.member.read} 时解析不出用户名是**正常**的，所以申请人为空绝不代表这个人不存在。
   */
  private Map<UUID, String> memberNames(String auth) {
    var names = new LinkedHashMap<UUID, String>();
    for (Record r : sql.fetch("select id,username from member_names(?)", hash(auth)))
      names.put(r.get("id", UUID.class), r.get("username", String.class));
    return names;
  }

  // ------------------------------------------------------------------
  // 「这条待办归我」判定
  // ------------------------------------------------------------------

  /**
   * 实例 i 对 membership {@code me} 是否可动 —— 列表、详情、单条动作、批量动作**共用这一段**。
   *
   * <p>两条分支，顺序不能换：
   *
   * <ul>
   *   <li>被**转交指派**（{@code assigned_membership_id} 非空）：只有被指派的那个人能看见并审批。
   *       这是实例级指派，优先于节点配置 —— 否则「转交」就只是列表里的一条记录，谁也没真被转走。
   *   <li>未被指派：按**当前节点的审批人类型**判定。
   *       <ul>
   *         <li>{@code USER} 指定人 → 节点上的 membership_id 就是我；
   *         <li>{@code ROLE} 角色 → 我在本租户被分配了这个角色（查 {@code role_assignment}：操作与数据范围必须在同一次分配中
   *             同时满足，所以这里只看这次分配确实存在，不额外放宽数据范围）；
   *         <li>{@code POSITION} 岗位 → <b>解析不出人</b>，分支恒 false。岗位字典不带成员绑定，
   *             硬要判定就得另造一套绑定关系，那是 17.4.1.1 的权威而不是本模块的。这类待办由 {@link
   *             #unclaimed} 如实报出来。
   *       </ul>
   * </ul>
   *
   * <p>三个 {@code ?} 绑定的是同一个 {@code me}，顺序固定：指派目标 → 指定人节点 → 角色分配。
   */
  private static final String MINE =
      """
      ((i.assigned_membership_id = ?)
        or (i.assigned_membership_id is null and (
              (n.approver_type = 'USER' and n.membership_id = ?)
           or (n.approver_type = 'ROLE' and exists (
                 select 1 from role_assignment ra
                  where ra.tenant_id = i.tenant_id and ra.membership_id = ? and ra.role_id = n.role_id))
        )))""";

  /** 列表、详情与动作共用的取数列。flow_id 只给动作侧用（要查下一节点），不出现在契约里。 */
  private static final String SELECT_COLUMNS =
      """
      select i.id, i.instance_no, i.flow_id, i.subject_title, i.amount,
             i.currency_code::text as currency_code, i.status,
             i.applicant_membership_id, i.assigned_membership_id,
             i.source_system, i.source_reference_id,
             i.submitted_at, i.node_entered_at, i.finished_at,
             f.document_type_code, dt.name as document_type_name, f.flow_name,
             n.node_key as current_node_key, n.node_name as current_node_name,
             n.node_order as current_node_order, n.stage_index as current_stage_index,
             n.approver_type,
             approval_dwell_seconds(coalesce(i.finished_at, now()), i.node_entered_at) as dwell_seconds,
             approval_dwell_seconds(coalesce(i.finished_at, now()), i.submitted_at) as total_seconds
        from approval_instance i
        join approval_flow f on f.tenant_id = i.tenant_id and f.id = i.flow_id
        join approval_document_type dt on dt.code = f.document_type_code
        join approval_flow_node n on n.tenant_id = i.tenant_id and n.flow_id = i.flow_id
                                and n.node_key = i.current_node_key""";

  /** {@link #MINE} 接在 WHERE 之后时用（前面已经有一个 id 条件）。 */
  private static String andMine() {
    return " and (" + MINE + ")";
  }

  /**
   * 「已结实例不可再动」的落点。
   *
   * <p>{@link #MINE} 只回答「归属」不回答「是否还在待办」：一张刚被结掉的单子，它的节点审批人仍然满足归属判定。
   * 待审批列表是<b>待办</b>，所以详情与动作一律再叠一个 {@code status='PENDING'} —— 已经通过的/被驳回的实例
   * 对任何人都回到 404，而不是留一个能被重复批的入口。
   */
  private static String andPending() {
    return " and i.status = 'PENDING'";
  }

  /** 参数：{id, me, me, me}。 */
  private static Object[] idThenMe(UUID id, UUID me) {
    return new Object[] {id, me, me, me};
  }

  /** 参数：{me, me, me}（无 id 条件）。 */
  private static Object[] me(UUID me) {
    return new Object[] {me, me, me};
  }

  private ApprovalInboxItem item(Record row, Map<UUID, String> names) {
    var applicant = row.get("applicant_membership_id", UUID.class);
    var assigned = row.get("assigned_membership_id", UUID.class);
    return new ApprovalInboxItem(
        row.get("id", UUID.class),
        row.get("instance_no", String.class),
        row.get("document_type_code", String.class),
        row.get("document_type_name", String.class),
        row.get("flow_name", String.class),
        row.get("subject_title", String.class),
        row.get("amount", BigDecimal.class),
        row.get("currency_code", String.class),
        applicant,
        applicant == null ? null : names.get(applicant),
        assigned,
        row.get("current_node_key", String.class),
        row.get("current_node_name", String.class),
        row.get("current_node_order", Integer.class),
        row.get("current_stage_index", Integer.class),
        row.get("approver_type", String.class),
        row.get("source_system", String.class),
        row.get("source_reference_id", String.class),
        row.get("submitted_at", Instant.class),
        row.get("node_entered_at", Instant.class),
        row.get("dwell_seconds", Long.class),
        row.get("total_seconds", Long.class),
        row.get("status", String.class));
  }

  // ------------------------------------------------------------------
  // 待审批列表
  // ------------------------------------------------------------------

  /**
   * 待审批列表。
   *
   * <p><b>筛选与排序全在 DB 层</b>，不在内存里排：
   *
   * <ul>
   *   <li>{@code minDwellSeconds} 走 {@code node_entered_at <= now() - N * interval '1 second'}，
   *       命中 {@code approval_instance_pending} 部分索引。停留时长对 node_entered_at 单调，
   *       所以「停留超过 N 分钟」与「在 node_entered_at 之前进入」是同一个可索引条件，不需要额外的函数索引。
   *   <li>{@code sort=WAITING} 就是 {@code order by node_entered_at asc} —— 与「按停留时长倒序」**恒等**，
   *       同样走索引。所以「最久的排最前」既正确又不付出全表排序的代价。
   *   <li>停留时长的**展示值**由 DB 侧 IMMUTABLE 函数 {@code approval_dwell_seconds} 计算。
   * </ul>
   */
  @GetMapping("/my-approvals")
  public ApprovalInboxPage search(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String documentTypeCode,
      @RequestParam(required = false) String sourceSystem,
      @RequestParam(required = false) String currencyCode,
      @RequestParam(required = false) UUID applicantId,
      @RequestParam(required = false) BigDecimal minAmount,
      @RequestParam(required = false) BigDecimal maxAmount,
      @RequestParam(required = false) Long minDwellSeconds,
      @RequestParam(required = false) String submittedFrom,
      @RequestParam(required = false) String submittedTo,
      @RequestParam(defaultValue = "WAITING") String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        INBOX_READ,
        false,
        a -> {
          check(page >= 0, "页码不能为负");
          check(size >= 1 && size <= MAX_PAGE_SIZE, "每页条数需在 1 到 " + MAX_PAGE_SIZE + " 之间");
          check(minAmount == null || minAmount.signum() >= 0, "金额下限不能为负");
          check(
              minAmount == null || maxAmount == null || minAmount.compareTo(maxAmount) <= 0,
              "金额下限不能大于上限");
          check(minDwellSeconds == null || minDwellSeconds >= 0, "停留时长下限不能为负");
          var from = instant(submittedFrom);
          var to = instant(submittedTo);
          check(from == null || to == null || !from.isAfter(to), "开始时间不能晚于结束时间");
          var order = orderBy(sort);

          var filter = new Condition();
          if (documentTypeCode != null && !documentTypeCode.isBlank())
            filter.add("f.document_type_code = ?", documentTypeCode.trim());
          if (sourceSystem != null && !sourceSystem.isBlank())
            filter.add("i.source_system = ?", sourceSystem.trim());
          if (currencyCode != null && !currencyCode.isBlank())
            filter.add("i.currency_code = ?", currencyCode.trim());
          if (applicantId != null) filter.add("i.applicant_membership_id = ?", applicantId);
          if (minAmount != null) filter.add("i.amount >= ?::numeric", minAmount);
          if (maxAmount != null) filter.add("i.amount <= ?::numeric", maxAmount);
          if (minDwellSeconds != null)
            filter.add(
                "i.node_entered_at <= now() - (?::bigint * interval '1 second')", minDwellSeconds);
          if (from != null) filter.add("i.submitted_at >= ?::timestamptz", AuditLog.utc(from));
          if (to != null) filter.add("i.submitted_at <= ?::timestamptz", AuditLog.utc(to));

          var me = a.membershipId();
          var where = new StringBuilder(" where i.status = 'PENDING'");
          filter.appendMine(where, MINE);

          long total =
              sql.fetchOne(
                      "select count(*) from approval_instance i"
                          + " join approval_flow f on f.tenant_id = i.tenant_id and f.id = i.flow_id"
                          + " join approval_flow_node n on n.tenant_id = i.tenant_id"
                          + "   and n.flow_id = i.flow_id and n.node_key = i.current_node_key"
                          + where,
                      filter.bindings(me, me, me))
                  .get(0, Long.class);

          var params = filter.bindings(me, me, me);
          var paged = new ArrayList<Object>(Arrays.asList(params));
          paged.add(size);
          paged.add((long) page * size);
          var records =
              sql.fetch(
                  SELECT_COLUMNS + where + order + " limit ? offset ?", paged.toArray());
          var names = memberNames(auth);
          return new ApprovalInboxPage(
              records.map(r -> item(r, names)),
              total,
              page,
              size,
              summary(me),
              unclaimed());
        });
  }

  /**
   * 排序白名单。
   *
   * <p>未知值直接 400 而不是默默回落到默认序：排序键拼错却安静地给出另一种顺序，比报错更难查。
   */
  private static String orderBy(String sort) {
    return switch (sort == null ? "" : sort.trim()) {
      case "WAITING" -> " order by i.node_entered_at asc, i.id asc";
      case "NEWEST" -> " order by i.submitted_at desc, i.id desc";
      case "OLDEST" -> " order by i.submitted_at asc, i.id asc";
      case "AMOUNT_DESC" -> " order by i.amount desc nulls last, i.id desc";
      case "AMOUNT_ASC" -> " order by i.amount asc nulls last, i.id asc";
      case "DOCUMENT" -> " order by f.document_type_code asc, i.submitted_at desc, i.id desc";
      default -> throw IdentityController.error(
          HttpStatus.BAD_REQUEST,
          "排序键需为 WAITING / NEWEST / OLDEST / AMOUNT_DESC / AMOUNT_ASC / DOCUMENT");
    };
  }

  /** 表头汇总：当前用户未过滤的待办总数与最长停留。 */
  private ApprovalInboxSummary summary(UUID me) {
    var row =
        sql.fetchOne(
            "select count(*) as pending,"
                + " coalesce(max(approval_dwell_seconds(now(), i.node_entered_at)),0) as longest"
                + " from approval_instance i"
                + " join approval_flow_node n on n.tenant_id = i.tenant_id and n.flow_id = i.flow_id"
                + "   and n.node_key = i.current_node_key"
                + " where i.status = 'PENDING' and "
                + MINE,
            me,
            me,
            me);
    return new ApprovalInboxSummary(row.get("pending", Long.class), row.get("longest", Long.class));
  }

  /** 当前停在岗位节点上、解析不出具体审批人的待办。如实报出，不让它静默消失。 */
  private List<ApprovalUnclaimedNodeView> unclaimed() {
    return sql.fetch(
            "select n.node_key, n.node_name, f.flow_name, count(*) as pending_count"
                + " from approval_instance i"
                + " join approval_flow f on f.tenant_id = i.tenant_id and f.id = i.flow_id"
                + " join approval_flow_node n on n.tenant_id = i.tenant_id and n.flow_id = i.flow_id"
                + "   and n.node_key = i.current_node_key"
                + " where i.status = 'PENDING' and i.assigned_membership_id is null"
                + "   and n.approver_type = 'POSITION'"
                + " group by f.id, f.flow_name, n.node_key, n.node_name"
                + " order by pending_count desc, n.node_key")
        .map(
            r ->
                new ApprovalUnclaimedNodeView(
                    r.get("node_key", String.class),
                    r.get("node_name", String.class),
                    r.get("flow_name", String.class),
                    r.get("pending_count", Long.class)));
  }

  private static Instant instant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value.trim());
    } catch (Exception e) {
      throw IdentityController.error(
          HttpStatus.BAD_REQUEST, "时间格式需为 ISO-8601，例如 " + Instant.EPOCH);
    }
  }

  /** WHERE 子句与绑定值成对累积，避免条件与参数顺序错位。 */
  private static final class Condition {
    private final List<String> clauses = new ArrayList<>();
    private final List<Object> bindings = new ArrayList<>();

    void add(String clause, Object value) {
      clauses.add(clause);
      bindings.add(value);
    }

    /**
     * 把条件续到已写好的 WHERE 后面。
     *
     * <p>调用方先写 {@code where i.status = 'PENDING'}，这里只往上追加 {@code and …}：条件自身不拼 {@code where}，
     * 两处各拼一次就会写出 {@code … where … where …}。
     */
    void appendTo(StringBuilder where) {
      for (var clause : clauses) where.append(" and ").append(clause);
    }

    /** 追加条件后再追加「归我」判定；绑定值顺序与之严格对应。 */
    void appendMine(StringBuilder where, String mine) {
      appendTo(where);
      where.append(" and (").append(mine).append(")");
    }

    Object[] bindings(UUID me, UUID me2, UUID me3) {
      var all = new ArrayList<>(bindings);
      all.add(me);
      all.add(me2);
      all.add(me3);
      return all.toArray();
    }
  }

  // ------------------------------------------------------------------
  // 单条详情
  // ------------------------------------------------------------------

  /**
   * 单条待审批详情。
   *
   * <p>跨租户访问在 RLS 下表现为「查不到」，非本人的待办同样查不到，因此统一 404 —— 403 会泄露「这条记录存在」。
   */
  @GetMapping("/my-approvals/{id}")
  public ApprovalInboxDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        INBOX_READ,
        false,
        a -> {
          var row = sql.fetchOne(SELECT_COLUMNS + " where i.id = ?" + andMine() + andPending(), idThenMe(id, a.membershipId()));
          if (row == null) notMine();
          var names = memberNames(auth);
          var assigned = row.get("assigned_membership_id", UUID.class);
          return new ApprovalInboxDetail(
              item(row, names), assigned == null ? null : names.get(assigned));
        });
  }

  private static void notMine() {
    throw IdentityController.error(
        HttpStatus.NOT_FOUND, "待审批不存在、不属于当前租户，或当前不在你的待办里");
  }

  // ------------------------------------------------------------------
  // 审批动作
  // ------------------------------------------------------------------

  /**
   * 单条审批。
   *
   * <p>「通过」不等于「这条单子结束」：只有当前节点是该流程**最后一个**节点时才结为 {@code APPROVED}，
   * 否则推进到下一个节点，并把停留时长的起算点重置为此刻。
   */
  @PostMapping("/my-approvals/{id}/decide")
  public ApprovalInboxItem decide(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ApprovalDecideInput input) {
    var outcome = apply(auth, id, input.decision(), input.comment(), null);
    if (!outcome.ok()) throw IdentityController.error(outcome.status(), outcome.message());
    return outcome.detail().instance();
  }

  /** 单条转交。{@code toMembershipId} 为空 = 交回节点配置的审批人。 */
  @PostMapping("/my-approvals/{id}/transfer")
  public ApprovalInboxItem transfer(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ApprovalTransferInput input) {
    var outcome = apply(auth, id, "TRANSFER", input.comment(), input.toMembershipId());
    if (!outcome.ok()) throw IdentityController.error(outcome.status(), outcome.message());
    return outcome.detail().instance();
  }

  /**
   * 批量审批 / 转交。
   *
   * <p>逐条一个**独立事务**：一条驳回不会把同一批里已经通过的 19 条打回待办 —— 审批动作不是「要么全成
   * 要么全败」的业务。结果与请求**同序同长**，调用方按下标回填表格即可。
   *
   * <p>权限是**整批**先判一次：没有 approve / transfer 权限就直接 403，而不是回一批「全失败」的结果 ——
   * 那会把「你没有这个权限」说成「这 20 条单子都出错了」，是误导。
   */
  @PostMapping("/my-approvals/batch")
  public ApprovalBatchResult batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ApprovalBatchInput input) {
    check(input.items().size() <= MAX_BATCH, "单次批量最多 " + MAX_BATCH + " 条");
    var permission = "TRANSFER".equals(input.action()) ? INBOX_TRANSFER : INBOX_APPROVE;
    call(auth, permission, true, a -> null);

    var results = new ArrayList<ApprovalBatchItemResult>();
    var succeeded = 0;
    for (var item : input.items()) {
      // 条目级覆盖优先于批量级默认值：同一批里可能既有「通过 A 单」也有「把 B 单转给某人」。
      var comment =
          item.comment() != null && !item.comment().isBlank() ? item.comment() : input.comment();
      var target = item.toMembershipId() != null ? item.toMembershipId() : input.toMembershipId();
      var outcome = apply(auth, item.instanceId(), input.action(), comment, target);
      results.add(
          new ApprovalBatchItemResult(
              item.instanceId(),
              outcome.ok(),
              outcome.code(),
              outcome.message(),
              outcome.detail() == null ? null : outcome.detail().instance().status()));
      if (outcome.ok()) succeeded++;
    }
    return new ApprovalBatchResult(
        input.action(), results.size(), succeeded, results.size() - succeeded, List.copyOf(results));
  }

  /**
   * 一次动作的结果。域内失败走 {@link Outcome} 而不是抛异常 —— 批量需要逐条拿到结论。
   *
   * @param code 机器可判定的结果码（NOT_MINE / CONDITIONAL_UNRESOLVED / INVALID_TARGET …）
   */
  private record Outcome(
      boolean ok,
      String code,
      String message,
      HttpStatus status,
      ApprovalInboxDetail detail) {

    static Outcome failed(HttpStatus status, String code, String message) {
      return new Outcome(false, code, message, status, null);
    }

    static Outcome rejectedByRule(HttpStatus status, String message) {
      return failed(status, "REJECTED_BY_RULE", message);
    }
  }

  /**
   * 动作的唯一实现：单条与批量共用，所以两条路径的行为**不可能**不一致。
   *
   * <p>权限码按动作分：审批要 {@code approve}，转交要 {@code transfer}。合成一个权限码的话，
   * 「只批不转」与「只转不批」就没法分配给不同角色。
   */
  private Outcome apply(
      String auth, UUID instanceId, String action, String comment, UUID toMembershipId) {
    var permission = "TRANSFER".equals(action) ? INBOX_TRANSFER : INBOX_APPROVE;
    try {
      return call(
          auth,
          permission,
          true,
          a -> {
            if ("REJECT".equals(action))
              check(
                  comment != null && !comment.isBlank(), "驳回必须填写理由，申请人需要知道被打回的原因");
            var locked = lockInstance(a, instanceId);
            if (locked == null)
              return Outcome.failed(
                  HttpStatus.NOT_FOUND, "NOT_MINE", "待审批不存在、不属于当前租户，或当前不在你的待办里");
            return "TRANSFER".equals(action)
                ? doTransfer(a, auth, locked, comment, toMembershipId)
                : doDecide(a, auth, locked, action, comment);
          });
    } catch (ResponseStatusException e) {
      // 域内校验失败也必须变成这一条的结论，否则一条非法输入会让整批 500。
      var status = HttpStatus.resolve(e.getStatusCode().value());
      return Outcome.rejectedByRule(
          status == null ? HttpStatus.BAD_REQUEST : status, e.getReason());
    }
  }

  /**
   * 取一条**属于我且仍在待办里**的实例并锁住它。
   *
   * <p>{@code for update of i} 是多人同时点批时的正确性保证：先拿到锁的那条动作生效，后拿到的那条看到的已经是新状态，
   * 因此返回「已不在你的待办里」而不是重复审批。{@code andPending()} 保证已结实例根本进不到这里。
   */
  private Record lockInstance(IdentityController.Access a, UUID instanceId) {
    return sql.fetchOne(
        SELECT_COLUMNS + " where i.id = ?" + andMine() + andPending() + " for update of i",
        idThenMe(instanceId, a.membershipId()));
  }

  private Outcome doDecide(
      IdentityController.Access a, String auth, Record row, String action, String comment) {
    var instanceId = row.get("id", UUID.class);
    var tenant = a.tenantId();
    var nodeKey = row.get("current_node_key", String.class);
    var flowId = row.get("flow_id", UUID.class);
    var order = row.get("current_node_order", Integer.class);
    var now = AuditLog.utc(Instant.now());

    var next = nextNode(tenant, flowId, order);
    // 条件节点的路由不执行（见类注释）：宁可 409 说清楚，也不要猜一个节点把单子送错人。
    if (next != null && "CONDITIONAL".equals(next.get("approval_mode", String.class)))
      return Outcome.failed(
          HttpStatus.CONFLICT,
          "CONDITIONAL_UNRESOLVED",
          "下一个节点是条件节点，其条件表达式尚未被解析执行，无法确定流转目标");

    var rejected = "REJECT".equals(action);
    var finished = rejected || next == null;
    if (finished) {
      sql.execute(
          "update approval_instance set status=?, finished_at=?::timestamptz where id=? and tenant_id=?",
          rejected ? "REJECTED" : "APPROVED",
          now,
          instanceId,
          tenant);
    } else {
      sql.execute(
          "update approval_instance set current_node_key=?, node_entered_at=?::timestamptz"
              + " where id=? and tenant_id=?",
          next.get("node_key", String.class),
          now,
          instanceId,
          tenant);
    }
    sql.execute(
        "insert into approval_instance_action"
            + "(id,tenant_id,instance_id,action,actor_membership_id,node_key,comment,decided_at)"
            + " values(?,?,?,?,?,?,?,?::timestamptz)",
        Ids.next(),
        tenant,
        instanceId,
        action,
        a.membershipId(),
        nodeKey,
        comment == null ? "" : comment.trim(),
        now);
    var status = rejected ? "REJECTED" : finished ? "APPROVED" : "PENDING";
    audit.record(
        a,
        auth,
        "identity.approval_instance",
        instanceId,
        row.get("instance_no", String.class),
        action,
        null,
        Map.of(
            "status", status,
            "nodeKey", nodeKey,
            "nextNodeKey", next == null ? "" : next.get("node_key", String.class),
            "comment", comment == null ? "" : comment.trim()));
    return reloaded(
        a, auth, instanceId, rejected ? "REJECTED" : "APPROVED", finished ? "" : "已流转到下一个节点");
  }

  private Outcome doTransfer(
      IdentityController.Access a, String auth, Record row, String comment, UUID toMembershipId) {
    var instanceId = row.get("id", UUID.class);
    var tenant = a.tenantId();
    if (toMembershipId != null) {
      if (toMembershipId.equals(a.membershipId()))
        return Outcome.failed(HttpStatus.BAD_REQUEST, "SAME_APPROVER", "转交目标不能是当前审批人自己");
      if (toMembershipId.equals(row.get("applicant_membership_id", UUID.class)))
        return Outcome.failed(
            HttpStatus.BAD_REQUEST, "SAME_APPROVER", "转交目标不能是申请人本人，否则等于自己批自己");
      var target =
          sql.fetchOne(
              "select status from membership where id=? and tenant_id=?", toMembershipId, tenant);
      if (target == null)
        return Outcome.failed(HttpStatus.BAD_REQUEST, "INVALID_TARGET", "转交目标不是当前租户的成员");
      if (!"ACTIVE".equals(target.get("status", String.class)))
        return Outcome.failed(HttpStatus.BAD_REQUEST, "INVALID_TARGET", "转交目标成员已停用");
    }
    // 刻意**不**重置 node_entered_at：停留时长是「这单压在当前节点多久」，
    // 每次转交都重置的话，转交就成了刷掉老化指标的开关 —— 谁都能把压了 30 天的记录转成「刚到」。
    sql.execute(
        "update approval_instance set assigned_membership_id=? where id=? and tenant_id=?",
        toMembershipId,
        instanceId,
        tenant);
    sql.execute(
        "insert into approval_instance_action"
            + "(id,tenant_id,instance_id,action,actor_membership_id,node_key,comment,to_membership_id,decided_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz)",
        Ids.next(),
        tenant,
        instanceId,
        "TRANSFER",
        a.membershipId(),
        row.get("current_node_key", String.class),
        comment == null ? "" : comment.trim(),
        toMembershipId,
        AuditLog.utc(Instant.now()));
    var names = memberNames(auth);
    var targetName = toMembershipId == null ? null : names.get(toMembershipId);
    audit.record(
        a,
        auth,
        "identity.approval_instance",
        instanceId,
        row.get("instance_no", String.class),
        "TRANSFER",
        null,
        Map.of(
            "toMembershipId", toMembershipId == null ? "" : toMembershipId.toString(),
            "toUsername", targetName == null ? "" : targetName,
            "comment", comment == null ? "" : comment.trim()));
    return reloaded(
        a,
        auth,
        instanceId,
        "TRANSFERRED",
        toMembershipId == null ? "已交回当前节点配置的审批人" : "已转交给 " + String.valueOf(targetName));
  }

  /**
   * 动作完成后重新读一次，保证返回的就是库里真实的样子（下一节点、结清时刻都是真实写回值）。
   *
   * <p>通过并结清后它已经不在待办里（{@code status != 'PENDING'}），所以按 id 直读；
   * 读不到就是真的读不到，此时如实返回 NOT_MINE 而不是编一个对象回去。
   */
  private Outcome reloaded(
      IdentityController.Access a, String auth, UUID instanceId, String code, String message) {
    var row =
        sql.fetchOne(SELECT_COLUMNS + " where i.id = ?", new Object[] {instanceId});
    if (row == null)
      return Outcome.failed(HttpStatus.NOT_FOUND, "NOT_MINE", "待审批不存在或不属于当前租户");
    var names = memberNames(auth);
    var assigned = row.get("assigned_membership_id", UUID.class);
    return new Outcome(
        true,
        code,
        message,
        HttpStatus.OK,
        new ApprovalInboxDetail(
            item(row, names), assigned == null ? null : names.get(assigned)));
  }

  /**
   * 下一个节点：按 node_order 取严格大于当前 order 的第一个。
   *
   * <p>阶段之间的串行、阶段内并行、条件跳过都属于流程定义（V83 明确只定义不解析），本模块不做展开。
   */
  private Record nextNode(UUID tenant, UUID flowId, int currentOrder) {
    return sql.fetchOne(
        "select node_key, node_name, node_order, approval_mode from approval_flow_node"
            + " where tenant_id=? and flow_id=? and node_order > ? order by node_order limit 1",
        tenant,
        flowId,
        currentOrder);
  }

  // ------------------------------------------------------------------
  // 提交入口（各业务系统调用）
  // ------------------------------------------------------------------

  /**
   * 提交审批：各业务系统把它要管控的单据登记成一条待审批实例。
   *
   * <p>两条边界写死在这里：
   *
   * <ol>
   *   <li><b>调用方不指定租户与申请人。</b>请求体里没有 {@code tenantId} 也没有 {@code applicantId}：
   *       租户来自已认证会话的成员关系，申请人也一样。业务系统转发终端用户的会话来提交，
   *       而不是声称「我要为某租户的某人提交」。{@code X-Tenant-Id} 头同样不参与判定。
   *   <li><b>起点节点由本服务决定。</b>先按金额阈值分支（V83）命中 {@code target_node_key}，命中不了取 node_order
   *       最小的节点。分支在这里是**真的被用上**的，不是定义完就摆着。
   * </ol>
   *
   * <p>幂等：同一来源单据在存活期间只能有一条实例（部分唯一索引 {@code approval_instance_live_source}），
   * 重复提交返回 409 而不是产生两条待办。
   *
   * <p>它**不要求** {@code identity.approval_inbox.read}：提交人是终端用户，他不需要「待我审批」页面的读权限
   * 才能发起一张要审批的单。授权来自内部密钥 + 已认证会话成员关系。
   */
  @PostMapping("/approval-instances")
  public ApprovalSubmitResult submit(
      @RequestHeader("X-Internal-Key") String key,
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ApprovalSubmitInput input) {
    if (!MessageDigest.isEqual(
        key.getBytes(StandardCharsets.UTF_8), internalKey.getBytes(StandardCharsets.UTF_8)))
      throw IdentityController.error(HttpStatus.FORBIDDEN, "拒绝内部调用");
    return call(
        auth,
        null,
        true,
        a -> {
          check(
              (input.amount() == null) == (input.currencyCode() == null),
              "金额与币种必须同时提供：只有金额没有币种，阈值分支匹配没有唯一答案");
          if (input.amount() != null) check(input.amount().signum() >= 0, "金额不能为负");
          var flow =
              sql.fetchOne(
                  "select id, flow_name, status from approval_flow where id=? and tenant_id=?",
                  input.flowId(),
                  a.tenantId());
          if (flow == null)
            throw IdentityController.error(HttpStatus.NOT_FOUND, "审批流不存在或不属于当前租户");
          // 只有生效流程在管控：草稿与停用流程不产生待办（V83「按单据类型注册」的运行时语义）。
          check(
              "ACTIVE".equals(flow.get("status", String.class)),
              "该审批流尚未生效，草稿或已停用的流程不产生待审批");

          var live =
              sql.fetchOne(
                  "select id from approval_instance where tenant_id=? and source_system=?"
                      + " and source_reference_id=? and status='PENDING'",
                  a.tenantId(),
                  input.sourceSystem().trim(),
                  input.sourceReferenceId().trim());
          if (live != null) conflict("该来源单据已有一条在办的审批实例，请勿重复提交");

          var start = startNode(a.tenantId(), input);
          var id = Ids.next();
          var now = AuditLog.utc(Instant.now());
          var instanceNo =
              sql.fetchOne("select approval_next_instance_no() as no").get("no", String.class);
          sql.execute(
              "insert into approval_instance(id,tenant_id,instance_no,flow_id,current_node_key,"
                  + " source_system,source_reference_id,subject_title,applicant_membership_id,"
                  + " amount,currency_code,status,submitted_at,node_entered_at,created_by_membership_id)"
                  + " values(?,?,?,?,?,?,?,?,?,?::numeric,?,'PENDING',?::timestamptz,?::timestamptz,?)",
              id,
              a.tenantId(),
              instanceNo,
              input.flowId(),
              start.nodeKey(),
              input.sourceSystem().trim(),
              input.sourceReferenceId().trim(),
              input.subjectTitle().trim(),
              a.membershipId(),
              input.amount(),
              input.currencyCode(),
              now,
              now,
              a.membershipId());
          audit.record(
              a,
              auth,
              "identity.approval_instance",
              id,
              input.subjectTitle().trim(),
              "SUBMIT",
              null,
              Map.of(
                  "instanceNo", instanceNo,
                  "flowId", input.flowId().toString(),
                  "sourceSystem", input.sourceSystem().trim(),
                  "sourceReferenceId", input.sourceReferenceId().trim(),
                  "nodeKey", start.nodeKey(),
                  "routedBy", start.routedBy()));
          return new ApprovalSubmitResult(
              id,
              instanceNo,
              input.flowId(),
              start.nodeKey(),
              start.nodeName(),
              a.membershipId(),
              start.routedBy(),
              now.toInstant());
        });
  }

  /** 起始节点的判定结果。{@code routedBy} 如实报出是金额分支命中还是流程顺序默认。 */
  private record StartNode(String nodeKey, String nodeName, String routedBy) {}

  /**
   * 起始节点：先金额分支后默认路径。
   *
   * <p>金额为 null 时**不**走分支（变更类操作没有金额，拿 0 去命中区间是错的），直接取 node_order 最小的节点。
   * 区间语义与 V83 一致：{@code [min_amount, max_amount)} 左闭右开。
   */
  private StartNode startNode(UUID tenant, ApprovalSubmitInput input) {
    if (input.amount() != null) {
      var branch =
          sql.fetchOne(
              "select target_node_key from approval_flow_amount_branch"
                  + " where tenant_id=? and flow_id=? and currency_code=?"
                  + "   and (min_amount is null or ?::numeric >= min_amount)"
                  + "   and (max_amount is null or ?::numeric < max_amount)"
                  + " order by priority desc, id limit 1",
              tenant,
              input.flowId(),
              input.currencyCode(),
              input.amount(),
              input.amount());
      if (branch != null)
        return node(tenant, input.flowId(), branch.get("target_node_key", String.class), "AMOUNT_BRANCH");
    }
    var first =
        sql.fetchOne(
            "select node_key from approval_flow_node where tenant_id=? and flow_id=?"
                + " order by node_order limit 1",
            tenant,
            input.flowId());
    if (first == null) conflict("该审批流没有任何节点，无法产生待审批");
    return node(tenant, input.flowId(), first.get("node_key", String.class), "FLOW_ORDER");
  }

  private StartNode node(UUID tenant, UUID flowId, String nodeKey, String routedBy) {
    var row =
        sql.fetchOne(
            "select node_name, approval_mode from approval_flow_node"
                + " where tenant_id=? and flow_id=? and node_key=?",
            tenant,
            flowId,
            nodeKey);
    if (row == null) conflict("金额分支指向的节点不属于该审批流");
    if ("CONDITIONAL".equals(row.get("approval_mode", String.class)))
      conflict("该单据按规则落在条件节点上，其条件表达式尚未被解析执行，无法确定起始节点");
    return new StartNode(nodeKey, row.get("node_name", String.class), routedBy);
  }

  // ------------------------------------------------------------------
  // 对接能力声明
  // ------------------------------------------------------------------

  /**
   * 各业务系统对本审批中心的对接状态。
   *
   * <p>蓝图说「上游 = 各业务系统提交」。这些系统各自在**别的库**里（AGENTS.md 禁止跨服务 SQL/FK/视图），
   * 所以本服务能做的只有两件事：提供一个授权提交 API（本侧已交付），以及**如实**报出每一侧是否已接上。
   * 下面每一条 {@code delivered=false} 都是真的没接，归属模块就是该车道自己去接的时候。
   *
   * <p>{@code observed} 里的计数全部来自 {@code approval_instance} 的真实聚合，不是摆在那里的样例。
   */
  @GetMapping("/my-approvals/capabilities")
  public ApprovalInboxCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        INBOX_READ,
        false,
        a ->
            new ApprovalInboxCapabilities(
                "POST /identity/approval-instances（X-Internal-Key + 已认证会话成员关系）",
                "本服务的提交入口与审批运行时已交付；各业务系统的调用方尚未交付",
                List.of(
                    new ApprovalSourceIntegration(
                        "trade", "09 订单与履约 / 11 售后与退货", "11.1.1.1 售后单等退款类单据",
                        false, "退款单的提交调用方未交付"),
                    new ApprovalSourceIntegration(
                        "warehouse", "08 仓储作业 / 07 库存", "7.x 库存调整、8.x 调拨单",
                        false, "库存调整与调拨单的提交调用方未交付"),
                    new ApprovalSourceIntegration(
                        "channel", "02 平台与店铺", "5.x 定价与调价单", false, "调价单的提交调用方未交付"),
                    new ApprovalSourceIntegration(
                        "finance", "12 财务", "12.x 核销单与贷项单", false, "核销与贷项的提交调用方未交付"),
                    new ApprovalSourceIntegration(
                        "identity",
                        "17 组织权限与系统设置",
                        "17.2.2.3 离职停用、17.3.1.1 角色授权",
                        false,
                        "本服务内的变更类操作目前走离职责任转移台账，尚未改为提交审批实例")),
                observed()));
  }

  private List<ApprovalSourceCount> observed() {
    return sql.fetch(
            "select source_system, count(*) as total,"
                + " count(*) filter (where status='PENDING') as pending"
                + " from approval_instance group by source_system order by source_system")
        .map(
            r ->
                new ApprovalSourceCount(
                    r.get("source_system", String.class),
                    r.get("total", Long.class),
                    r.get("pending", Long.class)));
  }
}
