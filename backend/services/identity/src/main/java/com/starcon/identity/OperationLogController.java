package com.starcon.identity;

import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
 * 17.5.3.2 操作日志读侧与链路关联视图。
 *
 * <p>蓝图要求「记录系统操作日志用于运维排障：登录、API 调用摘要、任务执行、错误堆栈，
 * 支持按用户/时间/请求 ID 关联链路」，输出是「操作日志 + 链路关联视图」。这里给出四件事：
 * 检索、按 ID 看详情、按请求 ID 的链路聚合、留存期清理。
 *
 * <p><b>与 {@link AuditController} 的分工</b>：审计回答「谁对什么做了什么、凭什么」，
 * 这里回答「这次调用发生了什么、慢在哪、错在哪」。所以本表没有变更前后值与授权上下文，
 * 审计表也没有登录流水、耗时与堆栈——两套字段、两种留存策略，不可互相替代。
 *
 * <p>租户隔离沿用 identity 既有纪律：租户来自 {@link IdentityController#resolve} 解析出的
 * 已认证成员关系（经 {@link TenantTransactions} 写入 {@code app.current_tenant}），
 * <b>不采信 {@code X-Tenant-Id} 头</b>；边界由 {@code operation_log} 的 FORCE RLS 兜底。
 */
@RestController
@RequestMapping("/identity/operation-log")
public class OperationLogController {
  private static final String READ = "identity.operation_log.read";
  private static final String PURGE = "identity.operation_log.purge";
  /** 单页上限，防止一次把整段留存期的排障日志拉进内存。 */
  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;
  private final OperationLog log;

  public OperationLogController(
      DSLContext sql, IdentityController identity, PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
    this.log = new OperationLog(sql);
  }

  /** 列表项：够排障判断「发生了什么」，堆栈与明细走详情端点按需拉取。 */
  public record OperationLogView(
      UUID id,
      String eventType,
      String level,
      String requestId,
      @Schema(nullable = true) String traceId,
      @Schema(nullable = true) String actorId,
      @Schema(nullable = true) String actorName,
      String summary,
      @Schema(nullable = true) String httpMethod,
      @Schema(nullable = true) String path,
      @Schema(nullable = true) Integer statusCode,
      @Schema(nullable = true) BigDecimal durationMs,
      @Schema(nullable = true) String taskName,
      @Schema(nullable = true) String taskStatus,
      @Schema(nullable = true) String errorClass,
      Instant occurredAt) {}

  /** 详情：在列表项基础上补错误堆栈与技术明细。 */
  public record OperationLogDetail(
      UUID id,
      String eventType,
      String level,
      String requestId,
      @Schema(nullable = true) String traceId,
      @Schema(nullable = true) String actorId,
      @Schema(nullable = true) String actorName,
      String summary,
      @Schema(nullable = true) String httpMethod,
      @Schema(nullable = true) String path,
      @Schema(nullable = true) Integer statusCode,
      @Schema(nullable = true) BigDecimal durationMs,
      @Schema(nullable = true) String taskName,
      @Schema(nullable = true) String taskStatus,
      @Schema(nullable = true) String errorClass,
      @Schema(nullable = true) String errorMessage,
      @Schema(nullable = true) String stackTrace,
      @Schema(nullable = true) Boolean stackTruncated,
      Instant occurredAt,
      Instant expiresAt,
      Map<String, Object> detail) {}

  /** 分页信封：total 来自真实 count。 */
  public record OperationLogPage(List<OperationLogView> items, long total, int page, int size) {}

  /** 检索条件。用户 / 时间 / 请求 ID 三个维度任意组合，缺省则全量。 */
  public record OperationLogQuery(
      UUID actorId,
      String eventType,
      String level,
      String requestId,
      Instant from,
      Instant to,
      int page,
      int size) {}

  /**
   * 链路关联视图。
   *
   * <p>{@code entries} 是这一次请求产生的全部日志（真实时序，不是前端拼接）；
   * {@code stats} 由数据库聚合得出，回答「这条链路一共几步、错了几步、总共慢多少」。
   */
  public record OperationLogTrace(
      String requestId,
      List<OperationLogView> entries,
      long entryCount,
      long errorCount,
      long warningCount,
      BigDecimal totalDurationMs,
      BigDecimal spanMs,
      // nullable：请求 ID 没有对应日志时这两个时刻不存在。
      // 必须显式声明，否则契约会给出 string 而实际可能返回 null——前端就会把
      // 「没记录」误当成「耗时 0」，那正是排障里最容易得出错误结论的地方。
      @Schema(nullable = true) Instant firstAt,
      @Schema(nullable = true) Instant lastAt) {}

  /** 清理结果。removed 来自真实 DELETE 的 ROW_COUNT，不是估算。 */
  public record OperationLogPurge(long removed, Instant before) {}

  /** 与控制器同构的租户内调用：解析会话 → 开租户事务 → 校验读权限。 */
  private <T> T call(String auth, Function<IdentityController.Access, T> work) {
    return call(auth, READ, "当前角色没有操作日志查看权限", work);
  }

  /** 同上，但校验指定权限。清理与查看分开授权，所以权限码要能传进来。 */
  private <T> T call(
      String auth, String permission, String message, Function<IdentityController.Access, T> work) {
    var access = identity.resolve(auth);
    return tenants.run(
        access.tenantId(),
        () -> {
          require(
              access.grants().stream()
                  .anyMatch(g -> g.scopeMode().equals("ALL") && g.permissions().contains(permission)),
              message);
          return work.apply(access);
        });
  }

  private static void require(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.FORBIDDEN, message);
  }

  private void check(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.BAD_REQUEST, message);
  }

  /** 条件与绑定值成对累积，避免条件与参数顺序错位（尤其是 {@code ?::timestamptz}）。 */
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

  private Condition where(OperationLogQuery query) {
    var condition = new Condition();
    if (query.actorId() != null) condition.add("actor_id = ?", query.actorId());
    if (query.requestId() != null && !query.requestId().isBlank())
      condition.add("request_id = ?", query.requestId().trim());
    if (query.eventType() != null && !query.eventType().isBlank()) {
      // 事件类型进 SQL 前先校验：非法值直接 400，而不是被当成「查不到」的空结果，
      // 那会让调用方误以为这个时间段没有日志。
      check(
          OperationLog.EVENT_TYPES.contains(query.eventType().trim()),
          "事件类型需为 " + String.join("、", OperationLog.EVENT_TYPES) + " 之一");
      condition.add("event_type = ?", query.eventType().trim());
    }
    if (query.level() != null && !query.level().isBlank()) {
      check(
          List.of("INFO", "WARNING", "ERROR").contains(query.level().trim()),
          "级别需为 INFO、WARNING 或 ERROR 之一");
      condition.add("level = ?", query.level().trim());
    }
    // 显式 cast：jOOQ 在纯文本语句里无法为 OffsetDateTime 推断类型。
    if (query.from() != null) condition.add("occurred_at >= ?::timestamptz", OperationLog.utc(query.from()));
    if (query.to() != null) condition.add("occurred_at <= ?::timestamptz", OperationLog.utc(query.to()));
    return condition;
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /** 手写解析 ISO-8601 时刻，好给出确切原因而不是笼统的 400。 */
  private static Instant instant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw IdentityController.error(
          HttpStatus.BAD_REQUEST, "时间格式需为 ISO-8601，例如 " + Instant.EPOCH);
    }
  }

  private OperationLogView view(org.jooq.Record row) {
    return new OperationLogView(
        row.get("id", UUID.class),
        row.get("event_type", String.class),
        row.get("level", String.class),
        row.get("request_id", String.class),
        row.get("trace_id", String.class),
        str(row.get("actor_id", UUID.class)),
        row.get("actor_name", String.class),
        row.get("summary", String.class),
        row.get("http_method", String.class),
        row.get("path", String.class),
        row.get("status_code", Integer.class),
        row.get("duration_ms", BigDecimal.class),
        row.get("task_name", String.class),
        row.get("task_status", String.class),
        row.get("error_class", String.class),
        row.get("occurred_at", Instant.class));
  }

  private static final String LIST_COLUMNS =
      "id,event_type,level,request_id,trace_id,actor_id,actor_name,summary,"
          + "http_method,path,status_code,duration_ms,task_name,task_status,error_class,occurred_at";

  private static Object[] bindings(Condition condition, int size, long offset) {
    var all = new ArrayList<>(condition.bindings);
    all.add(size);
    all.add(offset);
    return all.toArray();
  }

  @GetMapping
  public OperationLogPage search(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String eventType,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) String requestId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    var query =
        new OperationLogQuery(actorId, eventType, level, requestId, instant(from), instant(to), page, size);
    return call(auth, access -> paged(query));
  }

  private OperationLogPage paged(OperationLogQuery query) {
    check(query.page() >= 0, "页码不能为负");
    check(query.size() >= 1 && query.size() <= MAX_PAGE_SIZE, "每页条数需在 1 到 " + MAX_PAGE_SIZE + " 之间");
    check(
        query.from() == null || query.to() == null || !query.from().isAfter(query.to()),
        "开始时间不能晚于结束时间");
    var condition = where(query);
    long total =
        sql.fetchOne("select count(*) from operation_log" + condition.sql(), condition.values())
            .get(0, Long.class);
    var records =
        sql.fetch(
            "select " + LIST_COLUMNS + " from operation_log" + condition.sql()
                + " order by occurred_at desc,id desc limit ? offset ?",
            bindings(condition, query.size(), (long) query.page() * query.size()));
    return new OperationLogPage(records.map(this::view), total, query.page(), query.size());
  }

  /**
   * 单条详情。
   *
   * <p>跨租户访问在 RLS 下表现为「查不到」，因此返回 404 而不是 403：
   * 403 会泄露「这条记录存在」，404 不泄露。
   */
  @GetMapping("/{id}")
  public OperationLogDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        access -> {
          var row =
              sql.fetchOne(
                  "select " + LIST_COLUMNS
                      + ",error_message,stack_trace,expires_at,detail from operation_log where id=?",
                  id);
          if (row == null)
            throw IdentityController.error(HttpStatus.NOT_FOUND, "操作日志不存在或无权查看");
          var base = view(row);
          var stack = row.get("stack_trace", String.class);
          return new OperationLogDetail(
              base.id(), base.eventType(), base.level(), base.requestId(), base.traceId(),
              base.actorId(), base.actorName(), base.summary(), base.httpMethod(), base.path(),
              base.statusCode(), base.durationMs(), base.taskName(), base.taskStatus(),
              base.errorClass(), row.get("error_message", String.class), stack,
              stack != null && stack.endsWith(OperationLog.TRUNCATED),
              base.occurredAt(), row.get("expires_at", Instant.class),
              OperationLog.parseDetail(row.get("detail", String.class)));
        });
  }

  /**
   * 链路关联视图：按请求 ID 把一次调用引发的多条日志串起来。
   *
   * <p>这是本模块区别于审计日志的核心交付物。统计量由数据库聚合（{@code operation_trace}），
   * 不是前端把列表数一遍——所以「这条链路一共几步、错几步、慢多少」与服务端口径一致。
   *
   * <p>请求 ID 不存在时返回空链路而不是 404：排障的人拿到的是「这个 ID 没记录」这一事实，
   * 而不是「你调错了」。
   */
  @GetMapping("/trace/{requestId}")
  public OperationLogTrace trace(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String requestId) {
    return call(
        auth,
        access -> {
          check(requestId.length() <= 64, "请求 ID 长度不能超过 64");
          var wanted = requestId.trim();
          check(!wanted.isEmpty(), "请求 ID 不能为空");
          var entries =
              sql.fetch(
                  "select " + LIST_COLUMNS + " from operation_log where request_id=?"
                      + " order by occurred_at,id",
                  wanted);
          var stats =
              sql.fetchOne(
                  "select request_id,entry_count,error_count,warning_count,total_duration_ms,"
                      + " span_ms,first_at,last_at from operation_trace(?,?)",
                  IdentityController.hash(identity.token(auth)), wanted);
          if (stats == null)
            return new OperationLogTrace(
                wanted, List.of(), 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null, null);
          return new OperationLogTrace(
              stats.get("request_id", String.class),
              entries.map(this::view),
              stats.get("entry_count", Long.class),
              stats.get("error_count", Long.class),
              stats.get("warning_count", Long.class),
              stats.get("total_duration_ms", BigDecimal.class),
              stats.get("span_ms", BigDecimal.class),
              stats.get("first_at", Instant.class),
              stats.get("last_at", Instant.class));
        });
  }

  /**
   * 留存期清理：删除已到期（{@code expires_at < before}）的本租户日志。
   *
   * <p>与审计日志的硬性对照：审计不可删（V7 触发器挡住），操作日志必须能被清理——
   * 蓝图说它「可短期留存」，那就得真的能到期删除，否则「短期」只是修辞。
   * 权限由 {@code purge_operation_log} 在数据库层校验，必须是 Owner + ALL 范围。
   */
  @PostMapping("/purge")
  public OperationLogPurge purge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String before) {
    var cutoff = instant(before);
    return call(
        auth,
        PURGE,
        "当前角色没有操作日志清理权限",
        access -> {
          // 归属判定在数据库层（purge_operation_log 要求 Owner + ALL），
          // 这里显式校验一次是为了给出可读的中文原因，而不是让数据库的 42501 冒泡。
          require(access.owner(), "只有租户所有者可以清理操作日志");
          var effective = cutoff == null ? Instant.now() : cutoff;
          var started = System.nanoTime();
          var removed = 0L;
          Throwable failure = null;
          try {
            removed =
                sql.fetchOne(
                        "select purge_operation_log(?,?::timestamptz)",
                        IdentityController.hash(identity.token(auth)), OperationLog.utc(effective))
                    .get(0, Long.class);
          } catch (Exception e) {
            failure = e;
            throw e;
          } finally {
            // 清理本身是一次「任务执行」，必须留下 TASK_RUN 记录：
            // 排障时最常问的就是「上周谁清过日志、删了多少」，
            // 而删除动作自身不留痕的话，删了就再也说不清了。
            var millis =
                BigDecimal.valueOf(System.nanoTime() - started)
                    .divide(BigDecimal.valueOf(1_000_000), 3, java.math.RoundingMode.HALF_UP);
            try {
              log.task(
                  access.tenantId(), RequestTrace.requestId(), "操作日志留存期清理",
                  failure == null ? "SUCCEEDED" : "FAILED", millis,
                  Map.of("removed", removed, "cutoff", effective.toString()), failure);
            } catch (Exception ignored) {
              // 清理已生效与否由上面的返回值为准，日志失败不覆盖它。
            }
          }
          return new OperationLogPurge(removed, effective);
        });
  }

  /**
   * 导出 CSV。
   *
   * <p>走与检索完全相同的条件拼装，所以导出的就是当前查到的那些行。
   * 上限 {@value #MAX_EXPORT_ROWS} 行：排障日志量大且含技术细节，一次导出全部既慢又扩大泄露面，
   * 超限明确报错让操作人收窄时间窗，而不是悄悄截断。
   */
  private static final int MAX_EXPORT_ROWS = 50_000;

  @GetMapping(value = "/export", produces = "text/csv")
  public ResponseEntity<byte[]> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID actorId,
      @RequestParam(required = false) String eventType,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) String requestId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    var bytes =
        call(
            auth,
            access -> {
              var query = new OperationLogQuery(actorId, eventType, level, requestId, instant(from), instant(to), 0, 1);
              var condition = where(query);
              long total =
                  sql.fetchOne(
                          "select count(*) from operation_log" + condition.sql(), condition.values())
                      .get(0, Long.class);
              check(total <= MAX_EXPORT_ROWS, "命中 " + total + " 条，超过单次导出上限，请收窄时间范围");
              var records =
                  sql.fetch(
                      "select " + LIST_COLUMNS + " from operation_log" + condition.sql()
                          + " order by occurred_at desc,id desc limit ?",
                      bindings(condition, MAX_EXPORT_ROWS, 0L));
              return csv(records);
            });
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"identity-operation-log-" + Instant.now().toEpochMilli() + ".csv\"")
        .body(bytes);
  }

  /** CSV 渲染：字段按 RFC 4180 转义，中文表头让运维拿到文件就能看懂。 */
  private byte[] csv(List<org.jooq.Record> records) {
    var out = new StringBuilder("﻿");
    out.append(
        String.join(
            ",",
            List.of(
                "发生时间", "事件类型", "级别", "请求ID", "链路ID", "操作人", "操作人ID", "摘要",
                "方法", "路径", "状态码", "耗时毫秒", "任务名", "任务状态", "错误类型")));
    out.append('\n');
    for (var row : records) {
      var cells = new ArrayList<String>();
      cells.add(str(row.get("occurred_at", Instant.class)));
      cells.add(row.get("event_type", String.class));
      cells.add(row.get("level", String.class));
      cells.add(row.get("request_id", String.class));
      cells.add(row.get("trace_id", String.class));
      cells.add(row.get("actor_name", String.class));
      cells.add(str(row.get("actor_id", UUID.class)));
      cells.add(row.get("summary", String.class));
      cells.add(row.get("http_method", String.class));
      cells.add(row.get("path", String.class));
      cells.add(str(row.get("status_code", Integer.class)));
      cells.add(str(row.get("duration_ms", BigDecimal.class)));
      cells.add(row.get("task_name", String.class));
      cells.add(row.get("task_status", String.class));
      cells.add(row.get("error_class", String.class));
      out.append(String.join(",", cells.stream().map(OperationLogController::escape).toList()));
      out.append('\n');
    }
    return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String escape(String value) {
    String text = value == null ? "" : value;
    // 前导 =/+/-/@ 会被 Excel 当成公式执行：加前缀单引号切断，这是 CSV 注入的常规处置。
    if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
    return '"' + text.replace("\"", "\"\"") + '"';
  }
}