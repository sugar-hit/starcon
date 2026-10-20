package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.1.2.1 的**跨服务边界**一条：2.1.2.1 的平台枚举映射在 channel 的独立库
 * {@code starcon_channel}，AGENTS.md 明文禁止跨服务 SQL / 跨库 FK / 共享视图
 * ⇒ 本模块读不到那张表，只能经 channel 的**授权 API**读。
 *
 * <p>本类用 JDK 自带的 {@link HttpServer} 起一个 **channel 桩**（真 HTTP 真 JSON，
 * 不用任何 mock 框架），钉住三件事：
 *
 * <ol>
 *   <li>请求打到的是 channel 的授权 API：路径是
 *       {@code /channel/platforms/{code}/enums?kind={kind}}，不是别的形状；
 *   <li><b>转发的是调用方自己的 Authorization bearer，租户不由本端口传递</b> ——
 *       桩把收到的请求头与查询串**原样记下来**，断言里面既没有租户头也没有租户参数；
 *       租户是 channel 侧按已认证成员关系自己解析的，本端口替它传就是越权；
 *   <li>channel 拉不到时**显式失败**：绝不把「没拉到」当成「没有映射」——
 *       后者会让订单状态机猜出一个内部状态码。
 * </ol>
 *
 * <p>订单落库那条路径**刻意不放 channel 桩**（见 {@code PlatformOrderSyncIntegrationTest}）：
 * 订单同步只依赖本模块自己的映射副本，不需要 channel 在场 —— 两件事分开钉。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformOrderSyncMappingSyncIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity, channel;
  static final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** channel 桩收到的请求（原样记录，供断言「bearer 转发 / 租户不传」）。 */
  record ChannelRequest(String path, String query, Map<String, String> headers) {}

  static final List<ChannelRequest> channelRequests = new CopyOnWriteArrayList<>();

  static final List<String> allPermissions =
      java.util.stream.Stream.of("orders")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenantId,
        "Test",
        UUID.randomUUID(),
        null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static {
    pg.start();
    try {
      try (var c =
              java.sql.DriverManager.getConnection(
                  pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var found = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (found == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(found);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, tenant, List.of(grant("ALL", allPermissions))));
      identity.start();

      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/platforms/",
          e -> {
            String path = e.getRequestURI().getPath();
            String query = e.getRequestURI().getQuery();
            Map<String, String> headers = new LinkedHashMap<>();
            e.getRequestHeaders()
                .forEach(
                    (k, v) ->
                        headers.put(k.toLowerCase(Locale.ROOT), v.isEmpty() ? "" : v.get(0)));
            channelRequests.add(
                new ChannelRequest(path, query == null ? "" : query, Map.copyOf(headers)));

            // BROKEN 平台：模拟 channel 侧故障（500）。
            if (path.contains("/BROKEN/")) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body =
                json.writeValueAsBytes(
                    List.of(
                        Map.of(
                            "id", "m-1",
                            "platformCode", "AMAZON",
                            "enumKind", "ORDER_STATUS",
                            "externalValue", "Shipped",
                            "internalValue", "CONFIRMED",
                            "source", "PLATFORM"),
                        Map.of(
                            "id", "m-2",
                            "platformCode", "AMAZON",
                            "enumKind", "ORDER_STATUS",
                            "externalValue", "Pending",
                            "internalValue", "PENDING_CONFIRMATION",
                            "source", "TENANT")));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      channel.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    channel.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() {
    channelRequests.clear();
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired PlatformEnumMappingClient enumMappings;

  com.fasterxml.jackson.databind.JsonNode sync(String platformCode, String kind) throws Exception {
    return json.readTree(syncRaw(platformCode, kind).getContentAsString());
  }

  org.springframework.mock.web.MockHttpServletResponse syncRaw(String platformCode, String kind)
      throws Exception {
    return http.perform(
            post("/trade/platform-orders/mappings/sync")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsBytes(Map.of("platformCode", platformCode, "enumKind", kind))))
        .andReturn()
        .getResponse();
  }

  // ================================================================
  // 跨服务边界：只能经 channel 的授权 API
  // ================================================================

  @Test
  void 映射同步走channel授权API_转发调用方bearer且租户不由端口传递() throws Exception {
    var res = sync("AMAZON", "ORDER_STATUS");

    assertThat(res.get("importedCount").asInt()).isEqualTo(2);
    assertThat(res.get("source").asText()).isEqualTo("MODULE_2_ENUM_MAPPING");
    assertThat(res.get("mappingVersion").asText()).isNotBlank();

    assertThat(channelRequests).as("必须真的打到 channel").hasSize(1);
    ChannelRequest request = channelRequests.get(0);
    assertThat(request.path()).isEqualTo("/channel/platforms/AMAZON/enums");
    assertThat(request.query()).isEqualTo("kind=ORDER_STATUS");

    // 转发的是**调用方自己的** bearer：channel 侧据此按已认证成员关系解析租户。
    assertThat(request.headers().get("authorization"))
        .as("bearer 必须原样转发给 channel，否则 channel 侧无法解析出调用者的成员关系")
        .isEqualTo("Bearer tenant-a");

    // 租户**不由本端口传递**：没有租户头，也没有租户参数。
    assertThat(request.headers()).doesNotContainKey("x-tenant-id");
    assertThat(request.headers()).doesNotContainKey("tenant-id");
    assertThat(request.query().toLowerCase(Locale.ROOT)).doesNotContain("tenant");
    assertThat(request.path()).doesNotContain(tenant.toString());
  }

  @Test
  void 同步来的映射落成映射副本_来源与版本随快照一起存() throws Exception {
    sync("AMAZON", "ORDER_STATUS");

    var rows =
        access.run(
            actor(user, tenant, List.of(grant("ALL", allPermissions))),
            "trade.orders.read",
            () ->
                sql.fetch(
                    "select external_value, internal_value, mapping_source, source_reference,"
                        + " mapping_version, captured_at, superseded_at"
                        + " from platform_order_enum_mapping where platform_code = 'AMAZON'"
                        + " order by external_value"));
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).get("external_value", String.class)).isEqualTo("Pending");
    assertThat(rows.get(0).get("internal_value", String.class)).isEqualTo("PENDING_CONFIRMATION");
    assertThat(rows.get(1).get("external_value", String.class)).isEqualTo("Shipped");
    assertThat(rows.get(1).get("internal_value", String.class)).isEqualTo("CONFIRMED");

    for (var row : rows) {
      assertThat(row.get("mapping_source", String.class)).isEqualTo("MODULE_2_ENUM_MAPPING");
      assertThat(row.get("source_reference", String.class))
          .as("同步来的行必须说明「哪份来源的哪一段」，否则「这行是同步器写的」只是口头声明")
          .contains("channel:GET /channel/platforms/AMAZON/enums?kind=ORDER_STATUS");
      assertThat(row.get("mapping_version", String.class))
          .as("映射版本要随快照一起存：订单得能说清「当时按哪一版归一的」")
          .isNotBlank();
      assertThat(row.get("captured_at", java.time.OffsetDateTime.class)).isNotNull();
      assertThat(row.get("superseded_at", java.time.OffsetDateTime.class)).isNull();
    }
  }

  @Test
  void channel拉不到时显式失败_不把没拉到当成没有映射() throws Exception {
    var response = syncRaw("BROKEN", "ORDER_STATUS");

    assertThat(response.getStatus())
        .as("channel 拉不到映射时必须显式失败（503），不能返回一份「空的成功」")
        .isEqualTo(503);

    // 端口层再钉一次「说清原因」：MockMvc 下 sendError 不渲染错误页（body 是空的），
    // 消息本身在抛出的异常里 —— 界面拿到的就是这句话。
    assertThatThrownBy(() -> enumMappings.fetch("Bearer tenant-a", "BROKEN", "ORDER_STATUS"))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("2.1.2.1")
        .hasMessageContaining("500");

    var count =
        access.run(
            actor(user, tenant, List.of(grant("ALL", allPermissions))),
            "trade.orders.read",
            () ->
                sql.fetchOne(
                        "select count(*) from platform_order_enum_mapping where platform_code = 'BROKEN'")
                    .get(0, Long.class));
    assertThat(count).as("拉失败时一行映射都不许写进去").isZero();
  }
}
