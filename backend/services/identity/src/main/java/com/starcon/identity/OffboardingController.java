package com.starcon.identity;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 17.2.2.3 离职与停用。
 *
 * <p>核心约束是**停用而非删除**（蓝图「关系」栏：离职员工的操作记录是审计追溯的依据）。 本类**不提供任何物理删除端点**：成员走 {@code membership.status}
 * 状态流转，离职本身落成一条 {@code offboarding_case}，它记着原因、生效时刻、操作人与被停用的成员，可逆可查。 删除防线不止一处：控制器无 DELETE 端点、迁移不授予
 * {@code identity_app} DELETE、 指向 membership 的外键全部 {@code ON DELETE RESTRICT}，membership
 * 上还有删除保护触发器。
 *
 * <p><b>服务边界</b>：审批（17.4.2.2）与工作台待办（01）尚未交付，AGENTS.md 禁止跨服务读表， 所以这里<b>不查询</b>它们的表，只维护两张台账 —— {@code
 * offboarding_responsibility_item} 记录「谁转给谁」，{@code offboarding_reclaim_item} 记录「哪些条目被标记待回收 / 已回收」。
 * 下游经授权 API 登记条目并消费状态；下游缺席时条目如实停在 {@code PENDING}， 不会被伪装成「已全部处理」。案卷状态也据此推导：有未结条目就是 {@code OPEN}。
 *
 * <p>独立成 {@code OffboardingController} 而不是并进 {@code AdministrationController}： 17.2.2.1 成员管理与本模块是两个
 * L4 单元，同一文件各自生长会让两侧改动在合并时互相打架。
 */
@RestController
@RequestMapping("/identity")
public class OffboardingController {
  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;
  private final AuditLog audit;

  public OffboardingController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.audit = new AuditLog(sql, identity);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  private static final String OFFBOARDING_READ = "identity.offboarding.read";
  private static final String OFFBOARDING_CREATE = "identity.offboarding.create";
  private static final String OFFBOARDING_UPDATE = "identity.offboarding.update";

  /**
   * 离职案卷。
   *
   * <p>四个待结计数是本模块「四块职责各有一个真实读出口」的落点：停用看 {@code memberName/status}， 责任转移看 {@code
   * responsibilityTotal/Pending}，待办回收看 {@code reclaimTotal/Pending}， 历史留痕看 {@code
   * operatorName/effectiveAt/revertedAt}。三个计数只报数字不够， 所以 {@code total} 与 {@code pending} 同时给出 —— 「共
   * 8 项、已结 8 项」才是「处理完了」。
   */
  public record OffboardingCaseView(
      UUID id,
      UUID membershipId,
      String memberName,
      UUID operatorMembershipId,
      String operatorName,
      String reason,
      OffsetDateTime effectiveAt,
      String status,
      String previousStatus,
      String note,
      int responsibilityTotal,
      int responsibilityPending,
      int reclaimTotal,
      int reclaimPending,
      OffsetDateTime createdAt,
      OffsetDateTime revertedAt,
      @Schema(nullable = true) UUID revertedByMembershipId) {}

  /** 在办单据 / 审批的责任转移条目。{@code successorName} 为空表示「还没定接手人」。 */
  public record OffboardingResponsibilityView(
      UUID id,
      UUID caseId,
      String itemKind,
      String sourceSystem,
      UUID referenceId,
      String referenceNo,
      String title,
      UUID ownerMembershipId,
      @Schema(nullable = true) UUID successorMembershipId,
      @Schema(nullable = true) String successorName,
      String status,
      OffsetDateTime registeredAt,
      OffsetDateTime transferredAt) {}

  /** 待办 / 任务的回收条目。{@code status} 为 PENDING 时还没有任何下游动作发生。 */
  public record OffboardingReclaimView(
      UUID id,
      UUID caseId,
      String itemKind,
      String sourceSystem,
      UUID referenceId,
      String title,
      UUID holderMembershipId,
      @Schema(nullable = true) UUID successorMembershipId,
      @Schema(nullable = true) String successorName,
      @Schema(nullable = true) UUID reclaimedByMembershipId,
      String status,
      OffsetDateTime registeredAt,
      OffsetDateTime reclaimedAt) {}

  public record OffboardingDetailView(
      OffboardingCaseView offboardingCase,
      List<OffboardingResponsibilityView> responsibilityItems,
      List<OffboardingReclaimView> reclaimItems) {}

  public record OffboardingInput(
      @NotNull UUID membershipId,
      @NotBlank @Size(max = 500) String reason,
      @NotNull OffsetDateTime effectiveAt,
      /** 该成员是部门负责人时必须指定接手人，否则拒绝。 */
      @Schema(nullable = true) UUID departmentLeaderSuccessorMembershipId,
      @Schema(nullable = true) @Size(max = 500) String note,
      List<@Valid @NotNull OffboardingResponsibilityInput> responsibilityItems,
      List<@Valid @NotNull OffboardingReclaimInput> reclaimItems) {}

  /**
   * 责任转移条目登记。
   *
   * <p>{@code sourceSystem + referenceId} 是跨服务定位键，刻意不建外键指向 trade / workflow： 那是别的服务的表。identity
   * 只登记「他们说名下还有这些活」，不去库里核对。
   */
  public record OffboardingResponsibilityInput(
      @NotNull @Pattern(regexp = "DOCUMENT|APPROVAL", message = "条目类型只能是单据或审批") String itemKind,
      @NotBlank @Pattern(regexp = "[a-z_]{2,32}", message = "来源系统用小写下划线标识") String sourceSystem,
      @NotNull UUID referenceId,
      @Schema(nullable = true) @Size(max = 64) String referenceNo,
      @NotBlank @Size(max = 200) String title,
      /** 已在登记时就知道接手人的，可直接结清这条。 */
      @Schema(nullable = true) UUID successorMembershipId) {}

  public record OffboardingReclaimInput(
      @NotNull @Pattern(regexp = "TODO|TASK", message = "回收条目只能是待办或任务") String itemKind,
      @NotBlank @Pattern(regexp = "[a-z_]{2,32}", message = "来源系统用小写下划线标识") String sourceSystem,
      @NotNull UUID referenceId,
      @NotBlank @Size(max = 200) String title) {}

  /** 执行责任转移。 */
  public record OffboardingTransferInput(@NotNull UUID successorMembershipId) {}

  /**
   * 回收待办 / 任务。
   *
   * <p>{@code successorMembershipId} 为空 = 直接回收（关闭），非空 = 改派给接手人。 两种结论都要在台账里留痕，所以「不填」是有含义的操作而不是漏填。
   */
  public record OffboardingReclaimActionInput(
      @Schema(nullable = true) UUID successorMembershipId) {}

  // ------------------------------------------------------------------
  // 读出口
  // ------------------------------------------------------------------

  /**
   * 本租户全部离职案卷，按创建时刻倒序。
   *
   * <p>用户名的解析刻意走 {@code member_names(hash)}（会话成员关系为条件），不用 app_user 直连： app_user 对 identity_app 没有
   * GRANT，且成员名必须以「我所在租户」为条件解析， 伪造 {@code X-Tenant-Id} 拿不到别人的用户名。
   */
  @GetMapping("/offboarding")
  public List<OffboardingCaseView> cases(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        OFFBOARDING_READ,
        false,
        a ->
            sql
                .fetch(caseSelect() + " order by c.created_at desc, c.id", hash(auth), hash(auth))
                .stream()
                .map(OffboardingController::toCase)
                .toList());
  }

  /** 案卷详情：案卷 + 两张台账的明细。逐条而不是只给计数，否则「8 项」无法核对。 */
  @GetMapping("/offboarding/{id}")
  public OffboardingDetailView detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        OFFBOARDING_READ,
        false,
        a -> {
          var row = sql.fetchOne(caseSelect() + " where c.id=?", hash(auth), hash(auth), id);
          if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "离职记录不存在");
          return new OffboardingDetailView(
              toCase(row),
              responsibility(a.tenantId(), id, hash(auth)),
              reclaims(a.tenantId(), id, hash(auth)));
        });
  }

  // ------------------------------------------------------------------
  // 停用
  // ------------------------------------------------------------------

  /**
   * 发起离职：停用成员（非删除）+ 建案 + 登记责任转移与待回收条目。
   *
   * <p>一次事务里做完四件事：写案卷、撤销角色分配、把 membership 置为 INACTIVE、
   * 移交部门负责人。顺序上先建案再停用成员，这样即使停用失败也不会留下「有人被停用却查不到为什么」。
   */
  @PostMapping("/offboarding")
  public UUID create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody OffboardingInput input) {
    return call(
        auth,
        OFFBOARDING_CREATE,
        true,
        a -> {
          var departing = member(a, input.membershipId());
          require(!a.membershipId().equals(input.membershipId()), "不能停用自己的账号，请指定其他成员");
          // 同一人已有在办离职时先报冲突：否则用户会收到一句「已是停用状态」，
          // 而真正的原因是上一条离职还没处理完（两条在办会让责任台账出现两个来源）。
          if (sql.fetchExists(
              sql.selectOne()
                  .from("offboarding_case")
                  .where(
                      "tenant_id=? and membership_id=? and status='OPEN'",
                      a.tenantId(),
                      input.membershipId())))
            throw IdentityController.error(HttpStatus.CONFLICT, "该成员已有一条在办的离职记录，请先处理或撤销");
          check(departing.get("status", String.class).equals("ACTIVE"), "该成员已是停用状态");
          require(!ownerOf(departing), "不能停用租户所有者，请先使用转移所有者操作");

          // 部门负责人必须有人接手，否则部门会指向一个已经离职的人。宁可拒绝也不要留下悬空引用。
          var led =
              sql.fetch(
                  "select id,code,name from department where tenant_id=? and leader_membership_id=?",
                  a.tenantId(),
                  input.membershipId());
          if (!led.isEmpty()) {
            require(
                input.departmentLeaderSuccessorMembershipId() != null,
                "该成员负责 " + led.size() + " 个部门，请先指定部门负责人接手人");
            var successor = member(a, input.departmentLeaderSuccessorMembershipId());
            check(successor.get("status", String.class).equals("ACTIVE"), "部门负责人接手人必须处于启用状态");
          }

          var before = memberSnapshot(a, input.membershipId(), hash(auth));
          UUID caseId = Ids.next();
          insertCase(
              a.tenantId(),
              caseId,
              input.membershipId(),
              a.membershipId(),
              input.reason().trim(),
              input.effectiveAt(),
              before.get("status").toString(),
              input.note() == null ? "" : input.note().trim());

          // 角色分配先撤：停用与撤权必须同时发生，否则会出现「已离职但仍持有管理角色」的窗口。
          sql.execute(
              "delete from role_assignment where tenant_id=? and membership_id=?",
              a.tenantId(),
              input.membershipId());
          sql.execute(
              "update membership set status='INACTIVE',department_id=null where tenant_id=? and id=?",
              a.tenantId(),
              input.membershipId());
          if (!led.isEmpty())
            sql.execute(
                "update department set leader_membership_id=? where tenant_id=? and leader_membership_id=?",
                input.departmentLeaderSuccessorMembershipId(),
                a.tenantId(),
                input.membershipId());

          for (var item : nullSafe(input.responsibilityItems()))
            registerResponsibility(a.tenantId(), caseId, input.membershipId(), item);
          for (var item : nullSafe(input.reclaimItems()))
            registerReclaim(a.tenantId(), caseId, input.membershipId(), item);

          settle(a.tenantId(), caseId);
          audit(
              a,
              auth,
              "identity.offboarding",
              caseId,
              String.valueOf(before.get("username")),
              "MemberOffboarded",
              before,
              caseSnapshot(caseId, hash(auth)));
          return caseId;
        });
  }

  /**
   * 撤销离职（可逆）：成员恢复启用，案卷标 REVERTED。
   *
   * <p>恢复的是案卷里记下的 {@code previousStatus}，不是硬编码成 ACTIVE —— 停用动作本身要如实留痕。
   * 已结清的责任转移与回收条目**不回滚**：它们已经发生在下游服务里，identity 无权假装没发生， 所以案卷保留这些行作为「当时转过什么」的证据。
   */
  @PostMapping("/offboarding/{id}/revert")
  public void revert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        OFFBOARDING_UPDATE,
        true,
        a -> {
          var row =
              sql.fetchOne(
                  "select * from offboarding_case where tenant_id=? and id=?", a.tenantId(), id);
          if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "离职记录不存在");
          // OPEN（还有未结条目）与 COMPLETED（台账已结清）都可以撤销：
          // 「条目都转完了才发现人是误停」是真实场景，不该因为状态已结算就锁死。
          // 只有 REVERTED 是终态。
          check(!row.get("status", String.class).equals("REVERTED"), "该离职记录已经撤销过了");
          // 撤销会把成员恢复成停用前的角色状态。若他还挂着 OWNER，恢复的瞬间就凭空多出
          // 一个所有者——所以先要求走「转移所有者」这条既有通道（见 AdministrationController）。
          if (isOwnerRole(row))
            throw IdentityController.error(HttpStatus.CONFLICT, "该成员持有所有者角色，请先使用转移所有者操作");

          var before = caseSnapshot(id, hash(auth));
          sql.execute(
              "update offboarding_case set status='REVERTED',reverted_at=?::timestamptz,"
                  + " reverted_by_membership_id=? where tenant_id=? and id=?",
              utcNow(),
              a.membershipId(),
              a.tenantId(),
              id);
          sql.execute(
              "update membership set status=? where tenant_id=? and id=?",
              row.get("previous_status", String.class),
              a.tenantId(),
              row.get("membership_id", UUID.class));
          audit(
              a,
              auth,
              "identity.offboarding",
              id,
              null,
              "OffboardingReverted",
              before,
              caseSnapshot(id, hash(auth)));
          return null;
        });
  }

  // ------------------------------------------------------------------
  // 责任转移台账
  // ------------------------------------------------------------------

  /**
   * 下游经授权 API 登记一条「名下还在办」的单据或审批。
   *
   * <p>这是跨服务边界的落点：trade / workflow 调用本端点把自己那侧的条目登记进来， identity 不去读它们的表。未指定接手人的条目停在
   * PENDING，不会假装已经转移。
   */
  @PostMapping("/offboarding/{caseId}/responsibility-items")
  public UUID addResponsibilityItem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID caseId,
      @Valid @RequestBody OffboardingResponsibilityInput input) {
    return call(
        auth,
        OFFBOARDING_UPDATE,
        true,
        a -> {
          var row = openCase(a.tenantId(), caseId);
          UUID id =
              registerResponsibility(
                  a.tenantId(), caseId, row.get("membership_id", UUID.class), input);
          settle(a.tenantId(), caseId);
          audit(
              a,
              auth,
              "identity.offboarding",
              caseId,
              input.title().trim(),
              "ResponsibilityRegistered",
              null,
              caseSnapshot(caseId, hash(auth)));
          return id;
        });
  }

  /** 执行责任转移：把在办条目从原责任人转到接手人，并记下转移时刻。 */
  @PostMapping("/offboarding/{caseId}/responsibility-items/{itemId}/transfer")
  public void transferResponsibility(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID caseId,
      @PathVariable UUID itemId,
      @Valid @RequestBody OffboardingTransferInput input) {
    call(
        auth,
        OFFBOARDING_UPDATE,
        true,
        a -> {
          openCase(a.tenantId(), caseId);
          var successor = member(a, input.successorMembershipId());
          check(successor.get("status", String.class).equals("ACTIVE"), "接手人必须处于启用状态");
          var item =
              sql.fetchOne(
                  "select * from offboarding_responsibility_item where tenant_id=? and case_id=? and id=?",
                  a.tenantId(),
                  caseId,
                  itemId);
          if (item == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "责任转移条目不存在");
          check(item.get("status", String.class).equals("PENDING"), "该条目已转移过");
          require(
              !item.get("owner_membership_id", UUID.class).equals(input.successorMembershipId()),
              "请选择与原责任人不同的接手人");

          var before = itemSnapshot(item);
          // cast 不在 values 末位：jOOQ 会把末位的 ? 按整体错位一格解析。
          sql.execute(
              "update offboarding_responsibility_item set status='TRANSFERRED',successor_membership_id=?,"
                  + " transferred_at=?::timestamptz where tenant_id=? and id=?",
              input.successorMembershipId(),
              utcNow(),
              a.tenantId(),
              itemId);
          settle(a.tenantId(), caseId);
          audit(
              a,
              auth,
              "identity.offboarding",
              caseId,
              item.get("title", String.class),
              "ResponsibilityTransferred",
              before,
              itemSnapshot(
                  sql.fetchOne(
                      "select * from offboarding_responsibility_item where tenant_id=? and id=?",
                      a.tenantId(),
                      itemId)));
          return null;
        });
  }

  // ------------------------------------------------------------------
  // 待办与任务回收台账
  // ------------------------------------------------------------------

  /** 登记一条待办或任务，标记为待回收。 */
  @PostMapping("/offboarding/{caseId}/reclaim-items")
  public UUID addReclaimItem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID caseId,
      @Valid @RequestBody OffboardingReclaimInput input) {
    return call(
        auth,
        OFFBOARDING_UPDATE,
        true,
        a -> {
          var row = openCase(a.tenantId(), caseId);
          UUID id =
              registerReclaim(a.tenantId(), caseId, row.get("membership_id", UUID.class), input);
          settle(a.tenantId(), caseId);
          audit(
              a,
              auth,
              "identity.offboarding",
              caseId,
              input.title().trim(),
              "ReclaimRegistered",
              null,
              caseSnapshot(caseId, hash(auth)));
          return id;
        });
  }

  /**
   * 回收待办或任务：关闭（不填接手人）或改派（填接手人）。
   *
   * <p>TODO 与 TASK 的处置不同，所以两种结论都写进台账而不是只留一个布尔值： 事后要能答出「这条是关掉了，还是转给别人了」。
   */
  @PostMapping("/offboarding/{caseId}/reclaim-items/{itemId}/reclaim")
  public void reclaimItem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID caseId,
      @PathVariable UUID itemId,
      @Valid @RequestBody OffboardingReclaimActionInput input) {
    call(
        auth,
        OFFBOARDING_UPDATE,
        true,
        a -> {
          openCase(a.tenantId(), caseId);
          if (input.successorMembershipId() != null) {
            var successor = member(a, input.successorMembershipId());
            check(successor.get("status", String.class).equals("ACTIVE"), "接手人必须处于启用状态");
          }
          var item =
              sql.fetchOne(
                  "select * from offboarding_reclaim_item where tenant_id=? and case_id=? and id=?",
                  a.tenantId(),
                  caseId,
                  itemId);
          if (item == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "回收条目不存在");
          check(item.get("status", String.class).equals("PENDING"), "该条目已回收过");
          require(
              !item.get("holder_membership_id", UUID.class).equals(input.successorMembershipId()),
              "请选择与原持有人不同的接手人");

          var before = itemSnapshot(item);
          boolean reassign = input.successorMembershipId() != null;
          // cast 落在 reclaimed_at 上（第 3 位，共 6 位）：末位会让 jOOQ 整体错位一格，
          // 写到别的列上则会被 PG 当 uuid 拒掉。
          sql.execute(
              "update offboarding_reclaim_item set status=?,successor_membership_id=?,"
                  + " reclaimed_at=?::timestamptz,reclaimed_by_membership_id=?"
                  + " where tenant_id=? and id=?",
              reassign ? "REASSIGNED" : "RECLAIMED",
              input.successorMembershipId(),
              utcNow(),
              a.membershipId(),
              a.tenantId(),
              itemId);
          settle(a.tenantId(), caseId);
          audit(
              a,
              auth,
              "identity.offboarding",
              caseId,
              item.get("title", String.class),
              reassign ? "TaskReassigned" : "TodoReclaimed",
              before,
              itemSnapshot(
                  sql.fetchOne(
                      "select * from offboarding_reclaim_item where tenant_id=? and id=?",
                      a.tenantId(),
                      itemId)));
          return null;
        });
  }

  // ------------------------------------------------------------------
  // 内部
  // ------------------------------------------------------------------

  /**
   * 与 {@code AdministrationController.call} 同口径：解析会话 → 切到该会话的租户事务 → 锁会话 → 二次校验租户未切换 → 校验权限码 →
   * 组织变更要求 OWNER。
   *
   * <p>租户来自会话成员关系，{@code X-Tenant-Id} 头从头到尾没有出现过。
   */
  private <T> T call(
      String auth,
      String permission,
      boolean mutation,
      Function<IdentityController.Access, T> work) {
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
                      g -> g.scopeMode().equals("ALL") && g.permissions().contains(permission)),
              "当前角色没有此操作权限");
          if (mutation) require(a.owner(), "离职与停用变更需由当前租户所有者执行");
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

  private static OffsetDateTime utcNow() {
    return OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
  }

  private static <T> List<T> nullSafe(List<T> items) {
    return items == null ? List.of() : items;
  }

  /** 案卷查询：待结计数与成员名一次性带出，避免前端拿到 N+1 的不一致瞬间。 */
  private static String caseSelect() {
    return "select c.*,"
        + " n.username as member_name,o.username as operator_name,"
        + " (select count(*) from offboarding_responsibility_item r"
        + "   where r.tenant_id=c.tenant_id and r.case_id=c.id) as responsibility_total,"
        + " (select count(*) from offboarding_responsibility_item r"
        + "   where r.tenant_id=c.tenant_id and r.case_id=c.id and r.status='PENDING') as responsibility_pending,"
        + " (select count(*) from offboarding_reclaim_item k"
        + "   where k.tenant_id=c.tenant_id and k.case_id=c.id) as reclaim_total,"
        + " (select count(*) from offboarding_reclaim_item k"
        + "   where k.tenant_id=c.tenant_id and k.case_id=c.id and k.status='PENDING') as reclaim_pending"
        + " from offboarding_case c"
        + " join member_names(?) n on n.id=c.membership_id"
        + " join member_names(?) o on o.id=c.operator_membership_id";
  }

  private static OffboardingCaseView toCase(Record r) {
    return new OffboardingCaseView(
        r.get("id", UUID.class),
        r.get("membership_id", UUID.class),
        r.get("member_name", String.class),
        r.get("operator_membership_id", UUID.class),
        r.get("operator_name", String.class),
        r.get("reason", String.class),
        r.get("effective_at", OffsetDateTime.class),
        r.get("status", String.class),
        r.get("previous_status", String.class),
        r.get("note", String.class),
        r.get("responsibility_total", Integer.class),
        r.get("responsibility_pending", Integer.class),
        r.get("reclaim_total", Integer.class),
        r.get("reclaim_pending", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("reverted_at", OffsetDateTime.class),
        r.get("reverted_by_membership_id", UUID.class));
  }

  private List<OffboardingResponsibilityView> responsibility(
      UUID tenantId, UUID caseId, String token) {
    return sql
        .fetch(
            "select r.*,s.username as successor_name from offboarding_responsibility_item r"
                + " left join member_names(?) s on s.id=r.successor_membership_id"
                + " where r.tenant_id=? and r.case_id=? order by r.registered_at,r.id",
            token,
            tenantId,
            caseId)
        .stream()
        .map(
            r ->
                new OffboardingResponsibilityView(
                    r.get("id", UUID.class),
                    r.get("case_id", UUID.class),
                    r.get("item_kind", String.class),
                    r.get("source_system", String.class),
                    r.get("reference_id", UUID.class),
                    r.get("reference_no", String.class),
                    r.get("title", String.class),
                    r.get("owner_membership_id", UUID.class),
                    r.get("successor_membership_id", UUID.class),
                    r.get("successor_name", String.class),
                    r.get("status", String.class),
                    r.get("registered_at", OffsetDateTime.class),
                    r.get("transferred_at", OffsetDateTime.class)))
        .toList();
  }

  private List<OffboardingReclaimView> reclaims(UUID tenantId, UUID caseId, String token) {
    return sql
        .fetch(
            "select k.*,s.username as successor_name from offboarding_reclaim_item k"
                + " left join member_names(?) s on s.id=k.successor_membership_id"
                + " where k.tenant_id=? and k.case_id=? order by k.registered_at,k.id",
            token,
            tenantId,
            caseId)
        .stream()
        .map(
            r ->
                new OffboardingReclaimView(
                    r.get("id", UUID.class),
                    r.get("case_id", UUID.class),
                    r.get("item_kind", String.class),
                    r.get("source_system", String.class),
                    r.get("reference_id", UUID.class),
                    r.get("title", String.class),
                    r.get("holder_membership_id", UUID.class),
                    r.get("successor_membership_id", UUID.class),
                    r.get("successor_name", String.class),
                    r.get("reclaimed_by_membership_id", UUID.class),
                    r.get("status", String.class),
                    r.get("registered_at", OffsetDateTime.class),
                    r.get("reclaimed_at", OffsetDateTime.class)))
        .toList();
  }

  private void insertCase(
      UUID tenantId,
      UUID caseId,
      UUID membershipId,
      UUID operatorId,
      String reason,
      OffsetDateTime effectiveAt,
      String previousStatus,
      String note) {
    // ?::timestamptz 刻意放在 values 中部（第 6 位，共 9 位）：末位会让 jOOQ 整体错位一格，
    // 裸 ? 又会被当 varchar 绑定，PG 报 character varying。
    sql.execute(
        "insert into offboarding_case(id,tenant_id,membership_id,operator_membership_id,reason,"
            + " effective_at,status,previous_status,note)"
            + " values(?,?,?,?,?,?::timestamptz,'OPEN',?,?)",
        caseId,
        tenantId,
        membershipId,
        operatorId,
        reason,
        effectiveAt,
        previousStatus,
        note);
  }

  private UUID registerResponsibility(
      UUID tenantId, UUID caseId, UUID ownerId, OffboardingResponsibilityInput input) {
    UUID id = Ids.next();
    boolean settled = input.successorMembershipId() != null;
    try {
      // cast 同样避开末位（第 8 位，共 11 位）。
      sql.execute(
          "insert into offboarding_responsibility_item(id,tenant_id,case_id,item_kind,source_system,"
              + " reference_id,reference_no,title,owner_membership_id,successor_membership_id,status,transferred_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
          id,
          tenantId,
          caseId,
          input.itemKind(),
          input.sourceSystem(),
          input.referenceId(),
          input.referenceNo() == null ? "" : input.referenceNo().trim(),
          input.title().trim(),
          ownerId,
          input.successorMembershipId(),
          settled ? "TRANSFERRED" : "PENDING",
          settled ? utcNow() : null);
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      throw IdentityController.error(
          HttpStatus.CONFLICT, "该来源系统下已登记过同一条单据或审批（" + input.sourceSystem() + "）");
    }
    return id;
  }

  private UUID registerReclaim(
      UUID tenantId, UUID caseId, UUID holderId, OffboardingReclaimInput input) {
    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into offboarding_reclaim_item(id,tenant_id,case_id,item_kind,source_system,"
              + " reference_id,title,holder_membership_id,status)"
              + " values(?,?,?,?,?,?,?,?,'PENDING')",
          id,
          tenantId,
          caseId,
          input.itemKind(),
          input.sourceSystem(),
          input.referenceId(),
          input.title().trim(),
          holderId);
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      throw IdentityController.error(
          HttpStatus.CONFLICT, "该来源系统下已登记过同一条待办或任务（" + input.sourceSystem() + "）");
    }
    return id;
  }

  /**
   * 按待结条目推导案卷状态：还有 PENDING 就是 OPEN，全部结清才 COMPLETED。
   *
   * <p>刻意不在停用那一刻就标「已完成」——下游服务还没消费台账，说完成了就是假的。 REVERTED 是终态，撤销后不再结算。
   */
  private void settle(UUID tenantId, UUID caseId) {
    sql.execute(
        "update offboarding_case set status='COMPLETED'"
            + " where tenant_id=? and id=? and status='OPEN'"
            + " and not exists (select 1 from offboarding_responsibility_item r"
            + "   where r.tenant_id=? and r.case_id=? and r.status='PENDING')"
            + " and not exists (select 1 from offboarding_reclaim_item k"
            + "   where k.tenant_id=? and k.case_id=? and k.status='PENDING')",
        tenantId,
        caseId,
        tenantId,
        caseId,
        tenantId,
        caseId);
  }

  private Record openCase(UUID tenantId, UUID caseId) {
    var row =
        sql.fetchOne("select * from offboarding_case where tenant_id=? and id=?", tenantId, caseId);
    if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "离职记录不存在");
    check(row.get("status", String.class).equals("OPEN"), "该离职记录已结案或已撤销");
    return row;
  }

  private Record member(IdentityController.Access a, UUID id) {
    var r = sql.fetchOne("select * from membership where tenant_id=? and id=?", a.tenantId(), id);
    if (r == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "成员不存在或不属于当前租户");
    return r;
  }

  private boolean ownerOf(Record membership) {
    return sql.fetchExists(
        sql.selectOne()
            .from("role_assignment ra join role r on r.id=ra.role_id and r.tenant_id=ra.tenant_id")
            .where(
                "ra.tenant_id=? and ra.membership_id=? and r.code='OWNER'",
                membership.get("tenant_id", UUID.class),
                membership.get("id", UUID.class)));
  }

  /**
   * 撤销前判断：被停用成员是否仍持有 OWNER 角色（撤销会把角色一并恢复）。
   *
   * <p>刻意 join 到 role 再按 code 判定，而不是 {@code role_id=(select id from role where
   * code='OWNER')}：那个标量子查询不带租户条件，多租户库里每户各有一个 OWNER 角色， 结果不确定（要么报错，要么命中别人家的角色）。
   */
  private boolean isOwnerRole(Record offboardingCase) {
    return sql.fetchExists(
        sql.selectOne()
            .from("role_assignment ra join role r on r.id=ra.role_id and r.tenant_id=ra.tenant_id")
            .where(
                "ra.tenant_id=? and ra.membership_id=? and r.code='OWNER'",
                offboardingCase.get("tenant_id", UUID.class),
                offboardingCase.get("membership_id", UUID.class)));
  }

  /** 成员快照：进审计，不含凭证（凭证在 app_user，审计不该看到）。 */
  private Map<String, Object> memberSnapshot(IdentityController.Access a, UUID id, String token) {
    var row = sql.fetchOne("select * from membership where tenant_id=? and id=?", a.tenantId(), id);
    if (row == null) return null;
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("status", row.get("status", String.class));
    snapshot.put("departmentId", str(row.get("department_id", UUID.class)));
    snapshot.put(
        "assignments",
        sql.fetch(
                "select r.code,ra.scope_mode from role_assignment ra join role r"
                    + " on r.id=ra.role_id and r.tenant_id=ra.tenant_id"
                    + " where ra.tenant_id=? and ra.membership_id=? order by r.code",
                a.tenantId(),
                id)
            .map(x -> x.get("code", String.class) + ":" + x.get("scope_mode", String.class)));
    var names =
        sql.fetch(
            "select username from member_names(?) n join membership m on m.id=n.id where n.id=?",
            token,
            id);
    snapshot.put("username", names.isEmpty() ? null : names.get(0).get("username", String.class));
    return snapshot;
  }

  /** 案卷快照：离职原因、生效时刻与两张台账的条目数，进审计才能事后解释「当时转移了什么」。 */
  private Map<String, Object> caseSnapshot(UUID caseId, String token) {
    var row = sql.fetchOne(caseSelect() + " where c.id=?", token, token, caseId);
    if (row == null) return null;
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("memberName", row.get("member_name", String.class));
    snapshot.put("reason", row.get("reason", String.class));
    snapshot.put("effectiveAt", str(row.get("effective_at", OffsetDateTime.class)));
    snapshot.put("status", row.get("status", String.class));
    snapshot.put("responsibilityTotal", row.get("responsibility_total", Integer.class));
    snapshot.put("responsibilityPending", row.get("responsibility_pending", Integer.class));
    snapshot.put("reclaimTotal", row.get("reclaim_total", Integer.class));
    snapshot.put("reclaimPending", row.get("reclaim_pending", Integer.class));
    return snapshot;
  }

  private Map<String, Object> itemSnapshot(Record row) {
    if (row == null) return null;
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("itemKind", row.get("item_kind", String.class));
    snapshot.put("sourceSystem", row.get("source_system", String.class));
    snapshot.put("referenceId", str(row.get("reference_id", UUID.class)));
    snapshot.put("title", row.get("title", String.class));
    snapshot.put("status", row.get("status", String.class));
    snapshot.put("successorMembershipId", str(row.get("successor_membership_id", UUID.class)));
    return snapshot;
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private void audit(
      IdentityController.Access a,
      String auth,
      String resourceType,
      UUID id,
      String label,
      String action,
      Map<String, Object> before,
      Map<String, Object> after) {
    audit.record(a, auth, resourceType, id, label, action, before, after);
  }
}
