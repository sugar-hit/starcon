package com.starcon.identity;

import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * 17.5.3.1 审计日志读侧。
 *
 * <p>蓝图要求「支持按资源/操作人/时间检索与导出」，而 V3 时期只 GRANT 了 INSERT—— 记录写得进、没人查得到。这里补上检索、变更详情与 CSV 导出三件事。
 *
 * <p>租户隔离沿用 identity 既有纪律：租户上下文来自 {@link IdentityController#resolve} 解析出的 已认证成员关系（经 {@link
 * TenantTransactions} 写入 {@code app.current_tenant}）， <b>不采信 {@code X-Tenant-Id} 头</b>；真正的边界由
 * {@code identity_audit} 的 FORCE RLS 兜底。 因此伪造 tenant 头查不到别的租户的审计行，而连接归还后租户设置是事务局部的（{@code
 * local=true}）， 下一个请求不会串租户。
 *
 * <p>与 17.5.3.2 操作日志的分工：这里只有合规所需的「谁、对什么、做了什么、凭什么」， 不含登录流水、API 调用摘要、错误堆栈与请求 ID
 * 链路——那些属于运维排障，用途和留存期都不同。
 */
@RestController
@RequestMapping("/identity/audit")
public class AuditController {
  private static final String READ = "identity.audit.read";

  /** 单页上限，防止一次把整年审计拉进内存。 */
  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;

  public AuditController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  /** 列表项：够运营判断「发生了什么」，变更详情走详情端点按需拉取。 */
  public record AuditView(
      UUID id,
      String action,
      String resourceType,
      UUID resourceId,
      @Schema(nullable = true) String resourceLabel,
      @Schema(nullable = true) String actorId,
      @Schema(nullable = true) String actorName,
      Instant occurredAt,
      boolean owner) {}

  /**
   * 变更详情。
   *
   * <p>{@code changes} 内含写入侧已脱敏的 before/after（见 {@link AuditRedactor}）， {@code authorization}
   * 是操作当时的角色与数据范围快照。
   */
  public record AuditDetail(
      UUID id,
      String action,
      String resourceType,
      UUID resourceId,
      @Schema(nullable = true) String resourceLabel,
      @Schema(nullable = true) String actorId,
      @Schema(nullable = true) String actorName,
      Instant occurredAt,
      boolean owner,
      Map<String, Object> authorization,
      Map<String, Object> changes) {}

  /** 分页信封：total 来自真实 count，不是前端猜的。 */
  public record AuditPage(List<AuditView> items, long total, int page, int size) {}

  /** 检索条件。三个维度（资源 / 操作人 / 时间）任意组合，缺省则全量。 */
  public record AuditQuery(
      String resourceType,
      UUID resourceId,
      UUID actorId,
      String action,
      Instant from,
      Instant to,
      int page,
      int size) {}

  /** 与控制器同构的租户内调用：解析会话 → 开租户事务 → 校验读权限。 */
  private <T> T call(String auth, Function<IdentityController.Access, T> work) {
    var access = identity.resolve(auth);
    return tenants.run(
        access.tenantId(),
        () -> {
          require(
              access.grants().stream()
                  .anyMatch(g -> g.scopeMode().equals("ALL") && g.permissions().contains(READ)),
              "当前角色没有审计日志查看权限");
          return work.apply(access);
        });
  }

  private static void require(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.FORBIDDEN, message);
  }

  private void check(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.BAD_REQUEST, message);
  }

  /**
   * 条件拼装：显式 SQL，不用字符串拼接用户输入。
   *
   * <p>时间列是 timestamptz，绑定走 {@link AuditLog#utc(Instant)}——直接用 {@code LocalDateTime} 会静默按
   * Asia/Shanghai 偏移 -28800 秒，检索窗口就整体错 8 小时。
   */
  private Condition where(AuditQuery query) {
    var condition = new Condition();
    if (query.resourceType() != null && !query.resourceType().isBlank()) {
      condition.add("resource_type = ?", query.resourceType().trim());
    }
    if (query.resourceId() != null) condition.add("resource_id = ?", query.resourceId());
    if (query.actorId() != null) condition.add("actor_id = ?", query.actorId());
    if (query.action() != null && !query.action().isBlank())
      condition.add("action = ?", query.action().trim());
    // 显式 cast：jOOQ 在纯文本语句里无法为 OffsetDateTime 推断类型，
    // 不写 ::timestamptz 会被驱动按 varchar 处理并报类型错误。
    if (query.from() != null)
      condition.add("occurred_at >= ?::timestamptz", AuditLog.utc(query.from()));
    if (query.to() != null)
      condition.add("occurred_at <= ?::timestamptz", AuditLog.utc(query.to()));
    return condition;
  }

  /** WHERE 子句与绑定值成对累积，避免条件与参数顺序错位。 */
  private static final class Condition {
    private final List<String> clauses = new ArrayList<>();
    private final List<Object> bindings = new ArrayList<>();

    void add(String clause, Object value) {
      clauses.add(clause);
      bindings.add(value);
    }

    String sql() {
      return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    }

    Object[] values() {
      return bindings.toArray();
    }
  }

  private AuditView view(org.jooq.Record row) {
    return new AuditView(
        row.get("id", UUID.class),
        row.get("action", String.class),
        row.get("resource_type", String.class),
        row.get("resource_id", UUID.class),
        row.get("resource_label", String.class),
        str(row.get("actor_id", UUID.class)),
        row.get("actor_name", String.class),
        row.get("occurred_at", Instant.class),
        Boolean.TRUE.equals(row.get("owner", Boolean.class)));
  }

  /**
   * 解析 ISO-8601 时刻。
   *
   * <p>手写解析而不是让 Spring 转 {@link Instant}：{@code Instant.toString()} 会输出 {@code
   * 2026-10-06T16:42:38.905614Z} 这种纳秒精度串，交给默认转换器容易踩到 格式不匹配的 400，而这里能给出确切原因。
   */
  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static Instant instant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw IdentityController.error(HttpStatus.BAD_REQUEST, "时间格式需为 ISO-8601，例如 " + Instant.EPOCH);
    }
  }

  @GetMapping
  public AuditPage search(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String resourceType,
      @RequestParam(required = false) UUID resourceId,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    var query =
        new AuditQuery(
            resourceType, resourceId, actorId, action, instant(from), instant(to), page, size);
    return call(auth, access -> page(query));
  }

  private AuditPage page(AuditQuery query) {
    check(query.page() >= 0, "页码不能为负");
    check(
        query.size() >= 1 && query.size() <= MAX_PAGE_SIZE, "每页条数需在 1 到 " + MAX_PAGE_SIZE + " 之间");
    check(
        query.from() == null || query.to() == null || !query.from().isAfter(query.to()),
        "开始时间不能晚于结束时间");
    var condition = where(query);
    long total =
        sql.fetchOne("select count(*) from identity_audit" + condition.sql(), condition.values())
            .get(0, Long.class);
    var records =
        sql.fetch(
            "select id,action,resource_type,resource_id,resource_label,actor_id,actor_name,"
                + " occurred_at,(authorization_context->>'owner')::boolean as owner from identity_audit"
                + condition.sql()
                + " order by occurred_at desc,id desc limit ? offset ?",
            bindings(condition, query.size(), (long) query.page() * query.size()));
    return new AuditPage(records.map(this::view), total, query.page(), query.size());
  }

  /** 把分页参数接在条件绑定之后，顺序与 SQL 里的占位符严格对应。 */
  private static Object[] bindings(Condition condition, int size, long offset) {
    var all = new ArrayList<>(condition.bindings);
    all.add(size);
    all.add(offset);
    return all.toArray();
  }

  /**
   * 单条变更详情。
   *
   * <p>跨租户访问在 RLS 下表现为「查不到」，因此返回 404 而不是 403： 403 会泄露「这条记录存在」，404 不泄露。
   */
  @GetMapping("/{id}")
  public AuditDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        access -> {
          var row =
              sql.fetchOne(
                  "select id,action,resource_type,resource_id,resource_label,actor_id,actor_name,"
                      + " occurred_at,authorization_context,changes,(authorization_context->>'owner')::boolean as owner"
                      + " from identity_audit where id=?",
                  id);
          if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "审计记录不存在或无权查看");
          var base = view(row);
          return new AuditDetail(
              base.id(),
              base.action(),
              base.resourceType(),
              base.resourceId(),
              base.resourceLabel(),
              base.actorId(),
              base.actorName(),
              base.occurredAt(),
              base.owner(),
              AuditLog.parseChanges(row.get("authorization_context", String.class)),
              AuditLog.parseChanges(row.get("changes", String.class)));
        });
  }

  /**
   * 导出 CSV。
   *
   * <p>走与检索完全相同的条件拼装，所以导出的就是当前查到的那些行，不是另一个数据源。 上限 {@value #MAX_EXPORT_ROWS}
   * 行：审计是长期留存的合规数据，一次导出全部历史既慢又容易 变成信息泄露面，超限明确报错让操作人收窄时间窗，而不是悄悄截断。
   */
  private static final int MAX_EXPORT_ROWS = 50_000;

  @GetMapping(value = "/export", produces = "text/csv")
  public ResponseEntity<byte[]> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String resourceType,
      @RequestParam(required = false) UUID resourceId,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    var bytes =
        call(
            auth,
            access -> {
              var query =
                  new AuditQuery(
                      resourceType, resourceId, actorId, action, instant(from), instant(to), 0, 1);
              var condition = where(query);
              long total =
                  sql.fetchOne(
                          "select count(*) from identity_audit" + condition.sql(),
                          condition.values())
                      .get(0, Long.class);
              check(total <= MAX_EXPORT_ROWS, "命中 " + total + " 条，超过单次导出上限，请收窄时间范围");
              var records =
                  sql.fetch(
                      "select id,action,resource_type,resource_id,resource_label,actor_id,actor_name,"
                          + " occurred_at,authorization_context,changes,(authorization_context->>'owner')::boolean as owner"
                          + " from identity_audit"
                          + condition.sql()
                          + " order by occurred_at desc,id desc limit ?",
                      bindings(condition, MAX_EXPORT_ROWS, 0L));
              return csv(records);
            });
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"identity-audit-" + Instant.now().toEpochMilli() + ".csv\"")
        .body(bytes);
  }

  /** CSV 渲染：字段按 RFC 4180 转义，中文表头让运营拿到文件就能看懂。 */
  private byte[] csv(List<org.jooq.Record> records) {
    var out = new StringBuilder("﻿");
    out.append(
        String.join(
            ",",
            List.of(
                "发生时间", "操作人", "操作人ID", "动作", "资源类型", "资源", "资源ID", "是否所有者", "授权上下文", "变更前",
                "变更后")));
    out.append('\n');
    for (var row : records) {
      var authorization = AuditLog.parseChanges(row.get("authorization_context", String.class));
      var changes = AuditLog.parseChanges(row.get("changes", String.class));
      var cells = new ArrayList<String>();
      cells.add(str(row.get("occurred_at", Instant.class)));
      cells.add(row.get("actor_name", String.class));
      cells.add(str(row.get("actor_id", UUID.class)));
      cells.add(row.get("action", String.class));
      cells.add(row.get("resource_type", String.class));
      cells.add(row.get("resource_label", String.class));
      cells.add(str(row.get("resource_id", UUID.class)));
      cells.add(String.valueOf(Boolean.TRUE.equals(row.get("owner", Boolean.class))));
      cells.add(render(authorization));
      cells.add(render(changes == null ? null : changes.get("before")));
      cells.add(render(changes == null ? null : changes.get("after")));
      out.append(String.join(",", cells.stream().map(AuditController::escape).toList()));
      out.append('\n');
    }
    return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  /** 变更详情在 CSV 里以 JSON 呈现：保留字段名，前端与 Excel 都能读懂改了什么。 */
  private static String render(Object value) {
    if (value == null) return "";
    if (value instanceof Map<?, ?> map) {
      var rendered = new LinkedHashMap<String, String>();
      for (var entry : map.entrySet())
        rendered.put(
            String.valueOf(entry.getKey()),
            entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
      return rendered.toString();
    }
    return String.valueOf(value);
  }

  private static String escape(String value) {
    String text = value == null ? "" : value;
    // 前导 =/+/-/@ 会被 Excel 当成公式执行：加前缀单引号切断，这是 CSV 注入的常规处置。
    if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
    return '"' + text.replace("\"", "\"\"") + '"';
  }
}
