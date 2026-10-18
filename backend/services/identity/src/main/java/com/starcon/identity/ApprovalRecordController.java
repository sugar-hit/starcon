package com.starcon.identity;

import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 17.4.2.2 审批记录。
 *
 * <blockquote>
 *
 * 「记录与查询审批全链路：发起、流转节点、各节点意见与时间、转交、驳回、撤回，最终结果；支持导出用于审计。」
 * 「上游 = 17.4.2.1；下游 = 各业务系统（审批结果驱动后续动作）。<b>审批记录不可修改</b>，
 * 驳回后重新发起要生成新实例并关联原实例。」
 *
 * </blockquote>
 *
 * <p><b>本模块是审批事实的读取、统计与导出层，不是第二个写入所有者。</b>
 *
 * <p>上游 17.4.2.1（{@code V160__my_approval_inbox.sql}）写下全部事实：
 * {@code approval_instance} 持有实例权威状态，{@code approval_instance_action} 持有只追加的动作留痕。
 * 蓝图点名的每一项 —— 发起、流转节点、各节点意见与时间、转交、驳回、最终结果、重新发起关联原实例 ——
 * 都能从这两张表<b>只读</b>推导，所以本模块<b>不建新表</b>（理由写在 {@code V163} 的文件头）。
 * V163 只加索引与页面权限。
 *
 * <p><b>三条边界写死在这里：</b>
 *
 * <ol>
 *   <li><b>不写任何审批事实。</b>本控制器一个写端点都没有。上游刻意给
 *       {@code approval_instance_action} 只授 {@code SELECT,INSERT}、从不授
 *       {@code UPDATE/DELETE}，「审批记录不可修改」在库上是权限级硬保证；本迁移一行未改这个授权。
 *   <li><b>不重复实现待我审批。</b>「谁现在该审这条单」是 17.4.2.1 的节点判定，不是记录查询。
 *       本模块回答的是「这条单走过哪些节点、每一步是谁在什么时候以什么意见做的」。
 *   <li><b>不假装撤回已交付。</b>{@code approval_instance.status} 的 CHECK 里刻意没有
 *       {@code WITHDRAWN}（V160 明确说明撤回由提交侧落地）。所以时间线里不会出现撤回动作，
 *       本模块也<b>不</b>预先往 CHECK 里塞一个没有任何代码路径能到达的状态 ——
 *       那会让「撤回」看起来已实现。{@link ApprovalRecordCapabilities} 如实报出这件事。
 * </ol>
 *
 * <p><b>与上游同一套时间口径：</b>停留时长不落列，只由 DB 侧 IMMUTABLE 函数
 * {@code approval_dwell_seconds} 现算；{@code timestamptz} 一律用
 * {@link AuditLog#utc} 绑 {@code OffsetDateTime} 并在 values 位置写 {@code ?::timestamptz}
 * （用 {@code Timestamp.valueOf} 会静默偏 −28800 秒）。
 *
 * <p><b>权限：</b>{@code identity.approval_record.read} 管检索与统计，
 * {@code identity.approval_record.export} 管导出 —— 「能查」和「能把全量审批链路导成 CSV 落到自己机器上」
 * 是两种不同的风险，所以是两个码而不是一个。两者都可授予任意自定义角色（合规/审计岗），
 * 刻意不复用 {@code identity.approval_inbox.*}：能看审批记录的人不该因此拿到审批权。
 *
 * <p>租户与操作人<b>全部</b>来自已认证会话的成员关系，{@code X-Tenant-Id} 头从头到尾没有出现，
 * 任何请求参数里也没有 tenantId。
 */
@RestController
@RequestMapping("/identity")
public class ApprovalRecordController {

  private static final String READ = "identity.approval_record.read";
  private static final String EXPORT = "identity.approval_record.export";

  private static final int MAX_PAGE_SIZE = 200;

  /**
   * 单次导出行数上限。审批记录是长期留存的合规数据，一次把全部历史导走既慢又扩大信息暴露面，
   * 超限明确报错让操作人收窄时间窗，而不是悄悄截断（与 17.5.3.1 审计导出同口径）。
   */
  private static final int MAX_EXPORT_ROWS = 50_000;

  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;

  public ApprovalRecordController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  // ------------------------------------------------------------------
  // 契约类型
  // ------------------------------------------------------------------

  /**
   * 审批记录列表的一行 —— 一个<b>实例</b>，不是一次动作。
   *
   * <p>「全链路记录」的列表按实例聚合是有意的：一条被 3 个人审过的单子如果按动作分行，
   * 同一个实例号会重复出现 3 次，而「这条单现在什么结果」这件事只有按实例看才成立。
   * 动作明细走 {@link ApprovalRecordNode} 里的时间线与 {@link #trace}。
   *
   * <p>{@code actionCount} / {@code rejectCount} / {@code transferCount} 是这条实例的动作统计，
   * 由 DB 侧聚合得出，让列表就能看出「这条单被转了几次手」而不必点进详情。
   *
   * <p>{@code dwellSeconds} 与上游同口径：未结实例算到此刻，终态实例冻结在 {@code finished_at}。
   */
  public record ApprovalRecordView(
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
      String currentNodeKey,
      @Schema(nullable = true) String currentNodeName,
      String status,
      String sourceSystem,
      String sourceReferenceId,
      Instant submittedAt,
      @Schema(nullable = true) Instant finishedAt,
      long dwellSeconds,
      long actionCount,
      long rejectCount,
      long transferCount,
      @Schema(nullable = true) UUID originalInstanceId,
      @Schema(nullable = true) String originalInstanceNo,
      long resubmittedCount) {}

  /**
   * 链路上的一个节点 —— 一次动作及其上下文。
   *
   * <p>{@code nodeKey} 是<b>动作发生时</b>的节点快照（来自 {@code approval_instance_action.node_key}），
   * 不是实例的当前节点：查一条已经流转了三步的单子时，用当前节点会把前两步显示成同一个节点。
   *
   * <p>{@code dwellSeconds} 是「这一步从进入该节点到做出动作压了多久」，同样由 DB 侧
   * {@code approval_dwell_seconds} 现算。
   */
  public record ApprovalRecordNode(
      UUID actionId,
      String action,
      String nodeKey,
      @Schema(nullable = true) String nodeName,
      int nodeOrder,
      UUID actorId,
      @Schema(nullable = true) String actorName,
      String comment,
      @Schema(nullable = true) UUID toMembershipId,
      @Schema(nullable = true) String toName,
      Instant decidedAt,
      long dwellSeconds) {}

  /** 一条实例的完整链路：实例头 + 逐节点动作 + 重发关联。 */
  public record ApprovalRecordDetail(
      ApprovalRecordView instance, List<ApprovalRecordNode> timeline, List<ApprovalResubmission> resubmissions) {}

  /**
   * 重发链上的一条。
   *
   * <p>蓝图那句「驳回后重新发起要生成新实例并关联原实例」在 V160 里落成
   * {@code approval_instance.original_instance_id}（带 FK 到同租户的 approval_instance）。
   * 本模块<b>只读</b>它：原实例详情里列出全部由它派生的重发实例，重发实例详情里指回原实例。
   */
  public record ApprovalResubmission(
      UUID id,
      String instanceNo,
      String status,
      Instant submittedAt,
      @Schema(nullable = true) Instant finishedAt) {}

  /**
   * 单条动作的全链路轨迹 —— 「某个动作经过了哪些节点」的按人视角。
   *
   * <p>与列表的差别：列表按<b>实例</b>聚合，这条按<b>动作</b>串。
   * 审批人问「上个月我批过的那些单子各自走到哪了」时，需要的是这一条而不是列表。
   */
  public record ApprovalRecordTrace(
      UUID instanceId,
      String instanceNo,
      String subjectTitle,
      String status,
      UUID applicantId,
      @Schema(nullable = true) String applicantName,
      String documentTypeCode,
      List<ApprovalRecordNode> timeline) {}

  /** 检索结果的表头汇总。{@code total} 是**未过滤**的全租户记录数，页面据此显示「筛选出 N / 共 M」。 */
  public record ApprovalRecordSummary(long total, long pendingTotal, long longestDwellSeconds) {}

  /** 分页信封：total 来自真实 count，不是前端猜的。 */
  public record ApprovalRecordPage(
      List<ApprovalRecordView> items, long total, int page, int size, ApprovalRecordSummary summary) {}

  // ------------------------------------------------------------------
  // 统计报表
  // ------------------------------------------------------------------

  /**
   * 某个分组维度上的一行。
   *
   * <p>{@code avgDwellSeconds} 是该分组下实例的平均停留时长（秒），
   * 由 DB 侧 {@code approval_dwell_seconds} 现算后取均值 —— 统计口径与列表展示值**同源**，
   * 不会出现「列表说压了 3 天、报表说平均 1 小时」这种自相矛盾。
   */
  public record ApprovalRecordBreakdown(
      String key, String label, long instanceCount, long pendingCount, long avgDwellSeconds) {}

  /**
   * 审批动作分类计数。
   *
   * <p>{@code action} 取自 {@code approval_instance_action.action} 的真实取值
   * （APPROVE / REJECT / TRANSFER），本模块不发明第四个。
   */
  public record ApprovalActionCount(String action, long count) {}

  /** 一个审批人维度：处理量、通过数、驳回数、转交发回数、被转交次数、平均处理时长。 */
  public record ApprovalActorStat(
      UUID actorId,
      @Schema(nullable = true) String actorName,
      long actionCount,
      long approveCount,
      long rejectCount,
      long transferOutCount,
      long transferInCount,
      long avgHandleSeconds) {}

  /**
   * 统计报表全量结果。
   *
   * <p>{@code periodFrom}/{@code periodTo} 如实回显本次统计实际覆盖的时间窗：
   * 调用方不传时间窗时这两个字段为 null，页面显示「全部时间」而不是假装统计了某个区间。
   */
  public record ApprovalRecordStatistics(
      @Schema(nullable = true) String periodFrom,
      @Schema(nullable = true) String periodTo,
      long instanceTotal,
      long pendingTotal,
      long finishedTotal,
      long rejectedTotal,
      long withdrawnTotal,
      double approveRate,
      long avgCycleSeconds,
      long avgActionSeconds,
      long longestDwellSeconds,
      List<ApprovalRecordBreakdown> byStatus,
      List<ApprovalRecordBreakdown> byDocumentType,
      List<ApprovalRecordBreakdown> byNode,
      List<ApprovalRecordBreakdown> bySourceSystem,
      List<ApprovalActionCount> byAction,
      List<ApprovalActorStat> byActor) {}

  /**
   * 交付边界声明。
   *
   * <p>{@code withdrawal.delivered=false} 是<b>如实</b>的：V160 明确说明撤回是申请人的动作、
   * 不在待我审批的职责内，{@code approval_instance.status} 的 CHECK 里刻意没有 {@code WITHDRAWN}。
   * 本模块<b>不</b>为了让自己好看起来就往 CHECK 里塞一个没有代码路径能到达的状态，
   * 也不在时间线上伪造撤回动作。撤回由提交侧车道落地后，本模块的链路查询自动就能呈现它。
   */
  public record ApprovalWithdrawCapability(String owningModule, boolean delivered, String note) {}

  /** 各来源系统的对接与计数。计数来自 DB 真实行，不是配置里写死的。 */
  public record ApprovalRecordSourceCount(String sourceSystem, long instanceCount, long pendingCount) {}

  public record ApprovalRecordCapabilities(
      String readsFactsFrom,
      boolean readsOnly,
      ApprovalWithdrawCapability withdrawal,
      List<ApprovalRecordSourceCount> observed) {}

  // ------------------------------------------------------------------
  // 调用骨架（与 ApprovalInboxController 同构）
  // ------------------------------------------------------------------

  /**
   * 解析会话 → 切到该会话的租户事务 → 锁会话 → 二次校验租户未切换 → 校验权限码。
   *
   * <p>租户来自会话成员关系，{@code X-Tenant-Id} 头在这里从头到尾没有出现过。
   */
  private <T> T call(String auth, String permission, Function<IdentityController.Access, T> work) {
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
                "当前角色没有审批记录查看权限");
          return work.apply(a);
        });
  }

  private static void require(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.FORBIDDEN, message);
  }

  private static void check(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.BAD_REQUEST, message);
  }

  private String hash(String auth) {
    return IdentityController.hash(identity.token(auth));
  }

  /**
   * 成员 id → 用户名。
   *
   * <p>与上游同口径：走 {@code member_names(hash)}（SECURITY DEFINER，以会话成员关系为条件）
   * 而不是直连 {@code app_user}。没有 {@code identity.member.read} 时解析不出用户名是正常的，
   * 所以 {@code applicantName} 为空绝不代表这个人不存在 —— {@code applicantId} 仍然可信。
   */
  private Map<UUID, String> memberNames(String auth) {
    var names = new LinkedHashMap<UUID, String>();
    for (Record r : sql.fetch("select id,username from member_names(?)", hash(auth)))
      names.put(r.get("id", UUID.class), r.get("username", String.class));
    return names;
  }

  // ------------------------------------------------------------------
  // 筛选条件
  // ------------------------------------------------------------------

  /**
   * 列表与导出**共用**的一套筛选条件与 SQL 片段拼装。
   *
   * <p>共用是硬要求：导出的必须是「当前查到的那些行」，不是另一个数据源。
   * 两处各写一份筛选拼装，迟早会出现「页面上看到 12 条、导出 400 条」。
   */
  private static final class Filter {
    private final List<String> clauses = new ArrayList<>();
    private final List<Object> bindings = new ArrayList<>();

    void add(String clause, Object value) {
      clauses.add(clause);
      bindings.add(value);
    }

    /** 校验并收下所有筛选参数。空串与 null 一律视为「不筛选」。 */
    static Filter of(
        String instanceNo,
        String documentTypeCode,
        String sourceSystem,
        String status,
        UUID applicantId,
        UUID actorId,
        String nodeKey,
        String action,
        String currencyCode,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        String submittedFrom,
        String submittedTo,
        String decidedFrom,
        String decidedTo) {
      var filter = new Filter();
      if (instanceNo != null && !instanceNo.isBlank()) filter.add("i.instance_no ilike ?", "%" + instanceNo.trim() + "%");
      if (documentTypeCode != null && !documentTypeCode.isBlank())
        filter.add("f.document_type_code = ?", documentTypeCode.trim());
      if (sourceSystem != null && !sourceSystem.isBlank())
        filter.add("i.source_system = ?", sourceSystem.trim());
      if (status != null && !status.isBlank()) filter.add("i.status = ?", status.trim());
      if (applicantId != null) filter.add("i.applicant_membership_id = ?", applicantId);
      if (currencyCode != null && !currencyCode.isBlank())
        filter.add("i.currency_code = ?", currencyCode.trim());
      if (minAmount != null) filter.add("i.amount >= ?::numeric", minAmount);
      if (maxAmount != null) filter.add("i.amount <= ?::numeric", maxAmount);
      var submittedFromAt = instant(submittedFrom);
      var submittedToAt = instant(submittedTo);
      check(
          submittedFromAt == null || submittedToAt == null || !submittedFromAt.isAfter(submittedToAt),
          "提交开始时间不能晚于结束时间");
      if (submittedFromAt != null)
        filter.add("i.submitted_at >= ?::timestamptz", AuditLog.utc(submittedFromAt));
      if (submittedToAt != null)
        filter.add("i.submitted_at <= ?::timestamptz", AuditLog.utc(submittedToAt));

      // 「按人」这一维有歧义：applicantId 是「谁发起的」，actorId 是「谁批过的」，
      // 两个都接进筛选器，界面上分成两个独立输入框而不是一个二选一。
      if (actorId != null)
        filter.add(
            "exists (select 1 from approval_instance_action x"
                + " where x.tenant_id=i.tenant_id and x.instance_id=i.id and x.actor_membership_id=?)",
            actorId);
      if (nodeKey != null && !nodeKey.isBlank()) filter.add("n.node_key = ?", nodeKey.trim());
      if (action != null && !action.isBlank())
        filter.add(
            "exists (select 1 from approval_instance_action x"
                + " where x.tenant_id=i.tenant_id and x.instance_id=i.id and x.action=?)",
            action.trim());
      var decidedFromAt = instant(decidedFrom);
      var decidedToAt = instant(decidedTo);
      check(
          decidedFromAt == null || decidedToAt == null || !decidedFromAt.isAfter(decidedToAt),
          "动作开始时间不能晚于结束时间");
      // 动作时间窗走 exists 子查询而不是 join：join 会把一条有 3 次动作的实例复制成 3 行，
      // 列表按实例聚合就废了，total 也会数重。
      if (decidedFromAt != null)
        filter.add(
            "exists (select 1 from approval_instance_action x where x.tenant_id=i.tenant_id"
                + " and x.instance_id=i.id and x.decided_at >= ?::timestamptz)",
            AuditLog.utc(decidedFromAt));
      if (decidedToAt != null)
        filter.add(
            "exists (select 1 from approval_instance_action x where x.tenant_id=i.tenant_id"
                + " and x.instance_id=i.id and x.decided_at <= ?::timestamptz)",
            AuditLog.utc(decidedToAt));
      return filter;
    }

    /** 接到一个已经写好 where 的查询后面。 */
    String appendTo(StringBuilder where) {
      for (var clause : clauses) where.append(" and ").append(clause);
      return where.toString();
    }

    Object[] bindings() {
      return bindings.toArray();
    }
  }

  /**
   * 实例 + 流程 + 当前节点的公共 FROM/JOIN。
   *
   * <p>{@code n} 只在需要节点名/节点筛选时 JOIN 进来（按当前节点筛 {@code nodeKey} 用它）；
   * 时间线与统计里的「按节点」分组走 {@code approval_instance_action.node_key}，不 JOIN 它 ——
   * 那是动作发生时的节点快照，与当前节点不是一回事。
   */
  private static String joins(boolean withCurrentNode) {
    return " from approval_instance i"
        + " join approval_flow f on f.tenant_id = i.tenant_id and f.id = i.flow_id"
        + " join approval_document_type dt on dt.code = f.document_type_code"
        + (withCurrentNode
            ? " join approval_flow_node n on n.tenant_id = i.tenant_id and n.flow_id = i.flow_id"
                + "   and n.node_key = i.current_node_key"
            : "");
  }

  /** 列表与详情共用的取数列（含动作计数与停留时长，聚合全部在 DB 侧）。 */
  private static final String SELECT_COLUMNS =
      """
      select i.id, i.instance_no, i.flow_id, i.subject_title, i.amount,
             i.currency_code::text as currency_code, i.status,
             i.applicant_membership_id, i.current_node_key,
             i.source_system, i.source_reference_id,
             i.submitted_at, i.node_entered_at, i.finished_at, i.original_instance_id,
             f.document_type_code, dt.name as document_type_name, f.flow_name,
             n.node_name as current_node_name,
             approval_dwell_seconds(coalesce(i.finished_at, now()), i.node_entered_at) as dwell_seconds,
             (select count(*) from approval_instance_action a
               where a.tenant_id = i.tenant_id and a.instance_id = i.id) as action_count,
             (select count(*) from approval_instance_action a
               where a.tenant_id = i.tenant_id and a.instance_id = i.id and a.action = 'REJECT') as reject_count,
             (select count(*) from approval_instance_action a
               where a.tenant_id = i.tenant_id and a.instance_id = i.id and a.action = 'TRANSFER') as transfer_count,
             (select count(*) from approval_instance_action a
               where a.tenant_id = i.tenant_id and a.instance_id = i.id and a.action = 'APPROVE') as approve_count,
             (select o.instance_no from approval_instance o
               where o.tenant_id = i.tenant_id and o.id = i.original_instance_id) as original_instance_no,
             (select count(*) from approval_instance r
               where r.tenant_id = i.tenant_id and r.original_instance_id = i.id) as resubmitted_count
      """
          + joins(true);

  private ApprovalRecordView view(Record row, Map<UUID, String> names) {
    var applicant = row.get("applicant_membership_id", UUID.class);
    return new ApprovalRecordView(
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
        row.get("current_node_key", String.class),
        row.get("current_node_name", String.class),
        row.get("status", String.class),
        row.get("source_system", String.class),
        row.get("source_reference_id", String.class),
        row.get("submitted_at", Instant.class),
        row.get("finished_at", Instant.class),
        row.get("dwell_seconds", Long.class),
        row.get("action_count", Long.class),
        row.get("reject_count", Long.class),
        row.get("transfer_count", Long.class),
        row.get("original_instance_id", UUID.class),
        row.get("original_instance_no", String.class),
        row.get("resubmitted_count", Long.class));
  }

  // ------------------------------------------------------------------
  // 全链路记录检索
  // ------------------------------------------------------------------

  /**
   * 全链路记录检索：按实例 / 按人 / 按节点 / 按时间范围，任意组合。
   *
   * <p><b>筛选、排序、分页全在 DB 层</b>，不在内存里筛：按节点与按动作都是
   * {@code exists} 子查询（而不是 join —— join 会把一条多动作的实例复制成多行，
   * 按实例聚合就废了，total 也会数重），两者都命中 V163 新建的
   * {@code approval_instance_action_by_node} / {@code _by_actor}。
   */
  @GetMapping("/approval-records")
  public ApprovalRecordPage search(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String instanceNo,
      @RequestParam(required = false) String documentTypeCode,
      @RequestParam(required = false) String sourceSystem,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID applicantId,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String nodeKey,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String currencyCode,
      @RequestParam(required = false) BigDecimal minAmount,
      @RequestParam(required = false) BigDecimal maxAmount,
      @RequestParam(required = false) String submittedFrom,
      @RequestParam(required = false) String submittedTo,
      @RequestParam(required = false) String decidedFrom,
      @RequestParam(required = false) String decidedTo,
      @RequestParam(defaultValue = "NEWEST") String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        READ,
        a -> {
          check(page >= 0, "页码不能为负");
          check(size >= 1 && size <= MAX_PAGE_SIZE, "每页条数需在 1 到 " + MAX_PAGE_SIZE + " 之间");
          check(minAmount == null || minAmount.signum() >= 0, "金额下限不能为负");
          check(
              minAmount == null || maxAmount == null || minAmount.compareTo(maxAmount) <= 0,
              "金额下限不能大于上限");
          check(
              status == null || List.of("PENDING", "APPROVED", "REJECTED").contains(status.trim()),
              "状态需为 PENDING / APPROVED / REJECTED");
          check(
              action == null || List.of("APPROVE", "REJECT", "TRANSFER").contains(action.trim()),
              "动作需为 APPROVE / REJECT / TRANSFER");

          var filter =
              Filter.of(
                  instanceNo, documentTypeCode, sourceSystem, status, applicantId, actorId, nodeKey,
                  action, currencyCode, minAmount, maxAmount,
                  submittedFrom, submittedTo, decidedFrom, decidedTo);
          var where = new StringBuilder(" where 1=1");
          filter.appendTo(where);

          long total =
              sql.fetchOne(
                      "select count(*)" + joins(true) + where, filter.bindings()).get(0, Long.class);

          var params = new ArrayList<Object>(Arrays.asList(filter.bindings()));
          params.add(size);
          params.add((long) page * size);
          var records =
              sql.fetch(
                  SELECT_COLUMNS + where + orderBy(sort) + " limit ? offset ?", params.toArray());
          var names = memberNames(auth);
          return new ApprovalRecordPage(
              records.map(r -> view(r, names)),
              total,
              page,
              size,
              summary());
        });
  }

  /**
   * 排序白名单。
   *
   * <p>未知值直接 400 而不是默默回落：排序键拼错却安静地给出另一种顺序，比报错更难查。
   * {@code WAITING} 与上游待办同义（{@code node_entered_at asc}），「最久的排最前」在
   * 审批记录页同样是一个真实需求 —— 找出压得最久的那几条。
   */
  private static String orderBy(String sort) {
    return switch (sort == null ? "" : sort.trim()) {
      case "NEWEST" -> " order by i.submitted_at desc, i.id desc";
      case "OLDEST" -> " order by i.submitted_at asc, i.id asc";
      case "WAITING" -> " order by i.node_entered_at asc, i.id asc";
      case "FINISHED_NEWEST" -> " order by i.finished_at desc nulls last, i.id desc";
      case "AMOUNT_DESC" -> " order by i.amount desc nulls last, i.id desc";
      case "AMOUNT_ASC" -> " order by i.amount asc nulls last, i.id asc";
      case "DOCUMENT" -> " order by f.document_type_code asc, i.submitted_at desc, i.id desc";
      default -> throw IdentityController.error(
          HttpStatus.BAD_REQUEST,
          "排序键需为 NEWEST / OLDEST / WAITING / FINISHED_NEWEST / AMOUNT_DESC / AMOUNT_ASC / DOCUMENT");
    };
  }

  /** 表头汇总：未过滤的全租户记录数、未结数与最长停留。 */
  private ApprovalRecordSummary summary() {
    var row =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where status='PENDING') as pending,"
                + " coalesce(max(approval_dwell_seconds(coalesce(finished_at, now()), node_entered_at)),0) as longest"
                + " from approval_instance");
    return new ApprovalRecordSummary(
        row.get("total", Long.class), row.get("pending", Long.class), row.get("longest", Long.class));
  }

  // ------------------------------------------------------------------
  // 单条实例的完整链路
  // ------------------------------------------------------------------

  /**
   * 一条实例的完整审批链路：逐节点的流转、各节点意见与时间、转交、驳回、最终结果、重发关联。
   *
   * <p>与 17.4.2.1 待办详情的差别是根本性的：那条只能查<b>还停在别人/你手上</b>的 PENDING 实例，
   * 这里查<b>任何状态</b>的实例 —— 审批记录要回答的是「三个月前那单最后怎么了」，
   * 而那单早就 APPROVED 或 REJECTED 了，在待办列表里根本不存在。
   *
   * <p>跨租户访问在 RLS 下表现为「查不到」，统一 404 —— 403 会泄露「这条记录存在」。
   */
  @GetMapping("/approval-records/{id}")
  public ApprovalRecordDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        READ,
        a -> {
          var row = sql.fetchOne(SELECT_COLUMNS + " where i.id = ?", id);
          if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "审批记录不存在或不属于当前租户");
          var names = memberNames(auth);
          return new ApprovalRecordDetail(
              view(row, names), timeline(id, names), resubmissions(id));
        });
  }

  /**
   * 时间线：按 {@code decided_at} 串起全部动作，**只读** {@code approval_instance_action}。
   *
   * <p>节点名用 LEFT JOIN 取：动作表里的 {@code node_key} 是当时的节点快照，
   * 而流程定义可能被改过（{@code approval_flow_node} 是活的），快照对应的名字取不到时
   * 宁可只显示 node_key，也不能把这条动作从时间线上漏掉 —— 漏掉一条动作等于伪造了一段历史。
   *
   * <p>停留时长 = 「进入该节点 → 做出这一步动作」压了多久。起算点是**上一步动作的时刻**，
   * 第一步的起算点是实例的 {@code node_entered_at}。这与上游「停留时长只落 node_entered_at、
   * 展示值由 IMMUTABLE 函数现算」是同一套口径。
   */
  private List<ApprovalRecordNode> timeline(UUID instanceId, Map<UUID, String> names) {
    var entered =
        sql.fetchOne(
                "select node_entered_at from approval_instance where id=?", instanceId)
            .get("node_entered_at", Instant.class);
    var rows =
        sql.fetch(
            """
            select a.id, a.action, a.node_key, a.actor_membership_id, a.comment,
                   a.to_membership_id, a.decided_at,
                   n.node_name, n.node_order,
                   approval_dwell_seconds(a.decided_at, coalesce(lag.decided_at, ?::timestamptz)) as dwell_seconds
              from approval_instance_action a
              left join approval_flow_node n
                on n.tenant_id = a.tenant_id and n.flow_id = (select flow_id from approval_instance where id = a.instance_id)
               and n.node_key = a.node_key
              left join lateral (
                select prev.decided_at from approval_instance_action prev
                 where prev.tenant_id = a.tenant_id and prev.instance_id = a.instance_id
                   and (prev.decided_at, prev.id) < (a.decided_at, a.id)
                 order by prev.decided_at desc, prev.id desc limit 1
              ) lag on true
             where a.instance_id = ?
             order by a.decided_at, a.id
            """,
            AuditLog.utc(entered), instanceId);
    var nodes = new ArrayList<ApprovalRecordNode>();
    for (Record r : rows) {
      var actor = r.get("actor_membership_id", UUID.class);
      var to = r.get("to_membership_id", UUID.class);
      nodes.add(
          new ApprovalRecordNode(
              r.get("id", UUID.class),
              r.get("action", String.class),
              r.get("node_key", String.class),
              r.get("node_name", String.class),
              r.get("node_order", Integer.class) == null ? 0 : r.get("node_order", Integer.class),
              actor,
              names.get(actor),
              r.get("comment", String.class),
              to,
              to == null ? null : names.get(to),
              r.get("decided_at", Instant.class),
              r.get("dwell_seconds", Long.class)));
    }
    return nodes;
  }

  /**
   * 由这条实例派生出的重发实例（驳回后重新发起）。
   *
   * <p>上游 {@code V160} 已把「重新发起要关联原实例」落成 {@code original_instance_id}
   * + 同租户自引用 FK。本模块只读它，顺着这条索引（{@code approval_instance_by_original}）
   * 一次查完全部派生实例。
   */
  private List<ApprovalResubmission> resubmissions(UUID instanceId) {
    return sql.fetch(
            "select id, instance_no, status, submitted_at, finished_at from approval_instance"
                + " where original_instance_id = ? order by submitted_at, id",
            instanceId)
        .map(
            r ->
                new ApprovalResubmission(
                    r.get("id", UUID.class),
                    r.get("instance_no", String.class),
                    r.get("status", String.class),
                    r.get("submitted_at", Instant.class),
                    r.get("finished_at", Instant.class)));
  }

  // ------------------------------------------------------------------
  // 按人视角的链路
  // ------------------------------------------------------------------

  /**
   * 某个审批人经手的所有实例，各自的完整时间线。
   *
   * <p>回答「我上个月批过的那些单子各自走到哪了」—— 列表按实例聚合且分页，
   * 一次只给一页；审批人真正想要的是「我的动作 → 所在实例 → 该实例后续走了哪些节点」。
   * 走 {@code approval_instance_action_by_actor}，按动作时间倒序。
   */
  @GetMapping("/approval-records/by-actor/{actorId}")
  public List<ApprovalRecordTrace> byActor(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID actorId,
      @RequestParam(defaultValue = "100") int limit) {
    return call(
        auth,
        READ,
        a -> {
          check(limit >= 1 && limit <= MAX_PAGE_SIZE, "条数需在 1 到 " + MAX_PAGE_SIZE + " 之间");
          var ids =
              sql.fetch(
                      "select instance_id from approval_instance_action"
                          + " where actor_membership_id = ? group by instance_id"
                          + " order by max(decided_at) desc limit ?",
                      actorId,
                      limit)
                  .map(r -> r.get("instance_id", UUID.class));
          var names = memberNames(auth);
          var traces = new ArrayList<ApprovalRecordTrace>();
          for (UUID instanceId : ids) {
            var row =
                sql.fetchOne(
                    SELECT_COLUMNS + " where i.id = ?", instanceId);
            if (row == null) continue;
            var applicant = row.get("applicant_membership_id", UUID.class);
            traces.add(
                new ApprovalRecordTrace(
                    row.get("id", UUID.class),
                    row.get("instance_no", String.class),
                    row.get("subject_title", String.class),
                    row.get("status", String.class),
                    applicant,
                    applicant == null ? null : names.get(applicant),
                    row.get("document_type_code", String.class),
                    timeline(instanceId, names)));
          }
          return traces;
        });
  }

  // ------------------------------------------------------------------
  // 统计报表
  // ------------------------------------------------------------------

  /**
   * 统计报表：按状态 / 单据类型 / 节点 / 来源系统 / 动作 / 审批人分组，含平均与最长停留。
   *
   * <p>全部聚合在 DB 侧完成（AGENTS.md：关键 SQL 必须可审阅，不用 JPA 懒加载掩盖逻辑）。
   * {@code approveRate} 与 {@code avgCycleSeconds} 用 {@code numeric} 侧算出后取数，
   * 不在 Java 里做除法 —— 金额与比率都不该经由浮点。
   *
   * <p>{@code avgHandleSeconds} 按审批人算的是「一个动作从进入节点到被处理」的均值，
   * 与 {@code avgCycleSeconds}（一个实例从提交到结清）是两个不同的时间尺度，不混用。
   */
  @GetMapping("/approval-records/statistics")
  public ApprovalRecordStatistics statistics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String documentTypeCode,
      @RequestParam(required = false) String sourceSystem,
      @RequestParam(required = false) String submittedFrom,
      @RequestParam(required = false) String submittedTo) {
    return call(
        auth,
        READ,
        a -> {
          var from = instant(submittedFrom);
          var to = instant(submittedTo);
          check(from == null || to == null || !from.isAfter(to), "开始时间不能晚于结束时间");
          var where = new StringBuilder(" where 1=1");
          var params = new ArrayList<Object>();
          if (documentTypeCode != null && !documentTypeCode.isBlank()) {
            where.append(" and f.document_type_code = ?");
            params.add(documentTypeCode.trim());
          }
          if (sourceSystem != null && !sourceSystem.isBlank()) {
            where.append(" and i.source_system = ?");
            params.add(sourceSystem.trim());
          }
          if (from != null) {
            where.append(" and i.submitted_at >= ?::timestamptz");
            params.add(AuditLog.utc(from));
          }
          if (to != null) {
            where.append(" and i.submitted_at <= ?::timestamptz");
            params.add(AuditLog.utc(to));
          }
          var joined = joins(false) + where;

          var head =
              sql.fetchOne(
                  """
                  select count(*) as total,
                         count(*) filter (where i.status='PENDING') as pending,
                         count(*) filter (where i.status='APPROVED') as approved,
                         count(*) filter (where i.status='REJECTED') as rejected,
                         count(*) filter (where i.status='WITHDRAWN') as withdrawn,
                         count(*) filter (where i.status<>'PENDING') as finished,
                         coalesce(round(avg(approval_dwell_seconds(i.finished_at, i.submitted_at))
                                 filter (where i.finished_at is not null)),0)::bigint as avg_cycle,
                         coalesce(max(approval_dwell_seconds(coalesce(i.finished_at, now()), i.node_entered_at)),0)::bigint as longest
                    """
                      + joined,
                  params.toArray());

          // approveRate 用 numeric 算再转 double：已结实例里通过的占比，分母为 0 时给 0 而不是 NaN。
          var finished = head.get("finished", Long.class);
          var approved = head.get("approved", Long.class);
          double approveRate = finished == 0 ? 0d : (double) approved / (double) finished;

          return new ApprovalRecordStatistics(
              submittedFrom,
              submittedTo,
              head.get("total", Long.class),
              head.get("pending", Long.class),
              finished,
              head.get("rejected", Long.class),
              head.get("withdrawn", Long.class),
              approveRate,
              head.get("avg_cycle", Long.class),
              avgActionSeconds(),
              head.get("longest", Long.class),
              breakdown("i.status", joined, params, false),
              breakdown("f.document_type_code", joined, params, true),
              nodeBreakdown(),
              breakdown("i.source_system", joined, params, false),
              actionCounts(),
              actorStats(auth));
        });
  }

  /** 分组维度的实例计数与平均停留。与列表取同一套 {@code approval_dwell_seconds}，口径同源。 */
  private List<ApprovalRecordBreakdown> breakdown(
      String groupBy, String joined, List<Object> params, boolean withName) {
    var nameSelect =
        withName
            ? ", max(dt.name) as name"
            : "";
    return sql.fetch(
            "select " + groupBy + " as key" + nameSelect + ", count(*) as instance_count,"
                + " count(*) filter (where i.status='PENDING') as pending_count,"
                + " coalesce(round(avg(approval_dwell_seconds(coalesce(i.finished_at, now()), i.node_entered_at))),0)::bigint as avg_dwell"
                + joined
                + " group by " + groupBy
                + " order by instance_count desc, key asc",
            params.toArray())
        .map(
            r ->
                new ApprovalRecordBreakdown(
                    r.get("key", String.class),
                    withName ? r.get("name", String.class) : r.get("key", String.class),
                    r.get("instance_count", Long.class),
                    r.get("pending_count", Long.class),
                    r.get("avg_dwell", Long.class)));
  }

  /**
   * 按节点分组：统计这条链路上**各节点经手过多少实例**。
   *
   * <p>刻意走 {@code approval_instance_action.node_key}（动作发生时的节点快照）而不是
   * {@code approval_instance.current_node_key}：问「哪个节点是瓶颈」时，答案必须包含那些
   * 已经被流转走掉的节点 —— 只看当前节点的话，一条三步流程的三步会被全算到同一个节点头上。
   */
  private List<ApprovalRecordBreakdown> nodeBreakdown() {
    return sql.fetch(
            """
            select x.node_key as key,
                   max(n.node_name) as name,
                   count(*) as instance_count,
                   0::bigint as pending_count,
                   coalesce(round(avg(approval_dwell_seconds(x.decided_at, coalesce(prev.decided_at, i.node_entered_at)))),0)::bigint as avg_dwell
              from approval_instance_action x
              join approval_instance i on i.tenant_id = x.tenant_id and i.id = x.instance_id
              left join approval_flow_node n on n.tenant_id = i.tenant_id and n.flow_id = i.flow_id
                 and n.node_key = x.node_key
              left join lateral (
                select p.decided_at from approval_instance_action p
                 where p.tenant_id = x.tenant_id and p.instance_id = x.instance_id
                   and (p.decided_at, p.id) < (x.decided_at, x.id)
                 order by p.decided_at desc, p.id desc limit 1
              ) prev on true
             group by x.node_key
             order by instance_count desc, key asc
            """)
        .map(
            r ->
                new ApprovalRecordBreakdown(
                    r.get("key", String.class),
                    r.get("name", String.class) == null ? r.get("key", String.class) : r.get("name", String.class),
                    r.get("instance_count", Long.class),
                    r.get("pending_count", Long.class),
                    r.get("avg_dwell", Long.class)));
  }

  /** 动作分类计数。分组键取自表里的真实取值，本模块不发明第四种动作。 */
  private List<ApprovalActionCount> actionCounts() {
    return sql.fetch(
            "select action, count(*) as count from approval_instance_action"
                + " group by action order by count desc, action")
        .map(r -> new ApprovalActionCount(r.get("action", String.class), r.get("count", Long.class)));
  }

  /**
   * 按审批人聚合。
   *
   * <p>{@code avg_handle} 是「一个动作从进入节点到被处理」的均值秒数 —— 用窗口函数取每个动作的
   * 上一步时刻算停留，再对同一人取均值。转交被计入处理量但不计入通过/驳回。
   *
   * <p>成员名用 {@code member_names} 在 Java 侧合并（与列表同口径）：
   * 直连 {@code membership} 不行（V1：service account 不能直接枚举用户），而
   * SQL 里 join {@code member_names} 需要会话哈希、还要与时间窗参数交叉绑定，在
   * {@code grouped} 之外再加一次逐 id 查询反而更清楚。
   */
  private List<ApprovalActorStat> actorStats(String auth) {
    var rows =
        sql.fetch(
            """
            select a.actor_membership_id as actor_id,
                   count(*) as action_count,
                   count(*) filter (where a.action='APPROVE') as approve_count,
                   count(*) filter (where a.action='REJECT') as reject_count,
                   count(*) filter (where a.action='TRANSFER') as transfer_out,
                   (select count(*) from approval_instance_action t
                     where t.to_membership_id = a.actor_membership_id) as transfer_in,
                   coalesce(round(avg(approval_dwell_seconds(a.decided_at, coalesce(prev.decided_at, i.node_entered_at)))),0)::bigint as avg_handle
              from approval_instance_action a
              join approval_instance i on i.tenant_id = a.tenant_id and i.id = a.instance_id
              left join lateral (
                select p.decided_at from approval_instance_action p
                 where p.tenant_id = a.tenant_id and p.instance_id = a.instance_id
                   and (p.decided_at, p.id) < (a.decided_at, a.id)
                 order by p.decided_at desc, p.id desc limit 1
              ) prev on true
             group by a.actor_membership_id
             order by action_count desc, actor_id asc
             limit 50
            """);
    var resolved = memberNames(auth);
    var stats = new ArrayList<ApprovalActorStat>();
    for (Record r : rows) {
      var id = r.get("actor_id", UUID.class);
      stats.add(
          new ApprovalActorStat(
              id,
              resolved.get(id),
              r.get("action_count", Long.class),
              r.get("approve_count", Long.class),
              r.get("reject_count", Long.class),
              r.get("transfer_out", Long.class),
              r.get("transfer_in", Long.class),
              r.get("avg_handle", Long.class)));
    }
    return stats;
  }

  /** 平均单步处理时长（全部动作）。与 {@code actorStats} 里逐人算的口径一致。 */
  private long avgActionSeconds() {
    var row =
        sql.fetchOne(
            """
            select coalesce(round(avg(approval_dwell_seconds(a.decided_at, coalesce(prev.decided_at, i.node_entered_at)))),0)::bigint as avg_action
              from approval_instance_action a
              join approval_instance i on i.tenant_id = a.tenant_id and i.id = a.instance_id
              left join lateral (
                select p.decided_at from approval_instance_action p
                 where p.tenant_id = a.tenant_id and p.instance_id = a.instance_id
                   and (p.decided_at, p.id) < (a.decided_at, a.id)
                 order by p.decided_at desc, p.id desc limit 1
              ) prev on true
            """);
    return row.get("avg_action", Long.class);
  }

  // ------------------------------------------------------------------
  // 审计导出
  // ------------------------------------------------------------------

  /**
   * 导出 CSV 供审计使用。
   *
   * <p>与 {@link #search} **共用同一套筛选拼装**，所以导出的就是当前查到的那些行。
   * 上限 {@value #MAX_EXPORT_ROWS} 行，超限明确报错让操作人收窄时间窗，而不是悄悄截断
   * （与 17.5.3.1 审计导出同口径）。
   *
   * <p>导出的内容**是实例级摘要 + 链路动作逐行展开**：一行实例摘要不足以支撑审计
   * （看不出谁在什么时候以什么意见做的），只有动作时间线才是蓝图说的「审批链路记录」。
   * 因此每行是一条 (实例, 动作) 组合，没有动作的实例也会带一行 action 为空的实例摘要 ——
   * 一条还没人批的单子同样是审批记录的一部分。
   *
   * <p>需要独立的 {@code identity.approval_record.export} 权限：把全量审批链路导成 CSV
   * 落到自己机器上，与「在系统里看两眼」是不同的风险。
   */
  @GetMapping(value = "/approval-records/export", produces = "text/csv")
  public ResponseEntity<byte[]> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String instanceNo,
      @RequestParam(required = false) String documentTypeCode,
      @RequestParam(required = false) String sourceSystem,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID applicantId,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String nodeKey,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String currencyCode,
      @RequestParam(required = false) BigDecimal minAmount,
      @RequestParam(required = false) BigDecimal maxAmount,
      @RequestParam(required = false) String submittedFrom,
      @RequestParam(required = false) String submittedTo,
      @RequestParam(required = false) String decidedFrom,
      @RequestParam(required = false) String decidedTo) {
    var bytes =
        call(
            auth,
            EXPORT,
            a -> {
              var filter =
                  Filter.of(
                      instanceNo, documentTypeCode, sourceSystem, status, applicantId, actorId,
                      nodeKey, action, currencyCode, minAmount, maxAmount,
                      submittedFrom, submittedTo, decidedFrom, decidedTo);
              var where = new StringBuilder(" where 1=1");
              filter.appendTo(where);
              long total =
                  sql.fetchOne(
                          "select count(*)" + joins(true) + where, filter.bindings())
                      .get(0, Long.class);
              check(
                  total <= MAX_EXPORT_ROWS,
                  "命中 " + total + " 条，超过单次导出上限 " + MAX_EXPORT_ROWS + "，请收窄时间范围");
              var names = memberNames(auth);
              var records =
                  sql.fetch(
                      SELECT_COLUMNS + where + " order by i.submitted_at desc, i.id desc limit ?",
                      concat(filter.bindings(), MAX_EXPORT_ROWS));
              return csv(records, names);
            });
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"approval-records-" + Instant.now().toEpochMilli() + ".csv\"")
        .body(bytes);
  }

  /**
   * CSV 渲染：RFC 4180 转义 + 中文表头，前导 {@code =+-@} 加单引号切断 Excel 公式注入
   * （与 {@code AuditController} 的导出同一处置 —— 审批意见是人写的自由文本，注入面比审计日志更宽）。
   */
  private byte[] csv(List<Record> records, Map<UUID, String> names) {
    var out = new StringBuilder("﻿");
    out.append(
        String.join(
            ",",
            List.of(
                "实例号", "单据类型", "流程", "标题", "金额", "币种", "申请人", "申请人ID",
                "当前节点", "结果", "提交时间", "结清时间", "停留秒数", "动作条数",
                "节点Key", "动作", "操作人", "操作人ID", "转交目标", "转交目标ID",
                "审批意见", "动作时间", "来源系统", "来源单据", "原实例号")));
    out.append('\n');
    for (Record instance : records) {
      var instanceId = instance.get("id", UUID.class);
      var applicant = instance.get("applicant_membership_id", UUID.class);
      var nodes = timeline(instanceId, names);
      if (nodes.isEmpty()) {
        // 还没人动过的单子也是审批记录的一部分，给一行 action 为空的实例摘要，
        // 否则导出里会看不到「有一张单子正压着没人审」。
        out.append(instanceRow(instance, names, null, applicant));
        out.append('\n');
        continue;
      }
      for (ApprovalRecordNode node : nodes) {
        out.append(instanceRow(instance, names, node, applicant));
        out.append('\n');
      }
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private String instanceRow(
      Record instance, Map<UUID, String> names, ApprovalRecordNode node, UUID applicant) {
    var cells = new ArrayList<String>();
    cells.add(instance.get("instance_no", String.class));
    cells.add(instance.get("document_type_name", String.class));
    cells.add(instance.get("flow_name", String.class));
    cells.add(instance.get("subject_title", String.class));
    cells.add(instance.get("amount", BigDecimal.class) == null
        ? ""
        : instance.get("amount", BigDecimal.class).toPlainString());
    cells.add(instance.get("currency_code", String.class));
    cells.add(names.get(applicant));
    cells.add(applicant == null ? "" : applicant.toString());
    cells.add(instance.get("current_node_key", String.class));
    cells.add(instance.get("status", String.class));
    cells.add(str(instance.get("submitted_at", Instant.class)));
    cells.add(str(instance.get("finished_at", Instant.class)));
    cells.add(String.valueOf(instance.get("dwell_seconds", Long.class)));
    cells.add(String.valueOf(instance.get("action_count", Long.class)));
    if (node == null) {
      cells.add("");
      cells.add("");
      cells.add("");
      cells.add("");
      cells.add("");
      cells.add("");
    } else {
      cells.add(node.nodeKey());
      cells.add(node.action());
      cells.add(node.actorName());
      cells.add(node.actorId().toString());
      cells.add(node.toName());
      cells.add(node.toMembershipId() == null ? "" : node.toMembershipId().toString());
      cells.add(node.comment());
      cells.add(str(node.decidedAt()));
    }
    cells.add(instance.get("source_system", String.class));
    cells.add(instance.get("source_reference_id", String.class));
    cells.add(instance.get("original_instance_no", String.class));
    return String.join(",", cells.stream().map(ApprovalRecordController::escape).toList());
  }

  private static String str(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String escape(String value) {
    var text = value == null ? "" : value;
    if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  private static Object[] concat(Object[] values, int extra) {
    var all = new ArrayList<Object>(Arrays.asList(values));
    all.add(extra);
    return all.toArray();
  }

  // ------------------------------------------------------------------
  // 对接与边界声明
  // ------------------------------------------------------------------

  /**
   * 交付边界与按来源计数。
   *
   * <p>{@code withdrawal.delivered=false} 是<b>如实</b>的：V160 明确说明撤回是申请人的动作、
   * 不在待我审批的职责内，{@code approval_instance.status} 的 CHECK 里刻意没有 {@code WITHDRAWN}。
   * 本模块不预先往 CHECK 里塞一个没有代码路径能到达的状态，也不在时间线上伪造撤回动作 ——
   * 那样会让「撤回已支持」看起来成立。撤回由提交侧车道落地后，本模块的链路查询自动就能呈现它。
   */
  @GetMapping("/approval-records/capabilities")
  public ApprovalRecordCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        READ,
        a ->
            new ApprovalRecordCapabilities(
                "identity.approval_instance + identity.approval_instance_action（17.4.2.1 写入）",
                true,
                new ApprovalWithdrawCapability(
                    "提交侧（各业务系统撤回入口）",
                    false,
                    "撤回是申请人的动作，不在待我审批的职责内；approval_instance.status 的 CHECK 刻意未包含 "
                        + "WITHDRAWN，本模块如实报未交付，不伪造撤回动作"),
                sql.fetch(
                        "select source_system, count(*) as instance_count,"
                            + " count(*) filter (where status='PENDING') as pending_count"
                            + " from approval_instance group by source_system"
                            + " order by instance_count desc, source_system")
                    .map(
                        r ->
                            new ApprovalRecordSourceCount(
                                r.get("source_system", String.class),
                                r.get("instance_count", Long.class),
                                r.get("pending_count", Long.class)))));
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
}
