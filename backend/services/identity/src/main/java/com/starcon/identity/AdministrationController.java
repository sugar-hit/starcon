package com.starcon.identity;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/identity")
public class AdministrationController {
  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;
  private final AuditLog audit;

  public AdministrationController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.audit = new AuditLog(sql, identity);
    tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  public record PermissionView(String code, String pageCode, String pageName, String action) {}

  public record RoleView(
      UUID id, String code, String name, String status, boolean system, List<String> permissions) {}

  public record RoleInput(
      @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_-]{0,63}") String code,
      @NotBlank @Size(max = 100) String name,
      @Pattern(regexp = "ACTIVE|INACTIVE") @NotNull String status,
      @NotNull List<@NotBlank String> permissions) {}

  public record AssignmentInput(
      @NotNull UUID roleId, @NotNull @Pattern(regexp = "ALL|DEPARTMENT|SELF") String scopeMode) {}

  public record MemberView(
      UUID id,
      UUID userId,
      String username,
      String status,
      @Schema(nullable = true) UUID departmentId,
      List<AssignmentInput> assignments,
      boolean owner) {}

  public record MemberInput(
      @NotBlank @Size(max = 100) String username,
      @Schema(nullable = true) @Size(min = 12, max = 72) String password,
      @NotNull @Pattern(regexp = "ACTIVE|INACTIVE") String status,
      @Schema(nullable = true) UUID departmentId,
      @NotNull List<@NotNull @Valid AssignmentInput> assignments) {}

  public record MemberUpdate(
      @NotNull @Pattern(regexp = "ACTIVE|INACTIVE") String status,
      @Schema(nullable = true) UUID departmentId,
      @NotNull List<@NotNull @Valid AssignmentInput> assignments) {}

  /**
   * 17.2.1.1 部门树。
   *
   * <p>部门是**组织维度**，与法人（法律主体维度）分离：{@code legalEntityId} 只是部门的一条归属引用，
   * 不代表这个部门就是那个法人。输出 = 整棵树 + 每个节点的成员分布，children 递归嵌套根节点列表。
   */
  public record DepartmentView(
      UUID id,
      String code,
      String name,
      @Schema(nullable = true) UUID parentId,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) String legalEntityName,
      @Schema(nullable = true) UUID leaderMembershipId,
      @Schema(nullable = true) String leaderName,
      int memberCount,
      List<DepartmentView> children) {}

  /** 层级调整单独走 {@code PUT /departments/{id}/parent}，所以这里刻意不含 parentId。 */
  public record DepartmentInput(
      @NotBlank @Pattern(regexp = "[A-Z0-9_-]{1,64}", message = "部门编码只允许大写字母数字下划线中划线")
          String code,
      @NotBlank @Size(max = 100) String name,
      @Schema(nullable = true) UUID parentId,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID leaderMembershipId) {}

  /** 拖拽移动：parentId 为空表示提升为根部门。 */
  public record DepartmentMove(@Schema(nullable = true) UUID parentId) {}

  public record ChildView(UUID id, String code, String name, String status) {}

  public record ChildInput(
      @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_-]{0,63}") String code,
      @NotBlank @Size(max = 100) String name,
      @NotBlank @Size(max = 100) String ownerUsername,
      @Schema(nullable = true) @Size(min = 12, max = 72) String ownerPassword) {}

  public record ChildUpdate(
      @NotBlank @Size(max = 100) String name,
      @NotNull @Pattern(regexp = "ACTIVE|SUSPENDED") String status) {}

  public record OwnerTransfer(@NotNull UUID membershipId) {}

  /**
   * 17.1.2.1 法人档案。
   *
   * <p>法人是货权、收付款与记账主体，不等于租户。本位币与舍入规则挂在法人上，
   * 决定 12 财务总账的记账基准——租户默认币种只是展示默认，两者不能混。
   */
  public record LegalEntityView(
      UUID id,
      String code,
      String legalName,
      String registrationCountry,
      String registrationNo,
      String taxNo,
      String baseCurrency,
      String address,
      int amountScale,
      int quantityScale,
      String rounding,
      String status,
      long version,
      int referenceCount) {}

  public record LegalEntityInput(
      @NotBlank @Pattern(regexp = "[A-Z0-9_-]{1,64}", message = "法人编码只允许大写字母数字下划线中划线")
          String code,
      @NotBlank @Size(max = 200) String legalName,
      @Pattern(regexp = "[A-Z]{2}", message = "注册国家需为两位大写国家代码") String registrationCountry,
      @Size(max = 64) String registrationNo,
      @Size(max = 64) String taxNo,
      @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "本位币需为三位大写币种代码") String baseCurrency,
      @Size(max = 500) String address,
      @Min(0) @Max(6) Integer amountScale,
      @Min(0) @Max(6) Integer quantityScale,
      @Pattern(regexp = "HALF_UP|HALF_EVEN|DOWN|UP") String rounding,
      @NotNull @Pattern(regexp = "ACTIVE|SUSPENDED") String status) {}

  /** 引用登记：channel 等服务登记"这个法人被我用着"，让删除保护有真实依据。 */
  public record LegalEntityReferenceInput(
      @NotNull UUID legalEntityId, @NotBlank @Pattern(regexp = "[a-z_]{2,32}") String source, @NotNull UUID referenceId) {}

  // ------------------------------------------------------------------
  // 17.2.1.2 部门负责人
  // ------------------------------------------------------------------

  /**
   * 负责人记录。
   *
   * <p>{@code leaderId} / {@code membershipId} / {@code username} 全部可空：蓝图点名
   * 「负责人可为空」，没有负责人的部门要在审批流里指定兜底人。所以这类部门**必须出现在列表里**
   * （前端标「未指定负责人」），把它过滤掉等于让运营看不见自己漏配了审批人。
   */
  public record DepartmentLeaderView(
      @Schema(nullable = true) UUID leaderId,
      UUID departmentId,
      String departmentCode,
      String departmentName,
      @Schema(nullable = true) UUID membershipId,
      @Schema(nullable = true) String username,
      @Schema(nullable = true) String scopeMode,
      @Schema(nullable = true) BigDecimal approvalLimit,
      @Schema(nullable = true) String approvalCurrency,
      @Schema(nullable = true) String note,
      List<UUID> proxyIds,
      List<UUID> grantIds,
      @Schema(nullable = true) OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  /**
   * 设置负责人。
   *
   * <p>{@code approvalLimit} 与 {@code approvalCurrency} 同进同出：只有额度没有币种的话，
   * 「1000」在 USD 与 JPY 下是两回事，17.4 无从判断该批多少。两者要么都填要么都留空。
   */
  public record DepartmentLeaderInput(
      @NotNull UUID membershipId,
      @NotNull @Pattern(regexp = "DEPARTMENT|SUBTREE|TENANT") String scopeMode,
      @Schema(nullable = true)
          @Digits(integer = 13, fraction = 6)
          @DecimalMin(value = "0", inclusive = false)
          BigDecimal approvalLimit,
      @Schema(nullable = true) @Pattern(regexp = "[A-Z]{3}", message = "审批额度币种需为三位大写币种代码") String approvalCurrency,
      @Size(max = 500) String note) {}

  /**
   * 代管：负责人不在时由谁代行。
   *
   * <p>{@code endsAt} 可空表示长期代管（授权副总代管是真实业务形态），强行要求截止时间
   * 只会逼出「填一个假截止日」的数据。
   */
  public record LeaderProxyInput(
      @NotNull UUID membershipId,
      @Size(max = 500) String reason,
      @Schema(nullable = true) OffsetDateTime startsAt,
      @Schema(nullable = true) OffsetDateTime endsAt) {}

  /**
   * 临时授权：把审批权限在有界区间内借给别人。
   *
   * <p>额度必填且有上界——没有上界的「临时授权」等于凭空多出一个负责人，
   * 那是「改负责人」该做的事。区间也必填：临时就是有界，不设终点会退化成第二份长期负责人。
   */
  public record LeaderGrantInput(
      @NotNull UUID membershipId,
      @NotNull
          @Digits(integer = 13, fraction = 6)
          @DecimalMin(value = "0", inclusive = false)
          BigDecimal approvalLimit,
      @NotNull @Pattern(regexp = "[A-Z]{3}", message = "审批额度币种需为三位大写币种代码") String approvalCurrency,
      @NotNull OffsetDateTime startsAt,
      @NotNull OffsetDateTime endsAt,
      @Size(max = 500) String reason) {}

  /** 审批链的一环：某一时刻谁有审批权、批到什么程度。 */
  public record ApprovalChainEntry(
      int seq,
      String source,
      UUID membershipId,
      String username,
      String scopeMode,
      @Schema(nullable = true) BigDecimal approvalLimit,
      @Schema(nullable = true) String approvalCurrency,
      OffsetDateTime startsAt,
      @Schema(nullable = true) OffsetDateTime endsAt) {}

  /**
   * 审批链引用：下游 17.4 审批流的取数口。
   *
   * <p>{@code requiresFallback} 是蓝图的硬要求——负责人可为空，空的时候审批流必须自己指定兜底人。
   * 这里显式把这个信号返回出去，而不是让下游拿到空数组后自行猜测「没人需要审批」。
   */
  public record ApprovalChainView(
      UUID departmentId,
      String departmentCode,
      String departmentName,
      OffsetDateTime asOf,
      boolean requiresFallback,
      List<ApprovalChainEntry> entries) {}

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
          // Serializes revocation, delegation and last-Owner checks for this tenant.
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

  /**
   * 17.1.1.1 租户上下文的读路径：不挂页面权限码。
   *
   * <p>与 {@link #call} 的区别是少了权限码这道闸——「我属于哪些租户」对每个成员都必须可读，
   * 否则普通成员连自己在哪个租户都看不到。能读到哪些租户仍由 {@code switchable_tenants}
   * 从会话成员关系推导，不是这里放开的。
   *
   * <p>仍然要在租户事务里跑（而不是裸调）：会话锁定与租户二次校验要和写路径同口径，
   * 否则切换租户与读取会撞在一起，读到切换一半的上下文。
   */
  private <T> T context(String auth, Function<IdentityController.Access, T> work) {
    var initial = identity.resolve(auth);
    return tenants.run(
        initial.tenantId(),
        () -> {
          sql.fetch("select lock_session(?)", hash(auth));
          var a = identity.resolve(auth);
          require(a.tenantId().equals(initial.tenantId()), "租户已切换，请重试");
          return work.apply(a);
        });
  }

  private static void require(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.FORBIDDEN, message);
  }

  private static void check(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.BAD_REQUEST, message);
  }

  /** 业务规则冲突（不是字段格式问题）：删除保护、层级约束等，带可读原因。 */
  private static void reject(String message) {
    throw IdentityController.error(HttpStatus.CONFLICT, message);
  }

  /**
   * 记录一条审计。
   *
   * <p>资源类型 + 可读标题 + 变更前后值 + 授权上下文都经 {@link AuditLog} 落库，
   * 且在写入侧脱敏（见 {@link AuditRedactor}）。三个参数任一为 null 都合法：
   * 新建没有 before，删除没有 after。
   */
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
    // 事件外发与审计落库在同一事务里：下游收到的变更必然有一条对应的审计行。
    emitOutbox(a, id, action);
  }

  /** 角色快照：权限集必须进审计，否则事后无法解释「当时到底给了什么权限」。 */
  private Map<String, Object> roleSnapshot(UUID id) {
    var row = sql.fetchOne("select * from role where id=?", id);
    if (row == null) return null;
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("code", row.get("code", String.class));
    snapshot.put("name", row.get("name", String.class));
    snapshot.put("status", row.get("status", String.class));
    snapshot.put(
        "permissions",
        sql.fetch(
                "select permission_code from role_permission where role_id=? order by permission_code",
                id)
            .getValues("permission_code", String.class));
    return snapshot;
  }

  /**
   * 成员快照。
   *
   * <p>刻意不含密码或密码哈希：凭证在 app_user，identity_audit 不该、也不需要看到它。
   * 这里同时依赖 {@link AuditRedactor} 做第二道拦截——即使将来有人把 password 字段
   * 加进快照，写入侧也会把它打成 {@code ***}。
   *
   * <p>用户名经 {@code member_names(hash)} 解析：它以会话成员关系为条件解析，不采信调用方
   * 传入的 tenant 头，所以伪造 {@code X-Tenant-Id} 拿不到别人的用户名。
   */
  private Map<String, Object> memberSnapshot(UUID id, String auth) {
    var row = sql.fetchOne("select * from membership where id=?", id);
    if (row == null) return null;
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("userId", row.get("user_id", UUID.class).toString());
    snapshot.put("status", row.get("status", String.class));
    snapshot.put("departmentId", str(row.get("department_id", UUID.class)));
    snapshot.put(
        "assignments",
        sql.fetch(
                "select r.code,ra.scope_mode from role_assignment ra join role r"
                    + " on r.id=ra.role_id and r.tenant_id=ra.tenant_id"
                    + " where ra.membership_id=? order by r.code",
                id)
            .map(x -> x.get("code", String.class) + ":" + x.get("scope_mode", String.class)));
    var names =
        sql.fetch(
            "select username from member_names(?) n join membership m on m.id=n.id where n.id=?",
            hash(auth),
            id);
    snapshot.put("username", names.isEmpty() ? null : names.get(0).get("username", String.class));
    return snapshot;
  }

  /** 成员可读名，用于审计标题；解析不出来就留空，不拿别的东西顶替。 */
  private String username(UUID id, String auth) {
    var names =
        sql.fetch(
            "select username from member_names(?) n join membership m on m.id=n.id where n.id=?",
            hash(auth),
            id);
    return names.isEmpty() ? null : names.get(0).get("username", String.class);
  }

  /** 部门快照：层级与归属引用都要留痕，跨租户挂靠排查依赖这些字段。 */
  private Map<String, Object> departmentSnapshot(UUID id) {
    var row = sql.fetchOne("select * from department where id=?", id);
    if (row == null) return null;
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("code", row.get("code", String.class));
    snapshot.put("name", row.get("name", String.class));
    snapshot.put("parentId", str(row.get("parent_id", UUID.class)));
    snapshot.put("legalEntityId", str(row.get("legal_entity_id", UUID.class)));
    snapshot.put("leaderMembershipId", str(row.get("leader_membership_id", UUID.class)));
    return snapshot;
  }

  /** 法人快照：金额/数量口径（amountScale / quantityScale）改动必须可追溯。 */
  private Map<String, Object> legalEntitySnapshot(UUID id) {
    var row = sql.fetchOne("select * from legal_entity where id=?", id);
    if (row == null) return null;
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("code", row.get("code", String.class));
    snapshot.put("legalName", row.get("legal_name", String.class));
    snapshot.put("registrationCountry", row.get("registration_country", String.class));
    snapshot.put("registrationNo", row.get("registration_no", String.class));
    snapshot.put("taxNo", row.get("tax_no", String.class));
    snapshot.put("baseCurrency", row.get("base_currency", String.class));
    // address 会被 AuditRedactor 按「完整客户地址」口径打码：审计需要知道它变了，不需要原文。
    snapshot.put("address", row.get("address", String.class));
    snapshot.put("amountScale", row.get("amount_scale", Integer.class));
    snapshot.put("quantityScale", row.get("quantity_scale", Integer.class));
    snapshot.put("rounding", row.get("rounding", String.class));
    snapshot.put("status", row.get("status", String.class));
    snapshot.put("version", row.get("version", Long.class));
    return snapshot;
  }

  /**
   * 子租户快照。
   *
   * <p>刻意不直接查 {@code tenant} 表：identity_app 对它没有 GRANT（V3 只给了
   * {@code child_tenants} 这个 SECURITY DEFINER 函数），直查会以 42501 失败，
   * 连带整个「创建子租户」操作失败。审计快照不是绕过授权边界的理由。
   *
   * <p>{@code child_tenants(hash)} 以会话成员关系为条件解析租户，不采信调用方传入的 tenant 头。
   */
  private Map<String, Object> childSnapshot(UUID id, String auth) {
    var row =
        sql.fetchOne("select code,name,status from child_tenants(?) where id=?", hash(auth), id);
    if (row == null) return null;
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("code", row.get("code", String.class));
    snapshot.put("name", row.get("name", String.class));
    snapshot.put("status", row.get("status", String.class));
    return snapshot;
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private void emitOutbox(IdentityController.Access a, UUID id, String action) {
    sql.execute(
        "insert into identity_outbox(id,tenant_id,resource_id,event_type) values(?,?,?,?)",
        Ids.next(),
        a.tenantId(),
        id,
        action);
  }

  private String hash(String auth) {
    return IdentityController.hash(identity.token(auth));
  }

  private org.jooq.Record role(UUID id) {
    var r = sql.fetchOne("select * from role where id=?", id);
    if (r == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "角色不存在");
    return r;
  }

  private org.jooq.Record member(UUID id) {
    var r = sql.fetchOne("select * from membership where id=?", id);
    if (r == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "成员不存在");
    return r;
  }

  private boolean owner(UUID id) {
    return sql.fetchExists(
        sql.selectOne()
            .from("role_assignment ra join role r on r.id=ra.role_id and r.tenant_id=ra.tenant_id")
            .where("ra.membership_id=? and r.code='OWNER'", id));
  }

  @GetMapping("/permissions")
  public List<PermissionView> permissions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "identity.role.read",
        false,
        a ->
            sql.fetch(
                    "select p.*,r.name from permission p join page_resource r on r.code=p.page_code order by p.page_code,p.action")
                .map(
                    r ->
                        new PermissionView(
                            r.get("code", String.class),
                            r.get("page_code", String.class),
                            r.get("name", String.class),
                            r.get("action", String.class))));
  }

  @GetMapping("/roles")
  public List<RoleView> roles(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "identity.role.read",
        false,
        a ->
            sql.fetch("select * from role order by code")
                .map(
                    r ->
                        new RoleView(
                            r.get("id", UUID.class),
                            r.get("code", String.class),
                            r.get("name", String.class),
                            r.get("status", String.class),
                            r.get("code", String.class).equals("OWNER"),
                            sql.fetch(
                                    "select permission_code from role_permission where role_id=? order by permission_code",
                                    r.get("id"))
                                .getValues("permission_code", String.class))));
  }

  private void validatePermissions(RoleInput input) {
    check(!input.code().equals("OWNER"), "所有者角色由专用操作维护");
    var known = sql.fetch("select code from permission").getValues("code", String.class);
    check(known.containsAll(input.permissions()), "存在未知权限");
    check(new HashSet<>(input.permissions()).size() == input.permissions().size(), "权限不能重复");
    for (String p : input.permissions()) {
      String read = p.substring(0, p.lastIndexOf('.')) + ".read";
      check(input.permissions().contains(read), "配置操作权限时必须同时允许该页面查看");
    }
  }

  private UUID saveRole(String auth, IdentityController.Access a, UUID id, RoleInput input) {
    validatePermissions(input);
    // 先取变更前快照：更新与删除都会改掉 role / role_permission，取晚了就只剩新值。
    var before = id == null ? null : roleSnapshot(id);
    if (id == null) {
      id = Ids.next();
      sql.execute(
          "insert into role(id,tenant_id,code,name,status) values(?,?,?,?,?)",
          id,
          a.tenantId(),
          input.code(),
          input.name().trim(),
          input.status());
    } else {
      require(!role(id).get("code", String.class).equals("OWNER"), "所有者角色受保护");
      sql.execute(
          "update role set code=?,name=?,status=? where id=?",
          input.code(),
          input.name().trim(),
          input.status(),
          id);
      sql.execute("delete from role_permission where role_id=?", id);
    }
    if (input.permissions().stream().anyMatch(p -> !p.startsWith("catalog.")))
      check(
          !sql.fetchExists(
              sql.selectOne().from("role_assignment").where("role_id=? and scope_mode<>'ALL'", id)),
          "管理权限只能绑定租户内全部范围");
    for (String p : input.permissions())
      sql.execute("insert into role_permission values(?,?,?)", a.tenantId(), id, p);
    audit(
        a,
        auth,
        "identity.role",
        id,
        input.code(),
        "RoleSaved",
        before,
        roleSnapshot(id));
    return id;
  }

  @PostMapping("/roles")
  public UUID createRole(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RoleInput input) {
    return call(auth, "identity.role.create", true, a -> saveRole(auth, a, null, input));
  }

  @PutMapping("/roles/{id}")
  public UUID updateRole(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody RoleInput input) {
    return call(auth, "identity.role.update", true, a -> saveRole(auth, a, id, input));
  }

  @DeleteMapping("/roles/{id}")
  public void deleteRole(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "identity.role.delete",
        true,
        a -> {
          require(!role(id).get("code", String.class).equals("OWNER"), "所有者角色受保护");
          var before = roleSnapshot(id);
          sql.execute("delete from role_assignment where role_id=?", id);
          sql.execute("delete from role_permission where role_id=?", id);
          sql.execute("delete from role where id=?", id);
          audit(
              a,
              auth,
              "identity.role",
              id,
              str(before.get("code")),
              "RoleDeleted",
              before,
              null);
          return null;
        });
  }

  @GetMapping("/members")
  public List<MemberView> members(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "identity.member.read",
        false,
        a ->
            sql.fetch(
                    "select m.*,n.username from membership m join member_names(?) n on n.id=m.id order by n.username",
                    hash(auth))
                .map(
                    r -> {
                      UUID id = r.get("id", UUID.class);
                      return new MemberView(
                          id,
                          r.get("user_id", UUID.class),
                          r.get("username", String.class),
                          r.get("status", String.class),
                          r.get("department_id", UUID.class),
                          sql.fetch(
                                  "select role_id,scope_mode from role_assignment where membership_id=?",
                                  id)
                              .map(
                                  x ->
                                      new AssignmentInput(
                                          x.get("role_id", UUID.class),
                                          x.get("scope_mode", String.class))),
                          owner(id));
                    }));
  }

  private void assignments(
      IdentityController.Access a, UUID id, UUID department, List<AssignmentInput> inputs) {
    if (department != null)
      check(sql.fetchExists(sql.selectOne().from("department").where("id=?", department)), "部门不存在");
    check(
        inputs.stream().map(AssignmentInput::roleId).distinct().count() == inputs.size(), "角色不能重复");
    for (var input : inputs) {
      var role = role(input.roleId());
      require(!role.get("code", String.class).equals("OWNER"), "请使用转移所有者操作");
      check(role.get("status", String.class).equals("ACTIVE"), "不能分配停用角色");
      check(!input.scopeMode().equals("DEPARTMENT") || department != null, "本部门范围需要先设置成员部门");
      if (!input.scopeMode().equals("ALL"))
        check(
            !sql.fetchExists(
                sql.selectOne()
                    .from("role_permission")
                    .where("role_id=? and permission_code not like 'catalog.%'", input.roleId())),
            "管理权限只能绑定租户内全部范围");
    }
    sql.execute("delete from role_assignment where membership_id=?", id);
    for (var input : inputs) {
      // 显式列名 + 生效时间带 ?::timestamptz 且**不在末位**：jOOQ 对 VALUES 列表里的裸 ? 按 varchar
      // 推断类型，OffsetDateTime 会被当字符串塞进去且不报错（值偏一个时区偏移）；
      // 而把 cast 放到列表末位会让 jOOQ 整体绑定错位一格。两处都踩过，所以顺序是刻意的，不要「顺手整理」。
      sql.execute(
          "insert into role_assignment(tenant_id,membership_id,role_id,effective_from,scope_mode,assigned_by)"
              + " values(?,?,?,?::timestamptz,?,?)",
          a.tenantId(),
          id,
          input.roleId(),
          OffsetDateTime.now(),
          input.scopeMode(),
          a.userId());
      // V47 起「有角色必有范围」是库内延迟约束触发的硬规则。这里 DEPARTMENT 范围就是成员自己的部门，
      // 显式落成一条对象范围，库里就不会出现一条没有范围的分配。成员换部门时 updateMember 会整体重写
      // 本方法写下的行，所以范围不会滞留在旧部门上。
      if (input.scopeMode().equals("DEPARTMENT"))
        sql.execute(
            "insert into role_assignment_scope(tenant_id,membership_id,role_id,scope_type,scope_id,scope_code)"
                + " values(?,?,?,'DEPARTMENT',?,?)",
            a.tenantId(),
            id,
            input.roleId(),
            department,
            sql.fetchOne("select code from department where id=?", department).get("code", String.class));
    }
  }

  private UUID user(String auth, String username, String password) {
    String encrypted = password == null ? null : new BCryptPasswordEncoder().encode(password);
    return sql.fetchOne(
            "select member_user(?,?,?,?) as id", hash(auth), username.trim(), Ids.next(), encrypted)
        .get("id", UUID.class);
  }

  @PostMapping("/members")
  public UUID createMember(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody MemberInput input) {
    return call(
        auth,
        "identity.member.create",
        true,
        a -> {
          UUID uid = user(auth, input.username(), input.password()), id = Ids.next();
          sql.execute(
              "insert into membership(id,tenant_id,user_id,status,department_id) values(?,?,?,?,?)",
              id,
              a.tenantId(),
              uid,
              input.status(),
              input.departmentId());
          assignments(a, id, input.departmentId(), input.assignments());
          audit(
              a,
              auth,
              "identity.member",
              id,
              input.username().trim(),
              "MemberCreated",
              null,
              memberSnapshot(id, auth));
          return id;
        });
  }

  @PutMapping("/members/{id}")
  public void updateMember(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody MemberUpdate input) {
    call(
        auth,
        "identity.member.update",
        true,
        a -> {
          member(id);
          var before = memberSnapshot(id, auth);
          if (owner(id)) {
            check(input.status().equals("ACTIVE"), "所有者必须保持启用");
            var existing =
                sql.fetch(
                        "select role_id,scope_mode from role_assignment where membership_id=?", id)
                    .map(
                        r ->
                            new AssignmentInput(
                                r.get("role_id", UUID.class), r.get("scope_mode", String.class)));
            check(
                new HashSet<>(existing).equals(new HashSet<>(input.assignments()))
                    && existing.size() == input.assignments().size(),
                "请使用转移所有者操作调整角色");
          } else assignments(a, id, input.departmentId(), input.assignments());
          sql.execute(
              "update membership set status=?,department_id=? where id=?",
              input.status(),
              input.departmentId(),
              id);
          audit(
              a,
              auth,
              "identity.member",
              id,
              username(id, auth),
              "MemberUpdated",
              before,
              memberSnapshot(id, auth));
          return null;
        });
  }

  @DeleteMapping("/members/{id}")
  public void removeMember(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "identity.member.delete",
        true,
        a -> {
          member(id);
          require(!owner(id), "不能移除所有者");
          var before = memberSnapshot(id, auth);
          sql.execute("delete from role_assignment where membership_id=?", id);
          // Removing the identity also releases its department, otherwise the department could
          // never be deleted again.
          sql.execute("update membership set status='INACTIVE',department_id=null where id=?", id);
          audit(
              a,
              auth,
              "identity.member",
              id,
              username(id, auth),
              "MemberRemoved",
              before,
              memberSnapshot(id, auth));
          return null;
        });
  }

  @PostMapping("/owner-transfer")
  public void transfer(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody OwnerTransfer input) {
    call(
        auth,
        "identity.member.update",
        true,
        a -> {
          var target = member(input.membershipId());
          check(target.get("status", String.class).equals("ACTIVE"), "目标成员必须启用");
          check(!a.membershipId().equals(input.membershipId()), "请选择其他成员");
          UUID rid = sql.fetchOne("select id from role where code='OWNER'").get("id", UUID.class);
          sql.execute(
              "insert into role_assignment(tenant_id,membership_id,role_id,scope_mode,assigned_by)"
                  + " values(?,?,?,'ALL',?)"
                  + " on conflict(tenant_id,membership_id,role_id) do update"
                  + " set scope_mode='ALL',status='ACTIVE',revoked_at=null,revoked_by=null",
              a.tenantId(),
              input.membershipId(),
              rid,
              a.userId());
          sql.execute(
              "delete from role_assignment where membership_id=? and role_id=?",
              a.membershipId(),
              rid);
          audit(
              a,
              auth,
              "identity.member",
              input.membershipId(),
              username(input.membershipId(), auth),
              "OwnerTransferred",
              null,
              memberSnapshot(input.membershipId(), auth));
          return null;
        });
  }

  @GetMapping("/departments")
  public List<DepartmentView> departments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "identity.department.read",
        false,
        a -> {
          var nodes = new LinkedHashMap<UUID, DepartmentNode>();
          for (var r : sql.fetch("select * from department_tree(?)", hash(auth))) {
            nodes.put(
                r.get("id", UUID.class),
                new DepartmentNode(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("parent_id", UUID.class),
                    r.get("legal_entity_id", UUID.class),
                    r.get("legal_name", String.class),
                    r.get("leader_membership_id", UUID.class),
                    r.get("leader_name", String.class),
                    r.get("member_count", Integer.class)));
          }
          // 行序即 depth, code, name，父节点总在子节点之前入表，直接挂到父节点即可保持排序。
          var roots = new ArrayList<DepartmentNode>();
          for (var node : nodes.values()) {
            var parent = node.parentId == null ? null : nodes.get(node.parentId);
            if (parent == null) roots.add(node);
            else parent.children.add(node);
          }
          return roots.stream().map(DepartmentNode::view).toList();
        });
  }

  /** 建树用的可变节点；视图不可变，children 在装配完成后冻结。 */
  private static final class DepartmentNode {
    private final UUID id;
    private final String code;
    private final String name;
    private final UUID parentId;
    private final UUID legalEntityId;
    private final String legalEntityName;
    private final UUID leaderMembershipId;
    private final String leaderName;
    private final int memberCount;
    private final List<DepartmentNode> children = new ArrayList<>();

    private DepartmentNode(
        UUID id,
        String code,
        String name,
        UUID parentId,
        UUID legalEntityId,
        String legalEntityName,
        UUID leaderMembershipId,
        String leaderName,
        int memberCount) {
      this.id = id;
      this.code = code;
      this.name = name;
      this.parentId = parentId;
      this.legalEntityId = legalEntityId;
      this.legalEntityName = legalEntityName;
      this.leaderMembershipId = leaderMembershipId;
      this.leaderName = leaderName;
      this.memberCount = memberCount;
    }

    private DepartmentView view() {
      return new DepartmentView(
          id,
          code,
          name,
          parentId,
          legalEntityId,
          legalEntityName,
          leaderMembershipId,
          leaderName,
          memberCount,
          children.stream().map(DepartmentNode::view).toList());
    }
  }

  private boolean exists(String statement, Object... bindings) {
    return sql.fetchOne(statement, bindings) != null;
  }

  /**
   * 引用存在性校验。租户上下文由 RLS 决定，所以跨租户 ID 在这里表现为「不存在」——
   * 应用层给出可读错误，DB 层的复合外键再兜一道。
   */
  private void references(UUID parentId, UUID legalEntityId, UUID leaderMembershipId) {
    if (parentId != null)
      check(
          exists("select 1 from department where id=?", parentId), "上级部门不存在或不属于当前租户");
    if (legalEntityId != null)
      check(
          exists("select 1 from legal_entity where id=?", legalEntityId),
          "所属法人不存在或不属于当前租户");
    if (leaderMembershipId != null)
      check(
          exists("select 1 from membership where id=?", leaderMembershipId),
          "负责人不存在或不属于当前租户");
  }

  @PostMapping("/departments")
  public UUID createDepartment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DepartmentInput input) {
    return call(
        auth,
        "identity.department.create",
        true,
        a -> {
          references(input.parentId(), input.legalEntityId(), input.leaderMembershipId());
          UUID id = Ids.next();
          sql.execute(
              "insert into department(id,tenant_id,code,name,parent_id,legal_entity_id,leader_membership_id)"
                  + " values (?,?,?,?,?,?,?)",
              id,
              a.tenantId(),
              input.code().trim(),
              input.name().trim(),
              input.parentId(),
              input.legalEntityId(),
              input.leaderMembershipId());
          audit(
              a,
              auth,
              "identity.department",
              id,
              input.code().trim(),
              "DepartmentCreated",
              null,
              departmentSnapshot(id));
          return id;
        });
  }

  @PutMapping("/departments/{id}")
  public void updateDepartment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DepartmentInput input) {
    call(
        auth,
        "identity.department.update",
        true,
        a -> {
          // parentId 在这里被忽略：层级调整是独立的 DepartmentMoved 动作，
          // 防环校验只存在于那一处，不重复实现。
          references(null, input.legalEntityId(), input.leaderMembershipId());
          var before = departmentSnapshot(id);
          check(
              sql.execute(
                      "update department set code=?, name=?, legal_entity_id=?, leader_membership_id=?"
                          + " where id=?",
                      input.code().trim(),
                      input.name().trim(),
                      input.legalEntityId(),
                      input.leaderMembershipId(),
                      id)
                  == 1,
              "部门不存在");
          audit(
              a,
              auth,
              "identity.department",
              id,
              input.code().trim(),
              "DepartmentUpdated",
              before,
              departmentSnapshot(id));
          return null;
        });
  }

  /** 拖拽移动：只改 parent_id，并拒绝把节点挂到自己或自己的子孙下面（否则形成环）。 */
  @PutMapping("/departments/{id}/parent")
  public void moveDepartment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DepartmentMove input) {
    call(
        auth,
        "identity.department.update",
        true,
        a -> {
          check(exists("select 1 from department where id=?", id), "部门不存在");
          references(input.parentId(), null, null);
          var before = departmentSnapshot(id);
          // 成环的条件是「新父节点在当前节点的子树内」，等价于「当前节点是新父节点的祖先」。
          // 所以这里沿 parent_id 向上走新父节点的祖先链，看是否碰到自己——
          // 直接把父挂到自己也算：新父节点的祖先链第一个就是它自己。
          if (input.parentId() != null)
            check(
                !sql.fetchOne(
                        "with recursive ancestors as ("
                            + " select id, parent_id from department where id = ?"
                            + " union all"
                            + " select d.id, d.parent_id from department d"
                            + "   join ancestors a on d.id = a.parent_id)"
                            + " select exists(select 1 from ancestors where id = ?) as cycle",
                        input.parentId(),
                        id)
                    .get("cycle", Boolean.class),
                "不能把部门移动到自身或自己的下级部门下");
          sql.execute("update department set parent_id=? where id=?", input.parentId(), id);
          audit(
              a,
              auth,
              "identity.department",
              id,
              (String) before.get("code"),
              "DepartmentMoved",
              before,
              departmentSnapshot(id));
          return null;
        });
  }

  /**
   * 删除语义：有子部门或仍有成员挂靠就拒绝，并说明具体数量——不静默级联。
   * 组织树一旦被级联删掉，成员的部门归属和 17.3.2.2 的数据范围都会失去依据。
   */
  @DeleteMapping("/departments/{id}")
  public void deleteDepartment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "identity.department.delete",
        true,
        a -> {
          check(exists("select 1 from department where id=?", id), "部门不存在");
          var before = departmentSnapshot(id);
          int children =
              sql.fetchOne("select count(*) from department where parent_id=?", id)
                  .get(0, Integer.class);
          if (children > 0)
            reject("该部门下仍有 " + children + " 个子部门，请先移动或删除这些子部门");
          int members =
              sql.fetchOne("select count(*) from membership where department_id=?", id)
                  .get(0, Integer.class);
          if (members > 0) reject("该部门仍挂靠 " + members + " 名成员，请先调整成员归属");
          check(sql.execute("delete from department where id=?", id) == 1, "部门不存在");
          audit(
              a,
              auth,
              "identity.department",
              id,
              (String) before.get("code"),
              "DepartmentDeleted",
              before,
              null);
          return null;
        });
  }

  @GetMapping("/children")
  public List<ChildView> children(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "tenant.child.read",
        false,
        a ->
            sql.fetch("select * from child_tenants(?) order by code", hash(auth))
                .map(
                    r ->
                        new ChildView(
                            r.get("id", UUID.class),
                            r.get("code", String.class),
                            r.get("name", String.class),
                            r.get("status", String.class))));
  }

  @PostMapping("/children")
  public UUID createChild(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ChildInput input) {
    return call(
        auth,
        "tenant.child.create",
        true,
        a -> {
          UUID id = Ids.next(), uid = user(auth, input.ownerUsername(), input.ownerPassword());
          sql.fetch(
              "select create_child(?,?,?,?,?,?,?,?)",
              hash(auth),
              id,
              input.code(),
              input.name().trim(),
              uid,
              Ids.next(),
              Ids.next(),
              5);
          audit(
              a,
              auth,
              "tenant.child",
              id,
              input.code(),
              "ChildCreated",
              null,
              childSnapshot(id, auth));
          return id;
        });
  }

  @PutMapping("/children/{id}")
  public void updateChild(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ChildUpdate input) {
    call(
        auth,
        "tenant.child.update",
        true,
        a -> {
          var before = childSnapshot(id, auth);
          sql.fetch(
              "select update_child(?,?,?,?)", hash(auth), id, input.name().trim(), input.status());
          audit(
              a,
              auth,
              "tenant.child",
              id,
              (String) before.get("code"),
              "ChildUpdated",
              before,
              childSnapshot(id, auth));
          return null;
        });
  }

  // ------------------------------------------------------------------
  // 17.1.2 法人档案与本位币
  // ------------------------------------------------------------------

  private static final String LEGAL_ENTITY_READ = "identity.legal_entity.read";

  @GetMapping("/legal-entities")
  public List<LegalEntityView> legalEntities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        LEGAL_ENTITY_READ,
        false,
        a ->
            sql.fetch(
                    "select e.id,e.code,e.legal_name,e.registration_country,e.registration_no,e.tax_no,"
                        + " e.base_currency,e.address,e.amount_scale,e.quantity_scale,e.rounding,e.status,e.version,"
                        + " (select count(*) from legal_entity_reference r where r.tenant_id=e.tenant_id"
                        + "  and r.legal_entity_id=e.id) reference_count"
                        + " from legal_entity e order by e.status='ACTIVE' desc,e.code")
                .stream()
                .map(
                    r ->
                        new LegalEntityView(
                            r.get("id", UUID.class),
                            r.get("code", String.class),
                            r.get("legal_name", String.class),
                            r.get("registration_country", String.class),
                            r.get("registration_no", String.class),
                            r.get("tax_no", String.class),
                            r.get("base_currency", String.class),
                            r.get("address", String.class),
                            r.get("amount_scale", Integer.class),
                            r.get("quantity_scale", Integer.class),
                            r.get("rounding", String.class),
                            r.get("status", String.class),
                            r.get("version", Long.class),
                            r.get("reference_count", Integer.class)))
                .toList());
  }

  @PostMapping("/legal-entities")
  public UUID legalEntityCreate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LegalEntityInput input) {
    return call(
        auth,
        "identity.legal_entity.create",
        true,
        a -> {
          UUID id = Ids.next();
          sql.execute(
              "insert into legal_entity(id,tenant_id,code,legal_name,registration_country,registration_no,"
                  + " tax_no,base_currency,address,amount_scale,quantity_scale,rounding,status)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
              id,
              a.tenantId(),
              input.code().trim(),
              input.legalName().trim(),
              input.registrationCountry() == null ? "CN" : input.registrationCountry().trim(),
              trim(input.registrationNo()),
              trim(input.taxNo()),
              input.baseCurrency().trim().toUpperCase(),
              trim(input.address()),
              input.amountScale() == null ? 2 : input.amountScale(),
              input.quantityScale() == null ? 3 : input.quantityScale(),
              input.rounding() == null ? "HALF_UP" : input.rounding(),
              input.status());
          audit(
              a,
              auth,
              "identity.legal_entity",
              id,
              input.code().trim(),
              "LegalEntityCreated",
              null,
              legalEntitySnapshot(id));
          return id;
        });
  }

  @PutMapping("/legal-entities/{id}")
  public void legalEntityUpdate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody LegalEntityInput input) {
    call(
        auth,
        "identity.legal_entity.update",
        true,
        a -> {
          var before = legalEntitySnapshot(id);
          int updated =
              sql.execute(
                  "update legal_entity set code=?,legal_name=?,registration_country=?,registration_no=?,"
                      + " tax_no=?,base_currency=?,address=?,amount_scale=?,quantity_scale=?,rounding=?,"
                      + " status=?,version=version+1,updated_at=now() where tenant_id=? and id=?",
                  input.code().trim(),
                  input.legalName().trim(),
                  input.registrationCountry() == null ? "CN" : input.registrationCountry().trim(),
                  trim(input.registrationNo()),
                  trim(input.taxNo()),
                  input.baseCurrency().trim().toUpperCase(),
                  trim(input.address()),
                  input.amountScale() == null ? 2 : input.amountScale(),
                  input.quantityScale() == null ? 3 : input.quantityScale(),
                  input.rounding() == null ? "HALF_UP" : input.rounding(),
                  input.status(),
                  a.tenantId(),
                  id);
          check(updated == 1, "法人不存在或不属于当前租户");
          audit(
              a,
              auth,
              "identity.legal_entity",
              id,
              input.code().trim(),
              "LegalEntityUpdated",
              before,
              legalEntitySnapshot(id));
          return null;
        });
  }

  /**
   * 删除法人。
   *
   * <p>仍被业务对象引用时拒绝并提示先停用——货权与记账主体不能凭空消失，
   * 而停用是可逆且不会破坏历史单据的处置方式。
   */
  @DeleteMapping("/legal-entities/{id}")
  public void legalEntityDelete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth, @PathVariable UUID id) {
    call(
        auth,
        "identity.legal_entity.delete",
        true,
        a -> {
          var before = legalEntitySnapshot(id);
          try {
            int deleted = sql.execute("delete from legal_entity where tenant_id=? and id=?", a.tenantId(), id);
            check(deleted == 1, "法人不存在或不属于当前租户");
          } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw IdentityController.error(
                HttpStatus.CONFLICT, "该法人仍被业务对象引用，请先停用而不是删除");
          }
          audit(
              a,
              auth,
              "identity.legal_entity",
              id,
              (String) before.get("code"),
              "LegalEntityDeleted",
              before,
              null);
          return null;
        });
  }

  /** 登记引用：让删除保护基于真实引用事实，而不是靠约定。 */
  @PostMapping("/legal-entities/references")
  public void legalEntityReference(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LegalEntityReferenceInput input) {
    call(
        auth,
        "identity.legal_entity.update",
        true,
        a -> {
          check(
              sql.fetchExists(
                  sql.selectOne()
                      .from("legal_entity")
                      .where("tenant_id=? and id=?", a.tenantId(), input.legalEntityId())),
              "法人不存在或不属于当前租户");
          // 显式 Object[]：UUID 参数与 execute(String, QueryPart...) 重载会产生歧义
          sql.execute(
              "insert into legal_entity_reference(tenant_id,legal_entity_id,source,reference_id)"
                  + " values (?,?,?,?) on conflict do nothing",
              new Object[] {a.tenantId(), input.legalEntityId(), input.source(), input.referenceId()});
          return null;
        });
  }

  // ------------------------------------------------------------------
  // 17.2.1.2 部门负责人
  //
  // 上游是 17.2.1.1 的部门树：负责人挂在部门上（department_leader 唯一键 tenant+department），
  // 而不是另起一套组织节点——否则「谁能批」与「部门在哪」会各自漂移。
  //
  // 负责人可为空是蓝图硬约束，所以列表走 LEFT JOIN，未指定负责人的部门照样出现；
  // 审批链返回 requiresFallback，让下游 17.4 知道该去配兜底人而不是把单据卡住。
  // ------------------------------------------------------------------

  private static final String LEADER_READ = "identity.department_leader.read";

  /** 部门负责人 + 代管 + 临时授权的成员可读名；只解析调用者自己可见的成员，不采信 tenant 头。 */
  private String memberName(UUID membershipId, String auth) {
    var names =
        sql.fetch(
            "select username from member_names(?) n join membership m on m.id=n.id where n.id=?",
            hash(auth),
            membershipId);
    return names.isEmpty() ? null : names.get(0).get("username", String.class);
  }

  /** 负责人快照：授权口径（范围 + 额度 + 币种）必须进审计，否则事后无法解释「当时谁能批多少」。 */
  private Map<String, Object> leaderSnapshot(UUID departmentId) {
    var row =
        sql.fetchOne(
            "select * from department_leader where department_id=?", departmentId);
    if (row == null) return null;
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("membershipId", str(row.get("membership_id", UUID.class)));
    snapshot.put("scopeMode", row.get("scope_mode", String.class));
    snapshot.put("approvalLimit", str(row.get("approval_limit", BigDecimal.class)));
    snapshot.put("approvalCurrency", row.get("approval_currency", String.class));
    snapshot.put("note", row.get("note", String.class));
    return snapshot;
  }

  /** 代管 / 临时授权各自一条快照：区间与被授权人都是事后追责的关键事实。 */
  private Map<String, Object> grantSnapshot(String table, UUID id) {
    var row = sql.fetchOne("select * from " + table + " where id=?", id);
    if (row == null) return null;
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("departmentId", str(row.get("department_id", UUID.class)));
    snapshot.put("membershipId", str(row.get("membership_id", UUID.class)));
    snapshot.put("startsAt", str(row.get("starts_at", OffsetDateTime.class)));
    snapshot.put("endsAt", str(row.get("ends_at", OffsetDateTime.class)));
    snapshot.put("reason", row.get("reason", String.class));
    if (table.equals("department_leader_grant")) {
      snapshot.put("approvalLimit", str(row.get("approval_limit", BigDecimal.class)));
      snapshot.put("approvalCurrency", row.get("approval_currency", String.class));
    }
    return snapshot;
  }

  /**
   * 负责人清单。
   *
   * <p>走 {@code department_leaders(hash)}：{@code identity_app} 对这些表只有租户内的读写权，
   * 用户名必须在 SECURITY DEFINER 里经 {@code member_names} 解析，否则查询会以 42501 被拒
   * （与 17.2.1.1 的 {@code department_tree} 同一套约定）。
   */
  @GetMapping("/department-leaders")
  public List<DepartmentLeaderView> departmentLeaders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        LEADER_READ,
        false,
        a ->
            sql.fetch("select * from department_leaders(?)", hash(auth))
                .stream()
                .map(
                    r ->
                        new DepartmentLeaderView(
                            r.get("leader_id", UUID.class),
                            r.get("department_id", UUID.class),
                            r.get("department_code", String.class),
                            r.get("department_name", String.class),
                            r.get("membership_id", UUID.class),
                            r.get("username", String.class),
                            r.get("scope_mode", String.class),
                            r.get("approval_limit", BigDecimal.class),
                            r.get("approval_currency", String.class),
                            r.get("note", String.class),
                            Arrays.asList(r.get("proxy_ids", UUID[].class)),
                            Arrays.asList(r.get("grant_ids", UUID[].class)),
                            r.get("created_at", OffsetDateTime.class),
                            r.get("updated_at", OffsetDateTime.class)))
                .toList());
  }

  /**
   * 指定 / 更换部门负责人，并同时设定管理范围与审批权限。
   *
   * <p>同一个端点同时做三件事（谁负责、管多大、能批多少），因为这三者是一次授权决定：
   * 分成三个端点就会出现「先把权限开大、再补人」这种中间态，审批链会在那个窗口里放行。
   *
   * <p>唯一键 (tenant_id, department_id) 决定这是 upsert 而不是新建。
   */
  @PutMapping("/departments/{id}/leader")
  public UUID setDepartmentLeader(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DepartmentLeaderInput input) {
    return call(
        auth,
        "identity.department_leader.create",
        true,
        a -> {
          check(exists("select 1 from department where id=?", id), "部门不存在");
          var member =
              sql.fetchOne(
                  "select status from membership where id=? and tenant_id=?",
                  input.membershipId(),
                  a.tenantId());
          check(member != null, "负责人不存在或不属于当前租户");
          check(member.get("status", String.class).equals("ACTIVE"), "负责人必须处于启用状态");
          // 额度与币种同进同出：只有数字没有币种，下游 17.4 无从判断这是多少钱。
          check(
              (input.approvalLimit() == null) == (input.approvalCurrency() == null),
              "审批额度与币种必须同时填写或同时留空");
          var before = leaderSnapshot(id);
          UUID leaderId =
              sql.fetchOne(
                      "insert into department_leader(id,tenant_id,department_id,membership_id,scope_mode,"
                          + " approval_limit,approval_currency,note) values(?,?,?,?,?,?::numeric,?,?)"
                          + " on conflict(tenant_id,department_id) do update set"
                          + " membership_id=excluded.membership_id,scope_mode=excluded.scope_mode,"
                          + " approval_limit=excluded.approval_limit,"
                          + " approval_currency=excluded.approval_currency,note=excluded.note,"
                          + " updated_at=now() returning id",
                      Ids.next(),
                      a.tenantId(),
                      id,
                      input.membershipId(),
                      input.scopeMode(),
                      input.approvalLimit(),
                      input.approvalCurrency(),
                      trim(input.note()))
                  .get("id", UUID.class);
          audit(
              a,
              auth,
              "identity.department_leader",
              leaderId,
              memberName(input.membershipId(), auth),
              before == null ? "DepartmentLeaderAssigned" : "DepartmentLeaderChanged",
              before,
              leaderSnapshot(id));
          return leaderId;
        });
  }

  /** 撤销负责人：部门回到「可为空」状态，代管与临时授权一并清掉——它们依附于负责人而存在。 */
  @DeleteMapping("/departments/{id}/leader")
  public void clearDepartmentLeader(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "identity.department_leader.delete",
        true,
        a -> {
          check(exists("select 1 from department where id=?", id), "部门不存在");
          var before = leaderSnapshot(id);
          check(before != null, "该部门尚未指定负责人");
          // 先报数量再拒绝，而不是静默级联：撤销负责人会让已配置的审批链立刻失去归属，
          // 运营需要知道「连带作废了几条代管 / 临时授权」再决定。
          int proxies =
              sql.fetchOne(
                      "select count(*) from department_leader_proxy where department_id=?", id)
                  .get(0, Integer.class);
          int grants =
              sql.fetchOne(
                      "select count(*) from department_leader_grant where department_id=?", id)
                  .get(0, Integer.class);
          sql.execute("delete from department_leader_proxy where department_id=?", id);
          sql.execute("delete from department_leader_grant where department_id=?", id);
          check(sql.execute("delete from department_leader where department_id=?", id) == 1, "负责人已被撤销");
          audit(
              a,
              auth,
              "identity.department_leader",
              id,
              (String) before.get("membershipId"),
              "DepartmentLeaderCleared",
              before,
              Map.of("clearedProxies", proxies, "clearedGrants", grants));
          return null;
        });
  }

  /**
   * 代管：负责人不在时由谁代行。
   *
   * <p>区间允许开口（长期代管），但不允许闭区间——起止相同的「代管」在任何时刻都不生效，
   * 却会在列表里占一行，看起来像配了个授权其实没有。
   */
  @PostMapping("/departments/{id}/leader/proxies")
  public UUID createLeaderProxy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody LeaderProxyInput input) {
    return call(
        auth,
        "identity.department_leader.create",
        true,
        a -> {
          check(exists("select 1 from department where id=?", id), "部门不存在");
          check(leaderSnapshot(id) != null, "请先指定部门负责人再配置代管");
          checkActiveMember(a, input.membershipId(), "代管人");
          OffsetDateTime startsAt = now();
          OffsetDateTime endsAt = input.endsAt();
          check(endsAt == null || endsAt.isAfter(startsAt), "代管结束时间必须晚于开始时间");
          UUID proxyId = Ids.next();
          // 显式 ::timestamptz：jOOQ 在纯文本语句里无法为 OffsetDateTime 推断类型，
          // 不写 cast 会被驱动按 varchar 处理并报类型错误（见 AuditLog 的同款注释）。
          sql.execute(
              "insert into department_leader_proxy(id,tenant_id,department_id,membership_id,reason,"
                  + " starts_at,ends_at) values(?,?,?,?,?,?::timestamptz,?::timestamptz)",
              proxyId,
              a.tenantId(),
              id,
              input.membershipId(),
              trim(input.reason()),
              startsAt,
              endsAt);
          audit(
              a,
              auth,
              "identity.department_leader_proxy",
              proxyId,
              memberName(input.membershipId(), auth),
              "DepartmentLeaderProxyCreated",
              null,
              grantSnapshot("department_leader_proxy", proxyId));
          return proxyId;
        });
  }

  @DeleteMapping("/departments/{id}/leader/proxies/{proxyId}")
  public void deleteLeaderProxy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID proxyId) {
    call(
        auth,
        "identity.department_leader.delete",
        true,
        a -> {
          var before = grantSnapshot("department_leader_proxy", proxyId);
          check(before != null && before.get("departmentId").equals(id.toString()), "代管记录不存在");
          sql.execute("delete from department_leader_proxy where id=?", proxyId);
          audit(
              a,
              auth,
              "identity.department_leader_proxy",
              proxyId,
              (String) before.get("membershipId"),
              "DepartmentLeaderProxyDeleted",
              before,
              null);
          return null;
        });
  }

  /**
   * 临时授权：把审批权限在有界区间内借给别人。
   *
   * <p>额度必须有上界（DB 的 NOT NULL + CHECK 兜住），且不得超过负责人的额度——
   * 借出去比本人还大的权限等于凭空造出一个上级，绕过负责人这道闸。
   */
  @PostMapping("/departments/{id}/leader/grants")
  public UUID createLeaderGrant(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody LeaderGrantInput input) {
    return call(
        auth,
        "identity.department_leader.create",
        true,
        a -> {
          var leader = leaderSnapshot(id);
          check(leader != null, "请先指定部门负责人再配置临时授权");
          checkActiveMember(a, input.membershipId(), "被授权人");
          check(input.endsAt().isAfter(input.startsAt()), "临时授权结束时间必须晚于开始时间");
          var leaderLimit = (BigDecimal) decimal(leader.get("approvalLimit"));
          var leaderCurrency = (String) leader.get("approvalCurrency");
          check(
              leaderLimit != null && leaderCurrency != null,
              "负责人未设置审批额度，无法派生临时授权额度");
          check(
              leaderCurrency.equals(input.approvalCurrency()),
              "临时授权币种必须与负责人审批额度币种一致（" + leaderCurrency + "）");
          check(
              input.approvalLimit().compareTo(leaderLimit) <= 0,
              "临时授权额度不得超过负责人的审批额度 " + leaderLimit.stripTrailingZeros().toPlainString());
          UUID grantId = Ids.next();
          sql.execute(
              "insert into department_leader_grant(id,tenant_id,department_id,membership_id,"
                  + " approval_limit,approval_currency,reason,starts_at,ends_at)"
                  + " values(?,?,?,?,?::numeric,?,?,?::timestamptz,?::timestamptz)",
              grantId,
              a.tenantId(),
              id,
              input.membershipId(),
              input.approvalLimit(),
              input.approvalCurrency(),
              trim(input.reason()),
              input.startsAt(),
              input.endsAt());
          audit(
              a,
              auth,
              "identity.department_leader_grant",
              grantId,
              memberName(input.membershipId(), auth),
              "DepartmentLeaderGrantCreated",
              null,
              grantSnapshot("department_leader_grant", grantId));
          return grantId;
        });
  }

  @DeleteMapping("/departments/{id}/leader/grants/{grantId}")
  public void deleteLeaderGrant(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID grantId) {
    call(
        auth,
        "identity.department_leader.delete",
        true,
        a -> {
          var before = grantSnapshot("department_leader_grant", grantId);
          check(before != null && before.get("departmentId").equals(id.toString()), "临时授权记录不存在");
          sql.execute("delete from department_leader_grant where id=?", grantId);
          audit(
              a,
              auth,
              "identity.department_leader_grant",
              grantId,
              (String) before.get("membershipId"),
              "DepartmentLeaderGrantDeleted",
              before,
              null);
          return null;
        });
  }

  /**
   * 审批链引用：某一时刻该部门的审批权落在谁身上。
   *
   * <p>{@code asOf} 可空（取当前时刻），给了就能回答「上周三这条单该谁批」——
   * 这是临时授权与代管存在的意义：权限随时间变化，历史单据必须能还原当时的归属。
   *
   * <p>{@code requiresFallback} 在链为空时为 true：蓝图要求没有负责人的部门由审批流指定兜底人，
   * 这里把信号显式给下游，而不是让它拿到空数组后以为「这条单不需要审批」。
   */
  @GetMapping("/departments/{id}/approval-chain")
  public ApprovalChainView approvalChain(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(value = "asOf", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime asOf) {
    return call(
        auth,
        LEADER_READ,
        false,
        a -> {
          var department = sql.fetchOne("select code,name from department where id=?", id);
          check(department != null, "部门不存在");
          var entries =
              sql.fetch(
                      "select * from department_approval_chain(?,?,?::timestamptz)",
                      hash(auth),
                      id,
                      asOf)
                  .stream()
                  .map(
                      r ->
                          new ApprovalChainEntry(
                              r.get("seq", Integer.class),
                              r.get("source", String.class),
                              r.get("membership_id", UUID.class),
                              r.get("username", String.class),
                              r.get("scope_mode", String.class),
                              r.get("approval_limit", BigDecimal.class),
                              r.get("approval_currency", String.class),
                              r.get("starts_at", OffsetDateTime.class),
                              r.get("ends_at", OffsetDateTime.class)))
                  .toList();
          return new ApprovalChainView(
              id,
              department.get("code", String.class),
              department.get("name", String.class),
              asOf == null ? now() : asOf,
              entries.isEmpty(),
              entries);
        });
  }

  /** 授权对象必须是本租户启用中的成员：跨租户 id 由 RLS 表现为不存在，停用成员不能持有审批权。 */
  private void checkActiveMember(IdentityController.Access a, UUID membershipId, String label) {
    var member =
        sql.fetchOne(
            "select status from membership where id=? and tenant_id=?", membershipId, a.tenantId());
    check(member != null, label + "不存在或不属于当前租户");
    check(member.get("status", String.class).equals("ACTIVE"), label + "必须处于启用状态");
  }

  /** timestamptz 绑定走 OffsetDateTime：与 AuditLog.utc 同一条纪律，见那里的注释。 */
  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 快照里金额是字符串，派生比较时要还原成 BigDecimal——金额不经过 double。 */
  private static Object decimal(Object value) {
    if (value == null) return null;
    return new BigDecimal(String.valueOf(value));
  }

  // ------------------------------------------------------------------
  // 17.1.1.1 租户列表
  // ------------------------------------------------------------------

  /**
   * 可切换租户的一行：名称、标识、状态、角色、最后登录。
   *
   * <p>{@code membershipId} 而不是 {@code tenantId} 才是切换时该传的东西——切换的是「我在这个
   * 租户的身份」，租户 id 是被成员关系带着走的，单独传租户 id 等于允许在没有成员关系的租户
   * 上建立会话。
   */
  public record TenantSummaryView(
      UUID membershipId,
      UUID tenantId,
      String code,
      String name,
      String status,
      List<String> roles,
      @Schema(nullable = true) OffsetDateTime lastLoginAt,
      boolean current) {}

  /**
   * 当前租户上下文档案。
   *
   * <p>{@code managed} 表示当前身份能不能维护基本信息（Owner + ALL），由会话授权直接得出，
   * 前端用它决定要不要渲染表单——服务端仍然会再判一次。
   */
  public record TenantContextView(
      UUID tenantId,
      String code,
      String name,
      String status,
      @Schema(nullable = true) UUID parentTenantId,
      int memberCount,
      int childCount,
      @Schema(nullable = true) OffsetDateTime lastLoginAt,
      boolean managed) {}

  /** 基本信息只开放名称与状态：code 全局唯一且被外部集成引用，改了会让既有映射指向不存在的租户。 */
  public record TenantProfileInput(
      @NotBlank @Size(max = 100) String name,
      @NotNull @Pattern(regexp = "ACTIVE|SUSPENDED") String status) {}

  /**
   * 可切换租户列表。
   *
   * <p>全系统唯一允许的跨租户列表查询路径，租户集合只由 {@code switchable_tenants} 从当前用户的
   * membership 关系推导：端点签名里没有 tenantId 参数，{@code X-Tenant-Id} 头也没有任何读取点，
   * 管理员同样只能看到自己有成员关系的租户。
   */
  @GetMapping("/tenants")
  public List<TenantSummaryView> tenants(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return context(
        auth,
        a ->
            sql.fetch("select * from switchable_tenants(?)", hash(auth))
                .map(
                    r ->
                        new TenantSummaryView(
                            r.get("membership_id", UUID.class),
                            r.get("tenant_id", UUID.class),
                            r.get("code", String.class),
                            r.get("name", String.class),
                            r.get("status", String.class),
                            Arrays.asList(r.get("roles", String[].class)),
                            r.get("last_login_at", OffsetDateTime.class),
                            r.get("is_current", Boolean.class))));
  }

  /** 当前上下文标识：本租户的档案与规模，同样不接受调用方指定租户。 */
  @GetMapping("/tenants/current")
  public TenantContextView tenantContext(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return context(
        auth,
        a -> {
          var r = sql.fetchOne("select * from tenant_context(?)", hash(auth));
          if (r == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "租户上下文不存在");
          return new TenantContextView(
              r.get("tenant_id", UUID.class),
              r.get("code", String.class),
              r.get("name", String.class),
              r.get("status", String.class),
              r.get("parent_tenant_id", UUID.class),
              r.get("member_count", Integer.class),
              r.get("child_count", Integer.class),
              r.get("last_login_at", OffsetDateTime.class),
              a.owner());
        });
  }

  /**
   * 租户管理员维护本租户基本信息。
   *
   * <p>归属判定在 {@code update_tenant_profile} 内部走 {@code administration_tenant}（Owner + ALL），
   * 与成员管理、子租户同一道闸；非管理员会拿到 403 而不是静默成功。
   *
   * <p>把本租户停用会同时让该租户所有现存会话失效（{@code memberships_for} 会把它从可切换集合里
   * 剔除），所以停用是不可逆的一步，界面上必须先讲清楚。
   *
   * <p>因此变更后的快照直接由入参拼出，而不是再查一次：一旦把本租户停用，会话当场失效，
   * 再经会话去读 {@code tenant_context} 只会拿到「会话不可用」，并把这次停用一起回滚——
   * 结果就是停用永远存不下来。code 不可改，所以只有 name/status 变化。
   */
  @PutMapping("/tenants/current")
  public void updateTenantProfile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TenantProfileInput input) {
    context(
        auth,
        a -> {
          var before = tenantSnapshot(auth);
          sql.fetch(
              "select update_tenant_profile(?,?,?)",
              hash(auth),
              input.name().trim(),
              input.status());
          var after = new LinkedHashMap<String, Object>(before);
          after.put("name", input.name().trim());
          after.put("status", input.status());
          audit(
              a,
              auth,
              "tenant.profile",
              a.tenantId(),
              (String) before.get("code"),
              "TenantProfileUpdated",
              before,
              after);
          return null;
        });
  }

  /**
   * 租户基本信息快照：名称与状态进审计，否则事后答不出「当时这个租户叫什么、是否启用」。
   *
   * <p>刻意经 {@code tenant_context} 而不是直查 {@code tenant} 表：运行时角色本来就没有
   * {@code tenant} 的读权限（租户目录不允许被枚举），这条窄权限路径是它唯一的读法。
   */
  private Map<String, Object> tenantSnapshot(String auth) {
    var r = sql.fetchOne("select * from tenant_context(?)", hash(auth));
    if (r == null) return null;
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("code", r.get("code", String.class));
    snapshot.put("name", r.get("name", String.class));
    snapshot.put("status", r.get("status", String.class));
    return snapshot;
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }
}
