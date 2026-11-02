package com.starcon.crm;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.crm.CustomerDetailFactsPort.CasePage;
import com.starcon.crm.CustomerDetailFactsPort.CaseRow;
import com.starcon.crm.CustomerDetailFactsPort.OrderPage;
import com.starcon.crm.CustomerDetailFactsPort.OrderRow;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * 15.3.1.2 客户详情的集成测试。
 *
 * <p>跑的是 AGENTS.md 要求的<b>真实 PostgreSQL 16 + FORCE RLS</b>。
 * 租户隔离三断言在 {@link CustomerDetailTenancyIntegrationTest} 里，本类专攻业务不变量：
 *
 * <ul>
 *   <li><b>统一时间线真的合并五个来源</b>：订单（trade）/ 售后（trade）/ 消息（本服务）/
 *       评价（本服务）/ 接触记录（本服务）落到**同一条**时间线上，来源类别齐全。</li>
 *   <li><b>分页是真的</b>：{@code LIMIT/OFFSET} + 稳定全序，翻页不重不漏。</li>
 *   <li><b>重建是幂等的</b>：重建两次，条目数不变（不重复记账）。</li>
 *   <li><b>上游读不到 ≠ 没有数据</b>：读不到 ⇒ 该来源标「不可用」并写明原因，
 *       而且<b>一条条目都不落</b>（绝不编数据）。</li>
 *   <li><b>未合并就是未合并</b>：合并状态恒为 {@code NOT_MERGED}，
 *       跨店铺身份关系只给<b>线索</b>。</li>
 *   <li><b>联系方式默认脱敏</b>；明文要<b>显式授权</b>（{@code crm.customers.pii.read}），
 *       没授权还硬要 ⇒ 403，不是悄悄退回脱敏。</li>
 *   <li><b>禁浮点 / 时区不静默偏移</b>：金额逐位相同；带偏移量的时刻写回等于同一绝对时刻。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerDetailIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenant = UUID.randomUUID();
  static final UUID userId = UUID.randomUUID();
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          // ⚠️ 时刻一律写成 ISO-8601 字符串（带偏移量）：写成 epoch 数字会让
          // 「+08:00 的时刻写回还是不是同一个绝对时刻」这件事在测试里看不出来。
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          // ⚠️ 默认会把 JSON 数字读成 double —— 那样断言本身就先把值舍入了，
          // 「没掉精度」这件事就再也证不出来。这里强制读成 BigDecimal。
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  // ------------------------------------------------------------ 上游（trade）事实

  /** 客户甲的 party id（catalog 客户档案）。 */
  static final UUID partyA = UUID.randomUUID();

  /** 客户甲那一单：**带 +08:00 偏移量**的时刻，用来钉死时区不静默偏移。 */
  static final OffsetDateTime orderAt = OffsetDateTime.parse("2026-10-10T08:00:00+08:00");

  /**
   * 金额：{@code numeric(19,4)} 只留 4 位小数（列定义如此）。
   *
   * <p>刻意用 **12 位有效数字**（8 位整数 + 4 位小数）：double 只有 ~15-17 位有效数字，
   * 任何一次浮点往返都可能把它变成 {@code 12345678.1235} 之外的形态。
   */
  static final BigDecimal orderAmount = new BigDecimal("12345678.1234");

  static final List<OrderRow> upstreamOrders = new CopyOnWriteArrayList<>();
  static final List<CaseRow> upstreamCases = new CopyOnWriteArrayList<>();

  /** 上游读不到时的开关（用来验证「读不到」与「没有数据」是两件事）。 */
  static volatile boolean tradeDown = false;

  static HttpServer identity;
  static HttpServer trade;

  /** 本次测试的客户主档行 id。 */
  static UUID customerId;
  /** 同租户下另一行客户主档（联系方式指纹一致 ⇒ 身份线索）。 */
  static UUID siblingId;

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
    List<String> readWrite = List.of("crm.customers.read", "crm.customers.update");
    List<String> withPii =
        List.of("crm.customers.read", "crm.customers.update", "crm.customers.pii.read");
    actors.put(
        "session",
        new AccessService.Access(
            userId,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            readWrite,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", readWrite))));
    actors.put(
        "session-pii",
        new AccessService.Access(
            userId,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            withPii,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", withPii))));
    List<String> readOnly = List.of("crm.customers.read");
    actors.put(
        "session-readonly",
        new AccessService.Access(
            userId,
            tenant,
            "Test",
            UUID.randomUUID(),
            null,
            readOnly,
            List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", readOnly))));
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
      trade.createContext(
          "/trade/sales-orders/list",
          e -> {
            if (tradeDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            var query = query(e);
            String party = query.get("customerPartyId");
            List<OrderRow> matched = new ArrayList<>();
            for (OrderRow o : upstreamOrders) {
              if (party == null || party.isBlank() || party.equals(String.valueOf(o.customerPartyId()))) {
                matched.add(o);
              }
            }
            int size = Integer.parseInt(query.getOrDefault("size", "200"));
            int pageNo = Integer.parseInt(query.getOrDefault("page", "0"));
            int offset = Math.min(pageNo * size, matched.size());
            int limit = Math.min(offset + size, matched.size());
            write(
                e,
                new OrderPage(
                    new ArrayList<>(matched.subList(offset, limit)), pageNo, size, matched.size()));
          });
      trade.createContext(
          "/trade/aftersales/cases",
          e -> {
            if (tradeDown) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            var query = query(e);
            OffsetDateTime from = moment(query.get("receivedFrom"));
            OffsetDateTime to = moment(query.get("receivedTo"));
            List<CaseRow> matched = new ArrayList<>();
            for (CaseRow c : upstreamCases) {
              if (c.receivedAt() == null) continue;
              if (from != null && c.receivedAt().isBefore(from)) continue;
              if (to != null && !c.receivedAt().isBefore(to)) continue;
              matched.add(c);
            }
            int size = Integer.parseInt(query.getOrDefault("size", "200"));
            int pageNo = Integer.parseInt(query.getOrDefault("page", "0"));
            int offset = Math.min(pageNo * size, matched.size());
            int limit = Math.min(offset + size, matched.size());
            write(
                e,
                new CasePage(
                    new ArrayList<>(matched.subList(offset, limit)), pageNo, size, matched.size()));
          });
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
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired TenantTransactions tenants;

  // ------------------------------------------------------------ 测试脚手架

  private static Map<String, String> query(HttpExchange e) {
    Map<String, String> out = new HashMap<>();
    String raw = e.getRequestURI().getRawQuery();
    if (raw == null || raw.isBlank()) return out;
    for (String part : raw.split("&")) {
      int eq = part.indexOf('=');
      if (eq < 0) continue;
      out.put(
          URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
          URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
    }
    return out;
  }

  private static OffsetDateTime moment(String text) {
    return text == null || text.isBlank() ? null : OffsetDateTime.parse(text);
  }

  private static void write(HttpExchange e, Object body) {
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

  @BeforeEach
  void seed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table crm_customer_timeline_entry, crm_customer_timeline_run,"
              + " crm_customer_amount, crm_customer_profile, crm_customer_tag_assignment,"
              + " crm_customer_tag, crm_message, crm_product_review, crm_customer_contact cascade");
    }
    upstreamOrders.clear();
    upstreamCases.clear();
    tradeDown = false;

    customerId = UUID.randomUUID();
    siblingId = UUID.randomUUID();
    OffsetDateTime windowFrom = orderAt.minusDays(30);
    OffsetDateTime windowTo = orderAt.plusDays(30);

    // 🔴 RLS 是 **FORCE** 的：不在可信租户事务里写，WITH CHECK 会直接拒掉
    //   （「租户上下文没绑定」当场失败，而不是静默写到别的租户名下）。
    tenants.run(
        tenant,
        () -> {
          seedRows(windowFrom, windowTo);
          return null;
        });
  }

  /** 播客户主档 / 金额 / 消息 / 评价 / 接触记录（**必须**在可信租户事务里调用）。 */
  private void seedRows(OffsetDateTime windowFrom, OffsetDateTime windowTo) {
    // 客户甲：按来源店铺的客户标识，脱敏联系方式落库。
    sql.execute(
        "insert into crm_customer_profile"
            + " (id,tenant_id,customer_ref,source_shop,customer_party_id,customer_name,"
            + "  first_order_at,last_order_at,order_count,refund_case_count,value_tier,risk_flag,"
            + "  risk_reason,contact_email,contact_phone,contact_email_masked,contact_phone_masked,"
            + "  contact_source,opt_out_state,window_from,window_to,synced_at)"
            + " values (?,?,?,?,?,'张三',?::timestamptz,?::timestamptz,1,0,'HIGH','NONE','',"
            + " 'buyer@example.com','13800001234','b***@***.com','138****1234','MANUAL','NONE',"
            + " ?::timestamptz,?::timestamptz,?::timestamptz)",
        customerId,
        tenant,
        "CUST-A",
        "AMAZON",
        partyA,
        orderAt,
        orderAt,
        windowFrom,
        windowTo,
        orderAt);
    // 客户乙：同一脱敏邮箱、另一个来源店铺 ⇒ 身份线索（**不是**合并）。
    sql.execute(
        "insert into crm_customer_profile"
            + " (id,tenant_id,customer_ref,source_shop,customer_name,order_count,refund_case_count,"
            + "  value_tier,risk_flag,risk_reason,contact_email,contact_email_masked,"
            + "  contact_phone_masked,contact_source,opt_out_state,window_from,window_to,synced_at)"
            + " values (?,?,?,?,'张三',0,0,'LOW','NONE','','buyer@example.com','b***@***.com',"
            + " '138****1234','MANUAL','NONE',?::timestamptz,?::timestamptz,?::timestamptz)",
        siblingId,
        tenant,
        "CUST-A",
        "SHOPIFY",
        windowFrom,
        windowTo,
        orderAt);

    // 金额：按币种分开（触发器要求客单价与累计消费/订单数自洽）。
    sql.execute(
        "insert into crm_customer_amount"
            + " (id,tenant_id,customer_id,currency,total_amount,order_count,avg_order_amount)"
            + " values (?,?,?, 'USD', ?, 1, ?)",
        UUID.randomUUID(),
        tenant,
        customerId,
        orderAmount,
        orderAmount);

    // 消息（15.1 消息 · 本服务）：买家标识 = 客户标识。
    for (int i = 0; i < 3; i++) {
      sql.execute(
          "insert into crm_message"
              + " (id,tenant_id,message_no,shop_id,shop_name,platform_code,buyer_reference,"
              + "  order_reference,subject,body,direction,message_type,priority,read_status,"
              + "  source,external_message_id,platform_time,created_by)"
              + " values (?,?,?,?,'AMAZON 店','AMAZON','CUST-A','SO-1',?,?,'INBOUND','BUYER_MESSAGE',"
              // 🔴 read_status='READ' 必须同时给 read_at（DB CHECK 强制）：「已读」没有读的时刻，
              //   「这条什么时候被谁看的」就永远查不出来。
              + "  'NORMAL','UNREAD','MANUAL_ENTRY','',?::timestamptz,?)",
          UUID.randomUUID(),
          tenant,
          "MSG-TEST-" + i,
          UUID.randomUUID(),
          "消息标题 " + i,
          "消息正文 " + i,
          orderAt.plusHours(i),
          userId);
    }

    // 评价（15.2 评价 · 本服务）。
    sql.execute(
        "insert into crm_product_review"
            + " (id,tenant_id,review_no,shop_id,shop_name,platform_code,source,rating,title,"
            + "  content,buyer_ref,buyer_name,replied,platform_reviewed_at,registered_at,created_by)"
            + " values (?,?,?,?,'AMAZON 店','AMAZON','MANUAL_ENTRY',5,'好评','不错','CUST-A','张三',"
            + "  false,?::timestamptz,?::timestamptz,?)",
        UUID.randomUUID(),
        tenant,
        "PR-TEST-1",
        UUID.randomUUID(),
        orderAt.plusDays(1),
        orderAt.plusDays(1),
        userId);

    // 接触记录（15.3.2.2 · 本服务）。
    sql.execute(
        "insert into crm_customer_contact"
            + " (id,tenant_id,contact_no,customer_ref,customer_name,contact_type,direction,origin,"
            + "  subject,summary,occurred_at,operator_id,created_by)"
            + " values (?,?,?,'CUST-A','张三','CALL','OUTBOUND','MANUAL','回访电话','问了下尺寸',"
            + "  ?::timestamptz,?,?)",
        UUID.randomUUID(),
        tenant,
        "CT-TEST-1",
        orderAt.plusDays(2),
        userId,
        userId);

    // 上游（trade）事实：1 单 + 1 张售后单。
    upstreamOrders.add(
        new OrderRow(
            UUID.randomUUID(),
            "SO-1",
            "AMAZON",
            "CONFIRMED",
            "USD",
            orderAmount,
            2,
            partyA,
            "张三",
            orderAt,
            orderAt,
            "PLACED",
            "PAID",
            "FULFILLED"));
    upstreamCases.add(
        new CaseRow(
            UUID.randomUUID(),
            "CS-1",
            upstreamOrders.get(0).id(),
            "SO-1",
            "RETURN_REFUND",
            "退货退款",
            "OPEN",
            "待处理",
            "SIZE_NOT_FIT",
            new BigDecimal("10.0000"),
            "USD",
            new BigDecimal("1.000000"),
            orderAt.plusDays(3)));
  }

  /**
   * 在**可信租户事务**里跑一段工作。
   *
   * <p>🔴 RLS 是 FORCE 的：事务外读本模块的表只会读到**零行**（不是报错），
   * 事务外写会被 WITH CHECK 直接拒掉。所以测试里每一次碰本模块的表的查询
   * 都必须包在这里 —— 否则断言会拿到一个「看起来对、其实是空」的答案。
   */
  private <T> T inTenant(java.util.function.Supplier<T> work) {
    return tenants.run(tenant, work);
  }

  /** 取一个 GET 端点的响应体（**不叫 get**：会和 MockMvc 的静态导入重名）。 */
  private String fetch(String path, String session) throws Exception {
    return http.perform(get(path).header("Authorization", session).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String rebuild(String session) throws Exception {
    return http.perform(
            post("/crm/customers/" + customerId + "/timeline/rebuild")
                .header("Authorization", session)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  // ==========================================================================
  // 迁移确实落地了，且 RLS 是**强制**的
  // ==========================================================================
  @Test
  void 迁移落地且rls强制() {
    for (String table : List.of("crm_customer_timeline_entry", "crm_customer_timeline_run")) {
      var row =
          sql.fetchOne("select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V435 必须真的建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住「运行角色被误授 owner」").isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('crm_app','" + table + "','SELECT')")
                  .get(0, Boolean.class))
          .as("crm_app 必须真的能读 " + table)
          .isTrue();
    }
    assertThat(
            sql.fetchOne(
                    "select has_sequence_privilege('crm_app','crm_customer_timeline_run_no_seq','USAGE')")
                .get(0, Boolean.class))
        .as("crm_app 必须真的能用运行号序列")
        .isTrue();
  }

  // ==========================================================================
  // 统一时间线真的合并五个来源
  // ==========================================================================
  @Test
  void 时间线合并五个来源() throws Exception {
    String run = rebuild("session");
    // 1 订单 + 1 售后 + 3 消息 + 1 评价 + 4 接触记录
    //   （接触记录 4 条 = 人工补录 1 条 + 15.3.2.2 由 3 条消息**自动写入**的 3 条
    //     —— 那是同服务既有触发器的行为，本模块只读不写。）
    assertThat(run).contains("\"status\":\"SUCCEEDED\"").contains("\"entryCount\":10");

    String timeline = fetch("/crm/customers/" + customerId + "/timeline", "session");
    assertThat(timeline).contains("\"sourceKind\":\"ORDER\"");
    assertThat(timeline).contains("\"sourceKind\":\"AFTERSALES\"");
    assertThat(timeline).contains("\"sourceKind\":\"MESSAGE\"");
    assertThat(timeline).contains("\"sourceKind\":\"REVIEW\"");
    assertThat(timeline).contains("\"sourceKind\":\"CONTACT\"");
    // 订单号与售后单号都上了同一条时间线。
    assertThat(timeline).contains("SO-1").contains("CS-1");
  }

  // ==========================================================================
  // 分页是真的：LIMIT/OFFSET + 稳定全序
  // ==========================================================================
  @Test
  void 分页是真分页不重不漏() throws Exception {
    rebuild("session");
    // 10 条 / 每页 3 条 ⇒ 四页：3 + 3 + 3 + 1
    var pages = new java.util.ArrayList<List<String>>();
    for (int page = 0; page < 4; page++) {
      pages.add(
          idsOf(
              fetch(
                  "/crm/customers/" + customerId + "/timeline?page=" + page + "&size=3",
                  "session")));
    }
    assertThat(pages.get(0)).hasSize(3);
    assertThat(pages.get(1)).hasSize(3);
    assertThat(pages.get(2)).hasSize(3);
    assertThat(pages.get(3)).hasSize(1);
    // 不重
    for (int i = 0; i < pages.size(); i++) {
      for (int j = i + 1; j < pages.size(); j++) {
        assertThat(pages.get(i)).as("第 %d 页与第 %d 页有重复", i, j)
            .doesNotContainAnyElementsOf(pages.get(j));
      }
    }
    // 不漏：合计等于总数
    int total = pages.stream().mapToInt(List::size).sum();
    assertThat(total).isEqualTo(10);
    // 顺序稳定：按发生时刻降序（同一页内越靠前越新）
    var first = timesOf(
        fetch("/crm/customers/" + customerId + "/timeline?page=0&size=3", "session"));
    assertThat(first).isSortedAccordingTo(java.util.Comparator.reverseOrder());
  }

  // ==========================================================================
  // 重建是幂等的
  // ==========================================================================
  @Test
  void 重建是幂等的不重复记账() throws Exception {
    rebuild("session");
    long after1 = countEntries();
    rebuild("session");
    long after2 = countEntries();
    assertThat(after2).as("重建两次不得让条目翻倍").isEqualTo(after1).isEqualTo(10);
  }

  // ==========================================================================
  // 🔴 上游读不到 ≠ 没有数据
  // ==========================================================================
  @Test
  void 上游读不到时如实报未接入且不编条目() throws Exception {
    tradeDown = true;
    String run = rebuild("session");
    assertThat(run).contains("\"status\":\"PARTIAL\"");
    // 读不到的原因**原样**写进了运行记录（不是一句笼统的「失败」）。
    assertThat(run).contains("ORDER").contains("订单事实读不到");

    long orderEntries =
        inTenant(
            () ->
                sql.fetchOne(
                        "select count(*) from crm_customer_timeline_entry where source_kind = 'ORDER'")
                    .get(0, Long.class));
    assertThat(orderEntries).as("🔴 读不到上游 ⇒ 一条订单条目都不落（不编数据）").isZero();

    // 本服务那几个来源照常落库（部分来源读不到 ≠ 全部落空）：
    // 3 消息 + 1 评价 + 4 接触记录（3 条自动 + 1 条人工）。
    assertThat(countEntries()).isEqualTo(8);

    // 详情视图把「读不到」如实标出来，而不是画成 0 条。
    String detail = fetch("/crm/customers/" + customerId, "session");
    assertThat(detail).contains("\"source\":\"ORDER\"").contains("\"available\":false");
    assertThat(detail).contains("\"source\":\"MESSAGE\"").contains("\"available\":true");
  }

  // ==========================================================================
  // 🔴 客户主档没有上游标识 ⇒ 订单来源标「未接入」，仍然一条都不编
  // ==========================================================================
  @Test
  void 没有上游标识时订单来源如实报缺什么() throws Exception {
    inTenant(
        () -> {
          sql.execute(
              "update crm_customer_profile set customer_party_id = null where id = ?", customerId);
          return null;
        });
    String run = rebuild("session");
    assertThat(run).contains("\"status\":\"PARTIAL\"");
    assertThat(run).contains("customer_party_id");
    assertThat(
            inTenant(
                () ->
                    sql.fetchOne(
                            "select count(*) from crm_customer_timeline_entry where source_kind = 'ORDER'")
                        .get(0, Long.class)))
        .isZero();
  }

  // ==========================================================================
  // 🔴 未合并就是未合并；跨店铺身份关系只给线索
  // ==========================================================================
  @Test
  void 未合并且只给线索不给结论() throws Exception {
    String detail = fetch("/crm/customers/" + customerId, "session");
    assertThat(detail).contains("\"mergeState\":\"NOT_MERGED\"");
    assertThat(detail).contains("15.3.1.3");
    assertThat(detail).contains("线索");
    // 同脱敏邮箱的另一行客户主档 ⇒ 一条线索。
    assertThat(detail).contains("联系方式指纹一致");
    // 🔴 线索里**永远**是脱敏形态。
    assertThat(detail).doesNotContain("buyer@example.com");
  }

  // ==========================================================================
  // 联系方式默认脱敏；明文要显式授权
  // ==========================================================================
  @Test
  void 联系方式默认脱敏且明文要显式授权() throws Exception {
    String masked = fetch("/crm/customers/" + customerId, "session");
    assertThat(masked).contains("b***@***.com").contains("138****1234");
    assertThat(masked).doesNotContain("buyer@example.com");

    // 没有 pii 权限还硬要明文 ⇒ 403（不是悄悄退回脱敏）。
    http.perform(
            get("/crm/customers/" + customerId + "?revealContact=true")
                .header("Authorization", "session")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());

    // 有 pii 权限 ⇒ 明文。
    String plain = fetch("/crm/customers/" + customerId + "?revealContact=true", "session-pii");
    assertThat(plain).contains("buyer@example.com").contains("\"contactMasked\":false");
  }

  // ==========================================================================
  // 禁浮点与时区：金额逐位相同，时刻写回等于同一绝对时刻
  // ==========================================================================
  @Test
  void 金额逐位相同且时刻不静默偏移() throws Exception {
    rebuild("session");
    var row =
        inTenant(
            () ->
                sql.fetchOne(
                    "select amount, currency, occurred_at from crm_customer_timeline_entry"
                        + " where source_kind = 'ORDER'"));
    assertThat(row).isNotNull();
    // 10 位小数逐位相同：任何一次 double 往返都会变成 12345678.1234567890 → 12345678.12345679。
    assertThat(row.get("amount", BigDecimal.class)).isEqualByComparingTo(orderAmount);
    assertThat(row.get("amount", BigDecimal.class).toPlainString()).isEqualTo("12345678.1234");
    assertThat(row.get("currency", String.class)).isEqualTo("USD");
    // 写回的时刻是**同一个绝对时刻**（+08:00 的 08:00 == UTC 的 00:00）。
    OffsetDateTime stored = row.get("occurred_at", OffsetDateTime.class);
    assertThat(stored.toInstant()).isEqualTo(orderAt.toInstant());
    // 不许偏 −28800 秒（东八区静默偏移的经典症状）。
    assertThat(stored.toInstant().getEpochSecond()).isEqualTo(orderAt.toInstant().getEpochSecond());
  }

  // ==========================================================================
  // 不存在的客户 ⇒ 404；只读角色不能重建
  // ==========================================================================
  @Test
  void 不存在的客户是404且只读角色不能重建() throws Exception {
    http.perform(
            get("/crm/customers/" + UUID.randomUUID())
                .header("Authorization", "session")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound());

    http.perform(
            post("/crm/customers/" + customerId + "/timeline/rebuild")
                .header("Authorization", "session-readonly")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }

  // ==========================================================================
  // 小工具
  // ==========================================================================

  private long countEntries() {
    return inTenant(
        () ->
            sql.fetchOne("select count(*) from crm_customer_timeline_entry").get(0, Long.class));
  }

  /**
   * 从时间线响应里抽出条目 id。
   *
   * <p>🔴 用 Jackson 解析，**不用正则**：响应里除了 {@code entries[].id} 还有
   * {@code lastRun.id}，正则会把运行记录的 id 也当成条目 id 抓进来 ——
   * 「分页不重不漏」的断言就变成在断言一个假集合。
   */
  private static List<String> idsOf(String body) throws Exception {
    List<String> ids = new ArrayList<>();
    for (var node : json.readTree(body).get("entries")) ids.add(node.get("id").asText());
    return ids;
  }

  /** 从时间线响应里抽出发生时刻（按出现顺序；按**绝对时刻**比大小，不比字符串）。 */
  private static List<OffsetDateTime> timesOf(String body) throws Exception {
    List<OffsetDateTime> times = new ArrayList<>();
    for (var node : json.readTree(body).get("entries")) {
      times.add(OffsetDateTime.parse(node.get("occurredAt").asText()));
    }
    return times;
  }
}
