package com.starcon.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 17.5.3.2 请求级链路上下文。
 *
 * <p>一次 HTTP 请求会先后产生多条操作日志（登录、API 调用摘要、错误），
 * 它们必须共享同一个 {@code request_id}，否则「链路关联」无从谈起。
 *
 * <p>request_id 的来源有优先级：
 * <ol>
 *   <li>上游网关传入的 {@code X-Request-Id} —— 这样排障时可以直接拿网关看到的 ID 反查；
 *   <li>本次请求内新生成。
 * </ol>
 * 生成用 {@link java.util.UUID#randomUUID()} 而不是 {@code Ids.next()}：
 * 这是「本次 HTTP 调用的标识」，不是业务实体的主键，用 UUIDv4 更贴切，
 * 而且它的唯一性只需要在请求维度成立，不需要全局单调。
 *
 * <p>存在 {@link RequestContextHolder} 的请求作用域里而不是 ThreadLocal：
 * Spring 的请求作用域在请求结束时自动清理，线程池复用时不会像裸 ThreadLocal 那样串数据。
 */
final class RequestTrace {
  /** 与网关约定的请求 ID 头。名字沿用业界惯例，便于运维把手上的网关日志直接粘过来查。 */
  static final String HEADER = "X-Request-Id";
  private static final String REQUEST_ID = RequestTrace.class.getName() + ".requestId";
  private static final String TRACE_ID = RequestTrace.class.getName() + ".traceId";
  /** 头长度上限：不受限的头会被当作超长值塞进库，污染链路检索。 */
  private static final int MAX_LENGTH = 64;

  private RequestTrace() {}

  private static RequestAttributes attributes() {
    return RequestContextHolder.getRequestAttributes();
  }

  /** 本次请求的 request_id；不在请求上下文中（例如后台任务）时返回 null，如实表达「没有链路」。 */
  static String requestId() {
    var attrs = attributes();
    return attrs == null ? null : (String) attrs.getAttribute(REQUEST_ID, RequestAttributes.SCOPE_REQUEST);
  }

  /** 本次请求的链路 ID（网关传下来的跨服务 trace），没有则为空。 */
  static String traceId() {
    var attrs = attributes();
    return attrs == null ? null : (String) attrs.getAttribute(TRACE_ID, RequestAttributes.SCOPE_REQUEST);
  }

  /**
   * 为一次请求确定 request_id / trace_id。
   *
   * <p>由 {@link OperationLogInterceptor} 在请求入口调用一次，后续所有写入点只读。
   */
  static void begin(HttpServletRequest request) {
    var attrs = attributes();
    if (attrs == null) return;
    var inbound = header(request, HEADER);
    var requestId = inbound == null ? UUID.randomUUID().toString() : inbound;
    attrs.setAttribute(REQUEST_ID, clamp(requestId), RequestAttributes.SCOPE_REQUEST);
    // 跨服务链路 ID 单独一个头，与 request_id 分开存（两者语义不同，混用会丢掉跨服务串联）。
    attrs.setAttribute(TRACE_ID, clamp(header(request, "X-Trace-Id")), RequestAttributes.SCOPE_REQUEST);
  }

  /** 头值截断到上限：外部传入的字符串不可信，不设上限就等于给了链路检索一个注入面。 */
  private static String clamp(String value) {
    if (value == null) return null;
    var trimmed = value.trim();
    if (trimmed.isEmpty()) return null;
    return trimmed.length() <= MAX_LENGTH ? trimmed : trimmed.substring(0, MAX_LENGTH);
  }

  private static String header(HttpServletRequest request, String name) {
    var value = request.getHeader(name);
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * 取不到请求上下文时的兜底 request_id。
   *
   * <p>用于后台任务这类确实没有 HTTP 请求的写入点：给它一个独立 ID，
   * 而不是让 request_id 为空——否则它就成了链路上永远查不到的一段。
   */
  static String detachedRequestId(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}