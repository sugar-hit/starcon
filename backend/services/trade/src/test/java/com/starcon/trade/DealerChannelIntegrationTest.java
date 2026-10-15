package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.7.1.2 经销与分销管理。
 *
 * <p>逐条验证蓝图本模块的四个输出（授权台账 / 配额完成率 / 返点计提 / 窜货预警），
 * 外加 AGENTS.md 明文要求的三条多租户断言。全部走 <b>HTTP 端点 + 真实 PostgreSQL 16 + 强制
 * RLS</b>，不使用任何内存替身：走端点才能同时证明「服务层 + DB 层」两层都没把闸门漏掉。
 *
 * <p><b>本模块的核心风险是金额与比率</b>，所以比率断言全部钉<b>精确值</b>而不是「大约等于」：
 *
 * <ul>
 *   <li>完成率 {@code 100 / 300 → 33.333333}（6 位，HALF_UP），不是 0.33333333… 的浮点近似；</li>
 *   <li>返点 {@code 0.333333 × 1000.0000 → 333.3330}（金额刻度 4 位）；</li>
 *   <li>目标为 0 时完成率必须是 <b>null</b>——0 与 100% 都会把「不考核」说成「一个都没完成」。</li>
 * </ul>
 *
 * <p><b>catalog 侧提供一个可切换的 stub</b>，只为验证「上游不可达一律 503 且零落库」这一条：
 * 上线后本模块对 3.6.1.2 的依赖是一条真实的授权只读 HTTP 调用。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DealerChannelIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer catalog;
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          // 数值一律读回 BigDecimal：默认的 Double 会把 "333.3330" 变成 333.333，
          // 而本模块的比率/金额断言必须钉**精确值**，不能钉近似。
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 翻到 false 即模拟「商品主数据服务挂了」，用来钉 503 + 零落库。 */
  static final AtomicBoolean catalogUp = new AtomicBoolean(true);

  static final List<String> allPermissions =
      java.util.stream.Stream.of("dealer")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
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
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, List.of(grant("ALL", List.of("trade.dealer.read")))));
      identity.start();

      // catalog（3.6.1.2 / 3.6.1.1）只读 stub：正常时给候选，catalogUp=false 时给 503。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/parties/candidates",
          e -> {
            if (!catalogUp.get()) {
              byte[] body =
                  "{\"message\":\"商品主数据服务维护中\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
              e.getResponseHeaders().add("Content-Type", "application/json");
              e.sendResponseHeaders(503, body.length);
              e.getResponseBody().write(body);
              e.close();
              return;
            }
            byte[] body =
                ("[{\"id\":\"" + UUID.randomUUID() + "\",\"code\":\"C-1\",\"name\":\"甲\","
                        + "\"partyType\":\"ORGANIZATION\",\"status\":\"ACTIVE\"}]")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.start();
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
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：RLS 的 GUC 是事务级的，池不复用会让「归还后不串租户」这条断言
    // 变成「每次都新建连接」的假通过。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    catalog.stop(0);
    identity.stop(0);
    pg.stop();
  }

  @Autowired DealerChannelService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  static final UUID partyA = UUID.randomUUID();
  static final UUID partyB = UUID.randomUUID();

  /** A 租户的经销商投影主键（建授权用它）。 */
  UUID profileA;

  @BeforeEach
  void seed() throws Exception {
    catalogUp.set(true);
    var p1 = putProfile("Bearer tenant-a", partyA, "DEAL-A", "经销商甲", "KEY", 30, "USD", 1L);
    var p2 = putProfile("Bearer tenant-b", partyB, "DEAL-B", "经销商乙", "KEY", 30, "USD", 1L);
    profileA = UUID.fromString((String) p1.get("id"));
    assertThat(p2.get("id")).as("两个租户的投影必须是不同的主键").isNotEqualTo(p1.get("id"));
  }

  // ================================================================ 工具

  /** 把响应里的数值（BigDecimal / 数字 / 字符串）统一读成 BigDecimal 做精确比较。 */
  static BigDecimal bd(Object o) {
    return o == null ? null : new BigDecimal(o.toString());
  }

  String body(String auth, String method, String path, Object payload, int expected)
      throws Exception {
    var request =
        MockMvcRequestBuilders.request(
            org.springframework.http.HttpMethod.valueOf(method), "/trade/" + path);
    var m = request.header("Authorization", auth).contentType(MediaType.APPLICATION_JSON);
    if (payload != null) {
      m = m.content(json.writeValueAsBytes(payload));
    }
    var result = http.perform(m).andReturn();
    // ApiErrorHandler 把所有 DataAccessException 都映射成同一个 409「履约路径数据与既有记录冲突」，
    // 不把真正的 cause 带出来的话，DB 到底拒绝了哪一条约束在报告里完全看不到。
    // 这里把被处理的异常链拼进断言消息 —— 它对**通过**的用例没有副作用。
    var resolved = result.getResolvedException();
    String cause = resolved == null ? "" : "\n实际异常：" + chain(resolved);
    assertThat(result.getResponse().getStatus())
        .as(
            "期望 %d，实际 %d，响应体：%s%s",
            expected, result.getResponse().getStatus(), result.getResponse().getContentAsString(), cause)
        .isEqualTo(expected);
    return result.getResponse().getContentAsString();
  }

  /** 展开异常链（每一层的类名 + message），定位到真正被 DB 拒绝的那一条。 */
  static String chain(Throwable e) {
    var sb = new StringBuilder();
    for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
      sb.append("\n  - ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
    }
    return sb.toString();
  }

  Map<String, Object> postJson(String auth, String path, Object payload, int expected)
      throws Exception {
    return json.readValue(body(auth, "POST", path, payload, expected), Map.class);
  }

  Map<String, Object> getJson(String auth, String path, int expected) throws Exception {
    return json.readValue(body(auth, "GET", path, null, expected), Map.class);
  }

  Map<String, Object> putJson(String auth, String path, Object payload, int expected)
      throws Exception {
    return json.readValue(body(auth, "PUT", path, payload, expected), Map.class);
  }

  Map<String, Object> putProfile(
      String auth, UUID party, String code, String name, String level, int terms, String ccy,
      long version)
      throws Exception {
    return putJson(
        auth,
        "dealers/profiles",
        Map.of(
            "partyId", party,
            "partyCode", code,
            "partyName", name,
            "customerLevel", level,
            "paymentTermsDays", terms,
            "defaultCurrency", ccy,
            "sourceVersion", version),
        200);
  }

  /** 建一份授权：默认授权华东 / 主营线、2026-01-01 ~ 2026-12-31、经销价 10.0000、返点 5%。 */
  Map<String, Object> createAuth(
      String auth, UUID profileId, String code, String price, String rate) throws Exception {
    return postJson(
        auth,
        "dealers/authorizations",
        Map.of(
            "dealerProfileId", profileId,
            "authCode", code,
            "regionCodes", List.of("EAST"),
            "productLineCodes", List.of("MAIN"),
            "validFrom", "2026-01-01",
            "validTo", "2026-12-31",
            "currency", "USD",
            "dealerPrice", new BigDecimal(price),
            "rebateRate", new BigDecimal(rate),
            "note", "首个授权"),
        200);
  }

  Map<String, Object> recordSale(
      String auth, UUID authId, String region, String line, String qty, String settled)
      throws Exception {
    var m = salePayload(authId, region, line, qty, settled);
    return postJson(auth, "dealers/sales", m, 200);
  }

  void recordSaleExpect(
      String auth, UUID authId, String region, String line, String qty, String settled, int expected)
      throws Exception {
    body(auth, "POST", "dealers/sales", salePayload(authId, region, line, qty, settled), expected);
  }

  private Map<String, Object> salePayload(
      UUID authId, String region, String line, String qty, String settled) {
    var m = new java.util.LinkedHashMap<String, Object>();
    m.put("authorizationId", authId);
    m.put("skuId", UUID.randomUUID());
    m.put("skuCode", "SKU-1");
    m.put("skuName", "商品一");
    m.put("productLineCode", line);
    m.put("soldRegionCode", region);
    m.put("soldOn", "2026-03-15");
    m.put("quantity", new BigDecimal(qty));
    m.put("note", "");
    if (settled != null) {
      m.put("settledAmount", new BigDecimal(settled));
    }
    return m;
  }

  /** 以超管直连查 tenant_id：RLS 对超管无效，这正是断言「行确实落在哪个租户名下」的唯一办法。 */
  String tenantIdOf(String table, UUID id) throws Exception {
    try (var c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var ps =
            c.prepareStatement("select tenant_id from " + table + " where id = ?")) {
      ps.setObject(1, id);
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).as("行应当存在：" + table).isTrue();
        return rs.getObject(1).toString();
      }
    }
  }

  long countRows(String table) throws Exception {
    try (var c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery("select count(*) from " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明把未交付的上下游报成不可用而不是默认值() throws Exception {
    var caps = getJson("Bearer tenant-a", "dealers/capabilities", 200);

    @SuppressWarnings("unchecked")
    var deps = (List<Map<String, Object>>) caps.get("dependencies");
    var byId = deps.stream().collect(java.util.stream.Collectors.toMap(d -> (String) d.get("moduleId"), d -> d));

    // 已交付：3.6.1.2（catalog）与同 L3 的兄弟 9.7.1.1（trade）。
    assertThat(byId.get("3.6.1.2").get("available")).isEqualTo(true);
    assertThat(byId.get("9.7.1.1").get("available")).isEqualTo(true);

    // **未交付的四条必须是 false，且 note 说清谁没交付** —— 画成默认值会让调用方
    // 把「没接通」当成「数据就是空的」。
    for (String gap : List.of("16", "12", "5.5.2", "13", "5")) {
      assertThat(byId.get(gap))
          .as("%s 必须被报出", gap)
          .isNotNull();
      assertThat(byId.get(gap).get("available")).as("%s 应为 false", gap).isEqualTo(false);
      assertThat((String) byId.get(gap).get("note"))
          .as("%s 的缺口说明不能为空", gap)
          .isNotBlank();
    }
    assertThat((String) byId.get("12").get("note")).contains("财务");
    assertThat((String) byId.get("5.5.2").get("note")).contains("未与 5.5.2 合并");
    assertThat((String) byId.get("16").get("note")).contains("UNVERIFIED");
  }

  @Test
  void 合规状态只能是未校验而不是默认通过() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID id = UUID.fromString((String) saved.get("id"));

    @SuppressWarnings("unchecked")
    var auth =
        (Map<String, Object>)
            getJson("Bearer tenant-a", "dealers/authorizations/" + id, 200)
                .get("authorization");
    assertThat(auth)
        .as("16 合规未交付，compliance_state 必须写 UNVERIFIED 而不是 VERIFIED")
        .containsEntry("complianceState", "UNVERIFIED");
  }

  // ================================================================ 上游投影

  @Test
  void 经销商档案投影幂等且版本回退被拒() throws Exception {
    var again =
        putProfile("Bearer tenant-a", partyA, "DEAL-A", "经销商甲改名", "STRATEGIC", 45, "USD", 9L);
    assertThat(again.get("partyName")).isEqualTo("经销商甲改名");
    assertThat(again.get("customerLevel")).isEqualTo("STRATEGIC");
    assertThat(bd(again.get("sourceVersion")).longValue()).isEqualTo(9L);

    // 旧版本号的事件（乱序到达）不允许覆盖新值。
    var stale =
        putProfile("Bearer tenant-a", partyA, "DEAL-A", "不该生效的旧名字", "PROSPECT", 30, "USD", 3L);
    assertThat(stale.get("partyName")).as("版本回退必须被拒").isEqualTo("经销商甲改名");
    assertThat(stale.get("customerLevel")).isEqualTo("STRATEGIC");
  }

  @Test
  void 上游不可达一律503且零落库() throws Exception {
    long before = countRows("dealer_profile");

    catalogUp.set(false);
    // 上游挂了：候选清单必须 503，**绝不**返回空列表冒充「这个租户一个经销商都没有」。
    body("Bearer tenant-a", "GET", "dealers/candidates", null, 503);

    assertThat(countRows("dealer_profile"))
        .as("上游不可达时不得有任何落库")
        .isEqualTo(before);

    catalogUp.set(true);
    // 恢复正常后能读到真实候选（证明 503 不是因为端点没接）。
    body("Bearer tenant-a", "GET", "dealers/candidates", null, 200);
  }

  // ================================================================ 输出①：授权台账

  @Test
  void 授权台账落库且期限是日期区间() throws Exception {
    String authCode = code();
    var saved = createAuth("Bearer tenant-a", profileA, authCode, "10.0000", "0.05");
    UUID id = UUID.fromString((String) saved.get("id"));

    @SuppressWarnings("unchecked")
    var auth = (Map<String, Object>) getJson("Bearer tenant-a", "dealers/authorizations/" + id, 200)
        .get("authorization");
    assertThat(auth.get("authCode")).isEqualTo(authCode);
    assertThat(auth.get("id")).isEqualTo(id.toString());
    assertThat((List<String>) auth.get("regionCodes")).containsExactly("EAST");
    assertThat((List<String>) auth.get("productLineCodes")).containsExactly("MAIN");
    assertThat(auth.get("validFrom")).isEqualTo("2026-01-01");
    assertThat(auth.get("validTo")).isEqualTo("2026-12-31");
    assertThat(auth.get("status")).isEqualTo("ACTIVE");
    // 返点比例是 0..1 的小数，且刻度 6 位。
    assertThat(bd(auth.get("rebateRate")).stripTrailingZeros())
        .isEqualByComparingTo("0.05");
  }

  @Test
  void 授权期限倒置被拒() throws Exception {
    body(
        "Bearer tenant-a",
        "POST",
        "dealers/authorizations",
        Map.of(
            "dealerProfileId", profileA,
            "authCode", code(),
            "regionCodes", List.of("EAST"),
            "productLineCodes", List.of("MAIN"),
            "validFrom", "2026-12-31",
            "validTo", "2026-01-01",
            "currency", "USD",
            "dealerPrice", new BigDecimal("10.0000"),
            "rebateRate", new BigDecimal("0.05")),
        400);
  }

  @Test
  void 授权编号重复被拒() throws Exception {
    createAuth("Bearer tenant-a", profileA, "DUP-CODE", "10.0000", "0.05");
    // 第二次用同一个编号必须 409，而不是静默覆盖既有授权的条款。
    body(
        "Bearer tenant-a",
        "POST",
        "dealers/authorizations",
        Map.of(
            "dealerProfileId", profileA,
            "authCode", "DUP-CODE",
            "regionCodes", List.of("WEST"),
            "productLineCodes", List.of("OTHER"),
            "validFrom", "2026-01-01",
            "validTo", "2026-12-31",
            "currency", "USD",
            "dealerPrice", new BigDecimal("1.0000"),
            "rebateRate", new BigDecimal("0.01")),
        409);
  }

  @Test
  void 授权状态流转留痕且终态不可回退() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID id = UUID.fromString((String) saved.get("id"));

    putJson(
        "Bearer tenant-a",
        "dealers/authorizations/" + id + "/status",
        Map.of("status", "SUSPENDED", "note", "欠款暂停"),
        200);

    // 自环被拒：一次「暂停」如果本来就已暂停，报「状态没有变化」而不是悄悄 +1 版本号。
    body(
        "Bearer tenant-a",
        "PUT",
        "dealers/authorizations/" + id + "/status",
        Map.of("status", "SUSPENDED", "note", "再暂停一次"),
        409);

    putJson(
        "Bearer tenant-a",
        "dealers/authorizations/" + id + "/status",
        Map.of("status", "EXPIRED", "note", "合同到期"),
        200);
    body(
        "Bearer tenant-a",
        "PUT",
        "dealers/authorizations/" + id + "/status",
        Map.of("status", "ACTIVE", "note", "想复活"),
        409);

    @SuppressWarnings("unchecked")
    var events = (List<Map<String, Object>>) getJson("Bearer tenant-a", "dealers/authorizations/" + id, 200)
        .get("events");
    assertThat(events).extracting(e -> e.get("eventType")).containsExactly("CREATED", "SUSPENDED", "EXPIRED");
  }

  @Test
  void 授权事件流是只追加的() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID id = UUID.fromString((String) saved.get("id"));

    // 直接用 trade_app 改事件流：DB 层对 trade_app 收回了 UPDATE/DELETE，
    // 应用角色改不动（这里验证权限被收回，而不是靠「接口没开」）。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "trade_app", "test-only");
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update dealer_authorization_event set note='篡改' where authorization_id = '"
                          + id
                          + "'"))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("permission denied");
    }
  }

  // ================================================================ 销售记录与授权价

  @Test
  void 销售金额由授权价算出而不是调用方传值() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID authId = UUID.fromString((String) saved.get("id"));

    // 入参里根本没有 amount —— 金额是 7 × 10.0000 = 70.0000。
    var sale = recordSale("Bearer tenant-a", authId, "EAST", "MAIN", "7", null);
    assertThat(bd(sale.get("amount"))).isEqualByComparingTo("70.0000");
    assertThat(bd(sale.get("unitPrice"))).isEqualByComparingTo("10.0000");
    // 未回款必须是 null 而不是 0：「未回款」与「回款额为 0」是两件事。
    assertThat(sale.get("settledAmount")).isNull();
  }

  @Test
  void 授权期外的销售被拒() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID authId = UUID.fromString((String) saved.get("id"));

    var m = new java.util.LinkedHashMap<String, Object>();
    m.put("authorizationId", authId);
    m.put("skuId", UUID.randomUUID());
    m.put("productLineCode", "MAIN");
    m.put("soldRegionCode", "EAST");
    m.put("soldOn", "2025-06-01"); // 落在 [2026-01-01, 2026-12-31] 之外
    m.put("quantity", new BigDecimal("1"));
    // 4xx 响应体是空的（ResponseStatusException 不写 ProblemDetail），所以用 body() 断言状态码。
    body("Bearer tenant-a", "POST", "dealers/sales", m, 400);
  }

  @Test
  void 暂停后不能登记销售() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID authId = UUID.fromString((String) saved.get("id"));
    putJson(
        "Bearer tenant-a",
        "dealers/authorizations/" + authId + "/status",
        Map.of("status", "SUSPENDED", "note", "冻结"),
        200);
    recordSaleExpect("Bearer tenant-a", authId, "EAST", "MAIN", "1", null, 409);
  }

  @Test
  void 回款额超过销售额被拒() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID authId = UUID.fromString((String) saved.get("id"));
    // 7 × 10 = 70，回款 80 是多收，不是回款。
    recordSaleExpect("Bearer tenant-a", authId, "EAST", "MAIN", "7", "80.0000", 400);
  }

  // ================================================================ 输出②：配额完成率

  @Test
  void 配额完成率是精确的除法结果() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID authId = UUID.fromString((String) saved.get("id"));
    // 目标 300，实绩 100 → 33.333333%（6 位，HALF_UP），不是浮点近似 0.33333333…
    postJson(
        "Bearer tenant-a",
        "dealers/quotas",
        Map.of(
            "authorizationId", authId,
            "periodStart", "2026-03-01",
            "periodEnd", "2026-03-31",
            "targetQuantity", new BigDecimal("300"),
            "targetAmount", new BigDecimal("3000.0000"),
            "targetSettledAmount", new BigDecimal("1500.0000")),
        200);
    recordSale("Bearer tenant-a", authId, "EAST", "MAIN", "10", "50.0000"); // 10 × 10 = 100

    @SuppressWarnings("unchecked")
    var items =
        (List<Map<String, Object>>) getJson("Bearer tenant-a", "dealers/quota-attainment", 200)
            .get("items");
    var row = items.stream().filter(i -> authId.toString().equals(i.get("authorizationId"))).findFirst().orElseThrow();
    assertThat(bd(row.get("actualQuantity"))).isEqualByComparingTo("10.000000");
    assertThat(bd(row.get("quantityAttainmentRate")))
        .as("10 ÷ 300 的完成率必须精确到 6 位")
        .isEqualByComparingTo("0.033333");
    assertThat(bd(row.get("actualAmount"))).isEqualByComparingTo("100.0000");
    assertThat(bd(row.get("amountAttainmentRate")))
        .isEqualByComparingTo("0.033333");
    assertThat(bd(row.get("actualSettledAmount"))).isEqualByComparingTo("50.0000");
    assertThat(bd(row.get("settledAttainmentRate")))
        .isEqualByComparingTo("0.033333");
  }

  @Test
  void 目标为零时完成率是无定义而不是零或满额() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID authId = UUID.fromString((String) saved.get("id"));
    postJson(
        "Bearer tenant-a",
        "dealers/quotas",
        Map.of(
            "authorizationId", authId,
            "periodStart", "2026-03-01",
            "periodEnd", "2026-03-31",
            "targetQuantity", new BigDecimal("0"), // 0 = 不考核
            "targetAmount", new BigDecimal("0"),
            "targetSettledAmount", new BigDecimal("0")),
        200);
    recordSale("Bearer tenant-a", authId, "EAST", "MAIN", "5", null);

    @SuppressWarnings("unchecked")
    var items =
        (List<Map<String, Object>>) getJson("Bearer tenant-a", "dealers/quota-attainment", 200)
            .get("items");
    var row = items.stream().filter(i -> authId.toString().equals(i.get("authorizationId"))).findFirst().orElseThrow();
    assertThat(row.get("actualQuantity")).isNotNull();
    assertThat(row.get("quantityAttainmentRate"))
        .as("不限配额时完成率必须是 null：0 会被读成「一个都没完成」，100% 会被读成「超额完成」")
        .isNull();
    assertThat(row.get("amountAttainmentRate")).isNull();
    assertThat(row.get("settledAttainmentRate")).isNull();
  }

  @Test
  void 配额按期间去重() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID authId = UUID.fromString((String) saved.get("id"));
    var quota =
        Map.of(
            "authorizationId", authId,
            "periodStart", "2026-03-01",
            "periodEnd", "2026-03-31",
            "targetQuantity", new BigDecimal("100"),
            "targetAmount", new BigDecimal("1000.0000"),
            "targetSettledAmount", new BigDecimal("0"));
    postJson("Bearer tenant-a", "dealers/quotas", quota, 200);
    // 同授权 + 同期间重复 → 走 upsert 而不是再建一份（目标被整体替换）。
    postJson("Bearer tenant-a", "dealers/quotas", quota, 200);

    @SuppressWarnings("unchecked")
    var items =
        (List<Map<String, Object>>) getJson("Bearer tenant-a", "dealers/quota-attainment", 200)
            .get("items");
    assertThat(items.stream().filter(i -> authId.toString().equals(i.get("authorizationId"))).count())
        .as("同一授权同一期间只能有一份配额")
        .isEqualTo(1);
  }

  // ================================================================ 输出③：返点计提

  @Test
  void 返点计提是精确的乘法且口径未经财务确认() throws Exception {
    // 0.333333 是除不尽/放不下的比例，正好用来钉「不能用浮点、也不能少算一位」。
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.333333");
    UUID authId = UUID.fromString((String) saved.get("id"));
    recordSale("Bearer tenant-a", authId, "EAST", "MAIN", "100", null); // 100 × 10 = 1000.0000

    var rebate =
        postJson(
            "Bearer tenant-a",
            "dealers/rebates/accrue",
            Map.of(
                "authorizationId", authId,
                "periodStart", "2026-03-01",
                "periodEnd", "2026-03-31"),
            200);

    assertThat(bd(rebate.get("baseAmount")))
        .isEqualByComparingTo("1000.0000");
    // 1000.0000 × 0.333333 = 333.333000 → 金额刻度 4 位 → 333.3330
    assertThat(bd(rebate.get("rebateAmount")))
        .as("返点金额必须精确到金额刻度")
        .isEqualByComparingTo("333.3330");
    assertThat(bd(rebate.get("rebateRate"))).isEqualByComparingTo("0.333333");
    assertThat(rebate.get("financeConfirmed"))
        .as("蓝图写死「需与财务确认计提口径」，12 未交付 → 必须显式为 false")
        .isEqualTo(false);
    assertThat(rebate.get("accrualState")).isEqualTo("ACCRUED");
  }

  @Test
  void 同一期间不能重复计提且无销售时拒计提() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID authId = UUID.fromString((String) saved.get("id"));

    // 期间内没有销售 → 409（计提一个 0 基数的返点没有意义）。
    body(
        "Bearer tenant-a",
        "POST",
        "dealers/rebates/accrue",
        Map.of(
            "authorizationId", authId,
            "periodStart", "2026-03-01",
            "periodEnd", "2026-03-31"),
        409);

    recordSale("Bearer tenant-a", authId, "EAST", "MAIN", "3", null);
    postJson(
        "Bearer tenant-a",
        "dealers/rebates/accrue",
        Map.of(
            "authorizationId", authId,
            "periodStart", "2026-03-01",
            "periodEnd", "2026-03-31"),
        200);
    // 重复计提会把同一段负债记两次。
    body(
        "Bearer tenant-a",
        "POST",
        "dealers/rebates/accrue",
        Map.of(
            "authorizationId", authId,
            "periodStart", "2026-03-01",
            "periodEnd", "2026-03-31"),
        409);
  }

  // ================================================================ 输出④：窜货预警

  @Test
  void 授权范围内的销售不产生预警() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID authId = UUID.fromString((String) saved.get("id"));
    recordSale("Bearer tenant-a", authId, "EAST", "MAIN", "2", null);

    @SuppressWarnings("unchecked")
    var items =
        (List<Map<String, Object>>) getJson("Bearer tenant-a", "dealers/diversion-alerts", 200)
            .get("items");
    assertThat(items.stream().filter(i -> authId.toString().equals(i.get("authorizationId"))).count())
        .as("在授权区域与产品线内的销售不是窜货")
        .isZero();
  }

  @Test
  void 跨区域销售产生区域预警() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID authId = UUID.fromString((String) saved.get("id"));
    recordSale("Bearer tenant-a", authId, "NORTH", "MAIN", "2", null);

    @SuppressWarnings("unchecked")
    var items =
        (List<Map<String, Object>>) getJson("Bearer tenant-a", "dealers/diversion-alerts", 200)
            .get("items");
    var alert =
        items.stream().filter(i -> authId.toString().equals(i.get("authorizationId"))).findFirst().orElseThrow();
    assertThat(alert.get("breachKind")).isEqualTo("REGION");
    assertThat(alert.get("soldRegionCode")).isEqualTo("NORTH");
    assertThat((List<String>) alert.get("authorizedRegions")).containsExactly("EAST");
    assertThat(alert.get("alertState")).isEqualTo("OPEN");
    assertThat(bd(alert.get("saleAmount"))).isEqualByComparingTo("20.0000");
    assertThat(alert.get("severity")).isEqualTo("LOW");
  }

  @Test
  void 跨产品线销售产生产品线预警() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID authId = UUID.fromString((String) saved.get("id"));
    recordSale("Bearer tenant-a", authId, "EAST", "OTHER", "2", null);

    @SuppressWarnings("unchecked")
    var items =
        (List<Map<String, Object>>) getJson("Bearer tenant-a", "dealers/diversion-alerts", 200)
            .get("items");
    var alert =
        items.stream().filter(i -> authId.toString().equals(i.get("authorizationId"))).findFirst().orElseThrow();
    assertThat(alert.get("breachKind")).isEqualTo("PRODUCT_LINE");
  }

  @Test
  void 同时越界合并成一条而不是两条() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID authId = UUID.fromString((String) saved.get("id"));
    // 区域与产品线都不在授权内 → 一条 BOTH，拆成两条会让「有几笔窜货」被算大。
    recordSale("Bearer tenant-a", authId, "NORTH", "OTHER", "2", null);

    @SuppressWarnings("unchecked")
    var items =
        (List<Map<String, Object>>) getJson("Bearer tenant-a", "dealers/diversion-alerts", 200)
            .get("items");
    var mine = items.stream().filter(i -> authId.toString().equals(i.get("authorizationId"))).toList();
    assertThat(mine).as("一条销售最多一条预警").hasSize(1);
    assertThat(mine.get(0).get("breachKind")).isEqualTo("BOTH");
  }

  @Test
  void 判为误报必须写明理由且是终态() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID authId = UUID.fromString((String) saved.get("id"));
    recordSale("Bearer tenant-a", authId, "NORTH", "MAIN", "2", null);

    @SuppressWarnings("unchecked")
    var items =
        (List<Map<String, Object>>) getJson("Bearer tenant-a", "dealers/diversion-alerts", 200)
            .get("items");
    String alertId =
        items.stream()
            .filter(i -> authId.toString().equals(i.get("authorizationId")))
            .findFirst()
            .orElseThrow()
            .get("id")
            .toString();

    // 不写理由 → 400（「查过了确实没窜」与「懒得处理直接关掉」必须可区分）。
    body(
        "Bearer tenant-a",
        "PUT",
        "dealers/diversion-alerts/" + alertId + "/state",
        Map.of("alertState", "DISMISSED", "note", ""),
        400);

    putJson(
        "Bearer tenant-a",
        "dealers/diversion-alerts/" + alertId + "/state",
        Map.of("alertState", "DISMISSED", "note", "展会用货，非跨区分销"),
        200);
    body(
        "Bearer tenant-a",
        "PUT",
        "dealers/diversion-alerts/" + alertId + "/state",
        Map.of("alertState", "OPEN", "note", "想反悔"),
        409);
  }

  // ================================================================ 分页

  @Test
  void 分页是真切片() throws Exception {
    var saved = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    UUID authId = UUID.fromString((String) saved.get("id"));
    for (int i = 1; i <= 5; i++) {
      recordSale("Bearer tenant-a", authId, "EAST", "MAIN", String.valueOf(i), null);
    }
    @SuppressWarnings("unchecked")
    var page1 = getJson("Bearer tenant-a", "dealers/sales?authorizationId=" + authId + "&page=0&size=2", 200);
    @SuppressWarnings("unchecked")
    var page3 = getJson("Bearer tenant-a", "dealers/sales?authorizationId=" + authId + "&page=2&size=2", 200);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> i1 = (List<Map<String, Object>>) page1.get("items");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> i3 = (List<Map<String, Object>>) page3.get("items");

    assertThat(bd(page1.get("total")).longValue()).isEqualTo(5L);
    assertThat(i1).hasSize(2);
    assertThat(i3).hasSize(1);
    // 两页的 id 必须不相交，否则切片是假的。
    assertThat(i1.stream().map(x -> x.get("id")).toList())
        .doesNotContainAnyElementsOf(i3.stream().map(x -> x.get("id")).toList());
  }

  // ================================================================ 多租户三断言

  @Test
  void 两租户的授权台账互不可见() throws Exception {
    @SuppressWarnings("unchecked")
    var bProfiles = (List<Map<String, Object>>) getJson("Bearer tenant-b", "dealers/profiles?keyword=经销商乙", 200).get("items");
    UUID profileB = UUID.fromString((String) bProfiles.get(0).get("id"));

    var authA = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    var authB = createAuth("Bearer tenant-b", profileB, code(), "20.0000", "0.10");
    String idA = (String) authA.get("id");
    String idB = (String) authB.get("id");

    @SuppressWarnings("unchecked")
    var itemsA = (List<Map<String, Object>>) getJson("Bearer tenant-a", "dealers/authorizations?size=200", 200).get("items");
    @SuppressWarnings("unchecked")
    var itemsB = (List<Map<String, Object>>) getJson("Bearer tenant-b", "dealers/authorizations?size=200", 200).get("items");

    assertThat(itemsA).anyMatch(i -> idA.equals(i.get("id")));
    assertThat(itemsA).noneMatch(i -> idB.equals(i.get("id")));
    assertThat(itemsB).anyMatch(i -> idB.equals(i.get("id")));
    assertThat(itemsB).noneMatch(i -> idA.equals(i.get("id")));

    // 详情也是隔离面：直接按 id 取对方的授权必须是 404。
    body("Bearer tenant-a", "GET", "dealers/authorizations/" + idB, null, 404);
  }

  /** 依次取各列表接口中属于指定授权的记录。 */
  List<Map<String, Object>> rowsFor(String auth, String path, UUID authorizationId)
      throws Exception {
    @SuppressWarnings("unchecked")
    var items = (List<Map<String, Object>>) getJson(auth, path, 200).get("items");
    return items.stream()
        .filter(i -> authorizationId.toString().equals(String.valueOf(i.get("authorizationId"))))
        .toList();
  }

  @Test
  void 伪造租户头无效且行确实落在原租户名下() throws Exception {
    var authA = createAuth("Bearer tenant-a", profileA, code(), "10.0000", "0.05");
    String idA = (String) authA.get("id");

    @SuppressWarnings("unchecked")
    var bProfiles = (List<Map<String, Object>>) getJson("Bearer tenant-b", "dealers/profiles?keyword=经销商乙", 200).get("items");
    UUID profileB = UUID.fromString((String) bProfiles.get(0).get("id"));
    var authB = createAuth("Bearer tenant-b", profileB, code(), "20.0000", "0.10");
    String idB = (String) authB.get("id");

    for (String forged : List.of(b.toString(), UUID.randomUUID().toString())) {
      var result =
          http.perform(
                  get("/trade/dealers/authorizations?size=200")
                      .header("Authorization", "Bearer tenant-a")
                      .header("X-Tenant-Id", forged))
              .andExpect(status().is(200))
              .andReturn();
      @SuppressWarnings("unchecked")
      var page = json.readValue(result.getResponse().getContentAsString(), Map.class);
      @SuppressWarnings("unchecked")
      var items = (List<Map<String, Object>>) page.get("items");
      assertThat(items).as("伪造头后仍能看到会话租户的授权").anyMatch(i -> idA.equals(i.get("id")));
      assertThat(items)
          .as("伪造 X-Tenant-Id=%s 不应看到该租户的授权", forged)
          .noneMatch(i -> idB.equals(i.get("id")));
    }

    body(
        "Bearer tenant-a",
        "GET",
        "dealers/authorizations/" + idB,
        null,
        404);

    // 不只断言「看不见」，还断言行确实落在各自的租户名下（用不受 RLS 约束的超管直连核对）。
    assertThat(tenantIdOf("dealer_authorization", UUID.fromString(idA))).isEqualTo(a.toString());
    assertThat(tenantIdOf("dealer_authorization", UUID.fromString(idB))).isEqualTo(b.toString());
  }

  @Test
  void 连接池归还后不串租户() throws Exception {
    @SuppressWarnings("unchecked")
    var bProfiles = (List<Map<String, Object>>) getJson("Bearer tenant-b", "dealers/profiles?keyword=经销商乙", 200).get("items");
    UUID profileB = UUID.fromString((String) bProfiles.get(0).get("id"));
    var authB = createAuth("Bearer tenant-b", profileB, code(), "20.0000", "0.10");
    String idB = (String) authB.get("id");

    // 池大小被压到 1：每一次请求都复用同一条连接，租户上下文必须逐请求重绑。
    for (int i = 0; i < 6; i++) {
      @SuppressWarnings("unchecked")
      var itemsA = (List<Map<String, Object>>) getJson("Bearer tenant-a", "dealers/authorizations?size=200", 200).get("items");
      @SuppressWarnings("unchecked")
      var itemsB = (List<Map<String, Object>>) getJson("Bearer tenant-b", "dealers/authorizations?size=200", 200).get("items");
      assertThat(itemsA).noneMatch(x -> idB.equals(x.get("id")));
      assertThat(itemsB).anyMatch(x -> idB.equals(x.get("id")));
      assertThat(itemsA.stream().map(x -> x.get("id")).toList())
          .doesNotContainAnyElementsOf(itemsB.stream().map(x -> x.get("id")).toList());
    }
  }

  @Test
  void 四类输出全部受租户隔离() throws Exception {
    @SuppressWarnings("unchecked")
    var bProfiles = (List<Map<String, Object>>) getJson("Bearer tenant-b", "dealers/profiles?keyword=经销商乙", 200).get("items");
    UUID profileB = UUID.fromString((String) bProfiles.get(0).get("id"));
    var authB = createAuth("Bearer tenant-b", profileB, code(), "20.0000", "0.10");
    UUID authIdB = UUID.fromString((String) authB.get("id"));
    recordSale("Bearer tenant-b", authIdB, "NORTH", "MAIN", "9", null); // 顺带造一条窜货预警
    postJson(
        "Bearer tenant-b",
        "dealers/rebates/accrue",
        Map.of(
            "authorizationId", authIdB,
            "periodStart", "2026-03-01",
            "periodEnd", "2026-03-31"),
        200);

    // A 租户看到的每一类输出里，都不应该有 B 的东西。
    // 配额刻意走 quota-attainment 读（那里带目标与实绩），而不是 /quotas ——
    // 本模块没有单独的配额列表端点，写一个空壳路径只会让界面以为它存在。
    for (String path :
        List.of(
            "dealers/quota-attainment?size=200",
            "dealers/sales?size=200",
            "dealers/rebates?size=200",
            "dealers/diversion-alerts?size=200",
            "dealers/profiles?size=200")) {
      @SuppressWarnings("unchecked")
      var items = (List<Map<String, Object>>) getJson("Bearer tenant-a", path, 200).get("items");
      assertThat(items)
          .as("A 租户在 %s 里不应看到 B 的记录", path)
          .allMatch(i -> i.get("dealerProfileId") == null || !profileB.toString().equals(i.get("dealerProfileId").toString()));
    }
  }

  // ================================================================ 权限

  @Test
  void 读写权限分离且缺令牌是401() throws Exception {
    // 只读身份可以看列表，但不能建授权。
    getJson("Bearer read-only", "dealers/authorizations", 200);
    body(
        "Bearer read-only",
        "POST",
        "dealers/authorizations",
        Map.of(
            "dealerProfileId", profileA,
            "authCode", code(),
            "regionCodes", List.of("EAST"),
            "productLineCodes", List.of("MAIN"),
            "validFrom", "2026-01-01",
            "validTo", "2026-12-31",
            "currency", "USD",
            "dealerPrice", new BigDecimal("10.0000"),
            "rebateRate", new BigDecimal("0.05")),
        403);
    // 缺令牌 → 401。
    http.perform(get("/trade/dealers/authorizations")).andExpect(status().is(401));
  }

  // ================================================================ 小工具

  static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

  String code() {
    return "AUTH-" + UUID.randomUUID().toString().substring(0, 8) + "-" + SEQ.incrementAndGet();
  }
}
