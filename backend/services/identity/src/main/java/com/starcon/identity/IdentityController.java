package com.starcon.identity;

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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class IdentityController {
  private final DSLContext sql;
  private final String internalKey;
  private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
  private final Map<String, Attempt> attempts = new java.util.concurrent.ConcurrentHashMap<>();

  private record Attempt(long until, int count) {}

  public IdentityController(DSLContext sql, @Value("${INTERNAL_KEY}") String internalKey) {
    this.sql = sql;
    this.internalKey = internalKey;
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
    if (previous != null && previous.count() >= 10)
      throw error(HttpStatus.TOO_MANY_REQUESTS, "尝试次数过多，请 5 分钟后重试");
    if (attempts.size() > 10000) throw error(HttpStatus.TOO_MANY_REQUESTS, "请稍后重试");
    attempts.put(key, new Attempt(now + 300000, previous == null ? 1 : previous.count() + 1));
    var user = sql.fetchOne("select * from authenticate(?)", input.username());
    if (user == null
        || !passwords.matches(input.password(), user.get("password_hash", String.class)))
      throw error(HttpStatus.UNAUTHORIZED, "账号或密码不正确");
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
    return new LoginResult(token, memberships);
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
    sql.fetch("select revoke_session(?)", hash(token(bearer)));
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
