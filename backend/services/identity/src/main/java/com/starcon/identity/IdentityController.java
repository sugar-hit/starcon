package com.starcon.identity;

import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class IdentityController {
  private final DSLContext sql;
  private final String internalKey;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
  private final Map<String, Attempt> attempts = new java.util.concurrent.ConcurrentHashMap<>();

  /** 17.5.3.2 操作日志写入侧。登录/登出事件由本控制器记录（见 login / logout）。 */
  private final OperationLog operationLog;

  private final TenantTransactions tenants;

  private record Attempt(long until, int count) {}

  public IdentityController(
      DSLContext sql,
      @Value("${INTERNAL_KEY}") String internalKey,
      PlatformTransactionManager manager) {
    this.sql = sql;
    this.internalKey = internalKey;
    this.operationLog = new OperationLog(sql);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  public record Login(
      @NotBlank String username,
      @NotBlank String password,
      @Schema(nullable = true) UUID membershipId) {}

  public record Membership(UUID id, UUID tenantId, String name) {}

  public record LoginResult(@Schema(nullable = true) String token, List<Membership> memberships) {}

  public record Grant(UUID roleId, String roleCode, String scopeMode, List<String> permissions) {}

  public record Access(
      UUID userId,
      UUID tenantId,
      String tenantName,
      UUID membershipId,
      @Schema(nullable = true) UUID departmentId,
      List<String> permissions,
      List<Grant> grants) {
    boolean owner() {
      return grants.stream()
          .anyMatch(g -> g.roleCode().equals("OWNER") && g.scopeMode().equals("ALL"));
    }
  }

  @PostMapping("/auth/login")
  public synchronized LoginResult login(@Valid @RequestBody Login input) {
    if (input.username().length() > 100 || input.password().length() > 100)
      throw error(HttpStatus.BAD_REQUEST, "账号或密码格式不正确");
    long now = System.currentTimeMillis();
    attempts.entrySet().removeIf(e -> e.getValue().until() < now);
    String key = input.username();
    Attempt previous = attempts.get(key);
    if (previous != null && previous.count() >= 10) {
      recordLoginFailure(input.username(), "尝试次数过多，已被限流");
      throw error(HttpStatus.TOO_MANY_REQUESTS, "尝试次数过多，请 5 分钟后重试");
    }
    if (attempts.size() > 10000) throw error(HttpStatus.TOO_MANY_REQUESTS, "请稍后重试");
    attempts.put(key, new Attempt(now + 300000, previous == null ? 1 : previous.count() + 1));
    var user = sql.fetchOne("select * from authenticate(?)", input.username());
    if (user == null
        || !passwords.matches(input.password(), user.get("password_hash", String.class))) {
      recordLoginFailure(input.username(), "账号或密码不正确");
      throw error(HttpStatus.UNAUTHORIZED, "账号或密码不正确");
    }
    UUID uid = user.get("user_id", UUID.class);
    var memberships =
        sql.fetch("select * from memberships_for(?)", uid)
            .map(
                r ->
                    new Membership(
                        r.get("id", UUID.class),
                        r.get("tenant_id", UUID.class),
                        r.get("name", String.class)));
    if (memberships.isEmpty()) throw error(HttpStatus.FORBIDDEN, "没有可用的租户成员身份");
    if (input.membershipId() == null && memberships.size() > 1)
      return new LoginResult(null, memberships);
    UUID mid = input.membershipId() == null ? memberships.getFirst().id() : input.membershipId();
    if (memberships.stream().noneMatch(m -> m.id().equals(mid)))
      throw error(HttpStatus.FORBIDDEN, "无权进入此租户");
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    sql.fetch("select create_session(?,?,?)", hash(token), uid, mid);
    attempts.remove(key);
    // 登录成功后才有确定的租户归属，这时候才记 LOGIN 事件。
    // 归属取实际进入的那个 membership 的租户，而不是用户名关联的第一个租户——
    // 一个人可以是多个租户的成员，记到错的租户上等于把别人的登录流水写进本租户。
    UUID tenantId =
        memberships.stream().filter(m -> m.id().equals(mid)).findFirst().orElseThrow().tenantId();
    try {
      tenants.run(
          tenantId,
          () -> {
            operationLog.login(tenantId, requestId(), input.username(), true, null, null);
            return null;
          });
    } catch (Exception ignored) {
      // 记日志失败不能让人登不进来（与拦截器同一条纪律）。
    }
    return new LoginResult(token, memberships);
  }

  /**
   * 记录一次登录失败。
   *
   * <p>登录失败时还没有可信的租户上下文，而操作日志的 {@code tenant_id} 是 NOT NULL 且受 RLS 保护。
   * 做法是交给数据库按<b>用户名的成员关系</b>反查候选租户并写入：调用方无从指定租户， 这与 V40「租户集合只能由已认证成员关系推导」的纪律一致。查不到任何租户（账号不存在）
   * 就不写——给一个不存在的账号硬造归属，比不记更糟。
   */
  private void recordLoginFailure(String username, String reason) {
    var requestId = requestId();
    try {
      sql.fetch("select record_failed_login(?,?,?)", username, requestId, reason);
    } catch (Exception ignored) {
      // 同上：登录失败路径本身已经有明确的错误响应，日志失败不叠加第二个故障。
    }
  }

  /**
   * 当前请求的 request_id。
   *
   * <p>不在请求上下文里时（新生成的 ID + 固定前缀）返回一个独立值，而不是 null： {@code request_id} 是 NOT NULL，且一条查不到链路的日志等于没记。
   */
  private static String requestId() {
    var current = RequestTrace.requestId();
    return current == null ? RequestTrace.detachedRequestId("login") : current;
  }

  @GetMapping("/internal/access")
  public Access access(
      @RequestHeader("X-Internal-Key") String key, @RequestHeader("Authorization") String bearer) {
    if (!MessageDigest.isEqual(
        key.getBytes(StandardCharsets.UTF_8), internalKey.getBytes(StandardCharsets.UTF_8)))
      throw error(HttpStatus.FORBIDDEN, "拒绝内部调用");
    return resolve(bearer);
  }

  @GetMapping("/auth/session")
  public Access session(@RequestHeader(value = "Authorization", defaultValue = "") String bearer) {
    return resolve(bearer);
  }

  @PostMapping("/auth/logout")
  public void logout(@RequestHeader(value = "Authorization", defaultValue = "") String bearer) {
    var tokenHash = hash(token(bearer));
    // 先记登出再撤销：撤销之后再解析会话就已经查不到了，拿不到租户归属。
    // 解析失败不改变原有语义——原来的登出对无效令牌也是静默成功的，
    // 这里不能因为要记一条日志就把「登出」变成 401。
    IdentityController.Access access = null;
    try {
      access = resolve(bearer);
    } catch (Exception ignored) {
      // 无效令牌：照常撤销，只是不记登出事件（没有可信的租户可归属）。
    }
    sql.fetch("select revoke_session(?)", tokenHash);
    if (access == null) return;
    var tenant = access.tenantId();
    var userId = access.userId();
    try {
      tenants.run(
          tenant,
          () -> {
            operationLog.logout(tenant, requestId(), userId, null);
            return null;
          });
    } catch (Exception ignored) {
      // 同登录：登出本身已成功，不因日志失败而改写结果。
    }
  }

  Access resolve(String bearer) {
    var row = sql.fetchOne("select * from session_access(?)", hash(token(bearer)));
    if (row == null) throw error(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    return new Access(
        row.get("user_id", UUID.class),
        row.get("tenant_id", UUID.class),
        row.get("tenant_name", String.class),
        row.get("membership_id", UUID.class),
        row.get("department_id", UUID.class),
        Arrays.asList(row.get("permissions", String[].class)),
        readGrants(row.get("grants", String.class)));
  }

  private List<Grant> readGrants(String value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper()
          .readValue(value, new com.fasterxml.jackson.core.type.TypeReference<List<Grant>>() {});
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @GetMapping("/auth/memberships")
  public List<Membership> memberships(
      @RequestHeader(value = "Authorization", defaultValue = "") String bearer) {
    var a = resolve(bearer);
    return sql.fetch("select * from memberships_for(?)", a.userId())
        .map(
            r ->
                new Membership(
                    r.get("id", UUID.class),
                    r.get("tenant_id", UUID.class),
                    r.get("name", String.class)));
  }

  public record SwitchMembership(@jakarta.validation.constraints.NotNull UUID membershipId) {}

  @PostMapping("/auth/switch")
  public void switchMembership(
      @RequestHeader(value = "Authorization", defaultValue = "") String bearer,
      @Valid @RequestBody SwitchMembership input) {
    resolve(bearer);
    sql.fetch("select switch_membership(?,?)", hash(token(bearer)), input.membershipId());
  }

  String token(String bearer) {
    if (!bearer.startsWith("Bearer ") || bearer.length() > 200)
      throw error(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    return bearer.substring(7);
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static ResponseStatusException error(HttpStatus status, String message) {
    return new ResponseStatusException(status, message);
  }
}
