package com.starcon.identity;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * 17.3.2.1 角色分配。
 *
 * <p>蓝图对本模块的立场是一句话：<b>分配时必须同时设定范围</b>——「没有范围的角色等于全量权限，实践中极易误配」。 这条不在应用层兜底，落在 V47 的库内约束上：{@code
 * role_assignment} 的 scope_mode 去掉了默认值， 非 ALL/SELF 的分配必须挂至少一条 {@code
 * role_assignment_scope}，由延迟约束触发器在提交时强制。 所以这里能写出来的分配，一定是带着范围的分配。
 *
 * <p>本模块负责三件事，与邻居划清边界：
 *
 * <ul>
 *   <li><b>分配记录</b>：选角色、定生效时间、定作用范围（法人 / 店铺 / 仓库 / 部门）、撤销；
 *   <li><b>有效权限预览</b>：把一个成员身上同时生效的多条分配合并成一份权限清单，并说清合并方式；
 *   <li><b>合并规则显式化</b>：见 {@link #MERGE_RULE}，输出里逐条带出这条权限是被哪几条分配合并出来的。
 * </ul>
 *
 * <p><b>不做什么</b>：数据范围如何落到行级过滤是 17.3.2.2 的引擎（catalog V3 的 scopeMode RLS），
 * 本模块只负责把范围引用**携带**在分配上并在预览里说清楚，不在这里重写一套过滤引擎。
 *
 * <p>租户上下文来自 {@link IdentityController#resolve} 解析出的已认证成员关系， <b>不采信 {@code X-Tenant-Id} 头</b>；表级
 * FORCE RLS 再兜一层。
 */
@RestController
@RequestMapping("/identity")
public class RoleAssignmentController {
  private static final String READ = "identity.assignment.read";
  private static final String CREATE = "identity.assignment.create";
  private static final String UPDATE = "identity.assignment.update";

  /**
   * 合并规则说明。
   *
   * <p>「多个角色并存时的合并规则」在蓝图里是要能被解释的，所以它不是一句注释，而是输出的一部分：
   *
   * <ul>
   *   <li><b>权限取并集</b>——同一操作被多条有效分配覆盖时合并为一条权限，不因为角色多而互相抵消；
   *   <li><b>范围取并集</b>——各分配携带的范围对象去重合并，而不是各算各的交集；
   *   <li><b>全量范围吸收其余来源</b>——只要其中一条分配是租户内全部，整体按全量生效。
   * </ul>
   *
   * <p>刻意<b>不取交集</b>：成员同时有「店铺运营（范围=店铺 A）」和「仓库主管（范围=仓库 B）」时， 交集会让这个成员什么都看不到——并集才是「他确实同时负责 A 和
   * B」的正确答案。
   */
  public static final String MERGE_RULE =
      "权限取并集：同一操作被多条有效分配覆盖时合并为一条；范围取并集：各分配携带的范围对象去重合并；"
          + "其中任一分配为租户内全部时，整体按全部范围生效。不取交集——交集会让同时负责多个对象的成员什么都不剩。";

  /** 范围模式沿用 V3 既有词表（catalog 的 RLS 引擎消费的就是这三个值），不新造枚举。 */
  private static final String SCOPE_MODES = "ALL|SELF|DEPARTMENT";

  /** 范围对象类型：法人与部门由本服务持有，库内校验存在性与租户归属；店铺与仓库是跨服务不透明引用。 */
  private static final String SCOPE_TYPES = "LEGAL_ENTITY|SHOP|WAREHOUSE|DEPARTMENT";

  private static final Set<String> OWN_SCOPE_TYPES = Set.of("LEGAL_ENTITY", "DEPARTMENT");

  private final DSLContext sql;
  private final IdentityController identity;
  private final AuditLog audit;
  private final TenantTransactions tenants;

  public RoleAssignmentController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.audit = new AuditLog(sql, identity);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /** 一条范围引用。scopeCode 是展示名：法人与部门的编码由库内触发器覆写成真实值，店铺与仓库由调用方提供。 */
  public record RoleAssignmentScopeView(
      @Schema(
              description = "范围类型",
              allowableValues = {"LEGAL_ENTITY", "SHOP", "WAREHOUSE", "DEPARTMENT"})
          String scopeType,
      UUID scopeId,
      String scopeCode) {}

  /**
   * 分配记录。
   *
   * <p>{@code status} 是这一行的持久状态，{@code effectiveStatus} 是「此刻实际有没有效」。
   * 两者并存是刻意的：一条上个月到期但没人撤销的分配，status 仍是 ACTIVE，只看它会以为权限还在。
   */
  public record RoleAssignmentView(
      UUID membershipId,
      UUID roleId,
      String roleCode,
      String roleName,
      String roleStatus,
      @Schema(
              description = "范围模式",
              allowableValues = {"ALL", "SELF", "DEPARTMENT"})
          String scopeMode,
      List<RoleAssignmentScopeView> scopes,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveUntil,
      String status,
      String effectiveStatus,
      UUID assignedBy,
      OffsetDateTime createdAt,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) OffsetDateTime revokedAt,
      @Schema(nullable = true) UUID revokedBy) {}

  public record RoleAssignmentScopeInput(
      @NotNull @Pattern(regexp = SCOPE_TYPES, message = "范围类型必须是法人、店铺、仓库或部门") String scopeType,
      @NotNull UUID scopeId,
      @Schema(nullable = true, description = "范围名称；法人与部门由服务端覆写为真实编码，店铺与仓库必填") @Size(max = 64)
          String scopeCode) {}

  public record MemberRoleAssignmentInput(
      @NotNull UUID roleId,
      @NotNull @Pattern(regexp = SCOPE_MODES, message = "范围模式必须是租户内全部、仅本人或本部门") String scopeMode,
      @NotNull List<@NotNull @Valid RoleAssignmentScopeInput> scopes,
      @NotNull OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveUntil,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 合并后的一条权限：这条权限由哪些分配合并而来、范围怎么并、按哪条规则合的。 */
  public record RoleAssignmentMergedPermission(
      String permissionCode,
      String pageCode,
      String pageName,
      String action,
      @Schema(
              description = "合并后的范围模式",
              allowableValues = {"ALL", "SELF", "DEPARTMENT"})
          String mergedScopeMode,
      List<String> sourceRoles,
      List<RoleAssignmentScopeView> scopeObjects,
      @Schema(
              description = "合并规则",
              allowableValues = {"SINGLE", "UNION", "ABSORBED_BY_TENANT_SCOPE"})
          String mergeRule) {}

  /** 没有参与合并的分配，以及没参与的原因——管理员要看得见「为什么这个角色没生效」。 */
  public record RoleAssignmentInactiveReason(
      UUID roleId,
      String roleCode,
      @Schema(
              description = "未参与合并的原因",
              allowableValues = {"PENDING", "EXPIRED", "REVOKED", "ROLE_INACTIVE"})
          String reason) {}

  public record RoleAssignmentMergeView(
      UUID membershipId,
      @Schema(nullable = true) String username,
      int assignmentCount,
      int effectiveCount,
      String mergeRule,
      List<RoleAssignmentMergedPermission> permissions,
      List<RoleAssignmentInactiveReason> inactive) {}

  /**
   * 本服务可提供的范围候选：法人与部门。
   *
   * <p>店铺与仓库的候选不在这里——它们的表属于 channel 与 warehouse 服务，本库没有， 建跨库外键是被 AGENTS.md
   * 禁止的。页面分别向那两个服务取列表，取不到就只显示本服务能给的两类， 而不是拿一份假数据凑齐四类。
   */
  public record RoleAssignmentScopeOption(
      String scopeType, UUID scopeId, String code, String name) {}

  @GetMapping("/role-assignment-scopes")
  public List<RoleAssignmentScopeOption> scopeOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        READ,
        false,
        a -> {
          var options = new ArrayList<RoleAssignmentScopeOption>();
          for (var r :
              sql.fetch(
                  "select id,code,legal_name from legal_entity where tenant_id=? and status='ACTIVE' order by code",
                  a.tenantId()))
            options.add(
                new RoleAssignmentScopeOption(
                    "LEGAL_ENTITY",
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("legal_name", String.class)));
          for (var r :
              sql.fetch(
                  "select id,code,name from department where tenant_id=? order by code",
                  a.tenantId()))
            options.add(
                new RoleAssignmentScopeOption(
                    "DEPARTMENT",
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class)));
          return options;
        });
  }

  @GetMapping("/role-assignments")
  public List<RoleAssignmentView> assignments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam("membershipId") UUID membershipId) {
    return call(auth, READ, false, a -> views(a, membershipId));
  }

  @PostMapping("/members/{membershipId}/role-assignments")
  public UUID create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID membershipId,
      @Valid @RequestBody MemberRoleAssignmentInput input) {
    return call(
        auth,
        CREATE,
        true,
        a -> {
          member(membershipId);
          var role = role(input.roleId());
          validate(a, membershipId, input);
          check(
              !sql.fetchExists(
                  sql.selectOne()
                      .from("role_assignment")
                      .where("membership_id=? and role_id=?", membershipId, input.roleId())),
              "该成员已分配此角色，请直接修改现有分配");
          UUID id = Ids.next();
          write(a, membershipId, input, id);
          record(a, auth, membershipId, "RoleAssignmentCreated", null, snapshot(a, membershipId));
          return id;
        });
  }

  @PutMapping("/members/{membershipId}/role-assignments/{roleId}")
  public void update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID membershipId,
      @PathVariable UUID roleId,
      @Valid @RequestBody MemberRoleAssignmentInput input) {
    call(
        auth,
        UPDATE,
        true,
        a -> {
          member(membershipId);
          check(input.roleId().equals(roleId), "角色不能变更，请删除原分配后重新分配");
          role(roleId);
          validate(a, membershipId, input);
          check(assignment(a, membershipId, roleId) != null, "分配不存在");
          var before = snapshot(a, membershipId);
          // 范围行整体重写（先删后插），因此不会留下旧范围与新范围混合的中间记录。
          write(a, membershipId, input, null);
          record(a, auth, membershipId, "RoleAssignmentUpdated", before, snapshot(a, membershipId));
          return null;
        });
  }

  /**
   * 撤销分配。
   *
   * <p>走 POST 而不是 DELETE：撤销是审计事件（要留下谁在什么时候撤的），不是资源消失， 与 V42 层级授权同一取舍。撤销后 assignment 仍然可查，只是
   * effectiveStatus 变成 REVOKED， 权限预览里也会显式说明它没有参与合并。
   */
  @PostMapping("/members/{membershipId}/role-assignments/{roleId}/revoke")
  public void revoke(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID membershipId,
      @PathVariable UUID roleId) {
    call(
        auth,
        UPDATE,
        true,
        a -> {
          member(membershipId);
          var before = snapshot(a, membershipId);
          int changed =
              sql.execute(
                  "update role_assignment set status='REVOKED',revoked_at=?::timestamptz,revoked_by=?"
                      + " where membership_id=? and role_id=? and status='ACTIVE'",
                  OffsetDateTime.now(),
                  a.userId(),
                  membershipId,
                  roleId);
          check(changed > 0, "分配不存在或已撤销");
          record(a, auth, membershipId, "RoleAssignmentRevoked", before, snapshot(a, membershipId));
          return null;
        });
  }

  /**
   * 有效权限预览（合并后）。
   *
   * <p>蓝图要求输出「角色分配记录 + 有效权限预览（合并后）」。这个端点回答的是管理员配置时最需要知道的一件事： 这个人现在到底有什么权限、范围到哪、这条权限是哪几个角色合并出来的。
   *
   * <p>未生效的分配也在结果里（{@code inactive}），否则「我明明配了这个角色怎么没权限」就只能靠猜。
   */
  @GetMapping("/members/{membershipId}/effective-permissions")
  public RoleAssignmentMergeView effective(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID membershipId) {
    return call(
        auth,
        READ,
        false,
        a -> {
          member(membershipId);
          return merge(auth, a, membershipId);
        });
  }

  // ------------------------------------------------------------------
  // 内部
  // ------------------------------------------------------------------

  /**
   * 与 {@code AdministrationController.call} 同口径：租户事务 + 会话锁 + 租户二次校验 + 权限码。
   *
   * <p>授权与组织变更仍要求租户所有者执行——这与 V42 层级授权同一道闸，不另立一套「谁能分配角色」的身份。
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
          if (mutation) require(a.owner(), "授权与组织变更需由当前租户所有者执行");
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

  private org.jooq.Record member(UUID id) {
    var r = sql.fetchOne("select * from membership where id=?", id);
    if (r == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "成员不存在");
    return r;
  }

  private org.jooq.Record role(UUID id) {
    var r = sql.fetchOne("select * from role where id=?", id);
    if (r == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "角色不存在");
    return r;
  }

  private org.jooq.Record assignment(IdentityController.Access a, UUID membershipId, UUID roleId) {
    return sql.fetchOne(
        "select * from role_assignment where membership_id=? and role_id=?", membershipId, roleId);
  }

  /**
   * 应用层校验：与 V47 的库内约束互为防线，两边都留是因为它们回答的是不同的问题。
   *
   * <p>库内约束回答「结构上是否自洽」（范围非空、ALL 不得挂对象、对象必须属于本租户）； 这里回答「这条配置在业务上说不说得通」，返回 400 而不是笼统的 409，
   * 用户能直接看到哪一条填错了。
   */
  private void validate(
      IdentityController.Access a, UUID membershipId, MemberRoleAssignmentInput input) {
    var role = role(input.roleId());
    require(!role.get("code", String.class).equals("OWNER"), "请使用转移所有者操作");
    check(role.get("status", String.class).equals("ACTIVE"), "不能分配停用角色");
    check(
        input.effectiveUntil() == null || input.effectiveUntil().isAfter(input.effectiveFrom()),
        "生效结束时间必须晚于开始时间");

    var keys = new LinkedHashSet<String>();
    for (var scope : input.scopes()) {
      check(keys.add(scope.scopeType() + ":" + scope.scopeId()), "范围对象不能重复");
      if (OWN_SCOPE_TYPES.contains(scope.scopeType()))
        // 法人与部门的编码、名称由服务端覆写，这里只解释「为什么必须存在」这一层的原因。
        check(
            sql.fetchExists(
                sql.selectOne()
                    .from(scope.scopeType().equals("LEGAL_ENTITY") ? "legal_entity" : "department")
                    .where("tenant_id=? and id=?", a.tenantId(), scope.scopeId())),
            scope.scopeType().equals("LEGAL_ENTITY") ? "法人不存在" : "部门不存在");
      else
        // 店铺与仓库属于别的服务，本库无法校验存在性，只能要求调用方把名称说清楚，
        // 否则预览里会出现一个没有任何出处的范围名。
        check(scope.scopeCode() != null && !scope.scopeCode().isBlank(), "店铺与仓库范围必须提供名称");
    }

    if (input.scopeMode().equals("ALL") || input.scopeMode().equals("SELF")) {
      check(input.scopes().isEmpty(), "租户内全部与仅本人范围自带完整范围，无需再指定范围对象");
      return;
    }
    check(!input.scopes().isEmpty(), "本部门范围必须指定作用对象");
    var department =
        sql.fetchOne("select department_id from membership where id=?", membershipId)
            .get("department_id", UUID.class);
    check(department != null, "本部门范围需要先设置成员部门");
    for (var scope : input.scopes())
      if (scope.scopeType().equals("DEPARTMENT"))
        check(scope.scopeId().equals(department), "本部门范围只能落在该成员所属部门上");
  }

  /**
   * 写入一条分配及其范围。
   *
   * <p>{@code id} 传 null 表示更新（复用既有行），否则新建一行。
   *
   * <p>timestamptz 一律显式带 {@code ?::timestamptz} 且**不在 VALUES 列表末位**： jOOQ 对列表里的裸 {@code ?} 按
   * varchar 推断类型，{@code OffsetDateTime} 会被当字符串塞进去， 不抛异常、只让值偏掉；而把 cast 挪到末位会让 jOOQ
   * 整体绑定错位一格。两个坑都踩过，顺序是刻意的。
   */
  private void write(
      IdentityController.Access a, UUID membershipId, MemberRoleAssignmentInput input, UUID id) {
    sql.execute(
        "delete from role_assignment_scope where membership_id=? and role_id=?",
        membershipId,
        input.roleId());
    if (id == null) {
      // 三列一起改：V47 的 CHECK 规定 ACTIVE 必须没有撤销痕迹、REVOKED 必须有撤销人，
      // 分两条语句改会在中间态撞约束。改内容即视为重新生效，所以同时清掉撤销痕迹。
      sql.execute(
          "update role_assignment set effective_from=?::timestamptz,effective_until=?::timestamptz,"
              + "scope_mode=?,note=?,assigned_by=?,status='ACTIVE',revoked_at=null,revoked_by=null"
              + " where membership_id=? and role_id=?",
          input.effectiveFrom(),
          input.effectiveUntil(),
          input.scopeMode(),
          input.note(),
          a.userId(),
          membershipId,
          input.roleId());
    } else {
      sql.execute(
          "insert into role_assignment(tenant_id,membership_id,role_id,effective_from,effective_until,scope_mode,assigned_by,note)"
              + " values(?,?,?,?::timestamptz,?::timestamptz,?,?,?)",
          a.tenantId(),
          membershipId,
          input.roleId(),
          input.effectiveFrom(),
          input.effectiveUntil(),
          input.scopeMode(),
          a.userId(),
          input.note());
    }
    for (var scope : input.scopes())
      // 法人与部门的 scope_code 会被库内触发器覆写成真实编码；这里给的只对店铺与仓库有意义。
      sql.execute(
          "insert into role_assignment_scope(tenant_id,membership_id,role_id,scope_type,scope_id,scope_code)"
              + " values(?,?,?,?,?,?)",
          a.tenantId(),
          membershipId,
          input.roleId(),
          scope.scopeType(),
          scope.scopeId(),
          scope.scopeCode() == null || scope.scopeCode().isBlank()
              ? scope.scopeType()
              : scope.scopeCode());
  }

  /** 一个成员的分配列表。范围行一次取回后按角色分组，避免每条分配都发一次查询。 */
  private List<RoleAssignmentView> views(IdentityController.Access a, UUID membershipId) {
    var scopes = new LinkedHashMap<UUID, List<RoleAssignmentScopeView>>();
    for (var r :
        sql.fetch(
            "select * from role_assignment_scope where membership_id=? order by scope_type,scope_code",
            membershipId))
      scopes
          .computeIfAbsent(r.get("role_id", UUID.class), x -> new ArrayList<>())
          .add(
              new RoleAssignmentScopeView(
                  r.get("scope_type", String.class),
                  r.get("scope_id", UUID.class),
                  r.get("scope_code", String.class)));
    var list = new ArrayList<RoleAssignmentView>();
    for (var r :
        sql.fetch(
            "select ra.role_id,r.code,r.name,r.status as role_status,ra.scope_mode,ra.effective_from,"
                + "ra.effective_until,ra.status,ra.assigned_by,ra.created_at,ra.note,ra.revoked_at,ra.revoked_by"
                + " from role_assignment ra join role r on r.id=ra.role_id and r.tenant_id=ra.tenant_id"
                + " where ra.membership_id=? order by r.code",
            membershipId)) {
      UUID roleId = r.get("role_id", UUID.class);
      list.add(
          new RoleAssignmentView(
              membershipId,
              roleId,
              r.get("code", String.class),
              r.get("name", String.class),
              r.get("role_status", String.class),
              r.get("scope_mode", String.class),
              scopes.getOrDefault(roleId, List.of()),
              r.get("effective_from", OffsetDateTime.class),
              r.get("effective_until", OffsetDateTime.class),
              r.get("status", String.class),
              effective(
                  r.get("status", String.class),
                  r.get("effective_from", OffsetDateTime.class),
                  r.get("effective_until", OffsetDateTime.class)),
              r.get("assigned_by", UUID.class),
              r.get("created_at", OffsetDateTime.class),
              r.get("note", String.class),
              r.get("revoked_at", OffsetDateTime.class),
              r.get("revoked_by", UUID.class)));
    }
    return list;
  }

  /**
   * 合并有效权限。
   *
   * <p>合并在 Java 里显式做，而不是把多条分配塞进 SQL 交给数据库去猜：规则要能对着输出解释， 写成一句 {@code array_agg}
   * 就只剩结果、没有依据了。取数仍然是一条显式 SQL（可审阅）， 合并规则与 {@link #MERGE_RULE} 一一对应。
   */
  private RoleAssignmentMergeView merge(
      String auth, IdentityController.Access a, UUID membershipId) {
    var now = OffsetDateTime.now();
    var live = new ArrayList<org.jooq.Record>();
    var inactive = new ArrayList<RoleAssignmentInactiveReason>();
    for (var r :
        sql.fetch(
            "select ra.role_id,r.code,r.status as role_status,ra.scope_mode,ra.effective_from,ra.effective_until,"
                + "ra.status from role_assignment ra join role r on r.id=ra.role_id and r.tenant_id=ra.tenant_id"
                + " where ra.membership_id=? order by r.code",
            membershipId)) {
      String reason =
          inactiveReason(
              r.get("role_status", String.class),
              r.get("status", String.class),
              r.get("effective_from", OffsetDateTime.class),
              r.get("effective_until", OffsetDateTime.class),
              now);
      if (reason == null) live.add(r);
      else
        inactive.add(
            new RoleAssignmentInactiveReason(
                r.get("role_id", UUID.class), r.get("code", String.class), reason));
    }

    // 合并中间态：一条权限 → 贡献它的分配（角色编码 + 范围模式）与范围对象。
    var merged = new LinkedHashMap<String, Accumulator>();
    for (var assignmentRow : live) {
      UUID roleId = assignmentRow.get("role_id", UUID.class);
      String mode = assignmentRow.get("scope_mode", String.class);
      for (var p :
          sql.fetch(
              "select rp.permission_code,p.page_code,pr.name as page_name,p.action"
                  + " from role_permission rp join permission p on p.code=rp.permission_code"
                  + " join page_resource pr on pr.code=p.page_code"
                  + " where rp.role_id=? and rp.tenant_id=? order by p.page_code,p.action",
              roleId,
              a.tenantId())) {
        var acc =
            merged.computeIfAbsent(
                p.get("permission_code", String.class),
                code ->
                    new Accumulator(
                        code,
                        p.get("page_code", String.class),
                        p.get("page_name", String.class),
                        p.get("action", String.class)));
        acc.sources.add(assignmentRow.get("code", String.class));
        if (acc.modes.add(mode)) acc.mode = mergeMode(acc.mode, mode);
      }
    }
    // 范围对象按权限汇总：并集去重，先按 (类型,对象) 排序保证输出稳定。
    for (var row :
        sql.fetch(
            "select ra.role_id,r.code,s.scope_type,s.scope_id,s.scope_code"
                + " from role_assignment_scope s"
                + " join role_assignment ra on ra.membership_id=s.membership_id and ra.role_id=s.role_id"
                + " and ra.tenant_id=s.tenant_id"
                + " join role r on r.id=ra.role_id and r.tenant_id=ra.tenant_id"
                + " where s.membership_id=? order by s.scope_type,s.scope_code",
            membershipId)) {
      String roleCode = row.get("code", String.class);
      var view =
          new RoleAssignmentScopeView(
              row.get("scope_type", String.class),
              row.get("scope_id", UUID.class),
              row.get("scope_code", String.class));
      for (var acc : merged.values())
        if (acc.sources.contains(roleCode))
          // 按 (类型,对象) 去重：同一条范围被两个角色各带一次时只应出现一次，
          // 它在预览里代表的是「范围并集里有哪些对象」，不是「被几个角色提到」。
          acc.objects.putIfAbsent(view.scopeType() + ":" + view.scopeId(), view);
    }

    var permissions = new ArrayList<RoleAssignmentMergedPermission>();
    for (var acc : merged.values())
      permissions.add(
          new RoleAssignmentMergedPermission(
              acc.code,
              acc.pageCode,
              acc.pageName,
              acc.action,
              acc.mode,
              acc.sources.stream().sorted().toList(),
              List.copyOf(acc.objects.values()),
              acc.sources.size() > 1
                  ? (acc.mode.equals("ALL") ? "ABSORBED_BY_TENANT_SCOPE" : "UNION")
                  : "SINGLE"));
    permissions.sort(
        java.util.Comparator.comparing(RoleAssignmentMergedPermission::pageCode)
            .thenComparing(RoleAssignmentMergedPermission::action));
    return new RoleAssignmentMergeView(
        membershipId,
        username(membershipId, auth),
        live.size() + inactive.size(),
        live.size(),
        MERGE_RULE,
        permissions,
        inactive);
  }

  /** 合并预览要显示成员名；解析不出来就留空，不拿别的东西顶替。 */
  private String username(UUID membershipId, String auth) {
    var names =
        sql.fetch(
            "select username from member_names(?) n join membership m on m.id=n.id where n.id=?",
            hash(auth),
            membershipId);
    return names.isEmpty() ? null : names.get(0).get("username", String.class);
  }

  /** 一条权限的合并中间态。字段都在这里，合并过程不用回头翻别的表。 */
  private static final class Accumulator {
    final String code;
    final String pageCode;
    final String pageName;
    final String action;
    final LinkedHashSet<String> sources = new LinkedHashSet<>();
    final LinkedHashSet<String> modes = new LinkedHashSet<>();
    final LinkedHashMap<String, RoleAssignmentScopeView> objects = new LinkedHashMap<>();
    String mode = "DEPARTMENT";

    Accumulator(String code, String pageCode, String pageName, String action) {
      this.code = code;
      this.pageCode = pageCode;
      this.pageName = pageName;
      this.action = action;
    }
  }

  /** 范围模式合并：全量吸收其余来源，其次仅本人，再次本部门。 */
  private static String mergeMode(String current, String incoming) {
    return rank(incoming) > rank(current) ? incoming : current;
  }

  private static int rank(String mode) {
    return switch (mode) {
      case "ALL" -> 3;
      case "SELF" -> 2;
      default -> 1;
    };
  }

  /** 此刻这一行是否真的有效；返回 null 表示参与合并，否则返回未参与的原因。 */
  private static String inactiveReason(
      String roleStatus,
      String status,
      OffsetDateTime from,
      OffsetDateTime until,
      OffsetDateTime now) {
    if ("REVOKED".equals(status)) return "REVOKED";
    if (!"ACTIVE".equals(roleStatus)) return "ROLE_INACTIVE";
    if (from != null && now.isBefore(from)) return "PENDING";
    if (until != null && !now.isBefore(until)) return "EXPIRED";
    return null;
  }

  private static String effective(String status, OffsetDateTime from, OffsetDateTime until) {
    if ("REVOKED".equals(status)) return "REVOKED";
    var now = OffsetDateTime.now();
    if (from != null && now.isBefore(from)) return "PENDING";
    if (until != null && !now.isBefore(until)) return "EXPIRED";
    return "ACTIVE";
  }

  /** 分配快照进审计：范围对象必须一起留痕，否则事后无法解释「当时到底把权限放到哪」。 */
  private Map<String, Object> snapshot(IdentityController.Access a, UUID membershipId) {
    var entries = new ArrayList<Map<String, Object>>();
    for (var v : views(a, membershipId)) {
      var m = new LinkedHashMap<String, Object>();
      m.put("roleId", v.roleId().toString());
      m.put("roleCode", v.roleCode());
      m.put("scopeMode", v.scopeMode());
      m.put(
          "scopes",
          v.scopes().stream().map(s -> s.scopeType() + ":" + s.scopeId()).sorted().toList());
      m.put("effectiveFrom", v.effectiveFrom() == null ? null : v.effectiveFrom().toString());
      m.put("effectiveUntil", v.effectiveUntil() == null ? null : v.effectiveUntil().toString());
      m.put("status", v.status());
      m.put("effectiveStatus", v.effectiveStatus());
      m.put("note", v.note());
      entries.add(m);
    }
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("assignments", entries);
    return snapshot;
  }

  private void record(
      IdentityController.Access a,
      String auth,
      UUID membershipId,
      String action,
      Map<String, Object> before,
      Map<String, Object> after) {
    // 审计与事件外发同事务：下游收到的分配变更必然有一条对应审计行。
    audit.record(a, auth, "identity.assignment", membershipId, "角色分配", action, before, after);
    sql.execute(
        "insert into identity_outbox(id,tenant_id,resource_id,event_type) values(?,?,?,?)",
        Ids.next(),
        a.tenantId(),
        membershipId,
        action);
  }
}
