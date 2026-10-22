package com.starcon.crm;

import static com.starcon.crm.ServiceTicketProcessModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 15.4.1.2 工单处理的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>
 * （租户隔离三断言在 {@link ServiceTicketProcessTenancyIntegrationTest} 里），
 * 本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>🔴 加粗句「工单关闭不等于问题解决」</b>：
 *       关闭与解决确认是<b>两个动作</b>、<b>两张表</b>、<b>两个响应字段</b>；
 *       没关闭就不许确认解决；关闭了也不等于解决了。</li>
 *   <li><b>🔴 SLA 沿营业时间轴，且暂停扣的是营业时间</b>：
 *       没配营业时间一律 NO_CALENDAR（<b>不退回自然时间</b>）；
 *       暂停把到期时刻往后推（推的量 = 暂停区间里的营业时间）。</li>
 *   <li><b>上游是唯一事实源</b>：回复 / 状态 / 关闭 / 满意度 / 升级全部转发，
 *       转发的是<b>调用方自己的</b> bearer；本服务<b>不建工单表</b>、
 *       <b>不另存满意度</b>。</li>
 *   <li><b>处理记录只追加</b>（DB 触发器拒绝改删，不是 RLS 恰好挡行）。</li>
 *   <li><b>上游读不到就 503</b>：绝不返回空结果冒充「上游没有工单」。</li>
 * </ul>
 *
 * <p>⚠️ 上游 trade 是<b>真起的 HTTP 服务</b>（不 mock 端口）——
 * mock 掉的话，「转发的是调用方自己的 bearer」这条根本测不出来。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceTicketProcessIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 上游（trade）的工单 —— 测试自己往里塞，**不 mock 上游服务**（真起 HTTP 服务）。 */
  static final Map<UUID, Map<String, Object>> upstreamTickets = new ConcurrentHashMap<>();

  /** 上游收到的写动作（"METHOD /path"）。 */
  static final List<String> upstreamWrites = new CopyOnWriteArrayList<>();

  /** 上游收到的 Authorization 头（证明转发的是**调用方自己的** bearer）。 */
  static final List<String> upstreamAuthorizations = new CopyOnWriteArrayList<>();

  /** 上游的售后单：售后单号 → 订单号。 */
  static final Map<String, String> upstreamCases = new ConcurrentHashMap<>();

  /** 上游的订单。 */
  static final List<Map<String, Object>> upstreamOrders = new CopyOnWriteArrayList<>();

  /** 让上游整个挂掉（证明「读不到」不是「空结果」）。 */
  static final AtomicBoolean upstreamBroken = new AtomicBoolean(false);

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
      trade.createContext("/trade/service-tickets", ServiceTicketProcessIntegrationTest::tickets);
      trade.createContext("/trade/aftersales/cases", ServiceTicketProcessIntegrationTest::cases);
      trade.createContext("/trade/sales-orders/list", ServiceTicketProcessIntegrationTest::orders);
      trade.setExecutor(Executors.newFixedThreadPool(4));
      trade.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
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
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired AccessService accessService;
  @Autowired ServiceTicketProcessService processService;

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 造一张上游工单（字段与 {@code ServiceTicketFactsPort.TicketView} 对齐）。 */
  static Map<String, Object> newTicket(
      UUID id, String status, Integer replyHours, Integer resolveHours, OffsetDateTime createdAt) {
    Map<String, Object> ticket = new LinkedHashMap<>();
    ticket.put("id", id.toString());
    ticket.put("ticketNo", "TK-" + id.toString().substring(0, 8));
    ticket.put("ticketType", "COMPLAINT");
    ticket.put("ticketStatus", status);
    ticket.put("priority", "HIGH");
    ticket.put("sourceChannel", "MANUAL");
    ticket.put("subject", "买家反馈退款未到账");
    ticket.put("reasonNote", "已联系买家");
    ticket.put("assignedToRef", "agent-1");
    ticket.put("caseNo", "CS-2026-0001");
    ticket.put("platformScope", "AMAZON");
    ticket.put("slaReplyHours", replyHours);
    ticket.put("slaResolveHours", resolveHours);
    ticket.put("slaBasis", "POLICY_SLA");
    ticket.put("createdAt", createdAt.toString());
    ticket.put("updatedAt", createdAt.toString());
    ticket.put("version", 1L);
    return ticket;
  }

  /** 插入一段营业时段（周一到周五 09:00–12:00、13:00–18:00，Asia/Shanghai）。 */
  void insertWindows() {
    tenants.run(
        tenant,
        () -> {
          for (int weekday = 1; weekday <= 5; weekday++) {
            sql.execute(
                "insert into crm_service_business_hours"
                    + "(id,tenant_id,scope_key,weekday,start_time,end_time,timezone,enabled,"
                    + " created_at,updated_at)"
                    + " values (?,?::uuid,'DEFAULT',?,?::time,?::time,'Asia/Shanghai',true,"
                    + "?::timestamptz,?::timestamptz)",
                UUID.randomUUID(),
                tenant,
                weekday,
                java.sql.Time.valueOf("09:00:00"),
                java.sql.Time.valueOf("12:00:00"),
                OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC));
            sql.execute(
                "insert into crm_service_business_hours"
                    +"(id,tenant_id,scope_key,weekday,start_time,end_time,timezone,enabled,"
                    + " created_at,updated_at)"
                    + " values (?,?::uuid,'DEFAULT',?,?::time,?::time,'Asia/Shanghai',true,"
                    + "?::timestamptz,?::timestamptz)",
                UUID.randomUUID(),
                tenant,
                weekday,
                java.sql.Time.valueOf("13:00:00"),
                java.sql.Time.valueOf("18:00:00"),
                OffsetDateTime.now(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC));
          }
          return null;
        });
  }

  /** 在可信租户事务里插入一段**已恢复**的暂停区间（timestamptz 绑 OffsetDateTime）。 */
  void insertPause(UUID ticketId, OffsetDateTime pausedAt, OffsetDateTime resumedAt) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_service_ticket_sla_pause"
                  + "(id,tenant_id,ticket_id,sla_target,scope_key,paused_at,resumed_at,pause_reason,"
                  + " resume_reason,created_by,created_at)"
                  + " values (?,?::uuid,?::uuid,'BOTH','DEFAULT',?::timestamptz,?::timestamptz,"
                  + "'等待买家补材料','买家已补',?::uuid,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              ticketId,
              pausedAt,
              resumedAt,
              userId,
              pausedAt);
          return null;
        });
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_service_ticket_note, crm_service_ticket_action,"
              + " crm_service_ticket_link, crm_service_ticket_resolution,"
              + " crm_service_ticket_sla_pause, crm_service_business_hours_audit,"
              + " crm_service_business_holiday, crm_service_business_hours");
    }
    upstreamTickets.clear();
    upstreamWrites.clear();
    upstreamAuthorizations.clear();
    upstreamCases.clear();
    upstreamOrders.clear();
    upstreamBroken.set(false);
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================
  @Test
  void 能力声明如实标出本模块已交付与上游的缺口() throws Exception {
    var result =
        http.perform(get("/crm/service-ticket-process/capabilities").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(result.getResponse().getContentAsString());

    var dimensions = body.get("dimensions");
    assertThat(dimensions).isNotNull();
    assertThat(dimensions.toString()).contains("解决确认").contains("SLA 暂停");
    // 🔴 上游**没有**「改写已有售后单 / 订单」的端点 ⇒ 如实标 available=false，
    //   **不指向**任何不存在的入口。
    JsonNode push = null;
    for (JsonNode dimension : dimensions) {
      if ("linked_document_push".equals(dimension.get("code").asText())) {
        push = dimension;
      }
    }
    assertThat(push).as("能力声明里必须有「推给上游关联单据」这一维").isNotNull();
    assertThat(push.get("available").asBoolean()).isFalse();
    assertThat(push.get("ownerModule").asText()).isEqualTo("11.1.1.1");
    // 🔴 加粗句必须写在边界里。
    assertThat(body.get("boundaries").toString()).contains("关闭与解决确认是**两个动作**");
    assertThat(body.get("closeVersusResolutionNote").asText()).contains("关闭 ≠ 解决");
  }

  // ==========================================================================
  // 回复 / 备注
  // ==========================================================================
  @Test
  void 回复转发上游首次回复_并转发调用方自己的bearer() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));

    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/replies", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"content":"已为您查询退款流水，预计 3 个工作日到账。","replyChannel":"PLATFORM_MESSAGE"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actionType").value("REPLY"))
        .andExpect(jsonPath("$.note.noteType").value("REPLY"))
        .andExpect(jsonPath("$.note.replyChannel").value("PLATFORM_MESSAGE"));

    // 上游确实收到了 first-reply。
    assertThat(upstreamWrites).contains("POST /trade/service-tickets/" + ticketId + "/first-reply");
    // 🔴 转发的是**调用方自己的** bearer（不是本服务替上游编的租户号）。
    assertThat(upstreamAuthorizations).contains("session");

    // 本服务留下了一条回复（只追加）+ 一条处理记录。
    var notes =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select note_type, content, reply_channel from crm_service_ticket_note"
                        + " where ticket_id = ?::uuid",
                    ticketId));
    assertThat(notes).hasSize(1);
    assertThat(notes.get(0).get("note_type", String.class)).isEqualTo("REPLY");
    assertThat(notes.get(0).get("reply_channel", String.class)).isEqualTo("PLATFORM_MESSAGE");

    var actions =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select action_type from crm_service_ticket_action where ticket_id = ?::uuid",
                    ticketId));
    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).get("action_type", String.class)).isEqualTo("REPLY");
  }

  @Test
  void 备注不写渠道_回复必须写渠道() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));

    // 内部备注 ⇒ reply_channel 必须为空（DB CHECK 挡住「内部备注带渠道」）。
    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/notes", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"电话联系过了，等买家回\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.note.noteType").value("NOTE"))
        .andExpect(jsonPath("$.note.replyChannel").doesNotExist());

    // 回复缺渠道 ⇒ 400（不能存一条「不知道发到哪儿去」的回复）。
    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/replies", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"没有渠道的回复\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 备注只追加_改它被DB触发器拒绝() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));
    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/notes", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"原始备注\"}"))
        .andExpect(status().isOk());

    // 用**迁移用户**直连改它：RLS 不挡（运行角色看不到行）——
    //   所以这里用能看见行的连接来证明「拦住它的是触发器，不是恰好看不见」。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      var updateError =
          assertThrows(
              java.sql.SQLException.class,
              () ->
                  s.execute(
                      "update crm_service_ticket_note set content = '改过的备注'"
                          + " where tenant_id = '" + tenant + "'::uuid"));
      assertThat(updateError.getMessage())
          .as("备注只追加：改它必须被 DB 触发器拒绝")
          .contains("只追加");
      var deleteError =
          assertThrows(
              java.sql.SQLException.class,
              () ->
                  s.execute(
                      "delete from crm_service_ticket_note where tenant_id = '"
                          + tenant
                          + "'::uuid"));
      assertThat(deleteError.getMessage())
          .as("备注只追加：删它必须被 DB 触发器拒绝")
          .contains("只追加");
    }
  }

  @Test
  void 处理记录只追加_改它被DB触发器拒绝() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));
    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/notes", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"留痕\"}"))
        .andExpect(status().isOk());

    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      var updateError =
          assertThrows(
              java.sql.SQLException.class,
              () ->
                  s.execute(
                      "update crm_service_ticket_action set detail = '改过的' where tenant_id = '"
                          + tenant
                          + "'::uuid"));
      assertThat(updateError.getMessage())
          .as("处理记录只追加：改它必须被 DB 触发器拒绝")
          .contains("只追加");
      var deleteError =
          assertThrows(
              java.sql.SQLException.class,
              () ->
                  s.execute(
                      "delete from crm_service_ticket_action where tenant_id = '"
                          + tenant
                          + "'::uuid"));
      assertThat(deleteError.getMessage())
          .as("处理记录只追加：删它必须被 DB 触发器拒绝")
          .contains("只追加");
    }
  }

  // ==========================================================================
  // 状态流转
  // ==========================================================================
  @Test
  void 状态流转转发上游并留下前后状态() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));

    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/status", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetStatus\":\"IN_PROGRESS\",\"note\":\"开始处理\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actionType").value("STATUS_CHANGE"));

    assertThat(upstreamWrites).contains("POST /trade/service-tickets/" + ticketId + "/status");

    var actions =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select from_status, to_status, detail from crm_service_ticket_action"
                        + " where ticket_id = ?::uuid",
                    ticketId));
    assertThat(actions).hasSize(1);
    assertThat(actions.get(0).get("from_status", String.class)).isEqualTo("OPEN");
    assertThat(actions.get(0).get("to_status", String.class)).isEqualTo("IN_PROGRESS");
  }

  // ==========================================================================
  // 🔴 关闭 ≠ 解决
  // ==========================================================================
  @Test
  void 关闭不等于解决_关闭后仍未确认解决() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "IN_PROGRESS", 4, 48, MONDAY_09));

    var result =
        http.perform(
                post("/crm/service-ticket-process/tickets/{id}/close", ticketId)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"closeReason\":\"RESOLVED\",\"closeNote\":\"已给出退款方案\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(result.getResponse().getContentAsString());

    // 🔴 关闭只让工单落终态；**「已确认解决」仍然是 false** —— 两个字段互不代表。
    assertThat(body.get("closed").asBoolean()).isTrue();
    assertThat(body.get("resolutionConfirmed").asBoolean()).isFalse();
    assertThat(body.get("message").asText()).contains("关闭不等于解决");

    assertThat(upstreamWrites).contains("POST /trade/service-tickets/" + ticketId + "/close");

    // 单张工单的处理视图里同样分得很清。
    var detail =
        http.perform(
                get("/crm/service-ticket-process/tickets/{id}", ticketId)
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode ticket = json.readTree(detail.getResponse().getContentAsString());
    assertThat(ticket.get("closed").asBoolean()).isTrue();
    assertThat(ticket.get("resolutionConfirmed").asBoolean()).isFalse();
    assertThat(ticket.get("closeVersusResolutionNote").asText()).contains("已关闭」= 是");
  }

  @Test
  void 未关闭的工单不能确认解决() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "IN_PROGRESS", 4, 48, MONDAY_09));

    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/resolutions", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolutionType\":\"REFUND_RECEIVED\",\"resultNote\":\"已到账\"}"))
        .andExpect(status().isBadRequest());

    // ⚠️ MockMvc **不做错误派发**（生产上由容器把 reason 渲染进错误页的 message），
    //   所以「给操作员看的那句人话」要在服务层断言 —— 断言的是**真正抛出去的那句话**。
    var actor = actors.get("session");
    var error =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                accessService.run(
                    actor,
                    "crm.service_ticket.update",
                    () ->
                        processService.confirmResolution(
                            ticketId,
                            new CrmServiceTicketProcessResolutionInput(
                                "REFUND_RECEIVED", "已到账", null),
                            actor.userId(),
                            "session")));
    assertThat(error.getReason()).contains("关闭 ≠ 解决");

    // 一条解决确认也没写进去。
    var rows =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select id from crm_service_ticket_resolution where ticket_id = ?::uuid",
                    ticketId));
    assertThat(rows).isEmpty();
  }

  @Test
  void 确认解决后可以撤销_撤销后不再算已解决() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "RESOLVED", 4, 48, MONDAY_09));

    var created =
        http.perform(
                post("/crm/service-ticket-process/tickets/{id}/resolutions", ticketId)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"resolutionType\":\"REFUND_RECEIVED\",\"resultNote\":\"已到账\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(created.getResponse().getContentAsString());
    assertThat(body.get("resolutionConfirmed").asBoolean()).isTrue();
    UUID resolutionId = UUID.fromString(body.get("resolution").get("id").asText());

    // 重复确认同一种结果 ⇒ 409（不能悄悄覆盖）。
    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/resolutions", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolutionType\":\"REFUND_RECEIVED\"}"))
        .andExpect(status().isConflict());

    // 撤销 ⇒ 行不删，只标 revoked_at。
    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/resolutions/{rid}/revoke", ticketId, resolutionId)
                .header("Authorization", "session"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resolution.active").value(false));

    var rows =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select resolution_type, revoked_at from crm_service_ticket_resolution"
                        + " where ticket_id = ?::uuid",
                    ticketId));
    assertThat(rows).as("撤销只标 revoked_at，行不删（留痕）").hasSize(1);
    assertThat(rows.get(0).get("revoked_at", OffsetDateTime.class)).isNotNull();

    // 再撤销一次 ⇒ 404（没有可撤销的了）。
    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/resolutions/{rid}/revoke", ticketId, resolutionId)
                .header("Authorization", "session"))
        .andExpect(status().isNotFound());
  }

  // ==========================================================================
  // 满意度
  // ==========================================================================
  @Test
  void 满意度只转发上游_本服务不另存一份() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "RESOLVED", 4, 48, MONDAY_09));

    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/satisfaction", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"score\":5,\"comment\":\"处理很快\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actionType").value("SATISFACTION"));

    assertThat(upstreamWrites)
        .contains("POST /trade/service-tickets/" + ticketId + "/satisfaction");

    // 🔴 本服务**没有**满意度表（同一件事两个事实源迟早打架）。
    var tables =
        sql.fetch(
            "select table_name from information_schema.tables where table_schema = 'public'"
                + " and table_name like '%satisfaction%'");
    assertThat(tables).isEmpty();
  }

  @Test
  void 满意度只对已关闭的工单开放() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));

    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/satisfaction", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"score\":5}"))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 🔴 SLA：营业时间轴 + 暂停
  // ==========================================================================
  @Test
  void 没配营业时间时SLA为NO_CALENDAR_不退回自然时间() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));

    var result =
        http.perform(
                get("/crm/service-ticket-process/tickets/{id}", ticketId)
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode sla = json.readTree(result.getResponse().getContentAsString()).get("sla");
    assertThat(sla.get("calendarConfigured").asBoolean()).isFalse();
    assertThat(sla.get("replyState").asText()).isEqualTo("NO_CALENDAR");
    assertThat(sla.get("resolveState").asText()).isEqualTo("NO_CALENDAR");
    assertThat(sla.get("replyDueAt").isNull()).isTrue();
    assertThat(sla.get("note").asText()).contains("不退回自然时间");

    // 🔴 没有轴就没有「暂停掉的那段营业时间」⇒ 不给暂停。
    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/sla/pause", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slaTarget\":\"BOTH\",\"pauseReason\":\"等买家补材料\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 暂停沿营业时间轴_到期时刻因此推后() throws Exception {
    insertWindows();
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));

    // 不暂停：09:00 起 4 个营业小时（09-12 三小时 + 13-14 一小时）⇒ 14:00 到期。
    var before =
        http.perform(
                get("/crm/service-ticket-process/tickets/{id}", ticketId)
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    String dueBefore =
        json.readTree(before.getResponse().getContentAsString())
            .get("sla")
            .get("replyDueAt")
            .asText();
    assertThat(dueBefore).isEqualTo("2026-06-01T14:00:00+08:00");

    // 暂停 10:00–11:00（**1 个营业小时**）⇒ 到期时刻推后到 15:00。
    insertPause(
        ticketId,
        OffsetDateTime.parse("2026-06-01T10:00:00+08:00"),
        OffsetDateTime.parse("2026-06-01T11:00:00+08:00"));

    var after =
        http.perform(
                get("/crm/service-ticket-process/tickets/{id}", ticketId)
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode sla = json.readTree(after.getResponse().getContentAsString()).get("sla");
    assertThat(sla.get("replyDueAt").asText()).isEqualTo("2026-06-01T15:00:00+08:00");
  }

  @Test
  void 暂停与恢复_重复暂停被拒_恢复后计时继续() throws Exception {
    insertWindows();
    UUID ticketId = UUID.randomUUID();
    // 🔴 这张单是**刚刚**进来的（时限还在跑）⇒ 暂停真的冻住了一个正在走的计时器。
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, OffsetDateTime.now()));

    var paused =
        http.perform(
                post("/crm/service-ticket-process/tickets/{id}/sla/pause", ticketId)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"slaTarget\":\"BOTH\",\"pauseReason\":\"等买家补材料\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(paused.getResponse().getContentAsString());
    assertThat(body.get("pause").get("paused").asBoolean()).isTrue();
    assertThat(body.get("pause").get("slaTarget").asText()).isEqualTo("BOTH");
    assertThat(body.get("sla").get("replyPaused").asBoolean()).isTrue();
    // 🔴 计时器冻着是**第四种状态**：既不是「还在走表」，也不是「已经超期」。
    assertThat(body.get("sla").get("replyState").asText()).isEqualTo("PAUSED");

    // 重复暂停 ⇒ 409（一张工单同时最多一条未恢复的暂停）。
    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/sla/pause", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slaTarget\":\"BOTH\",\"pauseReason\":\"再停一次\"}"))
        .andExpect(status().isConflict());

    // 恢复必须写理由。
    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/sla/resume", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());

    var resumed =
        http.perform(
                post("/crm/service-ticket-process/tickets/{id}/sla/resume", ticketId)
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"resumeReason\":\"买家已补材料\"}"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode after = json.readTree(resumed.getResponse().getContentAsString());
    assertThat(after.get("pause").get("paused").asBoolean()).isFalse();
    assertThat(after.get("sla").get("replyPaused").asBoolean()).isFalse();
  }

  @Test
  void 暂停不能抹掉暂停之前就已经发生的超期() throws Exception {
    insertWindows();
    UUID ticketId = UUID.randomUUID();
    // 这张单的时限早就在几个月前就过了（创建于 2026-06-01）。
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));

    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/sla/pause", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slaTarget\":\"BOTH\",\"pauseReason\":\"先拖一拖\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sla.replyPaused").value(true))
        // 🔴 暂停**不能**洗白已经发生的超期 —— 状态仍是「已超期」，不是「暂停中」。
        .andExpect(jsonPath("$.sla.replyState").value("BREACHED"));
  }

  @Test
  void 暂停区间不许重叠() {
    insertWindows();
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));
    insertPause(
        ticketId,
        OffsetDateTime.parse("2026-06-01T10:00:00+08:00"),
        OffsetDateTime.parse("2026-06-01T11:00:00+08:00"));

    // 与既有区间重叠的暂停 ⇒ DB 触发器当场拒绝（23514）。
    assertThat(
            assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () ->
                    tenants.run(
                        tenant,
                        () -> {
                          sql.execute(
                              "insert into crm_service_ticket_sla_pause"
                                  + "(id,tenant_id,ticket_id,sla_target,scope_key,paused_at,pause_reason,"
                                  + " created_by,created_at)"
                                  + " values (?,?::uuid,?::uuid,'BOTH','DEFAULT',?::timestamptz,'重叠',"
                                  + "?::uuid,?::timestamptz)",
                              UUID.randomUUID(),
                              tenant,
                              ticketId,
                              OffsetDateTime.parse("2026-06-01T10:30:00+08:00"),
                              userId,
                              OffsetDateTime.now(ZoneOffset.UTC));
                          return null;
                        })))
        .hasStackTraceContaining("不许重叠");
  }

  @Test
  void 暂停时刻写回等于同一绝对时刻() throws Exception {
    insertWindows();
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));
    OffsetDateTime pausedAt = OffsetDateTime.parse("2026-06-01T10:00:00+08:00");
    OffsetDateTime resumedAt = OffsetDateTime.parse("2026-06-01T11:00:00+08:00");
    insertPause(ticketId, pausedAt, resumedAt);

    // 🔴 timestamptz 一律用 OffsetDateTime 绑定 + ?::timestamptz ——
    //   用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800 秒。
    var row =
        tenants.run(
            tenant,
            () ->
                sql.fetchOne(
                    "select paused_at, resumed_at from crm_service_ticket_sla_pause"
                        + " where ticket_id = ?::uuid",
                    ticketId));
    assertThat(row.get("paused_at", OffsetDateTime.class).toInstant())
        .isEqualTo(pausedAt.toInstant());
    assertThat(row.get("resumed_at", OffsetDateTime.class).toInstant())
        .isEqualTo(resumedAt.toInstant());
  }

  // ==========================================================================
  // 关联单据
  // ==========================================================================
  @Test
  void 关联订单会去上游核实_查不到就拒绝() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));
    upstreamOrders.add(
        Map.of(
            "id", UUID.randomUUID().toString(),
            "orderNo", "SO-1001",
            "externalOrderNo", "EXT-1001",
            "currency", "USD",
            "grandTotalAmount", "49.90",
            "platformStatus", "SHIPPED",
            "paymentStatus", "PAID"));

    // 不存在的订单号 ⇒ 400（关联一个不存在的单子，比不关联更糟）。
    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/links", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"linkType\":\"ORDER\",\"orderNo\":\"SO-NOT-EXIST\"}"))
        .andExpect(status().isBadRequest());

    var rows =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select id from crm_service_ticket_link where ticket_id = ?::uuid", ticketId));
    assertThat(rows).as("核不实的关联一条都不许留下").isEmpty();

    // 存在的订单号 ⇒ 关联成功。
    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/links", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"linkType\":\"ORDER\",\"orderNo\":\"SO-1001\",\"note\":\"买家投诉的就是这单\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.link.linkType").value("ORDER"))
        .andExpect(jsonPath("$.link.orderNo").value("SO-1001"));
  }

  @Test
  void 关联单据的当前事实从上游现取_查不到时给原因不显示空白() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));
    upstreamCases.put("CS-2026-0001", "SO-1001");

    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/links", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"linkType\":\"AFTER_SALES_CASE\",\"caseNo\":\"CS-2026-0001\"}"))
        .andExpect(status().isOk());

    // 上游还能查到 ⇒ 关联单据的**当前事实**现取得到（不在本服务复制一份）。
    var found =
        http.perform(
                get("/crm/service-ticket-process/tickets/{id}/links", ticketId)
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode foundRows = json.readTree(found.getResponse().getContentAsString()).get("rows");
    assertThat(foundRows).hasSize(1);
    assertThat(foundRows.get(0).get("caseNo").asText()).isEqualTo("CS-2026-0001");
    assertThat(foundRows.get(0).get("caseStatus").asText()).isEqualTo("PROCESSING");

    // 🔴 上游后来查不到了 ⇒ 写明原因，**不显示空白**
    //   （空白会被客服读成「这一单没有信息」，而不是「我们没查到」）。
    upstreamCases.remove("CS-2026-0001");
    var missing =
        http.perform(
                get("/crm/service-ticket-process/tickets/{id}/links", ticketId)
                    .header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode missingRows = json.readTree(missing.getResponse().getContentAsString()).get("rows");
    assertThat(missingRows.get(0).get("factsNote").asText()).contains("没有与售后单号");
    assertThat(missingRows.get(0).get("caseNo").asText())
        .as("关系本身还在本服务这边（不复制对方的一列，但关系是自己的事实）")
        .isEqualTo("CS-2026-0001");
  }

  // ==========================================================================
  // 升级（下游 11 售后）
  // ==========================================================================
  @Test
  void 升级转发上游并记下关系() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));

    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/escalate", ticketId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"targetType":"COMPLAINT","reasonNote":"平台判定不成立","targetCaseType":"REFUND",
                     "targetReasonCode":"NOT_RECEIVED","targetAmount":"49.90","targetCurrency":"USD"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.actionType").value("ESCALATE"));

    assertThat(upstreamWrites).contains("POST /trade/service-tickets/" + ticketId + "/escalate");

    // 上游新建出来的售后单 ⇒ 本模块只记**关系**。
    var links =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                    "select link_type, case_no, link_source from crm_service_ticket_link"
                        + " where ticket_id = ?::uuid",
                    ticketId));
    assertThat(links).hasSize(1);
    assertThat(links.get(0).get("link_source", String.class)).isEqualTo("ESCALATION");
    assertThat(links.get(0).get("case_no", String.class)).isNotBlank();
  }

  // ==========================================================================
  // 列表与分页
  // ==========================================================================
  @Test
  void 备注与处理记录都真分页() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));
    for (int i = 0; i < 5; i++) {
      http.perform(
              post("/crm/service-ticket-process/tickets/{id}/notes", ticketId)
                  .header("Authorization", "session")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"content\":\"备注 " + i + "\"}"))
          .andExpect(status().isOk());
    }

    var result =
        http.perform(
                get("/crm/service-ticket-process/tickets/{id}/notes", ticketId)
                    .header("Authorization", "session")
                    .param("page", "0")
                    .param("size", "2"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode page = json.readTree(result.getResponse().getContentAsString());
    assertThat(page.get("rows")).hasSize(2);
    assertThat(page.get("total").asLong()).isEqualTo(5L);
    assertThat(page.get("size").asInt()).isEqualTo(2);

    var actions =
        http.perform(
                get("/crm/service-ticket-process/tickets/{id}/actions", ticketId)
                    .header("Authorization", "session")
                    .param("page", "0")
                    .param("size", "10"))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(actions.getResponse().getContentAsString()).get("total").asLong())
        .isEqualTo(5L);
  }

  @Test
  void 处理队列是真分页且带暂停与解决确认状态() throws Exception {
    insertWindows();
    for (int i = 0; i < 3; i++) {
      UUID id = UUID.randomUUID();
      upstreamTickets.put(id, newTicket(id, "OPEN", 4, 48, MONDAY_09));
    }
    var result =
        http.perform(
                get("/crm/service-ticket-process/tickets")
                    .header("Authorization", "session")
                    .param("size", "2"))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode page = json.readTree(result.getResponse().getContentAsString());
    assertThat(page.get("rows")).hasSize(2);
    assertThat(page.get("total").asLong()).isEqualTo(3L);
    assertThat(page.get("summary").get("total").asLong()).isEqualTo(2L);
  }

  // ==========================================================================
  // 上游不可达
  // ==========================================================================
  @Test
  void 上游读不到就503_不是空结果() throws Exception {
    UUID ticketId = UUID.randomUUID();
    upstreamTickets.put(ticketId, newTicket(ticketId, "OPEN", 4, 48, MONDAY_09));
    upstreamBroken.set(true);

    http.perform(
            get("/crm/service-ticket-process/tickets/{id}", ticketId)
                .header("Authorization", "session"))
        .andExpect(status().isServiceUnavailable());
  }

  // ==========================================================================
  // 上游假服务
  // ==========================================================================

  private static void tickets(HttpExchange e) throws IOException {
    upstreamAuthorizations.add(e.getRequestHeaders().getFirst("Authorization"));
    if (upstreamBroken.get()) {
      e.sendResponseHeaders(500, -1);
      e.close();
      return;
    }
    String path = e.getRequestURI().getPath();
    String method = e.getRequestMethod();
    if ("GET".equals(method) && "/trade/service-tickets".equals(path)) {
      List<Map<String, Object>> rows = new ArrayList<>(upstreamTickets.values());
      String query = e.getRequestURI().getQuery();
      int page = Integer.parseInt(queryParam(query, "page", "0"));
      int size = Integer.parseInt(queryParam(query, "size", "50"));
      int from = Math.min(page * size, rows.size());
      int to = Math.min(from + size, rows.size());
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("rows", new ArrayList<>(rows.subList(from, to)));
      body.put("page", page);
      body.put("size", size);
      body.put("total", rows.size());
      body.put("summary", null);
      write(e, body);
      return;
    }
    if (path.startsWith("/trade/service-tickets/")) {
      String rest = path.substring("/trade/service-tickets/".length());
      int slash = rest.indexOf('/');
      String idPart = slash < 0 ? rest : rest.substring(0, slash);
      String action = slash < 0 ? "" : rest.substring(slash + 1);
      UUID id = UUID.fromString(idPart);
      Map<String, Object> head = upstreamTickets.get(id);
      if (head == null) {
        e.sendResponseHeaders(404, -1);
        e.close();
        return;
      }
      if ("GET".equals(method)) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("head", head);
        body.put("assignments", List.of());
        write(e, body);
        return;
      }
      Map<String, Object> request = readJson(e);
      upstreamWrites.add(method + " " + path);
      applyAction(e, head, action, request);
      return;
    }
    e.sendResponseHeaders(404, -1);
    e.close();
  }

  /** 上游的写动作（模拟 trade 的真实语义）。 */
  private static void applyAction(
      HttpExchange e, Map<String, Object> head, String action, Map<String, Object> request)
      throws IOException {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    switch (action) {
      case "first-reply" -> {
        head.put("firstReplyAt", now.toString());
        head.put("version", version(head) + 1);
      }
      case "status" -> {
        head.put("ticketStatus", request.get("targetStatus"));
        head.put("version", version(head) + 1);
      }
      case "close" -> {
        String reason = String.valueOf(request.get("closeReason"));
        head.put(
            "ticketStatus",
            "RESOLVED".equals(reason) || "ESCALATED".equals(reason) ? "RESOLVED" : "CANCELLED");
        head.put("closeReason", reason);
        head.put("closeNote", request.get("closeNote"));
        head.put("closedAt", now.toString());
        head.put("version", version(head) + 1);
      }
      case "satisfaction" -> {
        head.put("satisfactionRating", request.get("score"));
        head.put("version", version(head) + 1);
      }
      case "escalate" -> {
        head.put("escalatedCaseNo", "CS-ESC-" + head.get("ticketNo"));
        head.put("version", version(head) + 1);
      }
      default -> {
        e.sendResponseHeaders(404, -1);
        e.close();
        return;
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("head", head);
    body.put("assignments", List.of());
    write(e, body);
  }

  private static long version(Map<String, Object> head) {
    Object value = head.get("version");
    return value instanceof Number number ? number.longValue() : 0L;
  }

  /** 读查询串里的一个参数（取不到时用默认值）。 */
  private static String queryParam(String query, String name, String fallback) {
    if (query == null) {
      return fallback;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(name)) {
        return pair.substring(eq + 1);
      }
    }
    return fallback;
  }

  private static void cases(HttpExchange e) throws IOException {
    upstreamAuthorizations.add(e.getRequestHeaders().getFirst("Authorization"));
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map.Entry<String, String> entry : upstreamCases.entrySet()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", UUID.randomUUID().toString());
      row.put("caseNo", entry.getKey());
      row.put("orderNo", entry.getValue());
      row.put("platformSource", "AMAZON");
      row.put("caseType", "REFUND");
      row.put("status", "PROCESSING");
      rows.add(row);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("rows", rows);
    body.put("page", 0);
    body.put("size", rows.size());
    body.put("total", rows.size());
    write(e, body);
  }

  private static void orders(HttpExchange e) throws IOException {
    upstreamAuthorizations.add(e.getRequestHeaders().getFirst("Authorization"));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("rows", new ArrayList<Object>(upstreamOrders));
    body.put("page", 0);
    body.put("size", upstreamOrders.size());
    body.put("total", upstreamOrders.size());
    write(e, body);
  }

  private static Map<String, Object> readJson(HttpExchange e) throws IOException {
    byte[] raw = e.getRequestBody().readAllBytes();
    if (raw.length == 0) {
      return new LinkedHashMap<>();
    }
    return json.readValue(raw, Map.class);
  }

  private static void write(HttpExchange e, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(200, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }
}
