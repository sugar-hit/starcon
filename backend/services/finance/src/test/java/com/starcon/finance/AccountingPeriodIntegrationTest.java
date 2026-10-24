package com.starcon.finance;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jooq.Record;
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
 * 12.5.1.2 会计期间的业务断言，跑在**真实 PostgreSQL 16** 上。
 *
 * <p>覆盖蓝图 12-finance.md 里 12.5.1.2 的那几条：
 *
 * <ul>
 *   <li>「管理会计期间（**自然月或 4-4-5 fiscal 周期**）」⇒ 两种日历都能生成期间，周期序号与周期长度落库；
 *   <li>「期间状态（开放/关闭）」⇒ 关账真的落库、真的留下关账记录；
 *   <li>「**关账与重开**」⇒ 重开**必须审批留痕**：没有审批留痕时，**直连 SQL 也重开不了**；
 *   <li>「输入：会计日历配置；关账/重开操作；**审批**」⇒ 审批真的走 17 审批（identity），
 *       审批不可达时**如实报不可达**而不是假装批过了；
 *   <li>「输出：期间状态；**关账记录**」⇒ 留痕是 append-only 的真实数据；
 *   <li>「下游 = 12.5.2.1 凭证（**已关闭期间禁止过账**）」⇒ 接管本表之后那道闸**依然生效**。
 * </ul>
 *
 * <p>⚠️ 断言分两类：**服务层能拒绝的**（走 HTTP 端点）与**服务层绕不过的**（直连 SQL），
 * 后者才证明「绕过服务层直连数据库也造不出假数据」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountingPeriodIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();

  /** 审批流 id（identity 侧）。 */
  static final UUID FLOW_ID = UUID.randomUUID();

  static final List<String> PERMISSIONS =
      List.of(
          "finance.accounting_period.read",
          "finance.accounting_period.create",
          "finance.accounting_period.update",
          // 12.5.2.1 的权限：用来证明「已关闭期间禁止过账」在接管之后依然生效。
          "finance.voucher.read",
          "finance.voucher.create",
          "finance.voucher.update");

  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static HttpServer identity;

  /** 审批场景：NO_FLOW / PENDING / APPROVED / REJECTED / UNREACHABLE。 */
  static final AtomicReference<String> scenario = new AtomicReference<>("PENDING");

  /** identity 侧的审批实例（id → 状态）。 */
  static final Map<UUID, String> instances = new ConcurrentHashMap<>();

  /** 提交审批的次数。 */
  static final AtomicInteger submits = new AtomicInteger();

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role finance_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put(
        "session",
        new AccessService.Access(
            userId,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            PERMISSIONS,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", PERMISSIONS))));
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
      identity.createContext("/identity/approval-flows", AccountingPeriodIntegrationTest::flow);
      identity.createContext("/identity/approval-instances", AccountingPeriodIntegrationTest::submit);
      identity.createContext("/identity/approval-records", AccountingPeriodIntegrationTest::record);
      identity.setExecutor(Executors.newFixedThreadPool(4));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "finance_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 假 identity（审批）

  private static void flow(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
    var exchange0 = exchange;
    if ("NO_FLOW".equals(scenario.get())) {
      exchange0.sendResponseHeaders(404, -1);
      exchange0.close();
      return;
    }
    var body =
        """
        {"flow":{"id":"%s","flowName":"重开会计期间审批","status":"ACTIVE",
         "documentTypeCode":"ACCOUNTING_PERIOD_REOPEN"},
         "nodes":[{"nodeKey":"N1","nodeName":"财务负责人","nodeOrder":1,"stageIndex":1,
         "approvalMode":"SERIAL","approverType":"ROLE","approverLabel":"财务负责人"}]}
        """
            .formatted(FLOW_ID);
    write(exchange0, 200, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static void submit(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
    submits.incrementAndGet();
    var id = UUID.randomUUID();
    instances.put(id, "PENDING");
    var body =
        """
        {"id":"%s","instanceNo":"AP-2026-0001","flowId":"%s","currentNodeKey":"N1",
         "currentNodeName":"财务负责人","applicantId":"%s","routedBy":"FLOW_ORDER",
         "submittedAt":"2026-10-24T02:00:00Z"}
        """
            .formatted(id, FLOW_ID, userId);
    write(exchange, 200, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static void record(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
    var path = exchange.getRequestURI().getPath();
    var id = UUID.fromString(path.substring(path.lastIndexOf('/') + 1));
    var status = instances.getOrDefault(id, "PENDING");
    if ("UNREACHABLE".equals(scenario.get())) {
      // 不回响应直接断开 ⇒ 客户端拿到 IO 错误 ⇒ ApprovalPort 报「identity 服务不可达」。
      exchange.close();
      return;
    }
    var timeline = "[]";
    var finished = "";
    if ("APPROVED".equals(status)) {
      timeline =
          """
          [{"actionId":"%s","action":"APPROVE","nodeKey":"N1","nodeName":"财务负责人",
            "nodeOrder":1,"actorId":"%s","actorName":"财务负责人","comment":"同意重开",
            "decidedAt":"2026-10-24T03:00:00Z","dwellSeconds":3600}]
          """
              .formatted(UUID.randomUUID(), userId);
      finished = "\"finishedAt\":\"2026-10-24T03:00:00Z\"";
    } else if ("REJECTED".equals(status)) {
      timeline =
          """
          [{"actionId":"%s","action":"REJECT","nodeKey":"N1","nodeName":"财务负责人",
            "nodeOrder":1,"actorId":"%s","actorName":"财务负责人","comment":"本期已出报表，不许重开",
            "decidedAt":"2026-10-24T03:00:00Z","dwellSeconds":3600}]
          """
              .formatted(UUID.randomUUID(), userId);
      finished = "\"finishedAt\":\"2026-10-24T03:00:00Z\"";
    }
    var body =
        """
        {"instance":{"id":"%s","instanceNo":"AP-2026-0001","status":"%s",
         "subjectTitle":"重开会计期间","amount":0,"currencyCode":"USD",
         "currentNodeKey":"N1","currentNodeName":"财务负责人",
         "submittedAt":"2026-10-24T02:00:00Z",%s},
         "timeline":%s}
        """
            .formatted(id, status, finished, timeline);
    write(exchange, 200, body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static void write(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body) throws java.io.IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  // ================================================================ 夹具

  /** 清空本模块的表（含期间表 —— 期间不可删，只能 TRUNCATE）。 */
  @BeforeEach
  void reset() throws Exception {
    scenario.set("PENDING");
    instances.clear();
    submits.set(0);
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table finance_accounting_period_event, finance_accounting_period_approval,"
              + " finance_accounting_calendar, finance_voucher_event, finance_voucher_line,"
              + " finance_voucher, finance_voucher_mapping, finance_voucher_period cascade");
    }
  }

  /** 直连查（必须在租户事务里读）。 */
  Record fetchOneInTenant(String statement, Object... binds) {
    return tenants.run(tenant, () -> sql.fetchOne(statement, binds));
  }

  /** 建一个会计日历。 */
  UUID createCalendar(
      String code, String type, Integer startMonth, String anchorDate, String currency)
      throws Exception {
    var body =
        """
        {"calendarCode":"%s","calendarName":"测试日历","calendarType":"%s",
         "fiscalYearStartMonth":%s,"anchorDate":%s,"baseCurrency":"%s"}
        """
            .formatted(
                code,
                type,
                startMonth == null ? "null" : startMonth,
                anchorDate == null ? "null" : "\"" + anchorDate + "\"",
                currency);
    var response =
        http.perform(
                post("/finance/accounting-periods/calendars")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    assertThat(response.getResponse().getStatus())
        .as("建日历失败：%s", response.getResponse().getContentAsString())
        .isEqualTo(200);
    return UUID.fromString(
        json.readTree(response.getResponse().getContentAsString()).get("id").asText());
  }

  /** 从日历生成一个财年的期间。 */
  JsonNode generatePeriods(UUID calendarId, int fiscalYear) throws Exception {
    var response =
        http.perform(
                post("/finance/accounting-periods/calendars/" + calendarId + "/generate-periods")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"fiscalYear\":" + fiscalYear + "}"))
            .andReturn();
    assertThat(response.getResponse().getStatus())
        .as("生成期间失败：%s", response.getResponse().getContentAsString())
        .isEqualTo(200);
    return json.readTree(response.getResponse().getContentAsString());
  }

  /** 手工登记一个自定义期间。 */
  UUID createPeriod(String code, String start, String end) throws Exception {
    var response =
        http.perform(
                post("/finance/accounting-periods")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"periodCode":"%s","periodStart":"%s","periodEnd":"%s","baseCurrency":"USD"}
                        """
                            .formatted(code, start, end)))
            .andReturn();
    assertThat(response.getResponse().getStatus())
        .as("登记期间失败：%s", response.getResponse().getContentAsString())
        .isEqualTo(200);
    return UUID.fromString(
        json.readTree(response.getResponse().getContentAsString()).get("id").asText());
  }

  /** 读取期间当前版本号。 */
  int versionOf(UUID periodId) {
    return fetchOneInTenant("select version from finance_voucher_period where id = ?", periodId)
        .get("version", Integer.class);
  }

  /** 关闭一个期间。 */
  void closePeriod(UUID periodId, String reason) throws Exception {
    http.perform(
            post("/finance/accounting-periods/" + periodId + "/close")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"reason\":\"" + reason + "\",\"expectedVersion\":" + versionOf(periodId) + "}"))
        .andExpect(status().isOk());
  }

  /** 发起一次重开（返回响应体 JSON）。 */
  JsonNode reopen(UUID periodId, String reason) throws Exception {
    var result =
        http.perform(
                post("/finance/accounting-periods/" + periodId + "/reopen")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"reason\":\"" + reason + "\",\"expectedVersion\":" + versionOf(periodId)
                            + "}"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(result);
  }

  // ==========================================================================
  // 迁移确实落地了
  // ==========================================================================

  @Test
  void 迁移落地_表与列都在() throws Exception {
    for (String table :
        List.of(
            "finance_accounting_calendar",
            "finance_accounting_period_event",
            "finance_accounting_period_approval")) {
      var row = sql.fetchOne("select count(*) from pg_class where relname = ?", table);
      assertThat(row.get(0, Long.class)).as("V359 必须真的建出 " + table).isPositive();
    }
    // 期间表被**接管**：fiscal 周期与重开留痕的列必须都在同一张表上。
    for (String column :
        List.of(
            "calendar_id",
            "fiscal_year",
            "fiscal_period_no",
            "period_type",
            "period_length_days",
            "reopened_at",
            "reopened_by",
            "reopen_reason",
            "reopen_approval_instance_id",
            "reopen_count")) {
      var row =
          sql.fetchOne(
              "select count(*) from information_schema.columns"
                  + " where table_name = 'finance_voucher_period' and column_name = ?",
              column);
      assertThat(row.get(0, Long.class)).as("finance_voucher_period 必须有列 " + column).isPositive();
    }
    // 周期长度是**生成列**（不可能与日期漂移）。
    var generated =
        sql.fetchOne(
            "select is_generated from information_schema.columns"
                + " where table_name = 'finance_voucher_period' and column_name = 'period_length_days'");
    assertThat(generated.get(0, String.class)).as("周期长度必须是生成列").isEqualTo("ALWAYS");
  }

  @Test
  void 业务表强制rls而不是只启用() {
    for (String table :
        List.of(
            "finance_accounting_calendar",
            "finance_accounting_period_event",
            "finance_accounting_period_approval",
            "finance_voucher_period")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as(table + " 必须存在").isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」").isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('finance_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("finance_app 必须真的能读 " + table)
          .isTrue();
    }
  }

  @Test
  void 期间是日期概念_关账与重开时刻是timestamptz() {
    for (String column : List.of("period_start", "period_end")) {
      var row =
          sql.fetchOne(
              "select data_type from information_schema.columns"
                  + " where table_name = 'finance_voucher_period' and column_name = ?",
              column);
      assertThat(row.get(0, String.class)).as(column + " 必须是 date（期间是一段日历）").isEqualTo("date");
    }
    for (String column : List.of("closed_at", "reopened_at")) {
      var row =
          sql.fetchOne(
              "select data_type from information_schema.columns"
                  + " where table_name = 'finance_voucher_period' and column_name = ?",
              column);
      assertThat(row.get(0, String.class))
          .as(column + " 必须是 timestamptz（绝对时刻）")
          .isEqualTo("timestamp with time zone");
    }
    var anchor =
        sql.fetchOne(
            "select data_type from information_schema.columns"
                + " where table_name = 'finance_accounting_calendar' and column_name = 'anchor_date'");
    assertThat(anchor.get(0, String.class)).isEqualTo("date");
  }

  // ==========================================================================
  // 会计日历
  // ==========================================================================

  @Test
  void 会计日历可建可查可停用() throws Exception {
    var id = createCalendar("STD", "CALENDAR_MONTH", 4, null, "USD");
    var list =
        http.perform(get("/finance/accounting-periods/calendars").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(list).contains("STD").contains("自然月").contains("USD");

    // 改名称与状态（⚠️ 账期形状不可改）。
    http.perform(
            put("/finance/accounting-periods/calendars/" + id)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"calendarName\":\"改成停用\",\"status\":\"DISABLED\",\"note\":\"年末不用了\"}"))
        .andExpect(status().isOk());
    var row = fetchOneInTenant("select * from finance_accounting_calendar where id = ?", id);
    assertThat(row.get("calendar_name", String.class)).isEqualTo("改成停用");
    assertThat(row.get("status", String.class)).isEqualTo("DISABLED");
  }

  @Test
  void 日历编码重复被拒() throws Exception {
    createCalendar("DUP", "CALENDAR_MONTH", 1, null, "USD");
    http.perform(
            post("/finance/accounting-periods/calendars")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"calendarCode":"DUP","calendarName":"再来一次","calendarType":"CALENDAR_MONTH",
                     "fiscalYearStartMonth":1,"baseCurrency":"USD"}
                    """))
        .andExpect(status().isConflict());
  }

  @Test
  void 日历445必须给锚点日() throws Exception {
    // ⚠️ 只断言状态码：MockMvc 不渲染错误页（真实服务端会带上服务端给的阻断原因，
    //    前端据此显示「为什么不行」而不是把按钮灰掉）。
    http.perform(
            post("/finance/accounting-periods/calendars")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"calendarCode":"NOANCHOR","calendarName":"缺锚点","calendarType":"FISCAL_445",
                     "fiscalYearStartMonth":1,"baseCurrency":"USD"}
                    """))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 按日历生成期间（自然月 / 4-4-5）
  // ==========================================================================

  @Test
  void 自然月日历生成12个期间且财年边界正确() throws Exception {
    var calendarId = createCalendar("STD", "CALENDAR_MONTH", 4, null, "USD");
    var result = generatePeriods(calendarId, 2026);
    assertThat(result.get("created").asInt()).isEqualTo(12);
    assertThat(result.get("fiscalYear").asInt()).isEqualTo(2026);

    var periods =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select * from finance_voucher_period where calendar_id = ?"
                            + " order by fiscal_period_no",
                        calendarId)
                    .map(r -> r));
    assertThat(periods).hasSize(12);
    // 财年 2026 = 2026-04-01 .. 2027-03-31（起始月 = 4）。
    assertThat(periods.get(0).get("period_start", LocalDate.class)).isEqualTo(LocalDate.of(2026, 4, 1));
    assertThat(periods.get(0).get("period_end", LocalDate.class)).isEqualTo(LocalDate.of(2026, 4, 30));
    assertThat(periods.get(11).get("period_start", LocalDate.class)).isEqualTo(LocalDate.of(2027, 3, 1));
    assertThat(periods.get(11).get("period_end", LocalDate.class)).isEqualTo(LocalDate.of(2027, 3, 31));
    // fiscal 周期真的落库。
    assertThat(periods.get(0).get("fiscal_year", Integer.class)).isEqualTo(2026);
    assertThat(periods.get(0).get("fiscal_period_no", Integer.class)).isEqualTo(1);
    assertThat(periods.get(11).get("fiscal_period_no", Integer.class)).isEqualTo(12);
    assertThat(periods.get(0).get("period_type", String.class)).isEqualTo("CALENDAR_MONTH");
    // 周期长度是生成列：与实际天数一致（4 月 30 天、3 月 31 天）。
    assertThat(periods.get(0).get("period_length_days", Integer.class)).isEqualTo(30);
    assertThat(periods.get(11).get("period_length_days", Integer.class)).isEqualTo(31);
    // 期间编码跨日历也不撞。
    assertThat(periods.get(0).get("period_code", String.class)).isEqualTo("STD-FY2026-01");
  }

  @Test
  void 日历445生成12个周期且长度是28_28_35() throws Exception {
    var calendarId = createCalendar("F445", "FISCAL_445", 1, "2026-04-06", "USD");
    var result = generatePeriods(calendarId, 2026);
    assertThat(result.get("created").asInt()).isEqualTo(12);

    var periods =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select * from finance_voucher_period where calendar_id = ?"
                            + " order by fiscal_period_no",
                        calendarId)
                    .map(r -> r));
    assertThat(periods).hasSize(12);
    // 4-4-5 循环：28 / 28 / 35 天，重复 4 轮 = 52 周 = 364 天。
    for (var i = 0; i < 12; i++) {
      assertThat(periods.get(i).get("period_length_days", Integer.class))
          .as("第 %d 期长度", i + 1)
          .isEqualTo(new int[] {28, 28, 35}[i % 3]);
      assertThat(periods.get(i).get("period_type", String.class)).isEqualTo("FISCAL_445");
    }
    // 第一个周期从锚点日开始；最后一个周期正好在锚点 + 363 天结束。
    assertThat(periods.get(0).get("period_start", LocalDate.class)).isEqualTo(LocalDate.of(2026, 4, 6));
    assertThat(periods.get(0).get("period_end", LocalDate.class)).isEqualTo(LocalDate.of(2026, 5, 3));
    assertThat(periods.get(11).get("period_end", LocalDate.class))
        .isEqualTo(LocalDate.of(2026, 4, 6).plusDays(363));
    // 周期之间不留缝也不重叠。
    for (var i = 0; i < 11; i++) {
      assertThat(periods.get(i + 1).get("period_start", LocalDate.class))
          .as("第 %d 期与第 %d 期必须首尾相接", i + 2, i + 1)
          .isEqualTo(periods.get(i).get("period_end", LocalDate.class).plusDays(1));
    }
  }

  @Test
  void 重复生成跳过已存在的编码并逐个报出() throws Exception {
    var calendarId = createCalendar("STD", "CALENDAR_MONTH", 1, null, "USD");
    assertThat(generatePeriods(calendarId, 2026).get("created").asInt()).isEqualTo(12);
    var again = generatePeriods(calendarId, 2026);
    assertThat(again.get("created").asInt()).as("重跑不得覆盖已存在的期间").isZero();
    assertThat(again.get("skipped").asInt()).isEqualTo(12);
    assertThat(again.get("skippedCodes")).as("被跳过的编码必须逐个报出来").hasSize(12);
    assertThat(again.get("skippedCodes").get(0).asText()).isEqualTo("STD-FY2026-01");
  }

  @Test
  void 停用的日历不能再生成期间() throws Exception {
    var calendarId = createCalendar("STD", "CALENDAR_MONTH", 1, null, "USD");
    http.perform(
            put("/finance/accounting-periods/calendars/" + calendarId)
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"calendarName\":\"停用\",\"status\":\"DISABLED\",\"note\":\"\"}"))
        .andExpect(status().isOk());
    http.perform(
            post("/finance/accounting-periods/calendars/" + calendarId + "/generate-periods")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fiscalYear\":2026}"))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 关账
  // ==========================================================================

  @Test
  void 关账真的落库并留下关账记录() throws Exception {
    var periodId = createPeriod("2026-10", "2026-10-01", "2026-10-31");
    closePeriod(periodId, "月末关账");

    var row = fetchOneInTenant("select * from finance_voucher_period where id = ?", periodId);
    assertThat(row.get("status", String.class)).isEqualTo("CLOSED");
    assertThat(row.get("closed_by", UUID.class)).isEqualTo(userId);
    assertThat(row.get("closed_reason", String.class)).isEqualTo("月末关账");
    assertThat(row.get("closed_at", OffsetDateTime.class)).isNotNull();

    // 关账记录（= 蓝图输出里的「关账记录」）。
    var events =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select * from finance_accounting_period_event where period_id = ?"
                            + " order by occurred_at, id",
                        periodId)
                    .map(r -> r.get("event_type", String.class)));
    assertThat(events).contains("CREATED", "CLOSED");
  }

  @Test
  void 关账必须写原因() throws Exception {
    var periodId = createPeriod("2026-11", "2026-11-01", "2026-11-30");
    http.perform(
            post("/finance/accounting-periods/" + periodId + "/close")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"\",\"expectedVersion\":" + versionOf(periodId) + "}"))
        .andExpect(status().isBadRequest());
    assertThat(fetchOneInTenant("select status from finance_voucher_period where id = ?", periodId)
            .get("status", String.class))
        .isEqualTo("OPEN");
  }

  @Test
  void 关账必须带版本_版本不符报冲突() throws Exception {
    var periodId = createPeriod("2026-12", "2026-12-01", "2026-12-31");
    http.perform(
            post("/finance/accounting-periods/" + periodId + "/close")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"月末关账\"}"))
        .andExpect(status().isBadRequest());
    http.perform(
            post("/finance/accounting-periods/" + periodId + "/close")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"月末关账\",\"expectedVersion\":99}"))
        .andExpect(status().isConflict());
  }

  @Test
  void 已关闭的期间关不掉() throws Exception {
    var periodId = createPeriod("2026-09", "2026-09-01", "2026-09-30");
    closePeriod(periodId, "月末关账");
    http.perform(
            post("/finance/accounting-periods/" + periodId + "/close")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"再关一次\",\"expectedVersion\":" + versionOf(periodId) + "}"))
        .andExpect(status().isConflict());
  }

  // ==========================================================================
  // 重开：必须审批留痕
  // ==========================================================================

  @Test
  void 没有生效审批流就不许重开() throws Exception {
    var periodId = createPeriod("2026-08", "2026-08-01", "2026-08-31");
    closePeriod(periodId, "月末关账");
    scenario.set("NO_FLOW");
    http.perform(
            post("/finance/accounting-periods/" + periodId + "/reopen")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"补记一笔\",\"expectedVersion\":" + versionOf(periodId) + "}"))
        .andExpect(status().isNotFound());
    // 期间仍然关闭。
    assertThat(fetchOneInTenant("select status from finance_voucher_period where id = ?", periodId)
            .get("status", String.class))
        .isEqualTo("CLOSED");
  }

  @Test
  void 重开先提交审批_期间仍然关闭() throws Exception {
    var periodId = createPeriod("2026-07", "2026-07-01", "2026-07-31");
    closePeriod(periodId, "月末关账");
    var result = reopen(periodId, "补记一笔");
    assertThat(result.get("outcome").asText()).isEqualTo("REOPEN_REQUESTED");
    assertThat(result.get("approvalStatus").asText()).isEqualTo("PENDING");
    assertThat(result.get("approvalInstanceId").asText()).isNotBlank();
    assertThat(submits.get()).isEqualTo(1);
    // 🔴 期间**仍然关闭** —— 提交申请不等于重开。
    assertThat(fetchOneInTenant("select status from finance_voucher_period where id = ?", periodId)
            .get("status", String.class))
        .isEqualTo("CLOSED");
    // 重开申请留痕。
    var events =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select event_type from finance_accounting_period_event where period_id = ?",
                        periodId)
                    .map(r -> r.get("event_type", String.class)));
    assertThat(events).contains("REOPEN_REQUESTED");
  }

  @Test
  void 审批通过后重开真的执行() throws Exception {
    var periodId = createPeriod("2026-06", "2026-06-01", "2026-06-30");
    closePeriod(periodId, "月末关账");
    // 第一次：提交申请。
    var first = reopen(periodId, "补记一笔");
    assertThat(first.get("outcome").asText()).isEqualTo("REOPEN_REQUESTED");
    var instanceId = UUID.fromString(first.get("approvalInstanceId").asText());

    // identity 侧批了。
    instances.put(instanceId, "APPROVED");
    var second = reopen(periodId, "补记一笔");
    assertThat(second.get("outcome").asText()).isEqualTo("REOPENED");

    var row = fetchOneInTenant("select * from finance_voucher_period where id = ?", periodId);
    assertThat(row.get("status", String.class)).isEqualTo("OPEN");
    assertThat(row.get("reopened_by", UUID.class)).isEqualTo(userId);
    assertThat(row.get("reopen_reason", String.class)).isEqualTo("补记一笔");
    assertThat(row.get("reopen_approval_instance_id", UUID.class)).isEqualTo(instanceId);
    assertThat(row.get("reopen_count", Integer.class)).isEqualTo(1);
    assertThat(row.get("reopened_at", OffsetDateTime.class)).isNotNull();
    // 关账痕迹**仍然留着**（重开不是抹掉历史）。
    assertThat(row.get("closed_reason", String.class)).isEqualTo("月末关账");
  }

  @Test
  void 重开留痕与审批镜像落库() throws Exception {
    var periodId = createPeriod("2026-05", "2026-05-01", "2026-05-31");
    closePeriod(periodId, "月末关账");
    var first = reopen(periodId, "补记一笔");
    var instanceId = UUID.fromString(first.get("approvalInstanceId").asText());
    instances.put(instanceId, "APPROVED");
    reopen(periodId, "补记一笔");

    var events =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select event_type, approval_instance_id, approval_status"
                            + " from finance_accounting_period_event where period_id = ?"
                            + " order by occurred_at, id",
                        periodId));
    assertThat(events).hasSize(4); // CREATED / CLOSED / REOPEN_REQUESTED / REOPENED
    var reopened = events.get(3);
    assertThat(reopened.get("event_type", String.class)).isEqualTo("REOPENED");
    assertThat(reopened.get("approval_instance_id", UUID.class)).isEqualTo(instanceId);
    assertThat(reopened.get("approval_status", String.class)).isEqualTo("APPROVED");

    // 审批链路的本地镜像（DB 守卫就是查它）。
    var mirrored =
        tenants.run(
            tenant,
            () ->
                sql.fetch(
                        "select * from finance_accounting_period_approval where period_id = ?",
                        periodId)
                    .map(r -> r));
    assertThat(mirrored).isNotEmpty();
    assertThat(mirrored.stream().map(r -> r.get("action", String.class)).toList())
        .contains("APPROVE");
  }

  @Test
  void 审批被驳回期间仍然关闭() throws Exception {
    var periodId = createPeriod("2026-04", "2026-04-01", "2026-04-30");
    closePeriod(periodId, "月末关账");
    var first = reopen(periodId, "补记一笔");
    var instanceId = UUID.fromString(first.get("approvalInstanceId").asText());
    instances.put(instanceId, "REJECTED");
    var second = reopen(periodId, "补记一笔");
    assertThat(second.get("outcome").asText()).isEqualTo("REJECTED");
    assertThat(fetchOneInTenant("select status from finance_voucher_period where id = ?", periodId)
            .get("status", String.class))
        .isEqualTo("CLOSED");
  }

  @Test
  void 审批不可达如实报错且期间保持关闭() throws Exception {
    var periodId = createPeriod("2026-03", "2026-03-01", "2026-03-31");
    closePeriod(periodId, "月末关账");
    var first = reopen(periodId, "补记一笔");
    assertThat(first.get("outcome").asText()).isEqualTo("REOPEN_REQUESTED");
    var instanceId = UUID.fromString(first.get("approvalInstanceId").asText());

    // identity 不可达 ⇒ 整个调用失败，**期间保持关闭**（绝不假装批过了）。
    scenario.set("UNREACHABLE");
    http.perform(
            post("/finance/accounting-periods/" + periodId + "/reopen")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"补记一笔\",\"expectedVersion\":" + versionOf(periodId) + "}"))
        .andExpect(status().isServiceUnavailable());
    assertThat(fetchOneInTenant("select status from finance_voucher_period where id = ?", periodId)
            .get("status", String.class))
        .as("审批读不到时期间必须保持关闭")
        .isEqualTo("CLOSED");
    assertThat(instanceId).isNotNull();
  }

  @Test
  void 重开必须带版本() throws Exception {
    var periodId = createPeriod("2026-02", "2026-02-01", "2026-02-28");
    closePeriod(periodId, "月末关账");
    http.perform(
            post("/finance/accounting-periods/" + periodId + "/reopen")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"补记一笔\"}"))
        .andExpect(status().isBadRequest());
  }

  // ==========================================================================
  // 数据层守卫（服务层绕不过的）
  // ==========================================================================

  @Test
  void 直连sql也重开不了没有审批的期间() throws Exception {
    var periodId = createPeriod("2026-01", "2026-01-01", "2026-01-31");
    closePeriod(periodId, "月末关账");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update finance_voucher_period set status='OPEN' where id = '"
                          + periodId
                          + "'"))
          .as("没有审批留痕的重开，直连 SQL 也写不出来")
          .hasStackTraceContaining("不允许重开");
    }
    assertThat(fetchOneInTenant("select status from finance_voucher_period where id = ?", periodId)
            .get("status", String.class))
        .isEqualTo("CLOSED");
  }

  @Test
  void 直连sql也改不了期间身份字段() throws Exception {
    var periodId = createPeriod("2025-12", "2025-12-01", "2025-12-31");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update finance_voucher_period set period_code = 'HACKED' where id = '"
                          + periodId
                          + "'"))
          .as("期间编码一经入账不可改")
          .hasStackTraceContaining("不可修改");
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update finance_voucher_period set period_end = '2026-12-31' where id = '"
                          + periodId
                          + "'"))
          .as("期间区间一经入账不可改")
          .hasStackTraceContaining("不可修改");
    }
  }

  @Test
  void 直连sql也删不掉期间() throws Exception {
    var periodId = createPeriod("2025-11", "2025-11-01", "2025-11-30");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () -> s.execute("delete from finance_voucher_period where id = '" + periodId + "'"))
          .as("期间是会计事实，删不掉")
          .hasStackTraceContaining("不可删除");
    }
  }

  @Test
  void 直连sql也改不了已关闭期间的关账留痕() throws Exception {
    var periodId = createPeriod("2025-10", "2025-10-01", "2025-10-31");
    closePeriod(periodId, "月末关账");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update finance_voucher_period set closed_reason = '换个说法' where id = '"
                          + periodId
                          + "'"))
          .as("已关闭期间的关账留痕不可再改")
          .hasStackTraceContaining("关账留痕不可再改");
    }
  }

  @Test
  void 留痕只能追加() throws Exception {
    var periodId = createPeriod("2025-09", "2025-09-01", "2025-09-30");
    var eventId =
        fetchOneInTenant(
                "select id from finance_accounting_period_event where period_id = ? limit 1",
                periodId)
            .get("id", UUID.class);
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "finance_app", "test-only");
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update finance_accounting_period_event set reason = '篡改' where id = '"
                          + eventId
                          + "'"))
          .as("留痕是举证凭据，运行角色改不了")
          .hasStackTraceContaining("permission denied");
      assertThatThrownBy(
              () ->
                  s.execute(
                      "delete from finance_accounting_period_event where id = '" + eventId + "'"))
          .as("留痕是举证凭据，运行角色删不了")
          .hasStackTraceContaining("permission denied");
    }
  }

  @Test
  void 日历被引用后不可改账期形状() throws Exception {
    var calendarId = createCalendar("STD", "CALENDAR_MONTH", 1, null, "USD");
    generatePeriods(calendarId, 2026);
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update finance_accounting_calendar set calendar_type = 'FISCAL_445' where id = '"
                          + calendarId
                          + "'"))
          .as("已被期间引用的日历不可改账期形状")
          .hasStackTraceContaining("不可再改");
    }
  }

  // ==========================================================================
  // 与 12.5.2.1 的衔接：接管之后「已关闭期间禁止过账」依然生效
  // ==========================================================================

  @Test
  void 接管后已关闭期间仍然禁止过账() throws Exception {
    var periodId = createPeriod("2025-08", "2025-08-01", "2025-08-31");
    closePeriod(periodId, "月末关账");
    // 走 12.5.2.1 的端点建一张草稿凭证，再过账 ⇒ 必须被拒。
    var draft =
        http.perform(
                post("/finance/vouchers")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"sourceType":"MANUAL","periodId":"%s","voucherDate":"2025-08-20",
                         "description":"接管后过账闸门测试","currency":"USD",
                         "lines":[{"lineType":"DEBIT","subjectCode":"1001","subjectName":"库存现金",
                                   "summary":"借","amount":"100.0000","currency":"USD",
                                   "auxiliaryType":"NONE"},
                                  {"lineType":"CREDIT","subjectCode":"6001","subjectName":"主营业务收入",
                                   "summary":"贷","amount":"100.0000","currency":"USD",
                                   "auxiliaryType":"NONE"}]}
                        """
                            .formatted(periodId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var voucherId = json.readTree(draft).get("voucher").get("id").asText();
    http.perform(
            post("/finance/vouchers/" + voucherId + "/post")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0}"))
        .andExpect(status().isConflict());

    // 🔴 直连 SQL 也过不了账（那道守卫是 V357 的，本模块一个字都没改）。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update finance_voucher set status='POSTED', total_debit=100.0000,"
                          + " total_credit=100.0000, line_count=2, posted_at=now() where id='"
                          + voucherId
                          + "'"))
          .as("已关闭期间的凭证，直连 SQL 也过不了账")
          .hasStackTraceContaining("已关闭，不允许过账");
    }
  }

  @Test
  void 重开之后期间恢复可过账() throws Exception {
    var periodId = createPeriod("2025-07", "2025-07-01", "2025-07-31");
    closePeriod(periodId, "月末关账");
    var first = reopen(periodId, "补记一笔");
    var instanceId = UUID.fromString(first.get("approvalInstanceId").asText());
    instances.put(instanceId, "APPROVED");
    reopen(periodId, "补记一笔");

    var draft =
        http.perform(
                post("/finance/vouchers")
                    .header("Authorization", "session")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"sourceType":"MANUAL","periodId":"%s","voucherDate":"2025-07-20",
                         "description":"重开后过账","currency":"USD",
                         "lines":[{"lineType":"DEBIT","subjectCode":"1001","subjectName":"库存现金",
                                   "summary":"借","amount":"100.0000","currency":"USD",
                                   "auxiliaryType":"NONE"},
                                  {"lineType":"CREDIT","subjectCode":"6001","subjectName":"主营业务收入",
                                   "summary":"贷","amount":"100.0000","currency":"USD",
                                   "auxiliaryType":"NONE"}]}
                        """
                            .formatted(periodId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var voucherId = json.readTree(draft).get("voucher").get("id").asText();
    http.perform(
            post("/finance/vouchers/" + voucherId + "/post")
                .header("Authorization", "session")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0}"))
        .andExpect(status().isOk());
  }

  // ==========================================================================
  // 列表 / 汇总 / 能力声明 / 时间口径
  // ==========================================================================

  @Test
  void 列表是真分页真筛选且汇总覆盖整个结果集() throws Exception {
    createPeriod("2025-06", "2025-06-01", "2025-06-30");
    createPeriod("2025-05", "2025-05-01", "2025-05-31");
    closePeriod(createPeriod("2025-04", "2025-04-01", "2025-04-30"), "月末关账");

    var page =
        http.perform(
                get("/finance/accounting-periods?page=0&size=2").header("Authorization", "session"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var tree = json.readTree(page);
    assertThat(tree.get("items")).hasSize(2);
    assertThat(tree.get("total").asInt()).isEqualTo(3);
    // 汇总覆盖**整个筛选结果集**（不是当前页的两条）。
    var summary = tree.get("summaryByStatus");
    assertThat(summary).hasSize(2);
    var total = 0;
    for (var s : summary) total += s.get("count").asInt();
    assertThat(total).isEqualTo(3);

    // 筛选真的筛。
    var closed =
        json.readTree(
            http.perform(
                    get("/finance/accounting-periods?status=CLOSED")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(closed.get("total").asInt()).isEqualTo(1);
    assertThat(closed.get("items").get(0).get("status").asText()).isEqualTo("CLOSED");
  }

  @Test
  void 能力声明如实报出关账清单与报表两个缺口() throws Exception {
    var caps =
        json.readTree(
            http.perform(
                    get("/finance/accounting-periods/capabilities")
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    var body = caps.toString();
    assertThat(body).contains("12.5.1.3").contains("关账检查清单").contains("12.5.2.2");

    // 关账检查清单必须报成**不可用**（它属 12.5.1.3，未交付）。
    var checklistUnavailable = false;
    for (var e : caps.get("capabilities")) {
      if (e.get("name").asText().contains("关账检查清单")) {
        checklistUnavailable = !e.get("available").asBoolean();
        assertThat(e.get("ownerModule").asText()).isEqualTo("12.5.1.3");
      }
    }
    assertThat(checklistUnavailable).as("关账检查清单属 12.5.1.3，未交付 ⇒ 必须报成不可用").isTrue();

    // 下游缺口里必须同时报出关账与报表。
    var gapModules = new java.util.ArrayList<String>();
    for (var g : caps.get("downstreamGaps")) {
      gapModules.add(g.get("module").asText());
    }
    assertThat(gapModules).contains("12.5.1.3", "12.5.2.2");
  }

  @Test
  void 写入的时刻逐位相等不偏八小时() {
    // 🔴 用 +08:00 的夹具写进去，读回来必须是**同一个时刻**。
    var fixture = OffsetDateTime.parse("2026-10-24T10:00:00+08:00");
    var periodId = UUID.randomUUID();
    var eventId = UUID.randomUUID();
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into finance_voucher_period(id,tenant_id,period_code,period_start,period_end,"
                  + "status,base_currency,created_at,updated_at)"
                  + " values(?,?,'TZ-2026-10','2026-10-01','2026-10-31','OPEN','USD',"
                  + "?::timestamptz,?::timestamptz)",
              periodId,
              tenant,
              now,
              now);
          sql.execute(
              "insert into finance_accounting_period_event(id,tenant_id,period_id,event_type,"
                  + "from_status,to_status,reason,actor_id,approval_instance_id,approval_status,"
                  + "detail,occurred_at)"
                  + " values(?,?,?,'UPDATED','OPEN','OPEN','时区夹具',null,null,'','时区夹具',"
                  + "?::timestamptz)",
              eventId,
              tenant,
              periodId,
              fixture);
          return null;
        });
    var read =
        fetchOneInTenant(
                "select occurred_at from finance_accounting_period_event where id = ?", eventId)
            .get("occurred_at", OffsetDateTime.class);
    assertThat(read.toInstant()).as("写回的时刻必须逐位相等，不能偏八小时").isEqualTo(fixture.toInstant());
    assertThat(read.getOffset()).as("读回来的偏移量必须与写进去的一致").isEqualTo(fixture.getOffset());
  }

  @Test
  void 期间详情带关账记录与审批链路() throws Exception {
    var periodId = createPeriod("2025-03", "2025-03-01", "2025-03-31");
    closePeriod(periodId, "月末关账");
    var detail =
        json.readTree(
            http.perform(
                    get("/finance/accounting-periods/" + periodId)
                        .header("Authorization", "session"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(detail.get("period").get("periodCode").asText()).isEqualTo("2025-03");
    assertThat(detail.get("period").get("periodLengthDays").asInt()).isEqualTo(31);
    assertThat(detail.get("events")).isNotEmpty();
    assertThat(detail.get("approvals")).isNotNull();
  }

  @Test
  void 手工登记的期间没有fiscal周期() throws Exception {
    var periodId = createPeriod("CUSTOM-01", "2025-02-01", "2025-02-28");
    var row = fetchOneInTenant("select * from finance_voucher_period where id = ?", periodId);
    assertThat(row.get("period_type", String.class)).isEqualTo("CUSTOM");
    assertThat(row.get("calendar_id", UUID.class)).isNull();
    assertThat(row.get("fiscal_year", Integer.class)).isNull();
    assertThat(row.get("fiscal_period_no", Integer.class)).isNull();
    assertThat(row.get("period_length_days", Integer.class)).isEqualTo(28);
  }

  @Test
  void 本模块没有金额_也没有浮点列() throws Exception {
    var calendarId = createCalendar("STD", "CALENDAR_MONTH", 1, null, "USD");
    generatePeriods(calendarId, 2026);
    // 本模块**没有金额**：表里不该出现任何浮点列（real / double precision / money）。
    var floatColumns =
        sql.fetch(
                "select table_name, column_name from information_schema.columns"
                    + " where table_name in ('finance_voucher_period','finance_accounting_calendar')"
                    + " and data_type in ('real','double precision','money')")
            .map(r -> r.get("table_name", String.class) + "." + r.get("column_name", String.class));
    assertThat(floatColumns).as("全链路禁浮点").isEmpty();
    // 会计日历表**完全没有数值金额列**（唯一与数字有关的是财年起始月，整数）。
    var calendarNumeric =
        sql.fetch(
                "select column_name from information_schema.columns"
                    + " where table_name = 'finance_accounting_calendar'"
                    + " and data_type in ('numeric','real','double precision','money')")
            .map(r -> r.get(0, String.class));
    assertThat(calendarNumeric).as("会计日历不承载金额").isEmpty();
    assertThat(BigDecimal.class).isNotNull();
  }
}
