package com.starcon.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 16.5.1.1 测试用的**本地 trade 服务**（9.2 审单那一跳）。
 *
 * <p>这不是 mock：它是一个真的 HTTP 服务器，跑在真的 socket 上，真的按 trade 的
 * {@code GET /trade/order-review/buyer-profiles} 形状回 JSON。
 *
 * <p>之所以需要它，是因为本模块最要紧的一条不变量落在**跨服务那一跳**上：
 * <b>9.2 读不到时必须回 503，而不是「这个买家没有风险」</b>。
 * 把端口打桩成固定返回值的话，这条就测不出来 —— 而这条恰恰是黑名单系统里
 * 最致命的一种撒谎的防线。
 */
final class BuyerBlacklistTradeStub implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpServer server;
  private final String base;

  /** 买家画像（buyerKey → 一行）。 */
  final Map<String, Map<String, Object>> profiles = new ConcurrentHashMap<>();

  // ---- 剧本开关 ----
  /** 让 9.2 拒绝（403）：上游正常但没权限。 */
  volatile boolean profilesForbidden = false;
  /** 让 9.2 直接不可达（连接被拒）：模拟 trade 挂了。 */
  volatile boolean unreachable = false;

  BuyerBlacklistTradeStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/trade/order-review/buyer-profiles", this::buyerProfiles);
    server.setExecutor(null);
    server.start();
  }

  String base() {
    return base;
  }

  void reset() {
    profiles.clear();
    profilesForbidden = false;
    unreachable = false;
  }

  // ================================================================ 剧本

  /** 登记一个租户自维护的买家画像。 */
  void putProfile(
      String buyerKey,
      String name,
      String phone,
      String email,
      String level,
      boolean blacklisted,
      String blacklistReason) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("buyerKey", buyerKey);
    row.put("buyerName", name);
    row.put("buyerPhone", phone);
    row.put("buyerEmail", email);
    row.put("buyerLevel", level);
    row.put("blacklisted", blacklisted);
    row.put("blacklistReason", blacklistReason);
    profiles.put(buyerKey, row);
  }

  // ================================================================ 路由

  private void buyerProfiles(HttpExchange e) throws IOException {
    if (unreachable) {
      // 直接断开连接：客户端拿到的是「连不上」，不是「空集合」。
      e.close();
      return;
    }
    if (profilesForbidden) {
      json(e, 403, Map.of("message", "forbidden"));
      return;
    }
    List<Map<String, Object>> items = new ArrayList<>(profiles.values());
    json(
        e,
        200,
        Map.of(
            "items", items,
            "total", items.size(),
            "page", 0,
            "size", 200,
            "blacklistedCount", 0));
  }

  // ================================================================ 工具

  private static void json(HttpExchange e, int status, Object payload) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(payload);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
