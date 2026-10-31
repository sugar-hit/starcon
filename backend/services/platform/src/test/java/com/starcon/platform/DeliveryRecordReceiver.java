package com.starcon.platform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 18.1.2.2 集成测试里的**收方**：一个真的 HTTP 服务器。
 *
 * <p>🔴 <b>为什么不用 mock</b>：本模块的核心承诺是「**真的把包发出去，返回码真的记下来**」。
 * 用一个打桩的发送器，这条承诺永远验不了 —— 桩说「发出去了」不代表真发出去了。
 * 所以收方是真服务器：它真的收 POST、真的按测试指定的返回码应答、真的把请求头与请求体记下来。
 *
 * <p>它还让测试能**独立复算签名**：收到的 {@code X-Starcon-Signature} 头 + 收到的请求体
 * ⇒ 收方用自己的密钥算一遍 HMAC-SHA256，两边必须一致。
 */
final class DeliveryRecordReceiver {

  /** 收到的一个请求。 */
  record Received(String method, String path, Map<String, String> headers, String body) {}

  private final HttpServer server;
  private final List<Received> requests = Collections.synchronizedList(new ArrayList<>());
  private volatile int status = 200;
  private volatile String responseBody = "ok";

  DeliveryRecordReceiver() throws IOException {
    this.server = HttpServer.create(new InetSocketAddress(0), 0);
    this.server.createContext("/", this::handle);
    this.server.setExecutor(Executors.newFixedThreadPool(4));
  }

  void start() {
    server.start();
  }

  /** 收方的地址（拼上路径即可得到一个订阅端点）。 */
  String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  /** 之后所有请求都返回这个状态码。 */
  void status(int status) {
    this.status = status;
  }

  void responseBody(String body) {
    this.responseBody = body;
  }

  /** 收到的请求（按到达顺序）。 */
  List<Received> requests() {
    return List.copyOf(requests);
  }

  void reset() {
    requests.clear();
    status = 200;
    responseBody = "ok";
  }

  private void handle(HttpExchange exchange) throws IOException {
    var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    var headers = new HashMap<String, String>();
    exchange.getRequestHeaders().forEach((key, values) -> {
      if (!values.isEmpty()) headers.put(key.toLowerCase(), values.get(0));
    });
    requests.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body));

    var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
