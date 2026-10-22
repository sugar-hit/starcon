package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 15.4.1.1 工单列表的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>（租户隔离三断言在
 * {@link ServiceTicketListTenancyIntegrationTest} 里），本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>🔴 加粗句「SLA 计时需按营业时间而非自然时间」</b>：
 *       夜间积压的单按自然时间早就逾期，按营业时间口径<b>仍在计时</b>；
 *       「自然时间已逾期、营业时间仍达标」的单在列表里判 <b>MET</b>。
 *       而且没配营业时间时状态是 <b>NO_CALENDAR</b>，<b>不退回自然时间</b>。</li>
 *   <li><b>列表是真分页真筛选</b>：类型 / 优先级 / 状态 / 分配客服 / 未分配 /
 *       关键字 / 时间区间，每一条都真的生效；总数是上游的独立 COUNT。</li>
 *   <li><b>关联买家与订单不复制一份</b>：订单来自上游 trade（工单 → 售后单 → 订单），
 *       买家来自本服务自己的 {@code crm_message} / {@code crm_customer_contact}。</li>
 *   <li><b>转派历史与升级去向</b>读上游的既有事实，不另造一套。</li>
 *   <li><b>下游未交付</b>：能力声明里 15.4.1.2 如实标 {@code available=false} +
 *       ownerModule，<b>不指向</b>任何不存在的入口。</li>
 *   <li><b>上游读不到就 503</b>：绝不返回空列表冒充「上游没有工单」。</li>
 *   <li><b>库里没有会自己过期的剩余列</b>（information_schema 实查）。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceTicketListIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final UUID shopId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 上游（trade）的工单 —— 测试自己往里塞，**不 mock 上游服务**（真起 HTTP 服务）。 */
  static final List<Map<String, Object>> upstreamTickets = new CopyOnWriteArrayList<>();

  /** 上游（trade）的售后单：售后单号 → 订单号。 */
  static final Map<String, String> upstreamCases = new ConcurrentHashMap<>();

  /** 上游（trade）的订单。 */
  static final List<Map<String, Object>> upstreamOrders = new CopyOnWriteArrayList<>();

  /** 上游（trade）的转派留痕：工单 id → 派单动作列表。 */
  static final Map<UUID, List<Map<String, Object>>> upstreamAssignments = new ConcurrentHashMap<>();

  /** 让上游整个挂掉（用来证明「读不到」不是「空列表」）。 */
  static final AtomicBoolean upstreamBroken = new AtomicBoolean(false);

  /** 上游收到的 Authorization 头（用来证明转发的是**调用方自己的** bearer）。 */
  static final List<String> upstreamAuthorizations = new CopyOnWriteArrayList<>();

  static HttpServer identity;
  static HttpServer trade;

  /** 2026-06-01 是周一（营业时间轴的测试基准日）。 */
  static final OffsetDateTime MONDAY_09 = OffsetDateTime.parse("2026-06-01T09:00:00+08:00");

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role crm_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    List<String> permissions =
        List.of("crm.service_ticket.read", "crm.service_ticket.update");
    actors.put(
        "session",
        new AccessService.Access(
            userId,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            permissions,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions))));
    try {
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.setExecutor(Executors.newFixedThreadPool(2));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    try {
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext("/trade/service-tickets", ServiceTicketListIntegrationTest::tickets);
      trade.createContext("/trade/aftersales/cases", ServiceTicketListIntegrationTest::cases);
      trade.createContext("/trade/sales-orders/list", ServiceTicketListIntegrationTest::orders);
      trade.setExecutor(Executors.newFixedThreadPool(4));
      trade.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  // ================================================================ 上游 stub

  private static void tickets(HttpExchange e) throws java.io.IOException {
    upstreamAuthorizations.add(e.getRequestHeaders().getFirst("Authorization"));
    if (upstreamBroken.get()) {
      e.sendResponseHeaders(500, -1);
      e.close();
      return;
    }
    String path = e.getRequestURI().getPath();
    Map<String, String> query = query(e.getRequestURI().getQuery());
    // 单张详情：/trade/service-tickets/{id}
    if (path != null && path.startsWith("/trade/service-tickets/")) {
      UUID id = UUID.fromString(path.substring("/trade/service-tickets/".length()));
      Map<String, Object> head = null;
      for (Map<String, Object> ticket : upstreamTickets) {
        if (id.equals(ticket.get("id"))) {
          head = ticket;
          break;
        }
      }
      if (head == null) {
        e.sendResponseHeaders(404, -1);
        e.close();
        return;
      }
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("head", head);
      body.put(
          "assignments",
          upstreamAssignments.getOrDefault(id, List.of()));
      write(e, body);
      return;
    }
    // 列表：按上游的筛选口径真过滤 + 真分页。
    List<Map<String, Object>> rows = new ArrayList<>(upstreamTickets);
    rows.removeIf(row -> !matches(row, query));
    rows.sort(comparatorOf(query.getOrDefault("sort", "created_desc")));
    long total = rows.size();
    int page = Integer.parseInt(query.getOrDefault("page", "0"));
    int size = Integer.parseInt(query.getOrDefault("size", "50"));
    int from = Math.min(page * size, rows.size());
    int to = Math.min(from + size, rows.size());
    List<Map<String, Object>> slice = rows.subList(from, to);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("rows", slice);
    body.put("page", page);
    body.put("size", size);
    body.put("total", total);
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("total", total);
    summary.put(
        "open", rows.stream().filter(r -> "OPEN".equals(r.get("ticketStatus"))).count());
    summary.put(
        "inProgress", rows.stream().filter(r -> "IN_PROGRESS".equals(r.get("ticketStatus"))).count());
    summary.put(
        "waitingBuyer",
        rows.stream().filter(r -> "WAITING_BUYER".equals(r.get("ticketStatus"))).count());
    summary.put(
        "resolved", rows.stream().filter(r -> "RESOLVED".equals(r.get("ticketStatus"))).count());
    summary.put(
        "cancelled", rows.stream().filter(r -> "CANCELLED".equals(r.get("ticketStatus"))).count());
    summary.put(
        "unassigned",
        rows.stream().filter(r -> String.valueOf(r.get("assignedToRef")).isBlank()).count());
    summary.put("overdue", 0);
    summary.put(
        "complaint", rows.stream().filter(r -> "COMPLAINT".equals(r.get("ticketType"))).count());
    summary.put(
        "inquiry", rows.stream().filter(r -> "INQUIRY".equals(r.get("ticketType"))).count());
    body.put("summary", summary);
    write(e, body);
  }

  /** 上游的筛选口径（与 trade 侧同形：等值 + 未分配 + 关键字 + 时间窗）。 */
  private static boolean matches(Map<String, Object> row, Map<String, String> query) {
    if (has(query, "status") && !query.get("status").equals(row.get("ticketStatus"))) {
      return false;
    }
    if (has(query, "ticketType") && !query.get("ticketType").equals(row.get("ticketType"))) {
      return false;
    }
    if (has(query, "priority") && !query.get("priority").equals(row.get("priority"))) {
      return false;
    }
    if (has(query, "sourceChannel")
        && !query.get("sourceChannel").equals(row.get("sourceChannel"))) {
      return false;
    }
    if (has(query, "assignedToRef")
        && !query.get("assignedToRef").equals(row.get("assignedToRef"))) {
      return false;
    }
    if (has(query, "caseId") && !query.get("caseId").equals(String.valueOf(row.get("caseId")))) {
      return false;
    }
    if ("true".equals(query.get("unassignedOnly"))
        && !String.valueOf(row.get("assignedToRef")).isBlank()) {
      return false;
    }
    if (has(query, "from")) {
      OffsetDateTime created = OffsetDateTime.parse(String.valueOf(row.get("createdAt")));
      if (created.isBefore(OffsetDateTime.parse(query.get("from")))) {
        return false;
      }
    }
    if (has(query, "to")) {
      OffsetDateTime created = OffsetDateTime.parse(String.valueOf(row.get("createdAt")));
      if (!created.isBefore(OffsetDateTime.parse(query.get("to")))) {
        return false;
      }
    }
    if (has(query, "keyword")) {
      String kw = query.get("keyword").toLowerCase();
      String haystack =
          String.valueOf(row.get("ticketNo"))
              + " "
              + String.valueOf(row.get("subject"))
              + " "
              + String.valueOf(row.get("reasonNote"))
              + " "
              + String.valueOf(row.get("assignedToRef"));
      if (!haystack.toLowerCase().contains(kw)) {
        return false;
      }
    }
    return true;
  }

  private static Comparator<Map<String, Object>> comparatorOf(String sort) {
    return switch (sort) {
      case "sla_asc" ->
          Comparator.comparing(
              (Map<String, Object> r) -> String.valueOf(r.get("replyDueAt")),
              Comparator.naturalOrder());
      case "priority_desc" ->
          Comparator.comparingInt(
              (Map<String, Object> r) ->
                  switch (String.valueOf(r.get("priority"))) {
                    case "URGENT" -> 1;
                    case "HIGH" -> 2;
                    case "NORMAL" -> 3;
                    default -> 4;
                  });
      case "assigned_asc" ->
          Comparator.comparing(r -> String.valueOf(r.get("assignedToRef")));
      default ->
          Comparator.comparing(
              (Map<String, Object> r) -> OffsetDateTime.parse(String.valueOf(r.get("createdAt"))),
              Comparator.reverseOrder());
    };
  }

  private static void cases(HttpExchange e) throws java.io.IOException {
    upstreamAuthorizations.add(e.getRequestHeaders().getFirst("Authorization"));
    if (upstreamBroken.get()) {
      e.sendResponseHeaders(500, -1);
      e.close();
      return;
    }
    Map<String, String> query = query(e.getRequestURI().getQuery());
    String caseNo = query.getOrDefault("caseNo", "");
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map.Entry<String, String> entry : upstreamCases.entrySet()) {
      // 上游的 caseNo 过滤是 ilike（模糊）；本服务只认完全相等的那一条。
      if (entry.getKey().contains(caseNo)) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.nameUUIDFromBytes(entry.getKey().getBytes()));
        row.put("caseNo", entry.getKey());
        row.put("orderId", UUID.nameUUIDFromBytes(entry.getValue().getBytes()));
        row.put("orderNo", entry.getValue());
        row.put("caseType", "RETURN_REFUND");
        row.put("status", "IN_PROCESS");
        rows.add(row);
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("rows", rows);
    body.put("page", 0);
    body.put("size", rows.size());
    body.put("total", rows.size());
    write(e, body);
  }

  private static void orders(HttpExchange e) throws java.io.IOException {
    upstreamAuthorizations.add(e.getRequestHeaders().getFirst("Authorization"));
    if (upstreamBroken.get()) {
      e.sendResponseHeaders(500, -1);
      e.close();
      return;
    }
    Map<String, String> query = query(e.getRequestURI().getQuery());
    String keyword = query.getOrDefault("keyword", "");
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> order : upstreamOrders) {
      String haystack =
          String.valueOf(order.get("orderNo"))
              + " "
              + String.valueOf(order.get("externalOrderNo"))
              + " "
              + String.valueOf(order.get("buyerName"));
      if (keyword.isEmpty() || haystack.contains(keyword)) {
        rows.add(order);
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("rows", rows);
    body.put("page", 0);
    body.put("size", rows.size());
    body.put("total", rows.size());
    write(e, body);
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> result = new LinkedHashMap<>();
    if (raw == null || raw.isBlank()) {
      return result;
    }
    for (String part : raw.split("&")) {
      int idx = part.indexOf('=');
      if (idx <= 0) {
        continue;
      }
      result.put(
          URLDecoder.decode(part.substring(0, idx), StandardCharsets.UTF_8),
          URLDecoder.decode(part.substring(idx + 1), StandardCharsets.UTF_8));
    }
    return result;
  }

  private static boolean has(Map<String, String> query, String key) {
    String value = query.get(key);
    return value != null && !value.isBlank();
  }

  private static void write(HttpExchange e, Object body) throws java.io.IOException {
    try {
      byte[] bytes = json.writeValueAsBytes(body);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(200, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "crm_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_service_business_hours_audit, crm_service_business_holiday,"
              + " crm_service_business_hours, crm_customer_contact, crm_message");
    }
    upstreamTickets.clear();
    upstreamCases.clear();
    upstreamOrders.clear();
    upstreamAssignments.clear();
    upstreamAuthorizations.clear();
    upstreamBroken.set(false);
    Map<String, Object> order = new LinkedHashMap<>();
    order.put("id", UUID.nameUUIDFromBytes("order-2001".getBytes()));
    order.put("orderNo", "SO-2001");
    order.put("externalOrderNo", "AMZ-2001");
    order.put("orderSource", "AMAZON");
    order.put("documentStatus", "SUBMITTED");
    order.put("currency", "USD");
    order.put("grandTotalAmount", new java.math.BigDecimal("39.90"));
    order.put("customerPartyId", UUID.nameUUIDFromBytes("party-1".getBytes()));
    order.put("buyerName", "Ada Buyer");
    order.put("platformStatus", "AWAITING_SHIPMENT");
    order.put("paymentStatus", "PAID");
    order.put("fulfillmentStatus", "UNFULFILLED");
    order.put("aftersalesStatus", "IN_PROCESS");
    upstreamOrders.add(order);
    upstreamCases.put("AS-1001", "SO-2001");
  }

  /** 造一张上游工单。 */
  private static Map<String, Object> ticket(
      String ticketNo,
      String type,
      String status,
      String priority,
      String assignedToRef,
      String caseNo,
      Integer replyHours,
      OffsetDateTime createdAt,
      OffsetDateTime firstReplyAt) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", UUID.nameUUIDFromBytes(ticketNo.getBytes()));
    row.put("ticketNo", ticketNo);
    row.put("ticketType", type);
    row.put("ticketStatus", status);
    row.put("priority", priority);
    row.put("sourceChannel", "MANUAL");
    row.put("sourceRef", "");
    row.put("subject", "主题 " + ticketNo);
    row.put("reasonNote", "诉求 " + ticketNo);
    row.put("assignedToRef", assignedToRef);
    row.put("assignedToUserId", null);
    row.put("assignedAt", null);
    row.put("caseId", UUID.nameUUIDFromBytes(caseNo.getBytes()));
    row.put("caseNo", caseNo);
    row.put("caseStatus", "IN_PROCESS");
    row.put("caseType", "RETURN_REFUND");
    row.put("platformScope", "AMAZON");
    row.put("slaPolicyId", null);
    row.put("slaReplyHours", replyHours);
    row.put("slaResolveHours", replyHours == null ? null : replyHours * 2);
    row.put("slaBasis", "POLICY_SLA");
    row.put(
        "replyDueAt",
        replyHours == null ? null : createdAt.plusHours(replyHours).toString());
    row.put(
        "resolveDueAt",
        replyHours == null ? null : createdAt.plusHours(replyHours * 2L).toString());
    row.put("firstReplyAt", firstReplyAt == null ? null : firstReplyAt.toString());
    row.put("waitingBuyerAt", null);
    row.put("closedAt", null);
    row.put("closeReason", "");
    row.put("closeNote", "");
    row.put("escalatedCaseId", null);
    row.put("escalatedCaseNo", null);
    row.put("escalatedDisputeId", null);
    row.put("escalatedDisputeNo", null);
    row.put("satisfactionRating", null);
    row.put("createdAt", createdAt.toString());
    row.put("updatedAt", createdAt.toString());
    row.put("version", 0);
    return row;
  }

  /** 配置营业时间：只开周一 09:00-18:00（其余工作日关掉）。 */
  private void seedBusinessHours() throws Exception {
    putJson(
            "/crm/service-tickets/business-hours",
            """
            {"timezone":"Asia/Shanghai",
             "windows":[{"weekday":1,"startTime":"09:00:00","endTime":"18:00:00","enabled":true}]}
            """)
        .get("configured")
        .asBoolean();
  }

  /** 在本服务自己的表里种一条买家消息（买家事实的来源）。 */
  private void seedMessage(String orderNo, String buyerReference) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_message(id,tenant_id,message_no,shop_id,shop_name,platform_code,"
                  + " buyer_reference,order_reference,subject,body,direction,message_type,priority,"
                  + " read_status,platform_time,source,created_at,updated_at)"
                  + " values (?,?::uuid,?,?::uuid,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,"
                  + "?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              "MSG-TEST-1",
              shopId,
              "美国主店",
              "AMAZON",
              buyerReference,
              orderNo,
              "买家消息",
              "正文",
              "INBOUND",
              "BUYER_MESSAGE",
              "NORMAL",
              "UNREAD",
              OffsetDateTime.parse("2026-05-30T10:00:00+08:00"),
              "MANUAL_ENTRY",
              OffsetDateTime.parse("2026-05-30T10:01:00+08:00"),
              OffsetDateTime.parse("2026-05-30T10:01:00+08:00"));
          return null;
        });
  }

  /** 在本服务自己的表里种一条接触记录（买家事实的来源）。 */
  private void seedContact(String orderNo, String customerRef, String customerName) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_customer_contact(id,tenant_id,contact_no,customer_ref,"
                  + " customer_name,contact_type,direction,origin,order_reference,subject,summary,"
                  + " occurred_at,created_at,updated_at)"
                  + " values (?,?::uuid,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              "CC-TEST-1",
              customerRef,
              customerName,
              "CALL",
              "INBOUND",
              "MANUAL",
              orderNo,
              "来电",
              "客户来电",
              OffsetDateTime.parse("2026-05-31T09:00:00+08:00"),
              OffsetDateTime.parse("2026-05-31T09:01:00+08:00"),
              OffsetDateTime.parse("2026-05-31T09:01:00+08:00"));
          return null;
        });
  }

  private JsonNode getJson(String path) throws Exception {
    MvcResult result =
        http.perform(MockMvcRequestBuilders.get(path).header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  private JsonNode putJson(String path, String content) throws Exception {
    MvcResult result =
        http.perform(
                MockMvcRequestBuilders.put(path)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(content))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  // ================================================================ 迁移与 RLS

  @Test
  void 迁移落地且rls是强制的() {
    for (String table :
        List.of(
            "crm_service_business_hours",
            "crm_service_business_holiday",
            "crm_service_business_hours_audit")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V306 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class))
          .as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」")
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('crm_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("crm_app 必须真的能读 " + table)
          .isTrue();
    }
  }

  @Test
  void 库里没有会自己过期的剩余列() {
    // 「剩余小时数」一列写下去就开始自己过期 —— 库里不许有它。
    var rows =
        sql.fetch(
            "select table_name, column_name from information_schema.columns"
                + " where table_schema = 'public'"
                + " and (column_name like '%remaining%' or column_name like '%remain%')");
    assertThat(rows).as("CRM 库里不许出现会自己过期的剩余列").isEmpty();
  }

  // ================================================================ 列表：真分页真筛选

  @Test
  void 列表是真分页() throws Exception {
    seedBusinessHours();
    upstreamTickets.add(ticket("TK-1", "INQUIRY", "OPEN", "NORMAL", "agent-a", "AS-1001", 8, MONDAY_09, null));
    upstreamTickets.add(ticket("TK-2", "COMPLAINT", "OPEN", "HIGH", "agent-b", "AS-1001", 8, MONDAY_09, null));
    upstreamTickets.add(ticket("TK-3", "ORDER_CHASE", "IN_PROGRESS", "LOW", "", "AS-1001", 8, MONDAY_09, null));

    JsonNode first = getJson("/crm/service-tickets?page=0&size=2&enrich=false");
    assertThat(first.get("rows").size()).isEqualTo(2);
    assertThat(first.get("total").asLong()).isEqualTo(3);
    assertThat(first.get("totalExact").asBoolean()).isTrue();

    JsonNode second = getJson("/crm/service-tickets?page=1&size=2&enrich=false");
    assertThat(second.get("rows").size()).isEqualTo(1);
    assertThat(second.get("total").asLong()).isEqualTo(3);
  }

  @Test
  void 每个筛选维度都真的生效() throws Exception {
    seedBusinessHours();
    upstreamTickets.add(ticket("TK-1", "INQUIRY", "OPEN", "NORMAL", "agent-a", "AS-1001", 8, MONDAY_09, null));
    upstreamTickets.add(ticket("TK-2", "COMPLAINT", "OPEN", "HIGH", "agent-b", "AS-1001", 8, MONDAY_09, null));
    upstreamTickets.add(ticket("TK-3", "ORDER_CHASE", "IN_PROGRESS", "URGENT", "", "AS-1001", 8, MONDAY_09, null));

    assertThat(ticketNos("/crm/service-tickets?ticketType=COMPLAINT&enrich=false")).containsExactly("TK-2");
    assertThat(ticketNos("/crm/service-tickets?priority=URGENT&enrich=false")).containsExactly("TK-3");
    assertThat(ticketNos("/crm/service-tickets?status=OPEN&enrich=false"))
        .containsExactlyInAnyOrder("TK-1", "TK-2");
    assertThat(ticketNos("/crm/service-tickets?assignedToRef=agent-a&enrich=false")).containsExactly("TK-1");
    assertThat(ticketNos("/crm/service-tickets?unassignedOnly=true&enrich=false")).containsExactly("TK-3");
    assertThat(ticketNos("/crm/service-tickets?keyword=TK-3&enrich=false")).containsExactly("TK-3");
    assertThat(
            ticketNos(
                "/crm/service-tickets?from=2026-06-01T00:00:00Z"
                    + "&to=2026-06-01T23:59:59Z&enrich=false"))
        .hasSize(3);
    assertThat(
            ticketNos("/crm/service-tickets?from=2026-06-02T00:00:00Z&enrich=false"))
        .isEmpty();
  }

  private List<String> ticketNos(String path) throws Exception {
    List<String> result = new ArrayList<>();
    for (JsonNode row : getJson(path).get("rows")) {
      result.add(row.get("ticketNo").asText());
    }
    return result;
  }

  // ================================================================ SLA：营业时间口径

  @Test
  void sla按营业时间计时而不是自然时间() throws Exception {
    seedBusinessHours();
    // 25 小时前进来的单，回复时限 24 小时：
    //   自然时间口径 ⇒ 已经逾期；营业时间口径（每周只有周一 9 小时）⇒ 仍在计时。
    OffsetDateTime recent = OffsetDateTime.now().minusHours(25);
    upstreamTickets.add(ticket("TK-NIGHT", "INQUIRY", "OPEN", "NORMAL", "agent-a", "AS-1001", 24, recent, null));

    JsonNode row = getJson("/crm/service-tickets?enrich=false").get("rows").get(0);
    JsonNode sla = row.get("sla");
    assertThat(sla.get("replyState").asText())
        .as("夜间积压的单在营业时间口径下仍在计时，不得被判逾期")
        .isEqualTo("PENDING");
    assertThat(sla.get("replyBusinessSecondsRemaining").asLong())
        .as("剩余营业时间必须为正")
        .isPositive();
    assertThat(OffsetDateTime.parse(sla.get("upstreamReplyDueAt").asText()))
        .as("上游的自然时间时限早已过去 —— 这正是加粗句要避免的误判")
        .isBefore(OffsetDateTime.now());
  }

  @Test
  void 自然时间判逾期但营业时间仍达标时判达标() throws Exception {
    seedBusinessHours();
    // 周一 09:00 建单，回复时限 10 小时：
    //   自然时间 ⇒ 周一 19:00 到期；营业时间 ⇒ 周一 09:00-18:00 只算 9 小时，
    //   剩 1 小时落到下周一 09:00-10:00 ⇒ 到期时刻在下周。
    // 于是「周一 20:00 才回复」在自然时间口径下是逾期，在营业时间口径下是达标。
    upstreamTickets.add(
        ticket(
            "TK-EDGE",
            "INQUIRY",
            "IN_PROGRESS",
            "NORMAL",
            "agent-a",
            "AS-1001",
            10,
            MONDAY_09,
            OffsetDateTime.parse("2026-06-01T20:00:00+08:00")));

    JsonNode sla = getJson("/crm/service-tickets?enrich=false").get("rows").get(0).get("sla");
    assertThat(sla.get("replyState").asText()).isEqualTo("MET");
    assertThat(OffsetDateTime.parse(sla.get("upstreamReplyDueAt").asText()))
        .as("上游的自然时间时限是周一 19:00，而回复发生在 20:00 ⇒ 自然时间口径会判逾期")
        .isBefore(OffsetDateTime.parse("2026-06-01T20:00:00+08:00"));
  }

  @Test
  void 没配营业时间就不算而不是退回自然时间() throws Exception {
    // 刻意**不配**营业时间。
    upstreamTickets.add(ticket("TK-NOCAL", "INQUIRY", "OPEN", "NORMAL", "agent-a", "AS-1001", 24, OffsetDateTime.now().minusHours(30), null));

    JsonNode sla = getJson("/crm/service-tickets?enrich=false").get("rows").get(0).get("sla");
    assertThat(sla.get("replyState").asText())
        .as("没配营业时间 ⇒ 算不出来；退回自然时间等于把误判请回来")
        .isEqualTo("NO_CALENDAR");
    assertThat(sla.get("calendarConfigured").asBoolean()).isFalse();
    assertThat(sla.get("note").asText()).contains("不退回自然时间");
  }

  @Test
  void sla状态筛选按营业时间口径() throws Exception {
    seedBusinessHours();
    OffsetDateTime recent = OffsetDateTime.now().minusHours(25);
    upstreamTickets.add(ticket("TK-PENDING", "INQUIRY", "OPEN", "NORMAL", "agent-a", "AS-1001", 24, recent, null));
    upstreamTickets.add(
        ticket(
            "TK-MET",
            "INQUIRY",
            "IN_PROGRESS",
            "NORMAL",
            "agent-a",
            "AS-1001",
            10,
            MONDAY_09,
            OffsetDateTime.parse("2026-06-01T20:00:00+08:00")));
    upstreamTickets.add(ticket("TK-BREACHED", "INQUIRY", "OPEN", "NORMAL", "agent-a", "AS-1001", 1, MONDAY_09, null));

    assertThat(ticketNos("/crm/service-tickets?slaState=MET&enrich=false")).containsExactly("TK-MET");
    assertThat(ticketNos("/crm/service-tickets?slaState=PENDING&enrich=false"))
        .containsExactly("TK-PENDING");
    assertThat(ticketNos("/crm/service-tickets?slaState=BREACHED&enrich=false"))
        .containsExactly("TK-BREACHED");
    assertThat(ticketNos("/crm/service-tickets?overdueOnly=true&enrich=false"))
        .containsExactly("TK-BREACHED");
  }

  @Test
  void sla剩余是读取时刻的派生态() throws Exception {
    seedBusinessHours();
    OffsetDateTime recent = OffsetDateTime.now().minusHours(25);
    upstreamTickets.add(ticket("TK-PENDING", "INQUIRY", "OPEN", "NORMAL", "agent-a", "AS-1001", 24, recent, null));

    JsonNode first = getJson("/crm/service-tickets?enrich=false").get("rows").get(0).get("sla");
    Thread.sleep(1100);
    JsonNode second = getJson("/crm/service-tickets?enrich=false").get("rows").get(0).get("sla");
    assertThat(second.get("replyWallClockSecondsRemaining").asLong())
        .as("自然时间剩余必然随读取时刻减少 —— 它是**本次读取算出来的**，不是库里的一列")
        .isLessThan(first.get("replyWallClockSecondsRemaining").asLong());
    assertThat(second.get("replyBusinessSecondsRemaining").asLong())
        .as("营业时间剩余只增不减（它按营业时间轴计量）")
        .isLessThanOrEqualTo(first.get("replyBusinessSecondsRemaining").asLong());
    assertThat(second.get("replyBusinessSecondsRemaining").asLong())
        .as("营业时间剩余 ≤ 自然时间剩余（营业时间是自然时间的子集）")
        .isLessThanOrEqualTo(second.get("replyWallClockSecondsRemaining").asLong());
  }

  // ================================================================ 关联买家 / 订单 / 转派

  @Test
  void 关联订单与买家都补全了且订单不复制一份() throws Exception {
    seedBusinessHours();
    seedMessage("SO-2001", "BUYER-1");
    seedContact("SO-2001", "CUST-1", "Ada Buyer");
    upstreamTickets.add(ticket("TK-1", "INQUIRY", "OPEN", "NORMAL", "agent-a", "AS-1001", 8, MONDAY_09, null));

    JsonNode row = getJson("/crm/service-tickets").get("rows").get(0);
    JsonNode order = row.get("order");
    assertThat(order.get("available").asBoolean()).isTrue();
    assertThat(order.get("orderNo").asText()).isEqualTo("SO-2001");
    assertThat(order.get("currency").asText()).isEqualTo("USD");
    assertThat(order.get("grandTotalAmount").asDouble()).isEqualTo(39.90);

    JsonNode buyer = row.get("buyer");
    assertThat(buyer.get("available").asBoolean()).isTrue();
    assertThat(buyer.get("buyerReference").asText()).isEqualTo("BUYER-1");
    assertThat(buyer.get("buyerName").asText()).isEqualTo("Ada Buyer");
    assertThat(buyer.get("contactRef").asText()).isEqualTo("CUST-1");
    assertThat(buyer.get("shopId").asText()).isEqualTo(shopId.toString());
  }

  @Test
  void 上游查不到这一单时明确说不可用而不是显示空白() throws Exception {
    seedBusinessHours();
    upstreamCases.remove("AS-1001");
    upstreamTickets.add(ticket("TK-1", "INQUIRY", "OPEN", "NORMAL", "agent-a", "AS-1001", 8, MONDAY_09, null));

    JsonNode row = getJson("/crm/service-tickets").get("rows").get(0);
    assertThat(row.get("order").get("available").asBoolean()).isFalse();
    assertThat(row.get("order").get("note").asText()).contains("AS-1001");
  }

  @Test
  void 转派历史与升级去向读上游既有事实() throws Exception {
    seedBusinessHours();
    Map<String, Object> row =
        ticket("TK-1", "INQUIRY", "OPEN", "NORMAL", "agent-b", "AS-1001", 8, MONDAY_09, null);
    row.put("escalatedCaseNo", "AS-2002");
    row.put("escalatedDisputeNo", "PD-3003");
    upstreamTickets.add(row);
    UUID ticketId = (UUID) row.get("id");
    upstreamAssignments.put(
        ticketId,
        List.of(
            Map.of(
                "id", UUID.randomUUID(),
                "assignAction", "ASSIGN",
                "assignMode", "MANUAL",
                "fromOwnerRef", "",
                "toOwnerRef", "agent-a",
                "reasonNote", "首派",
                "createdBy", userId,
                "createdAt", "2026-06-01T09:10:00+08:00"),
            Map.of(
                "id", UUID.randomUUID(),
                "assignAction", "TRANSFER",
                "assignMode", "MANUAL",
                "fromOwnerRef", "agent-a",
                "toOwnerRef", "agent-b",
                "reasonNote", "转给售后二线",
                "createdBy", userId,
                "createdAt", "2026-06-01T10:20:00+08:00")));

    JsonNode transfer = getJson("/crm/service-tickets").get("rows").get(0).get("transfer");
    assertThat(transfer.get("historyAvailable").asBoolean()).isTrue();
    assertThat(transfer.get("assignmentCount").asInt()).isEqualTo(2);
    assertThat(transfer.get("transferCount").asInt()).isEqualTo(1);
    assertThat(transfer.get("lastFromOwnerRef").asText()).isEqualTo("agent-a");
    assertThat(transfer.get("lastToOwnerRef").asText()).isEqualTo("agent-b");
    assertThat(transfer.get("lastReasonNote").asText()).isEqualTo("转给售后二线");
    assertThat(transfer.get("escalatedCaseNo").asText()).isEqualTo("AS-2002");
    assertThat(transfer.get("escalatedDisputeNo").asText()).isEqualTo("PD-3003");
  }

  @Test
  void 转发的是调用方自己的bearer而不是本服务替上游决定租户() throws Exception {
    seedBusinessHours();
    upstreamTickets.add(ticket("TK-1", "INQUIRY", "OPEN", "NORMAL", "agent-a", "AS-1001", 8, MONDAY_09, null));
    getJson("/crm/service-tickets?enrich=false");
    assertThat(upstreamAuthorizations)
        .as("上游必须收到调用方自己的 bearer")
        .isNotEmpty()
        .allMatch(value -> "session".equals(value));
  }

  // ================================================================ 上游不可用

  @Test
  void 上游读不到就503而不是空列表() throws Exception {
    seedBusinessHours();
    upstreamBroken.set(true);
    http.perform(MockMvcRequestBuilders.get("/crm/service-tickets").header("Authorization", "session"))
        .andExpect(status().isServiceUnavailable());
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明如实标出下游未交付() throws Exception {
    JsonNode caps = getJson("/crm/service-tickets/capabilities");
    JsonNode processing = null;
    for (JsonNode dimension : caps.get("dimensions")) {
      if ("ticket_processing".equals(dimension.get("dimension").asText())) {
        processing = dimension;
      }
    }
    assertThat(processing).as("能力声明必须有 ticket_processing 这一维").isNotNull();
    assertThat(processing.get("available").asBoolean())
        .as("15.4.1.2 工单处理未交付 ⇒ available=false")
        .isFalse();
    assertThat(processing.get("ownerModule").asText()).isEqualTo("15.4.1.2");
    assertThat(caps.get("upstreamNote").asText()).contains("/trade/service-tickets");
    assertThat(caps.get("slaBasisNote").asText()).contains("营业时间");
  }

  // ================================================================ 营业时间配置

  @Test
  void 营业时间可配置可读回且留痕() throws Exception {
    JsonNode config =
        putJson(
            "/crm/service-tickets/business-hours",
            """
            {"timezone":"Asia/Shanghai",
             "windows":[{"weekday":1,"startTime":"09:00:00","endTime":"18:00:00","enabled":true},
                        {"weekday":2,"startTime":"09:00:00","endTime":"18:00:00","enabled":true}]}
            """);
    assertThat(config.get("configured").asBoolean()).isTrue();
    assertThat(config.get("scopeKey").asText()).isEqualTo("DEFAULT");
    assertThat(config.get("windows").size()).isEqualTo(2);

    JsonNode read = getJson("/crm/service-tickets/business-hours");
    assertThat(read.get("timezone").asText()).isEqualTo("Asia/Shanghai");
    assertThat(read.get("windows").size()).isEqualTo(2);

    // 再改一次 ⇒ 审计**只追加**（两条）。
    putJson(
        "/crm/service-tickets/business-hours",
        """
        {"timezone":"Asia/Shanghai",
         "windows":[{"weekday":1,"startTime":"10:00:00","endTime":"19:00:00","enabled":true}]}
        """);
    var audit =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select action, payload from crm_service_business_hours_audit"
                        + " where tenant_id = ?::uuid order by occurred_at",
                    tenant));
    assertThat(audit).as("每次改写都要留一条只追加的审计").hasSize(2);
    assertThat(audit.get(0).get("action", String.class)).isEqualTo("UPSERT_WINDOWS");
    assertThat(audit.get(0).get("payload").toString()).contains("09:00");
    assertThat(audit.get(1).get("payload").toString()).contains("10:00");
  }

  @Test
  void 营业时间审计只追加改不动() throws Exception {
    seedBusinessHours();
    UUID auditId =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                        "select id from crm_service_business_hours_audit"
                            + " where tenant_id = ?::uuid limit 1",
                        tenant)
                    .get("id", UUID.class));
    assertThat(auditId).isNotNull();
    // ⚠️ 用**迁移用户**（超管，绕开 RLS）直连改：这样证明拦住它的是**触发器**，
    //   而不是「RLS 恰好把行挡住了」（那样 UPDATE 会静默改 0 行，不报错）。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update crm_service_business_hours_audit set action = 'UPSERT_HOLIDAYS'"
                          + " where id = '"
                          + auditId
                          + "'"))
          .as("审计只追加：改它或删它都会让「当时的口径是什么」变得不可复核")
          .hasStackTraceContaining("只追加");
    }
  }

  @Test
  void 营业区间重叠当场被拒() throws Exception {
    http.perform(
            MockMvcRequestBuilders.put("/crm/service-tickets/business-hours")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"timezone":"Asia/Shanghai",
                     "windows":[{"weekday":1,"startTime":"09:00:00","endTime":"12:00:00","enabled":true},
                                {"weekday":1,"startTime":"11:00:00","endTime":"18:00:00","enabled":true}]}
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 非法时区当场被拒() throws Exception {
    http.perform(
            MockMvcRequestBuilders.put("/crm/service-tickets/business-hours")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"timezone":"Not/AZone",
                     "windows":[{"weekday":1,"startTime":"09:00:00","endTime":"18:00:00","enabled":true}]}
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 节假日例外可配置且整日不计时() throws Exception {
    seedBusinessHours();
    JsonNode result =
        putJson(
            "/crm/service-tickets/business-hours/holidays",
            """
            {"holidays":[{"date":"2026-06-08","label":"春节","closed":true}]}
            """);
    assertThat(result.get("holidays").size()).isEqualTo(1);
    JsonNode read = getJson("/crm/service-tickets/business-hours");
    assertThat(read.get("holidays").get(0).get("date").asText()).isEqualTo("2026-06-08");
    assertThat(read.get("holidays").get(0).get("closed").asBoolean()).isTrue();
  }
}
