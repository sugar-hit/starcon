package com.starcon.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 16.5.2.1 用的 identity 桩（测试专用）。
 *
 * <p>🔴 本仓的登录事件真源头是 identity 的 {@code operation_log}
 * （event_type = LOGIN / LOGOUT），读面是 {@code GET /identity/operation-log}。
 * 本桩把那三个读面照 identity 的真实响应形状做出来：
 *
 * <ul>
 *   <li>{@code /internal/access} —— {@link AccessService} 解析已认证成员关系用的；</li>
 *   <li>{@code /identity/operation-log} —— 登录 / 登出事件（<b>可配置成空 / 可达 / 拒绝 / 5xx</b>）；</li>
 *   <li>{@code /identity/auth/session} —— 当前会话。</li>
 * </ul>
 *
 * <p>🔴 <b>「上游不可达」必须能被真的造出来</b>：空集合与「读不到」是两件事，
 * 桩必须能分别表达，否则那条断言就是假的。
 */
final class AccessSecurityIdentityStub {

  /** 一条登录 / 登出事件。 */
  record Event(
      UUID id,
      String eventType,
      String level,
      String requestId,
      String actorName,
      String summary,
      Instant occurredAt) {}

  /** 桩的运行模式。 */
  enum Mode {
    /** 正常返回。 */
    OK,
    /** 返回 403（调用方缺权限）。 */
    FORBIDDEN,
    /** 返回 503（上游不可用）。 */
    UNAVAILABLE,
    /** 401（令牌失效）。 */
    UNAUTHORIZED
  }

  private final HttpServer server;
  private final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  private final ObjectMapper json = new ObjectMapper();

  volatile Mode mode = Mode.OK;
  volatile List<Event> events = new ArrayList<>();
  volatile boolean sessionPresent = true;

  AccessSecurityIdentityStub() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/internal/access", this::access);
    server.createContext("/identity/operation-log", this::operationLog);
    server.createContext("/identity/auth/session", this::session);
    server.start();
  }

  String base() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  void register(String bearer, AccessService.Access actor) {
    actors.put(bearer, actor);
  }

  void reset() {
    mode = Mode.OK;
    events = new ArrayList<>();
    sessionPresent = true;
  }

  /** 造 n 条「登录失败」事件（level=ERROR）。 */
  void failLogins(String username, int count, Instant base) {
    List<Event> list = new ArrayList<>(events);
    for (int i = 0; i < count; i++) {
      list.add(
          new Event(
              UUID.randomUUID(),
              "LOGIN",
              "ERROR",
              "req-" + i,
              username,
              "登录失败：账号或密码不正确",
              base.plusSeconds(i)));
    }
    events = list;
  }

  /** 造 n 条「登录成功」事件（level=INFO）。 */
  void successLogins(String username, int count, Instant base) {
    List<Event> list = new ArrayList<>(events);
    for (int i = 0; i < count; i++) {
      list.add(
          new Event(
              UUID.randomUUID(),
              "LOGIN",
              "INFO",
              "req-ok-" + i,
              username,
              "登录成功",
              base.plusSeconds(i)));
    }
    events = list;
  }

  private void access(com.sun.net.httpserver.HttpExchange e) throws java.io.IOException {
    AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
    if (who == null) {
      e.sendResponseHeaders(401, -1);
      e.close();
      return;
    }
    byte[] body = json.writeValueAsBytes(who);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, body.length);
    e.getResponseBody().write(body);
    e.close();
  }

  private void operationLog(com.sun.net.httpserver.HttpExchange e) throws java.io.IOException {
    if (mode == Mode.UNAVAILABLE) {
      e.sendResponseHeaders(503, -1);
      e.close();
      return;
    }
    if (mode == Mode.FORBIDDEN) {
      e.sendResponseHeaders(403, -1);
      e.close();
      return;
    }
    if (mode == Mode.UNAUTHORIZED) {
      e.sendResponseHeaders(401, -1);
      e.close();
      return;
    }
    String query = e.getRequestURI().getQuery();
    String wanted = query == null ? null : param(query, "eventType");
    List<Map<String, Object>> items = new ArrayList<>();
    for (Event event : events) {
      if (wanted != null && !wanted.equalsIgnoreCase(event.eventType())) continue;
      Map<String, Object> row = new java.util.LinkedHashMap<>();
      row.put("id", event.id().toString());
      row.put("eventType", event.eventType());
      row.put("level", event.level());
      row.put("requestId", event.requestId());
      row.put("traceId", null);
      row.put("actorId", null);
      row.put("actorName", event.actorName());
      row.put("summary", event.summary());
      row.put("occurredAt", event.occurredAt().toString());
      // 🔴 identity 的登录事件**不带来源 IP**（remoteHint 恒为 null）—— 桩如实复刻这一点。
      row.put("detail", Map.of("username", event.actorName()));
      items.add(row);
    }
    Map<String, Object> page = new java.util.LinkedHashMap<>();
    page.put("items", items);
    page.put("total", items.size());
    page.put("page", 0);
    page.put("size", 200);
    byte[] body = json.writeValueAsBytes(page);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, body.length);
    e.getResponseBody().write(body);
    e.close();
  }

  private void session(com.sun.net.httpserver.HttpExchange e) throws java.io.IOException {
    if (mode == Mode.UNAVAILABLE) {
      e.sendResponseHeaders(503, -1);
      e.close();
      return;
    }
    if (mode == Mode.UNAUTHORIZED || mode == Mode.FORBIDDEN) {
      e.sendResponseHeaders(mode == Mode.FORBIDDEN ? 403 : 401, -1);
      e.close();
      return;
    }
    Map<String, Object> row = new java.util.LinkedHashMap<>();
    row.put("userId", UUID.randomUUID().toString());
    row.put("tenantId", UUID.randomUUID().toString());
    row.put("tenantName", "Test");
    row.put("membershipId", UUID.randomUUID().toString());
    row.put("permissions", List.of());
    row.put("grants", List.of());
    byte[] body = json.writeValueAsBytes(row);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(sessionPresent ? 200 : 401, body.length);
    e.getResponseBody().write(body);
    e.close();
  }

  private static String param(String query, String key) {
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(key)) {
        return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
      }
    }
    return null;
  }
}
