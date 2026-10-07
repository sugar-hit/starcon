package com.starcon.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * 17.5.3.2 操作日志写入侧。
 *
 * <p>所有运行事件都经这一个入口落表，所以「事件类型、级别、请求 ID、脱敏、堆栈截断」的口径只有一处，
 * 不会因为某个调用点自己拼参数而漏字段。
 *
 * <p><b>与 {@link AuditLog} 的边界</b>：审计是合规证据（脱敏、不可篡改、长期留存），
 * 这里是排障证据（含技术细节、可清理）。两者字段几乎没有交集，也不共用表——
 * 蓝图明确要求二者不可互相替代。
 *
 * <p><b>但「含技术细节」不等于「可以无节制写入」</b>：这里仍然做三件事——
 * <ol>
 *   <li>技术细节走 {@link AuditRedactor} 同一套脱敏口径，密码与令牌不会进库；
 *   <li>错误堆栈截断到 {@value #MAX_STACK} 字符并追加可见的截断标记，
 *       库层还有 CHECK 兜底，不会无界写入；
 *   <li>只记 API 调用<b>摘要</b>（方法、路径、状态码、耗时），<b>不记请求体</b>——
 *       请求体里就是业务数据与凭证，属于审计域的内容，不属于排障域。
 * </ol>
 *
 * <p>时间绑定见 {@link AuditLog#utc}：{@code OffsetDateTime} 是唯一不会静默偏 -28800 秒的姿势。
 */
final class OperationLog {
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 堆栈截断上限。超过部分丢弃并留下标记，让读的人知道「这里被截过」而不是以为堆栈到此为止。 */
  static final int MAX_STACK = 8000;
  static final String TRUNCATED = "…[已截断]";
  /** 默认留存天数：蓝图说排障日志「可短期留存」，这个值就是「短期」的具体口径。 */
  static final int RETENTION_DAYS = 90;

  private final DSLContext sql;

  OperationLog(DSLContext sql) {
    this.sql = sql;
  }

  /** 供同包的采集点读取底层连接（操作人可读名这类窄查询走既有 SQL 函数，不另开事务）。 */
  DSLContext sql() {
    return sql;
  }

  /** 与 {@link AuditLog} 相同的绑定姿势，直接复用避免两处实现漂移。 */
  static OffsetDateTime utc(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private String json(Object value) {
    if (value == null) return "{}";
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("操作日志明细序列化失败", e);
    }
  }

  /** 堆栈截断：超长时截断并追加标记，标记本身计入长度上限，所以库层 CHECK 不会拒绝它。 */
  static String stack(String rendered) {
    if (rendered == null) return null;
    if (rendered.length() <= MAX_STACK) return rendered;
    return rendered.substring(0, MAX_STACK - TRUNCATED.length()) + TRUNCATED;
  }

  /** 渲染异常堆栈。带 message 行的完整堆栈才有排障价值，只留类型名等于没记。 */
  static String render(Throwable error) {
    if (error == null) return null;
    var text = new StringBuilder();
    text.append(error.getClass().getName());
    if (error.getMessage() != null) text.append(": ").append(error.getMessage());
    var cause = error.getCause();
    if (cause != null && cause != error) text.append(" | caused by ").append(cause.getClass().getName());
    for (var frame : error.getStackTrace()) text.append("\n\tat ").append(frame);
    return stack(text.toString());
  }

  /** 一条待写入的操作日志。所有写入方法最终都走这里。 */
  record Entry(
      UUID tenantId,
      String eventType,
      String level,
      String requestId,
      String traceId,
      UUID actorId,
      String actorName,
      String summary,
      String httpMethod,
      String path,
      Integer statusCode,
      BigDecimal durationMs,
      String taskName,
      String taskStatus,
      String errorClass,
      String errorMessage,
      String stackTrace,
      Map<String, Object> detail,
      Instant occurredAt) {}

  /**
   * 记录一条操作日志。
   *
   * <p>调用方必须已经处于租户事务内（{@link com.starcon.tenancy.TenantTransactions}），
   * 否则 RLS 的 WITH CHECK 会拒写——这是刻意的：操作日志同样不允许跨租户写入。
   */
  void record(Entry entry) {
    var detail = AuditRedactor.redactAll(entry.detail() == null ? Map.of() : entry.detail());
    var occurredAt = entry.occurredAt() == null ? Instant.now() : entry.occurredAt();
    // 两个 timestamptz 都要显式 cast：jOOQ 在纯文本语句里无法为 OffsetDateTime 推断类型，
    // 不写 cast 会被驱动按 varchar 处理，PG 直接报「timestamp with time zone 但表达式是 character varying」。
    //
    // 值列表末位刻意留一个带 cast 的占位符而不是裸 `?`：`?::timestamptz` 落在末位会让 jOOQ
    // 的类型解析把后续绑定整体错位一格（加了反而更错），所以 expires_at 也带 cast 来收尾。
    sql.execute(
        "insert into operation_log(id,tenant_id,event_type,level,request_id,trace_id,"
            + " actor_id,actor_name,summary,http_method,path,status_code,duration_ms,"
            + " task_name,task_status,error_class,error_message,stack_trace,detail,occurred_at,expires_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::timestamptz,?::timestamptz)",
        Ids.next(),
        entry.tenantId(),
        entry.eventType(),
        entry.level(),
        entry.requestId(),
        entry.traceId(),
        entry.actorId(),
        entry.actorName(),
        entry.summary(),
        entry.httpMethod(),
        entry.path(),
        entry.statusCode(),
        entry.durationMs(),
        entry.taskName(),
        entry.taskStatus(),
        entry.errorClass(),
        entry.errorMessage(),
        entry.stackTrace(),
        json(detail),
        utc(occurredAt),
        OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC).plusDays(RETENTION_DAYS));
  }

  // ------------------------------------------------------------------
  // 四类事件的便捷入口（蓝图点名的事件）
  // ------------------------------------------------------------------

  /**
   * 登录事件。
   *
   * <p>登录必须能独立于会话存在：失败的登录没有已认证成员，所以 actor 允许为 null，
   * 记的是「谁尝试用哪个账号进来」而不是「谁成功了」。
   */
  void login(
      UUID tenantId,
      String requestId,
      String actorName,
      boolean success,
      String remoteHint,
      Map<String, Object> detail) {
    var merged = new LinkedHashMap<String, Object>();
    merged.put("username", actorName);
    if (remoteHint != null) merged.put("remote", remoteHint);
    if (detail != null) merged.putAll(detail);
    record(
        new Entry(
            tenantId, "LOGIN", success ? "INFO" : "ERROR", requestId, null, null, actorName,
            success ? "登录成功" : "登录失败", null, null, null, null, null, null, null, null, null,
            merged, null));
  }

  /** 登出。会话已撤销，所以只记「发生过登出」，不再附带令牌细节。 */
  void logout(UUID tenantId, String requestId, UUID actorId, String actorName) {
    record(
        new Entry(
            tenantId, "LOGOUT", "INFO", requestId, null, actorId, actorName, "退出登录",
            null, null, null, null, null, null, null, null, null, Map.of(), null));
  }

  /** API 调用摘要。只记方法/路径/状态码/耗时，**不记请求体与查询串**。 */
  void apiCall(
      UUID tenantId,
      String requestId,
      String traceId,
      UUID actorId,
      String actorName,
      String method,
      String path,
      int status,
      BigDecimal durationMs,
      boolean failed) {
    record(
        new Entry(
            tenantId, "API_CALL", levelFor(status), requestId, traceId, actorId, actorName,
            method + " " + path + " → " + status,
            method, path, status, durationMs, null, null, null, null, null, Map.of(), null));
  }

  /** 任务执行。影响行数等结果放进 detail，便于回答「跑了没有、跑了多少」。 */
  void task(
      UUID tenantId,
      String requestId,
      String taskName,
      String taskStatus,
      BigDecimal durationMs,
      Map<String, Object> detail,
      Throwable error) {
    record(
        new Entry(
            tenantId, "TASK_RUN", "SUCCEEDED".equals(taskStatus) ? "INFO" : "ERROR",
            requestId, null, null, null, "任务 " + taskName + " " + taskStatus,
            null, null, null, durationMs, taskName, taskStatus,
            error == null ? null : error.getClass().getName(),
            error == null ? null : error.getMessage(),
            render(error), detail, null));
  }

  /** 错误堆栈。这是排障最常用的一条：错误摘要 + 截断后的堆栈。 */
  void error(
      UUID tenantId,
      String requestId,
      String traceId,
      UUID actorId,
      String actorName,
      String summary,
      Throwable error,
      Map<String, Object> detail) {
    record(
        new Entry(
            tenantId, "ERROR", "ERROR", requestId, traceId, actorId, actorName, summary,
            null, null, null, null, null, null,
            error == null ? null : error.getClass().getName(),
            error == null ? null : error.getMessage(),
            render(error), detail, null));
  }

  /** 状态码映射：4xx 是调用方的问题（WARNING），5xx 是服务端的问题（ERROR）。 */
  static String levelFor(int status) {
    if (status >= 500) return "ERROR";
    if (status >= 400) return "WARNING";
    return "INFO";
  }

  /** 解析 detail 列；损坏时返回空对象而不是抛错——一条坏明细不该让整个列表 500。 */
  static Map<String, Object> parseDetail(String raw) {
    if (raw == null || raw.isBlank()) return Map.of();
    try {
      @SuppressWarnings("unchecked")
      var parsed = JSON.readValue(raw, Map.class);
      return parsed == null ? Map.of() : parsed;
    } catch (Exception e) {
      return Map.of();
    }
  }

  /** 事件类型全集，供查询端的入参校验使用——与迁移里的 CHECK 保持同一份取值。 */
  static final List<String> EVENT_TYPES =
      List.of("LOGIN", "LOGOUT", "API_CALL", "TASK_RUN", "ERROR");
}