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
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * 17.1.1.3 层级授权。
 *
 * <p>ADR-0005 的核心约束在这里落地：父子租户关系只表示结构，**不自动赋权**。
 * 集团要看子公司的数据，必须由父租户所有者在这里显式开一次口；没有记录就是拒绝。
 *
 * <p>三个能力对应蓝图的三段职责：
 * <ul>
 *   <li>授权记录：{@code /hierarchy-authorizations} 的增改查（读 + 配置）；
 *   <li>访问判定：{@code POST /hierarchy-authorizations/check}，下游全系统数据过滤的唯一入口；
 *   <li>审计：每一次配置变更都经 {@link AuditLog} 落 {@code identity_audit}，
 *       并在同一事务里写 {@code identity_outbox}，下游收到的变更必然有一条对应审计行。
 * </ul>
 *
 * <p>租户上下文来自 {@link IdentityController#resolve} 解析出的已认证成员关系，
 * <b>不采信 {@code X-Tenant-Id} 头</b>；判定落在数据库的 FORCE RLS 上兜底。
 * 配置授权是 Owner + ALL 专属（{@code hierarchy_owner}），与 17.1.1.2 管子租户同一道闸。
 */
@RestController
@RequestMapping("/identity")
public class HierarchyAuthorizationController {
  private static final String READ = "tenant.hierarchy.read";
  private static final String CREATE = "tenant.hierarchy.create";
  private static final String UPDATE = "tenant.hierarchy.update";

  private final DSLContext sql;
  private final IdentityController identity;
  private final AuditLog audit;
  private final TenantTransactions tenants;

  public HierarchyAuthorizationController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.audit = new AuditLog(sql, identity);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /**
   * 授权记录列表项。
   *
   * <p>{@code effectiveStatus} 与 {@code status} 并存是刻意的：{@code status} 是这一行的持久状态，
   * {@code effectiveStatus} 是「此刻实际有没有效」（过期、尚未生效都要在这里显形）。
   * 只显示 status 的话，一条上个月到期但没人撤销的授权看起来仍然是 ACTIVE，
   * 管理员会以为权限还在。
   */
  public record HierarchyGrantView(
      UUID id,
      UUID childTenantId,
      String childCode,
      String childName,
      String childStatus,
      List<String> domains,
      String accessLevel,
      OffsetDateTime validFrom,
      @Schema(nullable = true) OffsetDateTime validUntil,
      String status,
      String effectiveStatus,
      @Schema(nullable = true) String note,
      UUID createdBy,
      OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime revokedAt,
      @Schema(nullable = true) UUID revokedBy) {}

  public record HierarchyGrantInput(
      @NotNull UUID childTenantId,
      @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 64) String> domains,
      @NotNull @Pattern(regexp = "READ|WRITE") String accessLevel,
      @NotNull OffsetDateTime validFrom,
      @Schema(nullable = true) OffsetDateTime validUntil,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  public record HierarchyGrantUpdate(
      @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 64) String> domains,
      @NotNull @Pattern(regexp = "READ|WRITE") String accessLevel,
      @NotNull OffsetDateTime validFrom,
      @Schema(nullable = true) OffsetDateTime validUntil,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 访问请求：目标租户 + 数据域 + 需要的权限级别。 */
  public record AccessCheckInput(
      @Schema(nullable = true) UUID childTenantId,
      @NotBlank @Size(max = 64) String domain,
      boolean requireWrite) {}

  /** 判定结果。reason 让调用方能区分「没配过」与「配了但过期」，而不是只拿到 true/false。 */
  public record AccessDecisionView(
      boolean allowed,
      String reason,
      @Schema(nullable = true) String accessLevel,
      @Schema(nullable = true) OffsetDateTime validFrom,
      @Schema(nullable = true) OffsetDateTime validUntil,
      @Schema(nullable = true) UUID grantId) {}

  @GetMapping("/hierarchy-authorizations")
  public List<HierarchyGrantView> grants(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, READ, false, a -> sql.fetch("select * from hierarchy_grants(?)", hash(auth)).stream()
        .map(
            r ->
                new HierarchyGrantView(
                    r.get("grant_id", UUID.class),
                    r.get("child_tenant_id", UUID.class),
                    r.get("child_code", String.class),
                    r.get("child_name", String.class),
                    r.get("child_status", String.class),
                    r.get("domains", String[].class) == null
                        ? List.of()
                        : List.of(r.get("domains", String[].class)),
                    r.get("access_level", String.class),
                    r.get("valid_from", OffsetDateTime.class),
                    r.get("valid_until", OffsetDateTime.class),
                    r.get("status", String.class),
                    effective(r.get("status", String.class),
                        r.get("valid_from", OffsetDateTime.class),
                        r.get("valid_until", OffsetDateTime.class)),
                    r.get("note", String.class),
                    r.get("created_by", UUID.class),
                    r.get("created_at", OffsetDateTime.class),
                    r.get("revoked_at", OffsetDateTime.class),
                    r.get("revoked_by", UUID.class)))
        .toList());
  }

  @PostMapping("/hierarchy-authorizations")
  public UUID create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody HierarchyGrantInput input) {
    return call(
        auth,
        CREATE,
        true,
        a -> {
          UUID id = Ids.next();
          check(input.domains(), input.validFrom(), input.validUntil());
          sql.fetch(
              "select create_hierarchy_grant(?,?::uuid,?::uuid,?::text[],"
                  + "?::text,?::timestamptz,?::timestamptz,?::uuid,?::text)",
              hash(auth),
              id,
              input.childTenantId(),
              input.domains().toArray(String[]::new),
              input.accessLevel(),
              input.validFrom(),
              input.validUntil(),
              a.userId(),
              input.note());
          // after 回读库里那一行而不是用入参拼：写进去的 timestamptz 带的是库时区下的值，
          // 用入参拼出来的快照和真正落库的行可能有偏差，审计要记的是后者。
          record(a, auth, id, "HierarchyGrantCreated", null, load(id, auth));
          return id;
        });
  }

  @PutMapping("/hierarchy-authorizations/{id}")
  public void update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody HierarchyGrantUpdate input) {
    call(
        auth,
        UPDATE,
        true,
        a -> {
          var before = load(id, auth);
          check(input.domains(), input.validFrom(), input.validUntil());
          sql.fetch(
              "select update_hierarchy_grant(?,?::uuid,?::text[],?::text,"
                  + "?::timestamptz,?::timestamptz,?::text)",
              hash(auth),
              id,
              input.domains().toArray(String[]::new),
              input.accessLevel(),
              input.validFrom(),
              input.validUntil(),
              input.note());
          record(a, auth, id, "HierarchyGrantUpdated", before, load(id, auth));
          return null;
        });
  }

  /**
   * 撤销授权。
   *
   * <p>走 POST 而不是 DELETE：撤销是审计事件（要留下谁在什么时候撤的），
   * 不是资源消失。删除之后这两列就查不到了。
   */
  @PostMapping("/hierarchy-authorizations/{id}/revoke")
  public void revoke(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        UPDATE,
        true,
        a -> {
          var before = load(id, auth);
          sql.fetch("select revoke_hierarchy_grant(?,?,?)", hash(auth), id, a.userId());
          record(a, auth, id, "HierarchyGrantRevoked", before, load(id, auth));
          return null;
        });
  }

  /**
   * 访问判定：下游全系统数据过滤的单一入口。
   *
   * <p>刻意**不是** 403 —— 判定结果本身就是输出（蓝图「输出 = 授权记录 + 访问判定结果」），
   * 前端要据此渲染「为什么没有权限」。真正的数据端点在拿到 allowed=false 时自行拒绝。
   *
   * <p>不挂页面权限码：判定要问的是「这个成员在这处授权开口上有没有资格」，
   * 而这正是 {@code evaluate_hierarchy_access} 自己按角色算出来的；
   * 再在外面套一层 {@code tenant.hierarchy.read} 会把「能配置授权」和「能使用授权」混成一件事。
   */
  @PostMapping("/hierarchy-authorizations/check")
  public AccessDecisionView check(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AccessCheckInput input) {
    return call(
        auth,
        null,
        false,
        a -> {
          var row =
              sql.fetchOne(
                  "select * from evaluate_hierarchy_access(?,?::uuid,?::text,?::boolean)",
                  hash(auth),
                  input.childTenantId(),
                  input.domain(),
                  input.requireWrite());
          // 判定函数对任何会话都返回一行；这行缺失说明函数被改坏了，直接暴露而不是静默放行。
          if (row == null)
            throw IdentityController.error(
                HttpStatus.INTERNAL_SERVER_ERROR, "授权判定未返回结果");
          return new AccessDecisionView(
              row.get("allowed", Boolean.class),
              row.get("reason", String.class),
              row.get("access_level", String.class),
              row.get("valid_from", OffsetDateTime.class),
              row.get("valid_until", OffsetDateTime.class),
              row.get("grant_id", UUID.class));
        });
  }

  // ------------------------------------------------------------------
  // 内部
  // ------------------------------------------------------------------

  /**
   * 与 {@code AdministrationController.call} 同口径：租户事务 + 会话锁 + 租户二次校验 + 权限码。
   *
   * <p>{@code permission} 传 null 表示不挂页面权限码（判定路径，见 {@link #check} 的说明）。
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
          if (permission != null) {
            boolean allowed =
                a.grants()
                    .stream()
                    .anyMatch(
                        g -> g.scopeMode().equals("ALL") && g.permissions().contains(permission));
            if (!allowed)
              throw IdentityController.error(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
            if (mutation && !a.owner())
              throw IdentityController.error(HttpStatus.FORBIDDEN, "授权与组织变更需由当前租户所有者执行");
          }
          return work.apply(a);
        });
  }

  /**
   * 授权范围的格式校验，与数据库函数里的同名校验互为防线。
   *
   * <p>放在 Java 侧是为了让「域名写错」「有效期倒挂」返回 400 而不是笼统的 409：
   * 前者告诉用户哪里填错了，后者只说「记录不可用」，用户会以为是并发冲突去刷新页面。
   * 数据库侧仍然保留同样的判断，因为判定函数可能被别的服务直接调用，绕过这一层。
   */
  private static void check(List<String> domains, OffsetDateTime from, OffsetDateTime until) {
    for (var d : domains)
      if (!d.matches("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"))
        throw IdentityController.error(
            HttpStatus.BAD_REQUEST, "数据域格式不正确，形如 catalog.spu：" + d);
    if (until != null && !until.isAfter(from))
      throw IdentityController.error(HttpStatus.BAD_REQUEST, "有效期结束时间必须晚于开始时间");
  }

  /** 此刻这一行是否真的有效：撤销 / 未生效 / 已过期 / 生效中。 */
  private static String effective(String status, OffsetDateTime from, OffsetDateTime until) {
    if ("REVOKED".equals(status)) return "REVOKED";
    var now = OffsetDateTime.now();
    if (from != null && now.isBefore(from)) return "PENDING";
    if (until != null && !now.isBefore(until)) return "EXPIRED";
    return "ACTIVE";
  }

  /**
   * 授权快照。
   *
   * <p>范围、级别、有效期、备注、状态、目标子租户全部进审计：事后要能解释
   * 「当时到底把哪个子租户的哪些域、放到什么时候为止」。少一列就只剩一句「改过授权」，等于没法追责。
   *
   * <p>刻意从库里回读而不是用入参拼：timestamptz 落库时按库时区取值，
   * 入参拼出来的快照与真正生效的那一行可能差一个时区偏移，审计要记的是后者。
   */
  private Map<String, Object> load(UUID id, String auth) {
    for (var g : grants(auth))
      if (g.id().equals(id)) {
        var m = new LinkedHashMap<String, Object>();
        m.put("childTenantId", g.childTenantId().toString());
        m.put("childCode", g.childCode());
        m.put("domains", g.domains());
        m.put("accessLevel", g.accessLevel());
        m.put("validFrom", g.validFrom() == null ? null : g.validFrom().toString());
        m.put("validUntil", g.validUntil() == null ? null : g.validUntil().toString());
        m.put("status", g.status());
        m.put("effectiveStatus", g.effectiveStatus());
        m.put("note", g.note());
        return m;
      }
    throw IdentityController.error(HttpStatus.NOT_FOUND, "授权记录不存在");
  }

  private void record(
      IdentityController.Access a,
      String auth,
      UUID id,
      String action,
      Map<String, Object> before,
      Map<String, Object> after) {
    // 审计 + 事件外发同事务：下游收到的变更必然有一条对应的审计行。
    audit.record(a, auth, "tenant.hierarchy", id, "层级授权", action, before, after);
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
}
