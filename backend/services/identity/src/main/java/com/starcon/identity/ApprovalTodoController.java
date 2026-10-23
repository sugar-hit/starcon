package com.starcon.identity;

import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.1.1.3 审批待办 —— 工作台（卷 01）的**只读聚合层**。
 *
 * <blockquote>
 *
 * 「待我审批的单据统一入口，支持按单据类型、业务域、金额阈值、超时时长筛选；展示审批链路当前节点与已耗时长。」
 * 「上游 = 17 组织权限系统的 {@code approval_instance} 投影；输出 = 待审批列表（单据头信息、金额、申请人、停留时长、节点）；
 * 跳转到 17 的审批详情页。」
 *
 * </blockquote>
 *
 * <h2>边界三处，写死在这里</h2>
 *
 * <ol>
 *   <li><b>工作台不执行审批动作。</b>蓝图那句「<b>工作台不执行审批动作</b>，避免两处审批逻辑不一致」
 *       在上游 {@link ApprovalInboxController} 的类注释里是字面事实：审批与转交只在 17 执行。
 *       所以本控制器<b>只有 GET</b>：{@link ApprovalTodoCapabilities#actions()} 里把
 *       {@code APPROVE} / {@code REJECT} / {@code TRANSFER} 如实报成 {@code available=false} 并写明归属模块，
 *       页面上不会出现第二个能改单子的按钮。</li>
 *   <li><b>不新建业务事实表。</b>本模块是 {@code approval_instance} 的**只读投影**，
 *       一张表都不建、{@code legal_entity} / {@code user_preference} 一列都不改
 *       （它们分别是 17.1 与 1.4.1.2 的，写所有者不是本车道）。
 *       之所以仍然需要一个端点而不是纯前端聚合：维度计数（单据类型 / 业务域 / 币种）
 *       必须是<b>整集合</b>的计数，而不是当前这一页的计数 ——
 *       前端只能拿到分页后的切片，用切片算出来的分档数会让「点一下就按这一类筛」对不上。</li>
 *   <li><b>不假装阈值存在。</b>蓝图说「金额阈值按法人配置」，但 {@code legal_entity} 表<b>没有</b>金额阈值列，
 *       {@code approval_instance} 也<b>不</b>带 {@code legal_entity_id}（它连法人是谁都记不下来）。
 *       所以「法人级金额阈值」在当前 schema 下<b>判不了</b>，
 *       {@link ApprovalTodoCapabilities#filters()} 里如实报 {@code available=false} 并写明缺什么，
 *       绝不能拿一个硬编码常量冒充审批 SLA 口径。
 *       真正能用的是<b>流程上配的金额阈值分支</b>（{@code approval_flow_amount_branch}）：
 *       它按金额把单据分档，本模块既把档位显示在行上，也支持按档位筛选 ——
 *       那一维的口径与上游的分支路由判定逐字同式，所以工作台报出来的档位
 *       与这张单实际会走的路径对得上。</li>
 * </ol>
 *
 * <h2>数据来源（全部是真实表，零 mock）</h2>
 *
 * <ul>
 *   <li>{@code approval_instance} —— 单据头信息、金额、申请人、当前节点、停留起点（17.4.2.1 拥有，本模块只读）；</li>
 *   <li>{@code approval_flow} / {@code approval_document_type} —— 单据类型与流程名；</li>
 *   <li>{@code approval_flow_node} —— 当前节点的名称、顺序、审批人类型；</li>
 *   <li>{@code approval_flow_escalation} —— <b>超时口径</b>：节点上配的 {@code timeout_hours} 就是「多久算超时」，
 *       所以「已超时」是查出来的，不是猜的；</li>
 *   <li>{@code approval_flow_amount_branch} —— <b>金额阈值口径</b>：流程上配的金额区间分支，
 *       单据落在哪一档是按金额比出来的。</li>
 * </ul>
 *
 * <p><b>「归我」的判定逐字对齐上游</b>（{@link ApprovalInboxController} 的同名常量）：
 * 被转交指派的人优先；否则按当前节点的审批人类型（USER / ROLE）判定；POSITION 节点解析不出人，
 * 不在本模块假装能解析。这段判定一旦与上游漂移，两边就会给出不同的待办清单，
 * 所以 {@code ApprovalTodoIntegrationTest} 里有一条**清单等价性**断言把两边钉在一起。
 *
 * <p><b>租户来自已认证会话的成员关系</b>：{@code X-Tenant-Id} 头从头到尾没有出现过，
 * 请求参数里也不接受任何租户或成员 id。
 */
@RestController
@RequestMapping("/identity")
public class ApprovalTodoController {

  /**
   * 权限码沿用上游的 {@code identity.approval_inbox.read}。
   *
   * <p>本模块读的就是「我的待审批」这同一批事实，另立一个工作台专属权限码只会带来一个坏处：
   * 新建的角色默认没有这个码，于是管理员明明配了审批权限，待办页却静默空着。
   * 权限决定「能不能用这个页面」，节点决定「哪些单子归他」，两件事分开。
   */
  private static final String INBOX_READ = "identity.approval_inbox.read";

  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;

  public ApprovalTodoController(
      DSLContext sql,
      IdentityController identity,
      PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  // ------------------------------------------------------------------
  // 契约类型
  // ------------------------------------------------------------------

  /**
   * 待审批清单的一行。
   *
   * <p>{@code dwellSeconds} 是**在当前节点停了多久**（DB 侧 {@code approval_dwell_seconds} 算，不是 Java 算），
   * {@code totalSeconds} 是**从提交到今多久**。两个都给人看：前者回答「这单压在谁手里」，后者回答「申请人等了多久」。
   *
   * <p>{@code timeoutSeconds} / {@code overdueSeconds} 来自<b>节点上配的超时升级规则</b>：
   * 没配规则时两者都是 {@code null}（**判不了超时**，不是「没超时」）。
   *
   * <p>{@code amountThresholdName} 等字段来自<b>流程上配的金额区间分支</b>：
   * 没配分支、或金额没落在任何一档里时为 {@code null}。
   */
  public record ApprovalTodoItem(
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
      @Schema(nullable = true) Long timeoutSeconds,
      @Schema(nullable = true) Long overdueSeconds,
      @Schema(nullable = true) String amountThresholdName,
      @Schema(nullable = true) BigDecimal amountThresholdMin,
      @Schema(nullable = true) BigDecimal amountThresholdMax,
      @Schema(nullable = true) String amountThresholdCurrency,
      String status) {}

  /**
   * 概览。
   *
   * <p>{@code pendingTotal} 是**当前用户未过滤**的待办总数；{@code filteredTotal} 是当前筛选命中的条数。
   * 页面用它显示「筛选后 3 / 共 17」。
   *
   * <p>{@code unconfiguredTimeoutTotal} 是**判不了超时**的条数：它们的节点没有配超时升级规则。
   * 这批单子既不能算「已超时」也不能算「没超时」，页面必须把它们单列出来，而不是悄悄按「没超时」处理。
   */
  public record ApprovalTodoSummary(
      long pendingTotal,
      long filteredTotal,
      long longestDwellSeconds,
      long overdueTotal,
      long unconfiguredTimeoutTotal) {}

  /** 一个维度的分档。计数是<b>整集合</b>的计数，不是当前页的计数。 */
  public record ApprovalTodoBucket(String code, String name, long count) {}

  /**
   * 一个筛选维度的能力声明。
   *
   * <p>{@code available=false} 时 {@code reason} 必填：写明**缺什么**，页面照着显示成
   * 「阈值未配置」这类带缺口的说明，而不是让这一档静默消失。
   */
  public record ApprovalTodoFilterCapability(
      String key,
      String label,
      boolean available,
      @Schema(nullable = true) String reason,
      String source) {}

  /**
   * 一个动作的能力声明。
   *
   * <p>审批动作（通过 / 驳回 / 转交）在这里是 {@code available=false}：它们归 17.4.2.1，
   * 工作台只跳转到 17 的审批详情页。
   */
  public record ApprovalTodoAction(
      String code,
      String label,
      boolean available,
      String ownerModule,
      @Schema(nullable = true) String reason,
      @Schema(nullable = true) String target) {}

  /** 本模块的能力声明。 */
  public record ApprovalTodoCapabilities(
      String module,
      String ownerModule,
      String dataSource,
      List<ApprovalTodoFilterCapability> filters,
      List<ApprovalTodoAction> actions) {}

  /**
   * 分页信封：{@code total} 来自真实 count，不是前端猜的。
   *
   * <p>{@code amountBranches} 是**金额阈值分支**这一维的分档（整集合计数），
   * 与 {@code documentTypes} / {@code businessDomains} / {@code currencies} 同一套口径：
   * 每一维都排除自己那一维的筛选，点一下就按这一维筛。
   */
  public record ApprovalTodoPage(
      List<ApprovalTodoItem> items,
      long total,
      int page,
      int size,
      ApprovalTodoSummary summary,
      List<ApprovalTodoBucket> documentTypes,
      List<ApprovalTodoBucket> businessDomains,
      List<ApprovalTodoBucket> currencies,
      List<ApprovalTodoBucket> amountBranches,
      ApprovalTodoCapabilities capabilities) {}

  // ------------------------------------------------------------------
  // SQL
  // ------------------------------------------------------------------

  /**
   * 「这条待办归我」判定。
   *
   * <p>逐字对齐 {@link ApprovalInboxController} 的同名常量：被转交指派的人优先；
   * 否则按当前节点的审批人类型判定（USER 指定人 / ROLE 角色分配）；
   * POSITION 节点解析不出具体的人，这里也不假装能解析。
   *
   * <p>三个 {@code ?} 绑定的是同一个 membership id，顺序固定：指派目标 → 指定人节点 → 角色分配。
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

  /**
   * 清单 / 计数 / 分档共用的 FROM。
   *
   * <p>两个 lateral join 各自回答一个<b>真实配置</b>：
   *
   * <ul>
   *   <li>{@code esc} —— 节点上配的最小时限（{@code approval_flow_escalation.timeout_hours}）。
   *       同一节点可能挂多条不同时限的升级规则，<b>最先触发的那条</b>才是「多久算超时」，
   *       所以取 {@code min(timeout_hours)}。没配规则 → {@code esc.timeout_hours} 为 null。</li>
   *   <li>{@code br} —— 流程上配的金额区间分支（{@code approval_flow_amount_branch}）。
   *       按金额比出落在哪一档；金额为空（变更类单据）或不落在任何一档 → null。
   *       <b>区间语义与上游的路由判定逐字同式</b>：上界是<b>开区间</b>（{@code amount < max_amount}），
   *       取档按 {@code priority desc} —— 与 {@code ApprovalInboxController.startNode} 一致。
   *       两边算法若不同，工作台报出来的档位就会和这张单实际走的路径对不上。</li>
   * </ul>
   */
  private static final String BASE_FROM =
      "\n"
          + """
          from approval_instance i
          join approval_flow f on f.tenant_id = i.tenant_id and f.id = i.flow_id
          join approval_document_type dt on dt.code = f.document_type_code
          join approval_flow_node n on n.tenant_id = i.tenant_id and n.flow_id = i.flow_id
                                  and n.node_key = i.current_node_key
          left join lateral (
            select e.timeout_hours
              from approval_flow_escalation e
             where e.tenant_id = i.tenant_id and e.flow_id = i.flow_id
               and e.node_key = i.current_node_key
             order by e.timeout_hours asc
             limit 1
          ) esc on true
          left join lateral (
            select b.branch_name, b.min_amount, b.max_amount, b.currency_code
              from approval_flow_amount_branch b
             where b.tenant_id = i.tenant_id and b.flow_id = i.flow_id
               and i.amount is not null
               and b.currency_code = i.currency_code::text
               and (b.min_amount is null or i.amount >= b.min_amount)
               and (b.max_amount is null or i.amount < b.max_amount)
             order by b.priority desc, b.id asc
             limit 1
          ) br on true""";

  /** 清单取数列。 */
  private static final String SELECT_COLUMNS =
      """
      select i.id, i.instance_no, i.subject_title, i.amount,
             i.currency_code::text as currency_code,
             i.applicant_membership_id, i.assigned_membership_id,
             i.source_system, i.source_reference_id,
             i.submitted_at, i.node_entered_at, i.status,
             f.document_type_code, dt.name as document_type_name, f.flow_name,
             n.node_key as current_node_key, n.node_name as current_node_name,
             n.node_order as current_node_order, n.stage_index as current_stage_index,
             n.approver_type,
             approval_dwell_seconds(now(), i.node_entered_at) as dwell_seconds,
             approval_dwell_seconds(now(), i.submitted_at) as total_seconds,
             case when esc.timeout_hours is null then null
                  else esc.timeout_hours::bigint * 3600 end as timeout_seconds,
             case when esc.timeout_hours is null then null
                  else greatest(0, approval_dwell_seconds(now(), i.node_entered_at)
                                  - esc.timeout_hours::bigint * 3600) end as overdue_seconds,
             br.branch_name as amount_threshold_name,
             br.min_amount as amount_threshold_min,
             br.max_amount as amount_threshold_max,
             br.currency_code::text as amount_threshold_currency
      """
          + BASE_FROM;

  /** 「已超时」判定：节点配了时限，且停留时长已经超过它。 */
  private static final String OVERDUE =
      "esc.timeout_hours is not null"
          + " and approval_dwell_seconds(now(), i.node_entered_at) > esc.timeout_hours::bigint * 3600";

  // ------------------------------------------------------------------
  // 调用骨架
  // ------------------------------------------------------------------

  /**
   * 与上游 {@code ApprovalInboxController.call} 同口径：
   * 解析会话 → 切到该会话的租户事务 → 锁会话 → 二次校验租户未切换 → 校验权限码。
   *
   * <p>租户来自会话成员关系，{@code X-Tenant-Id} 头在这里从头到尾没有出现过。
   */
  private <T> T call(String auth, Function<IdentityController.Access, T> work) {
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
                  .anyMatch(
                      g ->
                          g.scopeMode().equals("ALL")
                              && g.permissions().contains(INBOX_READ)),
              "当前角色没有此操作权限");
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
   * <p>刻意走 {@code member_names(hash)}（SECURITY DEFINER，以会话成员关系为条件）而不是直连 {@code app_user}。
   * 没有 {@code identity.member.read} 时解析不出用户名是**正常**的，所以申请人为空绝不代表这个人不存在。
   */
  private Map<UUID, String> memberNames(String auth) {
    var names = new LinkedHashMap<UUID, String>();
    for (Record r : sql.fetch("select id,username from member_names(?)", hash(auth)))
      names.put(r.get("id", UUID.class), r.get("username", String.class));
    return names;
  }

  // ------------------------------------------------------------------
  // 端点
  // ------------------------------------------------------------------

  /**
   * 工作台审批待办清单（真分页 + 真筛选 + 真排序 + 整集合分档 + 能力声明）。
   *
   * <p><b>筛选全在 DB 层</b>：
   *
   * <ul>
   *   <li>{@code minDwellSeconds} 走 {@code node_entered_at <= now() - N * interval '1 second'}，
   *       命中 {@code approval_instance_pending} 部分索引；</li>
   *   <li>{@code overdueOnly} 用节点上配的时限判定（{@code approval_flow_escalation.timeout_hours}），
   *       没有硬编码的「多久算超时」；</li>
   *   <li>{@code minAmount} / {@code maxAmount} 打在 {@code approval_instance.amount}（numeric）上。</li>
   *   <li>{@code amountBranchName} 打在 {@code br.branch_name} 上 —— {@code br} 是清单行上那一档
   *       （金额阈值分支），所以按档位筛选与行上显示的档位**是同一个口径**，
   *       不会出现「筛出来的那一档和显示的档位对不上」。</li>
   * </ul>
   *
   * <p><b>分档是整集合的计数</b>：单据类型 / 业务域 / 币种 / 金额阈值分支四档各自 GROUP BY 整集合，
   * 并且**各自排除自己那一维的筛选**（点一下「按这一类筛」之后，这一档的计数仍然是它自己的真实条数，
   * 其余档的计数也不会因为多筛了一维就消失）。
   */
  @GetMapping("/approval-todos")
  public ApprovalTodoPage todos(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String documentTypeCode,
      @RequestParam(required = false) String sourceSystem,
      @RequestParam(required = false) String currencyCode,
      @RequestParam(required = false) String amountBranchName,
      @RequestParam(required = false) BigDecimal minAmount,
      @RequestParam(required = false) BigDecimal maxAmount,
      @RequestParam(required = false) Long minDwellSeconds,
      @RequestParam(required = false, defaultValue = "false") boolean overdueOnly,
      @RequestParam(defaultValue = "WAITING") String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        a -> {
          check(page >= 0, "页码不能为负");
          check(size >= 1 && size <= MAX_PAGE_SIZE, "每页条数需在 1 到 " + MAX_PAGE_SIZE + " 之间");
          check(minAmount == null || minAmount.signum() >= 0, "金额下限不能为负");
          check(
              minAmount == null || maxAmount == null || minAmount.compareTo(maxAmount) <= 0,
              "金额下限不能大于上限");
          check(minDwellSeconds == null || minDwellSeconds >= 0, "停留时长下限不能为负");

          var me = a.membershipId();
          var filter = new Condition();
          if (documentTypeCode != null && !documentTypeCode.isBlank())
            filter.add("DOCUMENT_TYPE", "f.document_type_code = ?", documentTypeCode.trim());
          if (sourceSystem != null && !sourceSystem.isBlank())
            filter.add("BUSINESS_DOMAIN", "i.source_system = ?", sourceSystem.trim());
          if (currencyCode != null && !currencyCode.isBlank())
            filter.add(
                "CURRENCY", "i.currency_code::text = ?", currencyCode.trim().toUpperCase());
          if (amountBranchName != null && !amountBranchName.isBlank())
            filter.add("AMOUNT_BRANCH", "br.branch_name = ?", amountBranchName.trim());
          if (minAmount != null) filter.add("MIN_AMOUNT", "i.amount >= ?::numeric", minAmount);
          if (maxAmount != null) filter.add("MAX_AMOUNT", "i.amount <= ?::numeric", maxAmount);
          if (minDwellSeconds != null)
            filter.add(
                "MIN_DWELL",
                "i.node_entered_at <= now() - (?::bigint * interval '1 second')",
                minDwellSeconds);
          if (overdueOnly) filter.addClause("OVERDUE_ONLY", OVERDUE);

          var where = new StringBuilder(" where i.status = 'PENDING'");
          filter.appendMine(where, MINE);

          long total =
              sql.fetchOne("select count(*)" + BASE_FROM + where, filter.bindings(me, me, me))
                  .get(0, Long.class);

          var params = new ArrayList<Object>(Arrays.asList(filter.bindings(me, me, me)));
          params.add(size);
          params.add((long) page * size);
          var records =
              sql.fetch(
                  SELECT_COLUMNS + where + orderBy(sort) + " limit ? offset ?", params.toArray());
          var names = memberNames(auth);
          var unfiltered = summary(me);

          return new ApprovalTodoPage(
              records.map(r -> item(r, names)),
              total,
              page,
              size,
              new ApprovalTodoSummary(
                  unfiltered.pendingTotal(),
                  total,
                  unfiltered.longestDwellSeconds(),
                  unfiltered.overdueTotal(),
                  unfiltered.unconfiguredTimeoutTotal()),
              documentTypeBuckets(me, filter.without("DOCUMENT_TYPE")),
              businessDomainBuckets(me, filter.without("BUSINESS_DOMAIN")),
              currencyBuckets(me, filter.without("CURRENCY")),
              amountBranchBuckets(me, filter.without("AMOUNT_BRANCH")),
              capabilities());
        });
  }

  private ApprovalTodoItem item(Record row, Map<UUID, String> names) {
    var applicant = row.get("applicant_membership_id", UUID.class);
    return new ApprovalTodoItem(
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
        row.get("current_node_order", Integer.class),
        row.get("current_stage_index", Integer.class),
        row.get("approver_type", String.class),
        row.get("source_system", String.class),
        row.get("source_reference_id", String.class),
        row.get("submitted_at", Instant.class),
        row.get("node_entered_at", Instant.class),
        row.get("dwell_seconds", Long.class),
        row.get("total_seconds", Long.class),
        row.get("timeout_seconds", Long.class),
        row.get("overdue_seconds", Long.class),
        row.get("amount_threshold_name", String.class),
        row.get("amount_threshold_min", BigDecimal.class),
        row.get("amount_threshold_max", BigDecimal.class),
        row.get("amount_threshold_currency", String.class),
        row.get("status", String.class));
  }

  /** 概览：未过滤的总数、最久停留、已超时条数、判不了超时的条数。 */
  private ApprovalTodoSummary summary(UUID me) {
    var row =
        sql.fetchOne(
            "select count(*) as pending,"
                + " coalesce(max(approval_dwell_seconds(now(), i.node_entered_at)),0) as longest,"
                + " count(*) filter (where " + OVERDUE + ") as overdue,"
                + " count(*) filter (where esc.timeout_hours is null) as unconfigured"
                + " from approval_instance i"
                + " join approval_flow_node n on n.tenant_id = i.tenant_id and n.flow_id = i.flow_id"
                + "   and n.node_key = i.current_node_key"
                + " left join lateral ("
                + "   select e.timeout_hours from approval_flow_escalation e"
                + "    where e.tenant_id = i.tenant_id and e.flow_id = i.flow_id"
                + "      and e.node_key = i.current_node_key"
                + "    order by e.timeout_hours asc limit 1) esc on true"
                + " where i.status = 'PENDING' and "
                + MINE,
            me,
            me,
            me);
    var pending = row.get("pending", Long.class);
    return new ApprovalTodoSummary(
        pending,
        pending,
        row.get("longest", Long.class),
        row.get("overdue", Long.class),
        row.get("unconfigured", Long.class));
  }

  /** 单据类型分档（整集合计数；排除自身这一维的筛选）。 */
  private List<ApprovalTodoBucket> documentTypeBuckets(UUID me, Condition filter) {
    var where = new StringBuilder(" where i.status = 'PENDING'");
    filter.appendMine(where, MINE);
    return sql.fetch(
            "select f.document_type_code as code, dt.name as name, count(*) as count"
                + BASE_FROM
                + where
                + " group by f.document_type_code, dt.name"
                + " order by count desc, f.document_type_code",
            filter.bindings(me, me, me))
        .map(
            r ->
                new ApprovalTodoBucket(
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("count", Long.class)));
  }

  /** 业务域分档（{@code approval_instance.source_system}）。 */
  private List<ApprovalTodoBucket> businessDomainBuckets(UUID me, Condition filter) {
    var where = new StringBuilder(" where i.status = 'PENDING'");
    filter.appendMine(where, MINE);
    return sql.fetch(
            "select i.source_system as code, i.source_system as name, count(*) as count"
                + BASE_FROM
                + where
                + " group by i.source_system"
                + " order by count desc, i.source_system",
            filter.bindings(me, me, me))
        .map(
            r ->
                new ApprovalTodoBucket(
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("count", Long.class)));
  }

  /** 币种分档（金额区间筛选要按币种分开看）。 */
  private List<ApprovalTodoBucket> currencyBuckets(UUID me, Condition filter) {
    var where = new StringBuilder(" where i.status = 'PENDING'");
    filter.appendMine(where, MINE);
    return sql.fetch(
            "select i.currency_code::text as code, i.currency_code::text as name,"
                + " count(*) as count"
                + BASE_FROM
                + where
                + " group by i.currency_code::text"
                + " order by count desc, i.currency_code::text",
            filter.bindings(me, me, me))
        .map(
            r ->
                new ApprovalTodoBucket(
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("count", Long.class)));
  }

  /**
   * 金额阈值分支分档（{@code approval_flow_amount_branch.branch_name}）。
   *
   * <p>⚠️ **只收落在某一档里的单据**：没有金额的单据（变更类）{@code br} 为 null，
   * 它们不在任何一档里 —— 把 null 收成一档「未分档」会让人以为那是一个可点的筛选，
   * 而筛选参数表达不了「不属于任何一档」。
   *
   * <p>口径与 {@code br} 那条 lateral 完全一致：同一张单落在哪一档，
   * 清单行上显示的 {@code amountThresholdName} 与这里的计数必须是同一个档。
   */
  private List<ApprovalTodoBucket> amountBranchBuckets(UUID me, Condition filter) {
    var where = new StringBuilder(" where i.status = 'PENDING'");
    filter.appendMine(where, MINE);
    where.append(" and br.branch_name is not null");
    return sql.fetch(
            "select br.branch_name as code, br.branch_name as name, count(*) as count"
                + BASE_FROM
                + where
                + " group by br.branch_name"
                + " order by count desc, br.branch_name",
            filter.bindings(me, me, me))
        .map(
            r ->
                new ApprovalTodoBucket(
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("count", Long.class)));
  }

  /**
   * 能力声明。
   *
   * <p>逐条如实报出：哪些筛选维度真的接通了、哪一档<b>判不了</b>（法人级金额阈值）、
   * 以及审批动作<b>不</b>在这里执行。
   */
  private ApprovalTodoCapabilities capabilities() {
    var amountBranchConfigured =
        sql.fetchOne("select count(*) from approval_flow_amount_branch").get(0, Long.class) > 0;
    var escalationConfigured =
        sql.fetchOne("select count(*) from approval_flow_escalation").get(0, Long.class) > 0;
    return new ApprovalTodoCapabilities(
        "1.1.1.3",
        "17.4.2.1 待我审批（approval_instance 的唯一写入所有者）",
        "approval_instance（只读投影）",
        List.of(
            new ApprovalTodoFilterCapability(
                "DOCUMENT_TYPE",
                "按单据类型筛选",
                true,
                null,
                "approval_document_type + approval_flow.document_type_code"),
            new ApprovalTodoFilterCapability(
                "BUSINESS_DOMAIN",
                "按业务域筛选",
                true,
                null,
                "approval_instance.source_system"),
            new ApprovalTodoFilterCapability(
                "AMOUNT_RANGE",
                "按金额区间筛选",
                true,
                null,
                "approval_instance.amount（用户自定上下限，DB 侧过滤）"),
            new ApprovalTodoFilterCapability(
                "AMOUNT_BRANCH",
                "按金额阈值分支筛选（流程配置）",
                amountBranchConfigured,
                amountBranchConfigured
                    ? null
                    : "本租户尚未配置金额阈值分支（approval_flow_amount_branch 为空）",
                "approval_flow_amount_branch.branch_name（分档口径与清单行的档位一致）"),
            new ApprovalTodoFilterCapability(
                "OVERDUE_DURATION",
                "按超时时长筛选",
                escalationConfigured,
                escalationConfigured
                    ? null
                    : "本租户尚未配置超时升级规则（approval_flow_escalation 为空）⇒ 判不了超时",
                "approval_flow_escalation.timeout_hours + approval_instance.node_entered_at"),
            new ApprovalTodoFilterCapability(
                "LEGAL_ENTITY_AMOUNT_THRESHOLD",
                "法人级金额阈值",
                false,
                "阈值未配置：legal_entity 没有金额阈值列，approval_instance 也不带 legal_entity_id ⇒ 法人级阈值在当前 schema 下判不了",
                "legal_entity（只读）")),
        List.of(
            new ApprovalTodoAction(
                "OPEN_APPROVAL_DETAIL",
                "跳转到 17 的审批详情页",
                true,
                "17.4.2.1",
                null,
                "/org/my-approvals"),
            new ApprovalTodoAction(
                "COPY_INSTANCE_NO",
                "复制审批单号",
                true,
                "1.1.1.3",
                null,
                null),
            new ApprovalTodoAction(
                "APPROVE",
                "通过",
                false,
                "17.4.2.1",
                "审批动作在 17 执行，工作台不重复实现（避免两处审批逻辑不一致）",
                null),
            new ApprovalTodoAction(
                "REJECT",
                "驳回",
                false,
                "17.4.2.1",
                "审批动作在 17 执行，工作台不重复实现（避免两处审批逻辑不一致）",
                null),
            new ApprovalTodoAction(
                "TRANSFER",
                "转交",
                false,
                "17.4.2.1",
                "审批动作在 17 执行，工作台不重复实现（避免两处审批逻辑不一致）",
                null)));
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
      case "OVERDUE" ->
          " order by (case when esc.timeout_hours is null then 1 else 0 end) asc,"
              + " (case when esc.timeout_hours is null then null"
              + "   else approval_dwell_seconds(now(), i.node_entered_at)"
              + "        - esc.timeout_hours::bigint * 3600 end) desc nulls last,"
              + " i.node_entered_at asc, i.id asc";
      default -> throw IdentityController.error(
          HttpStatus.BAD_REQUEST,
          "排序键需为 WAITING / NEWEST / OLDEST / AMOUNT_DESC / AMOUNT_ASC / DOCUMENT / OVERDUE");
    };
  }

  /**
   * WHERE 子句与绑定值成对累积，避免条件与参数顺序错位。
   *
   * <p>每一条条件都带一个**键**：键是这一维的身份（不是 SQL 片段），
   * 分档计数用它把「自己这一维」的筛选摘掉 —— 按 SQL 文本去重是脆的，
   * 片段改个空格就再也摘不掉了。
   */
  private static final class Condition {
    private record Entry(String key, String clause, Object binding) {}

    private final List<Entry> entries = new ArrayList<>();

    /** 带一个绑定值的条件。 */
    void add(String key, String clause, Object value) {
      entries.add(new Entry(key, clause, value));
    }

    /**
     * 追加一条**不带绑定值**的条件。
     *
     * <p>条件与绑定值必须成对：多塞一个 null 会让后面的 {@code ?} 全部错位。
     * 「只看已超时」这类判定里没有占位符，就走这一条路。
     */
    void addClause(String key, String clause) {
      entries.add(new Entry(key, clause, null));
    }

    /**
     * 复制一份并去掉某一维的筛选。
     *
     * <p>分档计数用它：某一维的分档要回答「这一维上各有多少条」，
     * 如果把**这一维自己**的筛选也算进去，点了某一档之后其它档就全变成 0，
     * 页面就没法再切回去了。
     */
    Condition without(String key) {
      var copy = new Condition();
      for (var entry : entries) {
        if (key.equals(entry.key())) continue;
        copy.entries.add(entry);
      }
      return copy;
    }

    /** 追加条件后再追加「归我」判定；绑定值顺序与之严格对应。 */
    void appendMine(StringBuilder where, String mine) {
      for (var entry : entries) where.append(" and ").append(entry.clause());
      where.append(" and (").append(mine).append(")");
    }

    Object[] bindings(UUID me, UUID me2, UUID me3) {
      var all = new ArrayList<>();
      for (var entry : entries) {
        if (entry.binding() != null) all.add(entry.binding());
      }
      all.add(me);
      all.add(me2);
      all.add(me3);
      return all.toArray();
    }
  }
}
