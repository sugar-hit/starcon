package com.starcon.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 16.5.2.2 用的 identity 桩（测试专用）。
 *
 * <p>🔴 本模块只经 identity 的**授权 API**读法人档案（{@code GET /identity/legal-entities}），
 * 与既有的 {@link LegalEntitySourcePort} 同一口径 —— AGENTS.md 禁止跨服务直读对方的表。
 *
 * <p>🔴 <b>「上游不可达」必须能被真的造出来</b>：法人名称**读不到**与「这个法人没有名字」
 * 是两件事，桩必须能分别表达，否则那条断言就是假的。
 */
final class LinkageRiskIdentityStub {

  /** 桩的运行模式。 */
  enum Mode {
    /** 正常返回法人列表。 */
    OK,
    /** identity 不可达（503）。 */
    UNAVAILABLE,
    /** 调用方无权读取（403）。 */
    FORBIDDEN
  }

  /** 一个法人。 */
  record LegalEntity(String id, String code, String legalName, String status) {}

  private final HttpServer server;
  private final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  private final ObjectMapper json = new ObjectMapper();

  volatile Mode mode = Mode.OK;
  volatile List<LegalEntity> legalEntities = new ArrayList<>();

  LinkageRiskIdentityStub() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/internal/access", this::access);
    server.createContext("/identity/legal-entities", this::legalEntities);
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
    legalEntities = new ArrayList<>();
  }

  /** 登记一个法人（供桩返回）。 */
  void addLegalEntity(String id, String code, String legalName) {
    List<LegalEntity> list = new ArrayList<>(legalEntities);
    list.add(new LegalEntity(id, code, legalName, "ACTIVE"));
    legalEntities = list;
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

  private void legalEntities(com.sun.net.httpserver.HttpExchange e) throws java.io.IOException {
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
    List<Map<String, Object>> items = new ArrayList<>();
    for (LegalEntity entity : legalEntities) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", entity.id());
      row.put("code", entity.code());
      row.put("legalName", entity.legalName());
      row.put("status", entity.status());
      items.add(row);
    }
    byte[] body = json.writeValueAsBytes(items);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, body.length);
    e.getResponseBody().write(body);
    e.close();
  }

  /** 造一个合法的法人 id（测试用）。 */
  static String legalEntityId() {
    return UUID.randomUUID().toString();
  }
}
