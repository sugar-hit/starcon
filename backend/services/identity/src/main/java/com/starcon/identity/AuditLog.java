package com.starcon.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * 17.5.3.1 审计写入侧。
 *
 * <p>所有关键操作点都经这一个入口落审计，所以「授权上下文 + 变更前后值 + 脱敏」的口径只有一处， 不会因为某个操作点自己拼参数而漏字段。
 *
 * <p>脱敏在这里（写入侧）发生：{@link AuditRedactor} 产出的就已经是脱敏后的值， 库里不存在明文，因此导出与任何直连 SQL 都拿不到原始凭证。
 *
 * <p>{@code occurred_at} 显式绑定而不是依赖列默认值：审计时间是**系统时间**（操作发生的时刻）， 与业务单据上的业务时间分开存。这里用 {@code
 * LocalDateTime.ofInstant(…, ZoneOffset.UTC)} 转换——直接把 {@code LocalDateTime.now()} 塞进 timestamptz
 * 会静默按 Asia/Shanghai 偏移 -28800 秒。
 */
final class AuditLog {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final DSLContext sql;
  private final IdentityController identity;

  AuditLog(DSLContext sql, IdentityController identity) {
    this.sql = sql;
    this.identity = identity;
  }

  /**
   * timestamptz 绑定的唯一安全姿势：直接给 {@link OffsetDateTime}。
   *
   * <p>踩过的坑：{@code Timestamp.valueOf(LocalDateTime.ofInstant(now, ZoneOffset.UTC))} 看着已经 「显式
   * UTC」，实测在 Asia/Shanghai 下仍会静默偏 −28800 秒——因为 {@code Timestamp.valueOf} 把这串墙钟按 JVM 默认时区换算成
   * epoch，等于又偏回本地时区一次。实测 drift： {@code valueOf(...UTC)} = −28800s，{@code Timestamp.from} = 0，{@link
   * OffsetDateTime} = 0。
   *
   * <p>选 {@link OffsetDateTime} 而不是 {@code Timestamp.from}：前者的类型语义就是「带偏移的 绝对时刻」，PG 驱动按 timestamptz
   * 直接解释，不经过任何本地时区换算；后者仍是无时区的 {@code Timestamp}，留给驱动去猜会话时区。
   */
  static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private String json(Object value) {
    if (value == null) return null;
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("审计内容序列化失败", e);
    }
  }

  /**
   * 授权上下文快照：角色编码 + 数据范围 + 当时的权限集。
   *
   * <p>这是事后解释「凭什么放行这次操作」的唯一依据，所以连权限集一起快照—— 角色后续被改写也改不了当时的事实。
   */
  private String authorization(IdentityController.Access a) {
    var roles = a.grants().stream().map(IdentityController.Grant::roleCode).sorted().toList();
    var scopes =
        a.grants().stream().map(IdentityController.Grant::scopeMode).sorted().distinct().toList();
    var permissions = a.permissions().stream().sorted().toList();
    var context = new LinkedHashMap<String, Object>();
    context.put("membershipId", a.membershipId().toString());
    context.put("departmentId", a.departmentId() == null ? null : a.departmentId().toString());
    context.put("owner", a.owner());
    context.put("roles", roles);
    context.put("scopeModes", scopes);
    context.put("permissions", permissions);
    return json(context);
  }

  /** 操作人可读名。只解析调用者自己，所以合规角色无需 member.read 也能写审计。 */
  private String actorName(String auth) {
    var row = sql.fetchOne("select session_actor_name(?) as name", hash(auth));
    return row == null ? null : row.get("name", String.class);
  }

  private String hash(String auth) {
    return IdentityController.hash(identity.token(auth));
  }

  /**
   * 记录一条审计。
   *
   * @param resourceType 资源类型（{@code identity.role} 等），决定按资源检索的粒度
   * @param label 资源可读标题，写入时快照，避免改名/删除后历史记录渲染为空白
   * @param before 变更前快照，可为 null（新建）
   * @param after 变更后快照，可为 null（删除）
   */
  void record(
      IdentityController.Access a,
      String auth,
      String resourceType,
      UUID resourceId,
      String label,
      String action,
      Map<String, Object> before,
      Map<String, Object> after) {
    var changes = new LinkedHashMap<String, Object>();
    changes.put("before", AuditRedactor.redactAll(before));
    changes.put("after", AuditRedactor.redactAll(after));
    // jsonb 列必须显式 cast：jOOQ 默认把 Java String 绑成 varchar，直接插入会被 PG 拒绝。
    sql.execute(
        "insert into identity_audit(id,tenant_id,actor_id,actor_name,resource_type,resource_id,"
            + " resource_label,action,authorization_context,changes,occurred_at)"
            + " values(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::timestamptz)",
        Ids.next(),
        a.tenantId(),
        a.userId(),
        actorName(auth),
        resourceType,
        resourceId,
        label,
        action,
        authorization(a),
        json(changes),
        utc(Instant.now()));
  }

  /** 解析 changes 列；损坏时返回 null 而不是抛错——审计详情读不出来不应让列表整体 500。 */
  static Map<String, Object> parseChanges(String raw) {
    if (raw == null || raw.isBlank()) return null;
    try {
      @SuppressWarnings("unchecked")
      var parsed = JSON.readValue(raw, Map.class);
      return parsed;
    } catch (Exception e) {
      return null;
    }
  }
}
