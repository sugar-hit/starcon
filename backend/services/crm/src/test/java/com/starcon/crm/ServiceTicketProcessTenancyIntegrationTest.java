package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
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
 * 15.4.1.2 工单处理的**租户隔离**证明，跑在 AGENTS.md 明文要求的三条断言上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <ol>
 *   <li>两租户互不可见；</li>
 *   <li>伪造租户上下文无效 —— 并且<b>要断言行确实落在自称的租户名下</b>，
 *       不是只断言 A 看不见（只断言「看不见」的话，一句「写入被全表拒掉」也能骗过它）；</li>
 *   <li>连接池归还后不串租户。</li>
 * </ol>
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>刻意跑在真实业务表上</b>（{@code crm_service_ticket_note} /
 * {@code crm_service_ticket_action} / {@code crm_service_ticket_link} /
 * {@code crm_service_ticket_resolution} / {@code crm_service_ticket_sla_pause}）：
 * 证明的是**真的要用的那几张表**被隔离了，而不是一张之后会被删掉的探针表。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceTicketProcessTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID();
  static final UUID ticketId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer trade;

  static AccessService.Access actor(UUID uid, UUID tenant, List<String> permissions) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
        permissions,
        List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions)));
  }

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
    actors.put("session-a", actor(userA, a, permissions));
    actors.put("session-b", actor(UUID.randomUUID(), b, permissions));
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
      // 上游（trade）只回一张固定工单 —— 本类专攻 **crm 侧** 的租户隔离。
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/service-tickets",
          e -> {
            String path = e.getRequestURI().getPath();
            Map<String, Object> head = new LinkedHashMap<>();
            head.put("id", ticketId.toString());
            head.put("ticketNo", "TK-TENANCY");
            head.put("ticketType", "INQUIRY");
            head.put("ticketStatus", "OPEN");
            head.put("priority", "NORMAL");
            head.put("sourceChannel", "MANUAL");
            head.put("slaReplyHours", 4);
            head.put("slaResolveHours", 48);
            head.put("slaBasis", "POLICY_SLA");
            head.put("createdAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
            head.put("version", 1L);
            Map<String, Object> body = new LinkedHashMap<>();
            if ("/trade/service-tickets".equals(path)) {
              body.put("rows", List.of(head));
              body.put("page", 0);
              body.put("size", 1);
              body.put("total", 1);
              body.put("summary", null);
            } else {
              body.put("head", head);
              body.put("assignments", List.of());
            }
            byte[] bytes = json.writeValueAsBytes(body);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);
            e.getResponseBody().write(bytes);
            e.close();
          });
      trade.setExecutor(Executors.newFixedThreadPool(2));
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  /** 在可信租户事务里插入一条备注（timestamptz 一律绑 OffsetDateTime + {@code ?::timestamptz}）。 */
  void insertNote(UUID tenant, String content) {
    tenants.run(
        tenant,
        () -> {
          sql.execute(
              "insert into crm_service_ticket_note"
                  + "(id,tenant_id,ticket_id,note_type,content,reply_channel,created_by,created_at)"
                  + " values (?,?::uuid,?::uuid,'NOTE',?,null,?::uuid,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              ticketId,
              content,
              userA,
              OffsetDateTime.now(ZoneOffset.UTC));
          return null;
        });
  }

  /** 以指定租户身份读备注（只读本服务自己的表）。 */
  List<String> notesOf(UUID tenant) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch("select content from crm_service_ticket_note order by content")
                .map(r -> r.get(0, String.class)));
  }

  /** 按主键直查（证明连「按 id 直查」也越不过去）。 */
  int countById(UUID tenant, UUID id) {
    return tenants.run(
        tenant,
        () ->
            sql.fetch(
                    "select id from crm_service_ticket_note where id = ?::uuid", id)
                .size());
  }

  /** 事务外读：没有任何租户上下文时能看见几行。 */
  int visibleWithoutTenant() {
    return sql.fetch("select id from crm_service_ticket_note").size();
  }

  String rawTenantSetting() {
    return sql.fetch("select current_setting('app.current_tenant', true)").stream()
        .findFirst()
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  /** RLS 策略**实际**使用的值：空串与 NULL 都归一成 null，也就是「没有租户」。 */
  UUID effectiveTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  /** 每个用例前清空五张业务表，再种入两租户各一行。 */
  @BeforeEach
  void resetThenSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_service_ticket_note, crm_service_ticket_action,"
              + " crm_service_ticket_link, crm_service_ticket_resolution,"
              + " crm_service_ticket_sla_pause");
    }
    insertNote(a, "租户 A 的备注");
    insertNote(b, "租户 B 的备注");
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 业务表强制rls而不是只启用() {
    for (String table :
        List.of(
            "crm_service_ticket_note",
            "crm_service_ticket_action",
            "crm_service_ticket_link",
            "crm_service_ticket_resolution",
            "crm_service_ticket_sla_pause")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V309 必须真的建出 " + table).isNotNull();
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

  // ==========================================================================
  // 断言 1
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    assertThat(notesOf(a)).containsExactly("租户 A 的备注");
    assertThat(notesOf(b)).containsExactly("租户 B 的备注");
  }

  // ==========================================================================
  // 断言 2 —— 伪造租户头无效，且**行落在会话租户名下**
  // ==========================================================================
  @Test
  void 断言2_伪造租户头无效且行落在会话租户名下() throws Exception {
    // (1) 服务在事务外没有「环境租户」：不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(effectiveTenant()).isNull();
    assertThat(visibleWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    // (2) 事务内绑定的值精确等于可信参数，提交后立刻失效。
    var bound =
        tenants.run(
            b,
            () -> {
              assertThat(effectiveTenant()).as("事务内只看得到本租户").isEqualTo(b);
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(b.toString());
    assertThat(effectiveTenant()).as("提交后租户上下文必须已清空").isNull();
    assertThat(visibleWithoutTenant()).isZero();

    // (3) 真正的入口断言：带**合法会话**（会话租户是 a）但**伪造 X-Tenant-Id: b**
    //     写一条备注。只断言「A 看不见 B」是不够的 —— 那一句「写入被全表拒掉」
    //     也能让它通过。这里要证明的是：这一行**确实写进去了**，
    //     而且**落在会话租户 a 名下**，完全没有落到调用方自称的 b 名下。
    http.perform(
            post("/crm/service-ticket-process/tickets/{id}/notes", ticketId)
                .header("Authorization", "session-a")
                .header("X-Tenant-Id", b.toString())
                .header("X-Internal-Key", "forged")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"伪造头写下的备注\"}"))
        .andExpect(status().isOk());

    // 行确实存在（不是被全表拒掉）。
    var rows =
        tenants.run(
            a,
            () ->
                sql.fetch(
                    "select content, tenant_id from crm_service_ticket_note"
                        + " where content = '伪造头写下的备注'"));
    assertThat(rows).as("伪造头的请求必须仍然写入成功 —— 头不是鉴权依据").hasSize(1);
    // ...且它落在**会话租户** a 名下，**不是**调用方自称的 b。
    assertThat(rows.get(0).get("tenant_id", UUID.class))
        .as("伪造 X-Tenant-Id 头不得改变写入归属")
        .isEqualTo(a)
        .isNotEqualTo(b);

    // 租户 b 看不到它（它不属于 b）。
    var visibleToB =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select id from crm_service_ticket_note"
                            + " where content = '伪造头写下的备注'")
                    .size());
    assertThat(visibleToB).as("这一行不属于 b，b 必须看不到").isZero();

    // 租户 a 看得到它（它确实属于 a）。
    assertThat(notesOf(a)).contains("伪造头写下的备注");
    assertThat(notesOf(b)).as("b 仍然只看得见自己那一行").containsExactly("租户 B 的备注");
  }

  // ==========================================================================
  // 断言 3
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证后一次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      assertThat(notesOf(a)).as("第 %d 轮租户 A", i).containsExactly("租户 A 的备注");
      assertThat(notesOf(b)).as("第 %d 轮租户 B", i).containsExactly("租户 B 的备注");
      assertThat(visibleWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(notesOf(a)).containsExactly("租户 A 的备注");
    assertThat(notesOf(b)).containsExactly("租户 B 的备注");
  }
}
