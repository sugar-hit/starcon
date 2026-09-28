package com.starcon.identity;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.util.function.Function;
import org.jooq.DSLContext;
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

  public AdministrationController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
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

  public record DepartmentView(UUID id, String name) {}

  public record DepartmentInput(@NotBlank @Size(max = 100) String name) {}

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

  private static void require(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.FORBIDDEN, message);
  }

  private static void check(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.BAD_REQUEST, message);
  }

  private void audit(IdentityController.Access a, UUID id, String action) {
    sql.execute(
        "insert into identity_audit(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(),
        a.tenantId(),
        a.userId(),
        id,
        action);
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

  private UUID saveRole(IdentityController.Access a, UUID id, RoleInput input) {
    validatePermissions(input);
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
    audit(a, id, "RoleSaved");
    return id;
  }

  @PostMapping("/roles")
  public UUID createRole(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RoleInput input) {
    return call(auth, "identity.role.create", true, a -> saveRole(a, null, input));
  }

  @PutMapping("/roles/{id}")
  public UUID updateRole(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody RoleInput input) {
    return call(auth, "identity.role.update", true, a -> saveRole(a, id, input));
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
          sql.execute("delete from role_assignment where role_id=?", id);
          sql.execute("delete from role_permission where role_id=?", id);
          sql.execute("delete from role where id=?", id);
          audit(a, id, "RoleDeleted");
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
    for (var input : inputs)
      sql.execute(
          "insert into role_assignment values(?,?,?,?)",
          a.tenantId(),
          id,
          input.roleId(),
          input.scopeMode());
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
          audit(a, id, "MemberCreated");
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
          audit(a, id, "MemberUpdated");
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
          sql.execute("delete from role_assignment where membership_id=?", id);
          // Removing the identity also releases its department, otherwise the department could
          // never be deleted again.
          sql.execute("update membership set status='INACTIVE',department_id=null where id=?", id);
          audit(a, id, "MemberRemoved");
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
              "insert into role_assignment values(?,?,?,'ALL') on conflict(tenant_id,membership_id,role_id) do update set scope_mode='ALL'",
              a.tenantId(),
              input.membershipId(),
              rid);
          sql.execute(
              "delete from role_assignment where membership_id=? and role_id=?",
              a.membershipId(),
              rid);
          audit(a, input.membershipId(), "OwnerTransferred");
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
        a ->
            sql.fetch("select * from department order by name")
                .map(
                    r -> new DepartmentView(r.get("id", UUID.class), r.get("name", String.class))));
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
          UUID id = Ids.next();
          sql.execute(
              "insert into department values(?,?,?)", id, a.tenantId(), input.name().trim());
          audit(a, id, "DepartmentCreated");
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
          check(
              sql.execute("update department set name=? where id=?", input.name().trim(), id) == 1,
              "部门不存在");
          audit(a, id, "DepartmentUpdated");
          return null;
        });
  }

  @DeleteMapping("/departments/{id}")
  public void deleteDepartment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "identity.department.delete",
        true,
        a -> {
          check(sql.execute("delete from department where id=?", id) == 1, "部门不存在");
          audit(a, id, "DepartmentDeleted");
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
          audit(a, id, "ChildCreated");
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
          sql.fetch(
              "select update_child(?,?,?,?)", hash(auth), id, input.name().trim(), input.status());
          audit(a, id, "ChildUpdated");
          return null;
        });
  }
}
