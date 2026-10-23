package com.starcon.identity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 1.4.2.2 默认店铺与仓库 —— 集成测试底座。
 *
 * <p><b>上游是真的，只是被替身满足</b>：店铺清单来自 channel、仓库清单来自 warehouse，
 * 本底座用 {@link HttpServer} 起两个**真 HTTP** 的替身（与 catalog / warehouse 既有
 * 集成测试同一套路），并把地址通过 {@code CHANNEL_URL} / {@code WAREHOUSE_URL}
 * 注入到被测进程里 —— 生产代码路径永远是真实的 HTTP 调用，替身逻辑不写进生产代码。
 *
 * <p><b>替身按 bearer 分租户</b>：响应按 {@code Authorization} 头里的 token 登记，
 * 所以「父租户看到父租户的店铺、子租户看到子租户的店铺」这件事在替身上是真的 ——
 * 本模块透传调用方的 bearer，上游再按已认证成员关系解析租户。
 *
 * <p><b>容器与端口是 static 的</b>：两个测试类共享同一个 Spring 上下文与同一个
 * PostgreSQL 容器（见 {@link IdentityIntegrationTestBase}），端口不会每个测试类换一次。
 */
abstract class DefaultContextTestBase extends IdentityIntegrationTestBase {

  /** 按 token 登记的店铺清单响应体。 */
  static final Map<String, String> CHANNEL_BODIES = new ConcurrentHashMap<>();

  /** 按 token 登记的店铺清单响应状态码（默认 200）。 */
  static final Map<String, Integer> CHANNEL_STATUS = new ConcurrentHashMap<>();

  /** 按 token 登记的仓库清单响应体。 */
  static final Map<String, String> WAREHOUSE_BODIES = new ConcurrentHashMap<>();

  /** 按 token 登记的仓库清单响应状态码（默认 200）。 */
  static final Map<String, Integer> WAREHOUSE_STATUS = new ConcurrentHashMap<>();

  static final HttpServer CHANNEL;
  static final HttpServer WAREHOUSE;

  static {
    try {
      CHANNEL = HttpServer.create(new InetSocketAddress(0), 0);
      WAREHOUSE = HttpServer.create(new InetSocketAddress(0), 0);
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
    CHANNEL.createContext("/channel/shops", exchange -> respond(exchange, CHANNEL_BODIES, CHANNEL_STATUS, "[]"));
    WAREHOUSE.createContext(
        "/warehouse/facilities",
        exchange ->
            respond(
                exchange,
                WAREHOUSE_BODIES,
                WAREHOUSE_STATUS,
                "{\"rows\":[],\"total\":0,\"page\":0,\"size\":200}"));
    CHANNEL.start();
    WAREHOUSE.start();
  }

  @DynamicPropertySource
  static void upstreamProps(DynamicPropertyRegistry r) {
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + CHANNEL.getAddress().getPort());
    r.add("WAREHOUSE_URL", () -> "http://127.0.0.1:" + WAREHOUSE.getAddress().getPort());
  }

  private static void respond(
      HttpExchange exchange,
      Map<String, String> bodies,
      Map<String, Integer> statuses,
      String emptyBody)
      throws IOException {
    String token = bearer(exchange);
    Integer status = statuses.get(token);
    String body = bodies.get(token);
    if (status == null && body == null) {
      // 没登记的 token 一律 401：上游从 bearer 解析身份，解析不出来就是没登录。
      status = 401;
      body = "{\"message\":\"登录已失效，请重新登录\"}";
    }
    byte[] bytes = (body == null ? emptyBody : body).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
    exchange.sendResponseHeaders(status == null ? 200 : status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static String bearer(HttpExchange exchange) {
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    if (header == null || !header.startsWith("Bearer ")) return "";
    return header.substring(7);
  }

  // ------------------------------------------------------------------
  // 替身登记
  // ------------------------------------------------------------------

  /** 登记某个 token 可见的店铺清单。 */
  void stubShops(String token, String body) {
    CHANNEL_BODIES.put(token, body);
    CHANNEL_STATUS.remove(token);
  }

  /** 让某个 token 的店铺清单读取失败（模拟上游挂掉 / 被拒）。 */
  void stubShopsFailure(String token, int status) {
    CHANNEL_STATUS.put(token, status);
    CHANNEL_BODIES.remove(token);
  }

  /** 登记某个 token 可见的仓库清单。 */
  void stubWarehouses(String token, String body) {
    WAREHOUSE_BODIES.put(token, body);
    WAREHOUSE_STATUS.remove(token);
  }

  /** 让某个 token 的仓库清单读取失败（模拟上游挂掉 / 被拒）。 */
  void stubWarehousesFailure(String token, int status) {
    WAREHOUSE_STATUS.put(token, status);
    WAREHOUSE_BODIES.remove(token);
  }

  /** 清空替身登记（每个测试开始前调用，避免上一个测试的残留影响下一个）。 */
  void clearStubs() {
    CHANNEL_BODIES.clear();
    CHANNEL_STATUS.clear();
    WAREHOUSE_BODIES.clear();
    WAREHOUSE_STATUS.clear();
  }

  // ------------------------------------------------------------------
  // 便捷构造
  // ------------------------------------------------------------------

  /** 一条店铺（channel 的 ShopView 里本模块真正用到的字段）。 */
  String shopJson(UUID id, String code, String name, String status) {
    return "{\"id\":\"%s\",\"code\":\"%s\",\"name\":\"%s\",\"platformName\":\"Amazon\",\"status\":\"%s\"}"
        .formatted(id, code, name, status);
  }

  /** 店铺清单响应体。 */
  String shopListJson(String... shops) {
    return "[" + String.join(",", shops) + "]";
  }

  /** 一条仓库（warehouse 的 WarehouseRow 里本模块真正用到的字段）。 */
  String warehouseJson(UUID id, String code, String name, String status, boolean isDefault) {
    return ("{\"id\":\"%s\",\"code\":\"%s\",\"name\":\"%s\",\"status\":\"%s\",\"defaultWarehouse\":%s}")
        .formatted(id, code, name, status, isDefault);
  }

  /** 仓库清单响应体。 */
  String warehouseListJson(String... warehouses) {
    return "{\"rows\":["
        + String.join(",", warehouses)
        + "],\"total\":"
        + warehouses.length
        + ",\"page\":0,\"size\":200}";
  }
}
