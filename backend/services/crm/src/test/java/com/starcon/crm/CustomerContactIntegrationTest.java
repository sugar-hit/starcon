package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
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
 * 15.3.2.2 接触记录的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。租户隔离三断言在
 * {@link CustomerContactTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>🔴 加粗句「接触记录是自动化的豁免依据」</b>：
 *       <ul>
 *         <li>退订登记<b>必须带依据</b>，否则拒绝；</li>
 *         <li>退订之后发生的<b>营销触达被拦</b>（要留痕必须显式承认违规）；</li>
 *         <li>退订<b>不可被普通接触记录悄悄覆盖或撤销</b>；</li>
 *         <li>撤销退订是<b>显式动作</b>，必须带原因，且<b>留痕</b>（审计事件 + 时间线记录）；</li>
 *         <li>有一个<b>可查询的判定</b>：这个客户现在还能不能被营销。</li>
 *       </ul>
 *   </li>
 *   <li><b>各模块自动写入是真的</b>：15.1 消息与 15.1.2.1 邮件（同库同服务）
 *       的记录<b>真的</b>落成接触记录（库内物化）；ingest 入口<b>幂等</b>
 *       （同一条上游记录重复写入不重复记账）。</li>
 *   <li><b>真分页真筛选</b>：时间线与列表的总数、页数、筛选都来自真实查询。</li>
 *   <li><b>禁浮点 / 时区不静默偏移</b>：时长写回逐位相同；带偏移量的时刻写回等于同一绝对时刻。</li>
 * </ul>
 *
 * <p>⚠️ 本类所有**直连数据库**的读写都在 {@code tenants.run(tenant, …)} 里 ——
 * RLS 是<b>强制</b>的，事务外没有租户上下文时一行都看不到。
 * 断言「库里真的有这一行」若在事务外查，会永远得到 0（那不是数据没落库，是被 RLS 挡住了）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerContactIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          // ⚠️ 默认会把 JSON 数字读成 double —— 那样断言本身就先把值舍入了，
          // 「没掉精度」这件事就再也证不出来。这里强制读成 BigDecimal。
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static HttpServer identity;

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role crm_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    List<String> permissions = List.of("crm.contact.read", "crm.contact.update");
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
  }

  @Autowired DSLContext sql;
  @Autowired TenantTransactions tenants;
  @Autowired MockMvc http;

  // ------------------------------------------------------------ 测试脚手架

  /** 在**可信租户事务**里执行一段工作（RLS 强制：事务外一行都看不到）。 */
  private <T> T inTenant(Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_customer_opt_out_event, crm_customer_opt_out, crm_customer_contact"
              + " cascade");
      s.execute("truncate table crm_buyer_email_attachment, crm_buyer_email_send_record,"
          + " crm_buyer_email cascade");
      s.execute("truncate table crm_message_intent, crm_message_attachment, crm_message cascade");
    }
  }

  private String body(String path) throws Exception {
    return http.perform(
            get(path)
                .header("Authorization", "session")
                .header("X-Tenant-Id", tenant.toString())
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String postJson(String path, Object payload) throws Exception {
    return http.perform(
            post(path)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload == null ? "{}" : json.writeValueAsString(payload)))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** 在租户事务里插一条接触记录（绕过服务层，用于构造前置状态）。 */
  private void insertContact(
      String customerRef, String contactType, String direction, String occurredAt, String origin) {
    inTenant(
        () -> {
          sql.execute(
              "insert into crm_customer_contact"
                  + "(id,tenant_id,contact_no,customer_ref,customer_name,contact_type,direction,origin,"
                  + " source_module,source_ref,source_no,source_label,shop_id,shop_name,platform_code,"
                  + " order_reference,subject,summary,duration_seconds,occurred_at,operator_id,created_by,"
                  + " detail,compliance_state,created_at,updated_at)"
                  + " values (?,?,?,?,'',?,?,?,'','','','测试',null,'','','','','',null,"
                  + " ?::timestamptz,null,null,'{}'::jsonb,'COMPLIANT',now(),now())",
              UUID.randomUUID(),
              tenant,
              "CT-TEST-" + UUID.randomUUID().toString().substring(0, 8),
              customerRef,
              contactType,
              direction,
              origin,
              occurredAt);
          return null;
        });
  }

  /** 接触记录条数（真实计数，租户事务内）。 */
  private long countContacts(String where, Object... binds) {
    return inTenant(
        () -> {
          List<Object> all = new java.util.ArrayList<>();
          all.add(tenant);
          all.addAll(List.of(binds));
          return sql.fetchOne(
                  "select count(*) from crm_customer_contact where tenant_id = ?" + where,
                  all.toArray())
              .get(0, Long.class);
        });
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明把未交付的三块标成不可用() throws Exception {
    var node = json.readTree(body("/crm/customer-contacts/capabilities"));
    var dims = node.get("dimensions");
    Map<String, String> unavailable = new java.util.HashMap<>();
    for (var d : dims) {
      if (!d.get("available").asBoolean()) {
        unavailable.put(d.get("dimension").asText(), d.get("ownerModule").asText());
      }
    }
    // 🔴 三块未交付必须如实标 available=false + ownerModule。
    assertThat(unavailable).containsEntry("marketing_outreach", "15.5.2.1");
    assertThat(unavailable).containsEntry("customer_profile", "15.3.1.2");
    assertThat(unavailable).containsEntry("auto_write_aftersales", "11.1.1.1");
    // 豁免依据那一维必须**可用**（这是本模块的核心落点）。
    var available = new java.util.ArrayList<String>();
    for (var d : dims) {
      if (d.get("available").asBoolean()) available.add(d.get("dimension").asText());
    }
    assertThat(available).contains("opt_out_exemption_check", "opt_out_audit", "opt_out_revocation");
    assertThat(node.get("actionPolicy").asText()).contains("不发送");
  }

  // ================================================================ 手工补录

  @Test
  void 手工补录真的落库且时区不静默偏移() throws Exception {
    String created =
        postJson(
            "/crm/customer-contacts/contacts",
            Map.of(
                "customerRef", "BUYER-1",
                "customerName", "买家一",
                "contactType", "CALL",
                "direction", "INBOUND",
                "occurredAt", "2026-10-10T08:00:00+08:00",
                "subject", "关于发货的来电",
                "summary", "客户问什么时候发货",
                "durationSeconds", new BigDecimal("183.500000")));
    var node = json.readTree(created);
    assertThat(node.get("origin").asText()).isEqualTo("MANUAL");
    assertThat(node.get("contactNo").asText()).startsWith("CT-");
    // 🔴 写回的时刻必须是**同一绝对时刻**（东八区 08:00 == UTC 00:00），
    //   偏 8 小时的话页面看上去一切正常。
    assertThat(OffsetDateTime.parse(node.get("occurredAt").asText()))
        .isEqualTo(OffsetDateTime.parse("2026-10-10T08:00:00+08:00"));
    // 🔴 禁浮点：时长写回逐位相同。
    assertThat(node.get("durationSeconds").decimalValue())
        .isEqualByComparingTo(new BigDecimal("183.500000"));

    // 真落库：库里确实有这一行。
    assertThat(countContacts(" and customer_ref = ?", "BUYER-1")).isEqualTo(1);
  }

  @Test
  void 补录不能把退订当成接触类型写() throws Exception {
    // 🔴 退订是合规状态，只能走显式的退订端点 ——
    //   给它开一个「接触类型」的口子，退订就能被普通写入悄悄改掉。
    http.perform(
            post("/crm/customer-contacts/contacts")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "customerRef", "BUYER-1",
                            "contactType", "OPT_OUT",
                            "direction", "INBOUND",
                            "occurredAt", "2026-10-10T08:00:00+08:00"))))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 时间线检索

  @Test
  void 时间线真分页真筛选() throws Exception {
    for (int i = 1; i <= 5; i++) {
      insertContact(
          "BUYER-1", "CALL", "INBOUND", "2026-10-0" + i + "T08:00:00+08:00", "MANUAL");
    }
    insertContact("BUYER-2", "EMAIL", "INBOUND", "2026-10-08T08:00:00+08:00", "MANUAL");

    var all = json.readTree(body("/crm/customer-contacts/contacts"));
    assertThat(all.get("totalElements").asLong()).isEqualTo(6);
    assertThat(all.get("items").size()).isEqualTo(6);

    // 按客户筛。
    var one = json.readTree(body("/crm/customer-contacts/contacts?customerRef=BUYER-1"));
    assertThat(one.get("totalElements").asLong()).isEqualTo(5);

    // 按类型筛。
    var calls = json.readTree(body("/crm/customer-contacts/contacts?contactType=CALL"));
    assertThat(calls.get("totalElements").asLong()).isEqualTo(5);

    // 关键字筛。
    var keyword = json.readTree(body("/crm/customer-contacts/contacts?keyword=BUYER-2"));
    assertThat(keyword.get("totalElements").asLong()).isEqualTo(1);

    // 真分页：第 2 页每页 2 条 ⇒ 共 3 页，第 2 页有 2 条。
    var page2 = json.readTree(body("/crm/customer-contacts/contacts?page=1&size=2"));
    assertThat(page2.get("page").asInt()).isEqualTo(1);
    assertThat(page2.get("size").asInt()).isEqualTo(2);
    assertThat(page2.get("totalElements").asLong()).isEqualTo(6);
    assertThat(page2.get("totalPages").asInt()).isEqualTo(3);
    assertThat(page2.get("items").size()).isEqualTo(2);

    // 时间线（按客户）带**退订判定**。
    var timeline = json.readTree(body("/crm/customer-contacts/timeline?customerRef=BUYER-1"));
    assertThat(timeline.get("customerRef").asText()).isEqualTo("BUYER-1");
    assertThat(timeline.get("totalElements").asLong()).isEqualTo(5);
    assertThat(timeline.get("optOut").get("suppressed").asBoolean()).isFalse();
  }

  // ================================================================ 自动写入

  @Test
  void 自动写入幂等_同一条上游记录不重复记账() throws Exception {
    Map<String, Object> payload =
        Map.of(
            "customerRef", "BUYER-3",
            "contactType", "AFTERSALES",
            "direction", "INBOUND",
            "occurredAt", "2026-10-10T08:00:00+08:00",
            "summary", "售后沟通",
            "sourceModule", "11.1.1.1",
            "sourceRef", "CASE-1");
    var first = json.readTree(postJson("/crm/customer-contacts/ingest", payload));
    assertThat(first.get("created").asBoolean()).isTrue();

    var second = json.readTree(postJson("/crm/customer-contacts/ingest", payload));
    assertThat(second.get("created").asBoolean()).as("同一条上游记录重复写入必须幂等").isFalse();
    assertThat(second.get("note").asText()).contains("幂等");

    assertThat(countContacts(" and source_module = ? and source_ref = ?", "11.1.1.1", "CASE-1"))
        .as("重复写入不得重复记账")
        .isEqualTo(1);
  }

  @Test
  void 自动写入不能登记退订() throws Exception {
    http.perform(
            post("/crm/customer-contacts/ingest")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "customerRef", "BUYER-3",
                            "contactType", "OPT_OUT",
                            "direction", "INBOUND",
                            "occurredAt", "2026-10-10T08:00:00+08:00",
                            "sourceModule", "15.1.1.1",
                            "sourceRef", "MSG-1"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 消息收件箱的记录真的自动落成接触记录() throws Exception {
    // 15.1 消息收件箱与本模块**同库同服务** ⇒ 库内物化触发器真的把它写成接触记录。
    inTenant(
        () -> {
          sql.execute(
              "insert into crm_message"
                  + "(id,tenant_id,message_no,shop_id,shop_name,platform_code,buyer_reference,"
                  + " order_reference,subject,body,direction,message_type,platform_time,source,"
                  + " created_at,updated_at)"
                  + " values (?,?,?,?,'美国店','AMAZON','BUYER-9','SO-9','包裹没到','我的包裹还没到',"
                  + " 'INBOUND','BUYER_MESSAGE',?::timestamptz,'MANUAL_ENTRY',now(),now())",
              UUID.randomUUID(),
              tenant,
              "MSG-TEST-1",
              UUID.randomUUID(),
              OffsetDateTime.now(ZoneOffset.UTC).minusHours(3));
          return null;
        });

    var rows =
        inTenant(
            () ->
                sql.fetch(
                    "select source_module, source_ref, contact_type, direction, customer_ref"
                        + " from crm_customer_contact where tenant_id = ? and contact_type = 'MESSAGE'",
                    tenant));
    assertThat(rows).as("15.1 的消息必须真的自动落成接触记录").hasSize(1);
    assertThat(rows.get(0).get("source_module", String.class)).isEqualTo("15.1.1.1");
    assertThat(rows.get(0).get("direction", String.class)).isEqualTo("INBOUND");
    assertThat(rows.get(0).get("customer_ref", String.class)).isEqualTo("BUYER-9");
  }

  @Test
  void 平台通知不算与客户的接触() throws Exception {
    inTenant(
        () -> {
          sql.execute(
              "insert into crm_message"
                  + "(id,tenant_id,message_no,shop_id,shop_name,platform_code,buyer_reference,"
                  + " order_reference,subject,body,direction,message_type,platform_time,source,"
                  + " created_at,updated_at)"
                  + " values (?,?,?,?,'美国店','AMAZON','BUYER-9','SO-9','平台通知','请更新税务信息',"
                  + " 'INBOUND','SYSTEM_NOTICE',?::timestamptz,'MANUAL_ENTRY',now(),now())",
              UUID.randomUUID(),
              tenant,
              "MSG-TEST-2",
              UUID.randomUUID(),
              OffsetDateTime.now(ZoneOffset.UTC).minusHours(3));
          return null;
        });
    assertThat(countContacts(" and contact_type = 'MESSAGE'"))
        .as("平台通知不是与客户的接触")
        .isZero();
  }

  @Test
  void 买家邮件只有发出的才算一次触达() throws Exception {
    UUID shop = UUID.randomUUID();
    // 收到的邮件 ⇒ 一次触达。
    inTenant(
        () -> {
          sql.execute(
              "insert into crm_buyer_email"
                  + "(id,tenant_id,email_no,shop_id,shop_name,platform_code,direction,source,buyer_ref,"
                  + " buyer_name,buyer_email,subject,body,conversation_key,sales_order_no,"
                  + " external_message_id,received_at,status,created_at,updated_at)"
                  + " values (?,?,?,?,'美国店','AMAZON','INBOUND','MANUAL_ENTRY','BUYER-8','买家八',"
                  + " 'b@example.com','退款','我要退款','AMAZON|BUYER-8|SO-8','SO-8','',"
                  + " ?::timestamptz,'RECEIVED',now(),now())",
              UUID.randomUUID(),
              tenant,
              "BE-TEST-1",
              shop,
              OffsetDateTime.now(ZoneOffset.UTC).minusHours(2));
          // 待发（QUEUED）的外发邮件 ⇒ **不算**触达。
          sql.execute(
              "insert into crm_buyer_email"
                  + "(id,tenant_id,email_no,shop_id,shop_name,platform_code,direction,source,buyer_ref,"
                  + " buyer_name,buyer_email,subject,body,conversation_key,sales_order_no,"
                  + " external_message_id,received_at,status,created_at,updated_at)"
                  + " values (?,?,?,?,'美国店','AMAZON','OUTBOUND','MANUAL_ENTRY','BUYER-8','买家八',"
                  + " 'b@example.com','回复','稍后发','AMAZON|BUYER-8|SO-8','SO-8','',"
                  + " ?::timestamptz,'QUEUED',now(),now())",
              UUID.randomUUID(),
              tenant,
              "BE-TEST-2",
              shop,
              OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
          // 已发出的外发邮件 ⇒ 一次触达。
          sql.execute(
              "insert into crm_buyer_email"
                  + "(id,tenant_id,email_no,shop_id,shop_name,platform_code,direction,source,buyer_ref,"
                  + " buyer_name,buyer_email,subject,body,conversation_key,sales_order_no,"
                  + " external_message_id,received_at,status,created_at,updated_at)"
                  + " values (?,?,?,?,'美国店','AMAZON','OUTBOUND','MANUAL_ENTRY','BUYER-8','买家八',"
                  + " 'b@example.com','回复已发','已处理','AMAZON|BUYER-8|SO-8','SO-8','',"
                  + " ?::timestamptz,'SENT',now(),now())",
              UUID.randomUUID(),
              tenant,
              "BE-TEST-3",
              shop,
              OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(30));
          return null;
        });

    var rows =
        inTenant(
            () ->
                sql.fetch(
                    "select direction, source_module, source_no from crm_customer_contact"
                        + " where tenant_id = ? and contact_type = 'EMAIL' order by direction",
                    tenant));
    assertThat(rows).as("收到的 + 已发出的，各算一次触达；待发的不算").hasSize(2);
    assertThat(rows.get(0).get("direction", String.class)).isEqualTo("INBOUND");
    assertThat(rows.get(1).get("direction", String.class)).isEqualTo("OUTBOUND");
    assertThat(rows.get(0).get("source_module", String.class)).isEqualTo("15.1.2.1");
  }

  // ================================================================ 退订 / 豁免

  @Test
  void 退订必须带依据否则拒绝() throws Exception {
    http.perform(
            post("/crm/customer-contacts/opt-outs")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("customerRef", "BUYER-5", "scope", "ALL", "reason", "   "))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 退订之后发生的营销触达被拦_要留痕必须显式承认违规() throws Exception {
    postJson(
        "/crm/customer-contacts/opt-outs",
        Map.of(
            "customerRef", "BUYER-5",
            "customerName", "买家五",
            "scope", "ALL",
            "reason", "客户明确说不要再联系我"));
    String optOutAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1).toString();

    // 🔴 退订生效之后的营销触达落不了库。
    http.perform(
            post("/crm/customer-contacts/contacts")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "customerRef", "BUYER-5",
                            "contactType", "MARKETING",
                            "direction", "OUTBOUND",
                            "occurredAt", optOutAt,
                            "subject", "双十一大促"))))
        .andExpect(status().isConflict());

    // 显式承认违规 ⇒ 落库并留痕。
    var violation =
        json.readTree(
            postJson(
                "/crm/customer-contacts/contacts",
                Map.of(
                    "customerRef", "BUYER-5",
                    "contactType", "MARKETING",
                    "direction", "OUTBOUND",
                    "occurredAt", optOutAt,
                    "subject", "双十一大促",
                    "complianceState", "VIOLATION")));
    assertThat(violation.get("complianceState").asText()).isEqualTo("VIOLATION");
  }

  @Test
  void 退订之前的历史触达照常补录() throws Exception {
    postJson(
        "/crm/customer-contacts/opt-outs",
        Map.of("customerRef", "BUYER-5", "scope", "ALL", "reason", "客户明确说不要再联系我"));
    // 退订之前发生的一次营销触达 —— 补录历史不是补造违规。
    var created =
        json.readTree(
            postJson(
                "/crm/customer-contacts/contacts",
                Map.of(
                    "customerRef", "BUYER-5",
                    "contactType", "MARKETING",
                    "direction", "OUTBOUND",
                    "occurredAt", "2020-01-01T08:00:00+08:00")));
    assertThat(created.get("id").asText()).isNotEmpty();
  }

  @Test
  void 普通接触记录改不动退订状态() throws Exception {
    postJson(
        "/crm/customer-contacts/opt-outs",
        Map.of("customerRef", "BUYER-6", "scope", "ALL", "reason", "客户明确说不要再联系我"));
    // 写一堆普通接触记录（各种类型、各种方向）。
    insertContact("BUYER-6", "CALL", "INBOUND", "2026-10-10T08:00:00+08:00", "MANUAL");
    insertContact("BUYER-6", "EMAIL", "OUTBOUND", "2026-10-11T08:00:00+08:00", "MANUAL");
    insertContact("BUYER-6", "MESSAGE", "INBOUND", "2026-10-12T08:00:00+08:00", "MANUAL");

    // 🔴 退订**没有**被普通接触记录悄悄覆盖或撤销。
    var check = json.readTree(body("/crm/customer-contacts/opt-out-check?customerRef=BUYER-6"));
    assertThat(check.get("suppressedCount").asInt()).isEqualTo(1);
    assertThat(check.get("items").get(0).get("suppressed").asBoolean()).isTrue();
    assertThat(check.get("items").get(0).get("marketingSuppressed").asBoolean()).isTrue();

    long active =
        inTenant(
            () ->
                sql.fetchOne(
                        "select count(*) from crm_customer_opt_out where tenant_id = ?"
                            + " and customer_ref = ? and state = 'ACTIVE'",
                        tenant,
                        "BUYER-6")
                    .get(0, Long.class));
    assertThat(active).isEqualTo(1);
  }

  @Test
  void 撤销退订是显式动作且必须留痕() throws Exception {
    String granted =
        postJson(
            "/crm/customer-contacts/opt-outs",
            Map.of(
                "customerRef", "BUYER-7",
                "customerName", "买家七",
                "scope", "ALL",
                "reason", "客户在消息里说不要再联系我"));
    String optOutId = json.readTree(granted).get("id").asText();

    // 不写原因 ⇒ 拒绝。
    http.perform(
            post("/crm/customer-contacts/opt-outs/" + optOutId + "/revoke")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("reason", "  "))))
        .andExpect(status().isBadRequest());

    // 显式撤销（带原因）⇒ 状态变更 + 留痕。
    var revoked =
        json.readTree(
            postJson(
                "/crm/customer-contacts/opt-outs/" + optOutId + "/revoke",
                Map.of("reason", "客户主动回来说可以继续联系")));
    assertThat(revoked.get("state").asText()).isEqualTo("REVOKED");
    assertThat(revoked.get("revokeReason").asText()).isEqualTo("客户主动回来说可以继续联系");
    assertThat(revoked.get("revokedAt").asText()).isNotEmpty();

    // 审计流水里必须有 GRANTED 与 REVOKED 两条。
    var events = json.readTree(body("/crm/customer-contacts/opt-outs/" + optOutId + "/events"));
    assertThat(events.get("totalElements").asLong()).isEqualTo(2);
    boolean grantedSeen = false;
    boolean revokedSeen = false;
    for (var e : events.get("items")) {
      if ("GRANTED".equals(e.get("eventType").asText())) grantedSeen = true;
      if ("REVOKED".equals(e.get("eventType").asText())) revokedSeen = true;
    }
    assertThat(grantedSeen).isTrue();
    assertThat(revokedSeen).isTrue();

    // 时间线上也留了痕（登记 + 撤销各一条）。
    assertThat(countContacts(" and customer_ref = ? and contact_type = 'OPT_OUT'", "BUYER-7"))
        .as("登记与撤销都要留在时间线上")
        .isEqualTo(2);

    // 判定随之改变：撤销后不再豁免。
    var check = json.readTree(body("/crm/customer-contacts/opt-out-check?customerRef=BUYER-7"));
    assertThat(check.get("suppressedCount").asInt()).isZero();
    assertThat(check.get("items").get(0).get("suppressed").asBoolean()).isFalse();
    assertThat(check.get("items").get(0).get("marketingSuppressed").asBoolean()).isFalse();
  }

  @Test
  void 已撤销的退订不能被改回生效且退订记录删不掉() throws Exception {
    String granted =
        postJson(
            "/crm/customer-contacts/opt-outs",
            Map.of("customerRef", "BUYER-8", "scope", "ALL", "reason", "客户明确要求"));
    String optOutId = json.readTree(granted).get("id").asText();
    postJson(
        "/crm/customer-contacts/opt-outs/" + optOutId + "/revoke",
        Map.of("reason", "客户改主意了"));

    // 改回生效 ⇒ DB 触发器拒绝。
    assertThatThrownBy(
            () ->
                inTenant(
                    () ->
                        sql.execute(
                            "update crm_customer_opt_out set state = 'ACTIVE'"
                                + " where tenant_id = ? and id = ?",
                            tenant,
                            UUID.fromString(optOutId))))
        .hasMessageContaining("不能改回生效");

    // 删除退订记录 ⇒ DB 触发器拒绝（退订是凭据，只能撤销不能删）。
    assertThatThrownBy(
            () ->
                inTenant(
                    () ->
                        sql.execute(
                            "delete from crm_customer_opt_out where tenant_id = ? and id = ?",
                            tenant,
                            UUID.fromString(optOutId))))
        .hasMessageContaining("删不掉");
  }

  @Test
  void 退订审计流水只追加() throws Exception {
    postJson(
        "/crm/customer-contacts/opt-outs",
        Map.of("customerRef", "BUYER-9", "scope", "ALL", "reason", "客户明确要求"));
    assertThatThrownBy(() -> inTenant(() -> sql.execute("update crm_customer_opt_out_event set reason = 'x'")))
        .hasMessageContaining("只追加");
    assertThatThrownBy(() -> inTenant(() -> sql.execute("delete from crm_customer_opt_out_event")))
        .hasMessageContaining("只追加");
  }

  @Test
  void 豁免判定可以批量查() throws Exception {
    postJson(
        "/crm/customer-contacts/opt-outs",
        Map.of("customerRef", "BUYER-A", "scope", "MARKETING", "reason", "只退订营销"));
    var result =
        json.readTree(
            body("/crm/customer-contacts/opt-out-check?customerRefs=BUYER-A,BUYER-B,BUYER-C"));
    assertThat(result.get("checkedCount").asInt()).isEqualTo(3);
    assertThat(result.get("suppressedCount").asInt()).isEqualTo(1);
    assertThat(result.get("items").size()).isEqualTo(3);
  }

  @Test
  void 退订后不能重复登记一条生效中的退订() throws Exception {
    postJson(
        "/crm/customer-contacts/opt-outs",
        Map.of("customerRef", "BUYER-D", "scope", "ALL", "reason", "客户明确要求"));
    http.perform(
            post("/crm/customer-contacts/opt-outs")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("customerRef", "BUYER-D", "scope", "ALL", "reason", "又登记一次"))))
        .andExpect(status().isConflict());
  }
}
