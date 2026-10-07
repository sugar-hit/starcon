package com.starcon.identity;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * 17.2.2.2 成员开通与入职。
 *
 * <p>与 {@link AdministrationController} 的 17.2.2.1 成员管理分工：那边管「已经在册的成员」怎么改
 * （改部门、改角色、停用、移除），这里管「一个人怎么进来」——建立账号（新建或关联已有全局账号）、
 * 建立本租户成员关系、设定初始部门与初始角色。
 *
 * <p>蓝图把邮件邀请、接受令牌和自助激活划给后续独立 PRD，所以这里**没有**邀请态端点、
 * 也没有令牌列。需要特别说明的是本模块的凭据纪律：
 * <ul>
 *   <li>初始凭据由**服务端生成**（不是管理员填写），所以不存在「管理员挑了个弱密码」这条路；
 *   <li>明文只在 {@code POST /identity/onboardings} 的这一次响应里返回，
 *       之后列表、详情、审计、CSV 导出都拿不到——库里根本没有这一列，连哈希都不在这里
 *       （哈希在 {@code app_user.password_hash}，只有一份）；
 *   <li>关联已有账号（LINKED）时服务端根本不知道那个人的密码，所以响应里的
 *       {@code initialCredential} 为 null。返回一个假的比不返回更糟。
 * </ul>
 *
 * <p>租户上下文来自 {@link IdentityController#resolve} 解析出的已认证成员关系，
 * <b>不采信 {@code X-Tenant-Id} 头</b>：三个数据库函数的签名里都没有 tenant_id 参数，
 * 客户端想传租户也无处可传。判定最终仍由 FORCE RLS 兜底。
 */
@RestController
@RequestMapping("/identity")
public class MemberOnboardingController {
  private static final String READ = "identity.onboarding.read";
  private static final String CREATE = "identity.onboarding.create";
  private static final String UPDATE = "identity.onboarding.update";
  /** bcrypt 的输入上限是 72 字节；生成 20 位远在区间内，也满足 17.2.2.1 的 12–72 位校验。 */
  private static final int PASSWORD_LENGTH = 20;
  /**
   * 初始密码字符集：大小写字母 + 数字，去掉了容易互相看错的 I/l/1/O/0。
   * 去掉歧义字符不是为了好看——这些字符在工单、IM 和截图里被认错，会直接变成「登录不上去」。
   */
  private static final String PASSWORD_ALPHABET =
      "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

  private final DSLContext sql;
  private final IdentityController identity;
  private final AuditLog audit;
  private final TenantTransactions tenants;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  private final SecureRandom random = new SecureRandom();

  public MemberOnboardingController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.audit = new AuditLog(sql, identity);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /**
   * 入职记录列表项。
   *
   * <p>刻意只有 {@code credentialIssued}（发过没有）而没有任何凭据字段：
   * 蓝图写明「不在页面或日志返回完整初始凭据」，这一条由 DTO 形状保证——
   * 就算将来有人给它加字段，列表接口也拿不到可以泄露的东西。
   */
  public record OnboardingView(
      UUID id,
      UUID membershipId,
      UUID userId,
      String accountMode,
      String username,
      @Schema(nullable = true) UUID departmentId,
      @Schema(nullable = true) String departmentName,
      List<String> roleCodes,
      boolean credentialIssued,
      String status,
      UUID createdBy,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime completedAt,
      @Schema(nullable = true) UUID completedBy) {}

  /** 初始角色与范围。与 17.2.2.1 的 AssignmentInput 同形，入职之后可在成员管理里继续改。 */
  public record RoleAssignmentInput(
      @NotNull UUID roleId, @NotNull @Pattern(regexp = "ALL|DEPARTMENT|SELF") String scopeMode) {}

  /**
   * 开通入职入参。
   *
   * <p>刻意**没有** password 字段：初始凭据由服务端生成。这是本模块与 17.2.2.1 成员新增
   * 最实质的差别——那边管理员自己填密码（所以有 12–72 位校验和「留空表示关联已有账号」），
   * 这边不给填的机会，凭据只在创建响应里出现一次。
   */
  public record OnboardingInput(
      @NotBlank @Size(max = 100) String username,
      @Schema(nullable = true) UUID departmentId,
      @NotNull List<@NotNull @Valid RoleAssignmentInput> roles) {}

  /**
   * 创建响应。
   *
   * <p>{@code initialCredential} 是本模块唯一一处会出现凭据明文的地方，且只在账号**新建**时非空。
   * 它不会落审计、不会进日志：审计快照由 {@link #snapshot} 从入库后的入职记录回读拼装，
   * 压根不经过这个对象。
   */
  public record OnboardingResult(
      UUID onboardingId,
      UUID membershipId,
      UUID userId,
      String accountMode,
      String username,
      @Schema(nullable = true) String initialCredential,
      OffsetDateTime createdAt) {}

  @GetMapping("/onboardings")
  public List<OnboardingView> list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        READ,
        false,
        a ->
            sql.fetch("select * from onboardings(?)", hash(auth)).stream()
                .map(
                    r ->
                        new OnboardingView(
                            r.get("onboarding_id", UUID.class),
                            r.get("membership_id", UUID.class),
                            r.get("user_id", UUID.class),
                            r.get("account_mode", String.class),
                            r.get("username", String.class),
                            r.get("department_id", UUID.class),
                            r.get("department_name", String.class),
                            codes(r.get("role_codes", String[].class)),
                            r.get("credential_issued", Boolean.class),
                            r.get("status", String.class),
                            r.get("created_by", UUID.class),
                            r.get("created_at", OffsetDateTime.class),
                            r.get("completed_at", OffsetDateTime.class),
                            r.get("completed_by", UUID.class)))
                .toList());
  }

  /**
   * 开通入职。
   *
   * <p>先在内存里生成一份初始凭据，再连同它的 bcrypt 哈希一起交给数据库函数；
   * 函数返回 {@code account_mode} 告诉我们这次是不是真新建了账号：
   * <ul>
   *   <li>CREATED —— 返回明文，管理员必须当场转交给本人；系统不再有第二次机会。
   *   <li>LINKED —— 丢弃内存里那份凭据，返回 null。这个人的密码在人家自己手上，
   *       本次操作没有、也不应该生成一个新的。
   * </ul>
   *
   * <p>为什么不为「账号已存在」报错（17.2.2.1 的 member_user 在这种情况下会报
   * 「不能重置已有账号」）：那份哈希是**本方法生成的**，调用方既不能选择也不能看到它，
   * 关联分支直接丢弃即可。管理员填密码时那条拒绝规则才成立——那时继续走下去
   * 等于允许借「建成员」改掉一个已有账号的登录凭据。
   *
   * <p>代价是 LINKED 时也会白算一次 bcrypt。这是刻意的：运行时角色 identity_app 对
   * {@code app_user} 根本没有 GRANT（全局用户表不可枚举），所以 Java 侧无法先查
   * 「这个账号在不在」再决定要不要生成——生成与否只能由数据库函数判定，
   * 而函数要拿到哈希才知道该不该建号。开通是低频管理操作，这个代价可以接受。
   */
  @PostMapping("/onboardings")
  public OnboardingResult create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody OnboardingInput input) {
    return call(
        auth,
        CREATE,
        true,
        a -> {
          validate(input.departmentId(), input.roles());
          String login = input.username().trim();
          String plain = generatePassword();
          UUID onboardingId = Ids.next();
          var row =
              sql.fetchOne(
                  "select * from onboard_member(?::text,?::uuid,?::uuid,?::text,?::uuid,"
                      + "?::text,?::uuid,?::uuid[],?::text[],?::uuid)",
                  hash(auth),
                  Ids.next(),
                  onboardingId,
                  login,
                  Ids.next(),
                  encoder.encode(plain),
                  input.departmentId(),
                  input.roles().stream().map(RoleAssignmentInput::roleId).toArray(UUID[]::new),
                  input.roles().stream().map(RoleAssignmentInput::scopeMode).toArray(String[]::new),
                  a.userId());
          // 判定函数对合法输入必然返回一行；这行缺失说明函数被改坏了，直接暴露而不是静默放行。
          if (row == null)
            throw IdentityController.error(HttpStatus.INTERNAL_SERVER_ERROR, "开通入职未返回结果");
          String mode = row.get("account_mode", String.class);
          boolean issued = row.get("credential_issued", Boolean.class);
          UUID membershipId = row.get("membership_id", UUID.class);
          // after 回读库里那一行而不是用入参拼：审计要记的是真正落库的事实，
          // createdAt 也是库时区下取的值，用入参拼会差一个偏移。
          var stored = find(auth, onboardingId);
          record(a, auth, onboardingId, "MemberOnboarded", null, snapshot(stored));
          return new OnboardingResult(
              onboardingId,
              membershipId,
              row.get("user_id", UUID.class),
              mode,
              login,
              issued ? plain : null,
              stored.createdAt());
        });
  }

  /**
   * 完成入职。
   *
   * <p>走 POST 而不是 PUT/DELETE：完成是带操作人和时刻的状态流转，不是资源消失。
   */
  @PostMapping("/onboardings/{id}/complete")
  public void complete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        UPDATE,
        true,
        a -> {
          var before = find(auth, id);
          sql.fetch("select complete_onboarding(?::text,?::uuid,?::uuid)", hash(auth), id, a.userId());
          var after = find(auth, id);
          record(a, auth, id, "MemberOnboardingCompleted", snapshot(before), snapshot(after));
          return null;
        });
  }

  // ------------------------------------------------------------------
  // 内部
  // ------------------------------------------------------------------

  /**
   * 与 {@code AdministrationController.call} 同口径：租户事务 + 会话锁 + 租户二次校验 + 权限码。
   *
   * <p>开通入职是授权与组织变更，必须由当前租户所有者执行——放一个人进来
   * 等于给了他本租户的身份，这件事的闸与成员管理、子租户、层级授权完全一致。
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
          if (!a.tenantId().equals(initial.tenantId()))
            throw IdentityController.error(HttpStatus.CONFLICT, "租户已切换，请重试");
          boolean allowed =
              a.grants()
                  .stream()
                  .anyMatch(
                      g -> g.scopeMode().equals("ALL") && g.permissions().contains(permission));
          if (!allowed)
            throw IdentityController.error(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
          if (mutation && !a.owner())
            throw IdentityController.error(HttpStatus.FORBIDDEN, "授权与组织变更需由当前租户所有者执行");
          return work.apply(a);
        });
  }

  /**
   * 生成初始凭据。
   *
   * <p>用 {@link SecureRandom} 而不是 {@code UUID} 或 {@code Random}：初始凭据是账号的
   * 第一道门，UUID 只有 122 位随机熵且格式可预测（版本位固定），{@code Random} 更是线性同余、
   * 给定几个输出就能反推后续。字符集去掉了易混字符，见 {@link #PASSWORD_ALPHABET}。
   */
  private String generatePassword() {
    var chars = new char[PASSWORD_LENGTH];
    for (int i = 0; i < PASSWORD_LENGTH; i++)
      chars[i] = PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length()));
    return new String(chars);
  }

  private static List<String> codes(String[] values) {
    return values == null ? List.of() : List.of(values);
  }

  /**
   * 入职快照。
   *
   * <p>刻意**不含任何凭据字段**——连「初始密码是什么」都不该进审计。
   * {@code AuditRedactor} 会把命中 credential/password 的键整值打成 {@code ***}，
   * 但这里根本不构造这样的键：审计要回答的是「谁在什么时候把谁接进了哪个租户、给了什么角色」，
   * 凭据不属于这个问题，写进去只会多一个泄露点。
   */
  private static Map<String, Object> snapshot(OnboardingView o) {
    if (o == null) return null;
    var m = new LinkedHashMap<String, Object>();
    m.put("membershipId", o.membershipId().toString());
    m.put("userId", o.userId().toString());
    m.put("accountMode", o.accountMode());
    m.put("username", o.username());
    m.put("departmentId", o.departmentId() == null ? null : o.departmentId().toString());
    m.put("roleCodes", o.roleCodes());
    m.put("credentialIssued", o.credentialIssued());
    m.put("status", o.status());
    m.put("createdAt", o.createdAt() == null ? null : o.createdAt().toString());
    m.put("completedAt", o.completedAt() == null ? null : o.completedAt().toString());
    return m;
  }

  /** 取一条入职记录；不在本租户（或已被别的请求完成）时按 404 对外，不泄露「存在但无权」。 */
  private OnboardingView find(String auth, UUID id) {
    for (var o : list(auth))
      if (o.id().equals(id)) return o;
    throw IdentityController.error(HttpStatus.NOT_FOUND, "入职记录不存在");
  }

  private void record(
      IdentityController.Access a,
      String auth,
      UUID id,
      String action,
      Map<String, Object> before,
      Map<String, Object> after) {
    // 审计 + 事件外发同事务：下游收到的变更必然有一条对应的审计行。
    audit.record(a, auth, "identity.onboarding", id, "成员开通", action, before, after);
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

  private static void require(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.FORBIDDEN, message);
  }

  private static void check(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.BAD_REQUEST, message);
  }

  /**
   * 初始部门与初始角色的服务端校验。
   *
   * <p>规则与 17.2.2.1 的 {@code assignments()} 逐条一致（不能发 OWNER、不能给停用角色、
   * 本部门范围必须先有部门、管理权限只能绑全租户范围、角色不能重复）。
   *
   * <p>放在 Java 侧是为了让「选了停用角色」返回 400 而不是笼统的 409：
   * 前者告诉用户哪里填错了，后者只说「记录不可用」，用户会以为是并发冲突去刷新页面。
   * 数据库函数里保留同样的判断，因为 {@code onboard_member} 可能被别的服务直接调用，绕过这一层。
   *
   * <p>「不属于本租户」在这里表现为「查不到」而不是「越权」：role 与 department 都 FORCE RLS，
   * 当前租户的事务里根本看不到别人的行，所以跨租户引用和引用一个不存在的 id 得到同一个答案。
   */
  private void validate(UUID department, List<RoleAssignmentInput> roles) {
    if (department != null)
      check(
          sql.fetchExists(sql.selectOne().from("department").where("id=?", department)),
          "部门不存在");
    check(
        roles.stream().map(RoleAssignmentInput::roleId).distinct().count() == roles.size(),
        "角色不能重复");
    for (var input : roles) {
      var role = sql.fetchOne("select * from role where id=?", input.roleId());
      if (role == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "角色不存在");
      require(!role.get("code", String.class).equals("OWNER"), "请使用转移所有者操作");
      check(role.get("status", String.class).equals("ACTIVE"), "不能分配停用角色");
      check(
          !input.scopeMode().equals("DEPARTMENT") || department != null,
          "本部门范围需要先设置成员部门");
      if (!input.scopeMode().equals("ALL"))
        check(
            !sql.fetchExists(
                sql.selectOne()
                    .from("role_permission")
                    .where("role_id=? and permission_code not like 'catalog.%'", input.roleId())),
            "管理权限只能绑定租户内全部范围");
    }
  }
}