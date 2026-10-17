package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.5.2.1 履约质量。
 *
 * <p>本测试是 <b>V155 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，
 * 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不只直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p><b>零替身</b>：本模块五个上游（9.5.1.1 order_timeliness_fact、
 * V119 order_fulfillment_line_progress 与 order_cancellation_request、
 * 9.4.1.2 shipment_record、V99 sales_order 族、V152 fulfillment_request_*）
 * <b>全在 trade 自己的库里</b>，所以夹具直接以表所有者身份落真库，
 * 不需要起任何上游 HTTP 替身。
 *
 * <p><b>纠纷率与退货率的上游在别的服务的独立库</b>，按模块边界纪律禁止跨服务 SQL，
 * 所以本测试用**真实端点登记**（POST /after-sale-signals）来产生信号 ——
 * 那正是当前唯一诚实的来源形状，不是 mock。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>夹具里的时刻一律 pin 死</b>，且刻意用 {@code +08:00} 非 UTC 偏移：
 * 用 {@code now()} 构造会让同一个用例只在某个钟点通过；
 * 而只有真偏移才能把「写回时刻偏 −28800s」这条闸门测出来。
 *
 * <p><b>本测试要钉住的十一组</b>：
 * <ol>
 *   <li><b>六个指标各自的分子/分母断言精确分数</b>（不是「&gt; 0」）：50 / 66.666667 / 25 / 33.333333；</li>
 *   <li><b>分母为 0 ⇒ rate 为 null</b>，且不是 0 也不是 100；</li>
 *   <li><b>明细下钻能看到构成该指标的明细单</b>，含 DENOMINATOR_ONLY 的 UNSET 票；</li>
 *   <li><b>时间窗归一化</b>：回显写回的时刻值逐字等于夹具的 +08:00 偏移；</li>
 *   <li><b>维度对比是真 GROUP BY</b>，平台级与人员级都能切；</li>
 *   <li><b>登记即真算</b>：登记错发/纠纷/退货后对应指标立刻变；</li>
 *   <li><b>DB 级闸门</b>：观测值脱离分母 / 采集时刻早于发生 / 来源无引用 / 行号成对；</li>
 *   <li><b>append-only 两层</b>：权限 42501 + 触发器 23514；</li>
 *   <li><b>FORCE RLS 逐张</b>且 starcon_app 拿不到；</li>
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户；</li>
 *   <li><b>统计模块特有：两租户各自的指标互不可见</b> —— 聚合查询最容易把 RLS 漏掉。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class FulfillmentQualityIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  // ---------------------------------------------------------------- 夹具时刻（全部 pin 死，刻意 +08:00）
  static final OffsetDateTime SUBMITTED = OffsetDateTime.parse("2026-03-01T08:00:00+08:00");
  static final OffsetDateTime SHIPPED_AT = OffsetDateTime.parse("2026-03-02T12:30:00+08:00");
  static final OffsetDateTime SLA_ONTIME = OffsetDateTime.parse("2026-03-03T00:00:00+08:00");
  static final OffsetDateTime SLA_LATE = OffsetDateTime.parse("2026-03-01T00:00:00+08:00");
  static final OffsetDateTime DECIDED_AT = OffsetDateTime.parse("2026-03-03T10:00:00+08:00");

  /** 统计窗口。夹具全部落在 [FROM, TO) 内；EMPTY_* 落在窗口外，用来测「分母 0」。 */
  static final String FROM = "2026-03-01T00:00:00+08:00";
  static final String TO = "2026-03-10T00:00:00+08:00";
  static final String EMPTY_FROM = "2026-06-01T00:00:00+08:00";
  static final String EMPTY_TO = "2026-06-02T00:00:00+08:00";
  static final OffsetDateTime FROM_TIME = OffsetDateTime.parse(FROM);
  static final OffsetDateTime TO_TIME = OffsetDateTime.parse(TO);
  static final OffsetDateTime EMPTY_FROM_TIME = OffsetDateTime.parse(EMPTY_FROM);
  static final OffsetDateTime EMPTY_TO_TIME = OffsetDateTime.parse(EMPTY_TO);

  /** 落在窗口内的「登记时刻」夹具，刻意 pin 死。 */
  static final String OCCURRED_AT = "2026-03-02T00:00:00+08:00";

  // 租户 A 的四单，覆盖全部分支
  static final UUID o1 = UUID.randomUUID(); // MANUAL / 按时 / 发全 / 无缺陷
  static final UUID o2 = UUID.randomUUID(); // AMAZON / 超时 / 发全 / 登记错发
  static final UUID o3 = UUID.randomUUID(); // SHOPIFY / 未登记时限 / 未发全 / 已确认取消
  static final UUID o4 = UUID.randomUUID(); // MANUAL / 无任何进度投影 = 没测过
  static final UUID skuA = UUID.randomUUID(), skuB = UUID.randomUUID();
  static final UUID member = UUID.randomUUID();

  static final List<String> allPermissions =
      java.util.stream.Stream.of("fulfillment")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
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
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("trade.fulfillment.read")))));
      identity.start();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Autowired MockMvc mvc;

  // ================================================================ 夹具

  /**
   * 以**表所有者**身份把上游五张表落成真数据。
   *
   * <p>FORCE RLS 对表所有者同样生效，所以每段插入前都要显式绑定租户 GUC。
   */
  static void seed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + a + "',false)");

      // —— 订单（V99）。刻意先全部按 DRAFT 落，补完行与地址再提交：
      //     V99 的 guard_sales_order_submitted 规定「已提交的订单必须已有商品行」，
      //     guard_sales_order_totals 又规定「头侧金额必须与商品行真值一致」。
      insertOrder(s, a, o1, "SO-Q1", "MANUAL", "OFFLINE_STORE", "10.00");
      insertOrder(s, a, o2, "SO-Q2", "AMAZON", "", "15.00");
      insertOrder(s, a, o3, "SO-Q3", "SHOPIFY", "", "25.00");
      insertOrder(s, a, o4, "SO-Q4", "MANUAL", "B2B", "5.00");

      insertLine(s, a, o1, skuA, "SKU-A", 2);
      insertLine(s, a, o2, skuB, "SKU-B", 3);
      insertLine(s, a, o3, skuA, "SKU-A", 5);
      insertLine(s, a, o4, skuB, "SKU-B", 1);
      for (UUID o : List.of(o1, o2, o3, o4)) {
        insertParty(s, a, o);
        insertAddress(s, a, o);
        submit(s, a, o);
      }

      // —— 订单行履约进度（V119）：完整发货率与漏发推导的来源。
      //     O1/O2 发全；O3 只发了 2/5（漏发，**系统推导得到**，不靠登记）。
      insertProgress(s, a, o1, 2, 2);
      insertProgress(s, a, o2, 3, 3);
      insertProgress(s, a, o3, 5, 2);
      // O4 刻意没有进度投影 = 没测过，不是「不完整」。

      // —— 时效事实（V131）：发货及时率的来源。
      //     O1 按时、O2 超时、O3 未登记时限（UNSET，不进分母）。
      insertTimeliness(s, a, o1, "SO-Q1", SLA_ONTIME);
      insertTimeliness(s, a, o2, "SO-Q2", SLA_LATE);
      insertTimeliness(s, a, o3, "SO-Q3", null);

      // —— 发货记录（V127）：纠纷率 / 退货率的分母来源。O4 没有发货记录。
      insertRecord(s, a, o1, "SO-Q1");
      insertRecord(s, a, o2, "SO-Q2");
      insertRecord(s, a, o3, "SO-Q3");

      // —— 取消申请（V119）：取消率的分子来源。
      //     O3 已确认（进分子）；O2 只是申请中（**不进**分子 —— 还可能被驳回）。
      insertCancel(s, a, o2, "REQUESTED");
      insertCancel(s, a, o3, "CONFIRMED");

      // —— 人员维度（V152）：认领人经 order_ref 对齐 order_no。
      //     O1 → OP-A；O2/O3 → OP-B；O4 没有作业 → 落「未认领」桶。
      insertRequest(s, a, "FR-Q1", "SO-Q1", "OP-A");
      insertRequest(s, a, "FR-Q2", "SO-Q2", "OP-B");
      insertRequest(s, a, "FR-Q3", "SO-Q3", "OP-B");

      // —— 租户 B：同样落一整套真实数据，否则「互不可见」分不清是隔离生效还是 B 根本没数据
      s.execute("select set_config('app.current_tenant','" + b + "',false)");
      UUID ob = UUID.randomUUID();
      insertOrder(s, b, ob, "SO-TENANT-B", "AMAZON", "", "10.00");
      insertLine(s, b, ob, skuA, "SKU-A", 2);
      insertParty(s, b, ob);
      insertAddress(s, b, ob);
      submit(s, b, ob);
      insertProgress(s, b, ob, 2, 2);
      insertTimeliness(s, b, ob, "SO-TENANT-B", SLA_LATE);
      insertRecord(s, b, ob, "SO-TENANT-B");
      s.execute("select set_config('app.current_tenant','" + a + "',false)");
    }
  }

  static void insertOrder(
      Statement s, UUID tenant, UUID id, String no, String source, String manualKind, String amount)
      throws Exception {
    // 头侧金额按「每单一行 × 单价 5.00」落，与商品行真值一致。
    s.execute(
        "insert into sales_order (id,tenant_id,order_no,order_source,manual_kind,currency,"
            + " payment_method,status,subtotal_amount,grand_total_amount)"
            + " values ('" + id + "','" + tenant + "','" + no + "','" + source + "','" + manualKind
            + "','USD','PREPAID','DRAFT'," + amount + "," + amount + ")");
  }

  static void insertParty(Statement s, UUID tenant, UUID orderId) throws Exception {
    s.execute(
        "insert into sales_order_party_snapshot (id,tenant_id,order_id,customer_party_id,"
            + " customer_code,customer_name,customer_type)"
            + " values (gen_random_uuid(),'" + tenant + "','" + orderId + "',gen_random_uuid(),"
            + "'C-1','测试客户','INDIVIDUAL')");
  }

  static void insertAddress(Statement s, UUID tenant, UUID orderId) throws Exception {
    s.execute(
        "insert into order_address_snapshot (id,tenant_id,order_id,address_role,"
            + " recipient_name,country_code,address_line1)"
            + " values (gen_random_uuid(),'" + tenant + "','" + orderId + "','SHIPPING',"
            + "'测试收件人','US','1 Test Street')");
  }

  static void submit(Statement s, UUID tenant, UUID id) throws Exception {
    s.execute(
        "update sales_order set status='SUBMITTED',submitted_at=" + ts(SUBMITTED)
            + " where id='" + id + "' and tenant_id='" + tenant + "'");
  }

  static void insertLine(Statement s, UUID tenant, UUID orderId, UUID skuId, String code, int qty)
      throws Exception {
    s.execute(
        "insert into sales_order_line (id,tenant_id,order_id,line_no,sku_id,sku_code,sku_name,"
            + " quantity,unit_price,line_amount)"
            + " values (gen_random_uuid(),'" + tenant + "','" + orderId + "',1,'" + skuId
            + "','" + code + "','测试商品'," + qty + ",5.00," + (qty * 5) + ".00)");
  }

  static void insertProgress(Statement s, UUID tenant, UUID orderId, double ordered, double shipped)
      throws Exception {
    s.execute(
        "insert into order_fulfillment_line_progress (id,tenant_id,order_id,order_line_id,"
            + " ordered_qty,reserved_qty,picking_qty,shipped_qty,shipped_at,progress_source,observed_at)"
            + " select gen_random_uuid(),'" + tenant + "','" + orderId + "',l.id,"
            + ordered + ",0,0," + shipped + "," + ts(SHIPPED_AT) + ",'TENANT_MAINTAINED',"
            + ts(SHIPPED_AT)
            + " from sales_order_line l where l.order_id='" + orderId + "' limit 1");
  }

  static void insertTimeliness(
      Statement s, UUID tenant, UUID orderId, String orderNo, OffsetDateTime slaDue) throws Exception {
    // fact_no 租户内唯一，所以按订单号派生而不是写死一个字面量。
    s.execute(
        "insert into order_timeliness_fact (id,tenant_id,fact_no,source_record_id,source_record_no,"
            + " sales_order_id,sales_order_no,shop_ref,warehouse_ref,carrier_code,carrier_name,"
            + " handed_over_at,ship_sla_deadline_at,ship_sla_source,synced_at)"
            + " values (gen_random_uuid(),'" + tenant + "','TL-" + orderNo
            + "',gen_random_uuid(),'SR-FIX','"
            + orderId + "','" + orderNo + "','SHOP-A','WH-A','DHL','DHL'," + ts(SHIPPED_AT) + ","
            + (slaDue == null ? "null" : ts(slaDue)) + ","
            + (slaDue == null ? "''" : "'MANUAL_ATTESTATION'") + "," + ts(SUBMITTED) + ")");
  }

  static void insertRecord(Statement s, UUID tenant, UUID orderId, String orderNo) throws Exception {
    UUID id = UUID.randomUUID();
    s.execute(
        "insert into shipment_record (id,tenant_id,record_no,source_handover_id,"
            + " source_packing_order_id,source_outbound_order_id,warehouse_id,warehouse_ref,"
            + " carrier_code,carrier_name,master_waybill_no,shipped_at,parcel_count,"
            + " sales_order_id,sales_order_no,shop_ref,synced_at)"
            + " values ('" + id + "','" + tenant + "','SR-" + id.toString().substring(0, 8)
            + "',gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),'WH-A',"
            + "'DHL','DHL','WB-" + id + "'," + ts(SHIPPED_AT) + ",1,'" + orderId + "','" + orderNo
            + "','SHOP-A'," + ts(SUBMITTED) + ")");
  }

  static void insertCancel(Statement s, UUID tenant, UUID orderId, String status) throws Exception {
    boolean decided = "CONFIRMED".equals(status) || "REJECTED".equals(status);
    s.execute(
        "insert into order_cancellation_request (id,tenant_id,order_id,order_no,cancel_scope,"
            + " status,reason,currency,requested_by_membership_id,requested_at,"
            + " decided_by_membership_id,decided_at)"
            + " values (gen_random_uuid(),'" + tenant + "','" + orderId + "','FIX','FULL_ORDER','"
            + status + "','测试取消原因','USD','" + member + "'," + ts(SUBMITTED) + ","
            + (decided ? "'" + member + "'" : "null") + ","
            + (decided ? ts(DECIDED_AT) : "null") + ")");
  }

  static void insertRequest(Statement s, UUID tenant, String code, String orderRef, String assignee)
      throws Exception {
    UUID reqId = UUID.randomUUID();
    // 刻意停在 DRAFT / RESERVE：V152 的 guard_fulfillment_request_ladder 规定
    // 「推进到 WORK 前必须有已完成的 RESERVE 步」。本用例只需要这条请求存在，
    // 好让作业挂上认领人（人员维度的唯一用途），不需要它真的跑完编排。
    s.execute(
        "insert into fulfillment_request (id,tenant_id,request_code,order_ref,routing_decision_ref,"
            + " sku_ref,warehouse_ref,legal_entity_code,destination_country_code,requested_qty,"
            + " status,current_step,idempotency_key,requested_at,deadline_at,last_step_at,"
            + " created_by_membership_id)"
            + " values ('" + reqId + "','" + tenant + "','" + code + "','" + orderRef
            + "','RD-1','SKU-X','WH-A','LE-CN','US',1,'DRAFT','RESERVE','IK-" + code + "',"
            + ts(SUBMITTED) + "," + ts(DECIDED_AT) + "," + ts(SHIPPED_AT) + ",'" + member + "')");
    s.execute(
        "insert into fulfillment_request_work_order (id,tenant_id,request_id,work_order_code,"
            + " task_type,warehouse_ref,sku_ref,qty,status,assignee_ref,idempotency_key,"
            + " created_by_membership_id)"
            + " values (gen_random_uuid(),'" + tenant + "','" + reqId + "','" + code
            + "-W1','PACK','WH-A','SKU-X',1,'ASSIGNED','" + assignee + "','IK-" + code
            + "-W1','" + member + "')");
  }

  /** 时刻一律用**带时区偏移**的显式 timestamptz 字面量（不依赖会话 TimeZone）。 */
  static String ts(OffsetDateTime t) {
    return "'" + t.toString() + "'::timestamptz";
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-only");
    // 只有复用同一条连接，才能真正证明归还后不串租户
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  /**
   * 每个用例前清库重种。
   *
   * <p>时机必须在 Spring 上下文起来<b>之后</b>：Flyway 随上下文启动跑，
   * 静态初始化块执行时上游表还不存在。
   */
  @BeforeEach
  void resetSeed() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate fulfillment_quality_event, fulfillment_quality_alert,"
              + " fulfillment_quality_alert_rule, fulfillment_quality_fulfillment_exception,"
              + " fulfillment_quality_after_sale_signal, order_timeliness_fact,"
              + " order_cancellation_request, order_fulfillment_line_progress,"
              + " fulfillment_request_work_order, fulfillment_request,"
              + " shipment_record, sales_order_line, order_address_snapshot,"
              + " sales_order_party_snapshot, sales_order cascade");
    }
    seed();
  }

  // ================================================================ HTTP 辅助

  ResultActions req(String path, String who) throws Exception {
    return mvc.perform(get("/trade/fulfillment/quality" + path).header("Authorization", who));
  }

  JsonNodeObject getJson(String path, String who) throws Exception {
    return new JsonNodeObject(req(path, who).andExpect(status().isOk()));
  }

  /**
   * 统计窗口**首段**（路径后直接跟）：用于 /report 这类无其它参数的路径。
   */
  static String window() {
    return "?from=" + enc(FROM) + "&to=" + enc(TO);
  }

  /**
   * 统计窗口**后续段**：用于已经带 query 的路径。
   *
   * <p>刻意不用一个共用方法拼两种前缀 —— 拼错的后果是
   * {@code ?metric=X?from=...} 这种废 URL：服务层收到「from 参数缺失」，
   * 于是默认窗口生效、指标全为 0，失败信息完全不指向真正的原因。
   */
  static String andWindow() {
    return "&from=" + enc(FROM) + "&to=" + enc(TO);
  }

  /**
   * 时刻原样拼进查询串，<b>不做百分号编码</b>。
   *
   * <p>MockMvc 不会对查询参数做 URL 解码：编码过的 {@code %2B} 会原封不动地
   * 进 {@code OffsetDateTime} 解析器并失败（unparsed text at index 19）。
   * 用 {@code URLEncoder} 更糟 —— 它按表单规则把空格编成 {@code +}，
   * 而 {@code +} 在 RFC 3986 查询串里是合法字面量，于是夹具里的 {@code +08:00}
   * 偏移会被解成空格，报出一个和真实原因毫无关系的 400。
   */
  static String enc(String s) {
    return s;
  }

  /** 极简 JSON 取值包装：只取本测试需要的字段，避免引入额外依赖。 */
  record JsonNodeObject(ResultActions res) {
    com.fasterxml.jackson.databind.JsonNode node() throws Exception {
      String body = res.andReturn().getResponse().getContentAsString();
      return json.readTree(body);
    }

    long at(String path) throws Exception {
      com.fasterxml.jackson.databind.JsonNode n = node().at(path);
      assertThat(n.isNumber()).as("期望数字：" + path + "，实得 " + n).isTrue();
      return n.asLong();
    }

    String text(String path) throws Exception {
      com.fasterxml.jackson.databind.JsonNode n = node().at(path);
      return n.isMissingNode() ? null : n.asText();
    }

    boolean isNull(String path) throws Exception {
      return node().at(path).isNull();
    }

    int size(String path) throws Exception {
      return node().at(path).size();
    }
  }

  /** 取指标卡里某个指标的 JSON 节点（按 metricCode 定位，不靠下标）。 */
  com.fasterxml.jackson.databind.JsonNode metric(JsonNodeObject report, String code)
      throws Exception {
    for (com.fasterxml.jackson.databind.JsonNode n : report.node().at("/metrics")) {
      if (code.equals(n.get("metricCode").asText())) {
        return n;
      }
    }
    throw new AssertionError("指标卡里没有 " + code);
  }

  /** 登记一笔售后信号（走真端点，不是直接插库）。 */
  void postSignal(UUID orderId, String type, String occurredAt) throws Exception {
    mvc.perform(post("/trade/fulfillment/quality/after-sale-signals")
            .header("Authorization", "Bearer tenant-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"orderId\":\"" + orderId + "\",\"occurredAt\":\"" + occurredAt
                    + "\",\"signalType\":\"" + type + "\"}"))
        .andExpect(status().isOk());
  }

  void postException(UUID orderId, String type, String reportedAt) throws Exception {
    mvc.perform(post("/trade/fulfillment/quality/exceptions")
            .header("Authorization", "Bearer tenant-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"orderId\":\"" + orderId + "\",\"exceptionType\":\"" + type
                    + "\",\"reportedAt\":\"" + reportedAt + "\"}"))
        .andExpect(status().isOk());
  }

  void putRule(String metric, String threshold, String comparison) throws Exception {
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/trade/fulfillment/quality/alert-rules/" + metric)
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"thresholdValue\":" + threshold + ",\"comparison\":\"" + comparison
                        + "\",\"severity\":\"WARN\"}"))
        .andExpect(status().isOk());
  }

  /**
   * 评估预警。刻意传夹具窗口：默认窗口是「最近 30 天」，而夹具 pin 在 2026-03，
   * 用默认窗口会一个样本都圈不到，于是「没触发」看起来像是阈值不对。
   */
  JsonNodeObject evaluate() throws Exception {
    return evaluate(FROM_TIME, TO_TIME);
  }

  JsonNodeObject evaluate(OffsetDateTime from, OffsetDateTime to) throws Exception {
    String body =
        from == null
            ? "{}"
            : "{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    var res =
        mvc.perform(post("/trade/fulfillment/quality/alerts/evaluate")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());
    return new JsonNodeObject(res);
  }

  // ================================================================ 一、六个指标的口径

  @Test
  @DisplayName("发货及时率＝按时 1 ÷（按时 1 + 超时 1）＝50.000000，未登记时限独立报数不进分母")
  void shipOnTimeRateUsesOnTimeOverDueAsBasis() throws Exception {
    postSignal(o1, "DISPUTE", OCCURRED_AT);
    postSignal(o2, "RETURN", OCCURRED_AT);
    var m = metric(getJson("/report" + window(), "Bearer tenant-a"), "SHIP_ON_TIME_RATE");
    assertThat(m.get("numeratorCount").asLong()).isEqualTo(1L);
    assertThat(m.get("denominatorCount").asLong()).isEqualTo(2L);
    assertThat(m.get("excludedFromDenominator").asLong()).isEqualTo(1L); // O3 的 UNSET
    assertThat(m.get("rate").decimalValue()).isEqualByComparingTo("50.000000");
  }

  @Test
  @DisplayName("完整发货率按订单行：发全 2 ÷ 有进度投影 3 ＝66.666667")
  void completeShipmentRateIsPerOrderLine() throws Exception {
    var m = metric(getJson("/report" + window(), "Bearer tenant-a"), "COMPLETE_SHIPMENT_RATE");
    assertThat(m.get("numeratorCount").asLong()).isEqualTo(2L);
    assertThat(m.get("denominatorCount").asLong()).isEqualTo(3L);
    assertThat(m.get("rate").decimalValue()).isEqualByComparingTo("66.666667");
  }

  @Test
  @DisplayName("错发漏发率：漏发可推导（O3 已发 2 < 下单 5），登记错发后分子变 2")
  void wrongOrMissingRateCombinesDerivedAndReported() throws Exception {
    var before = metric(getJson("/report" + window(), "Bearer tenant-a"), "WRONG_OR_MISSING_RATE");
    // 只有系统推导出来的漏发：O3 一单
    assertThat(before.get("numeratorCount").asLong()).isEqualTo(1L);
    assertThat(before.get("denominatorCount").asLong()).isEqualTo(3L);

    postException(o2, "WRONG_ITEM", OCCURRED_AT);
    var after = metric(getJson("/report" + window(), "Bearer tenant-a"), "WRONG_OR_MISSING_RATE");
    assertThat(after.get("numeratorCount").asLong()).isEqualTo(2L);
    assertThat(after.get("denominatorCount").asLong()).isEqualTo(3L);
    assertThat(after.get("rate").decimalValue()).isEqualByComparingTo("66.666667");
  }

  @Test
  @DisplayName("取消率只认 CONFIRMED：申请中的 O2 不进分子，分母是窗口内已提交 4 单")
  void cancellationRateOnlyCountsConfirmed() throws Exception {
    var m = metric(getJson("/report" + window(), "Bearer tenant-a"), "CANCELLATION_RATE");
    assertThat(m.get("numeratorCount").asLong()).isEqualTo(1L); // 只 O3
    assertThat(m.get("denominatorCount").asLong()).isEqualTo(4L); // O1..O4
    assertThat(m.get("rate").decimalValue()).isEqualByComparingTo("25.000000");
  }

  @Test
  @DisplayName("纠纷率与退货率共用「已发货订单」分母，登记后立刻变 —— 不是硬编码的 0")
  void disputeAndReturnAreReallyAggregated() throws Exception {
    var empty = getJson("/report" + window(), "Bearer tenant-a");
    // 没登记任何信号时：分母是真实的三张发货记录，但分子为 0（这是真 0，不是缺口）
    var d0 = metric(empty, "DISPUTE_RATE");
    assertThat(d0.get("numeratorCount").asLong()).isEqualTo(0L);
    assertThat(d0.get("denominatorCount").asLong()).isEqualTo(3L);
    assertThat(d0.get("rate").decimalValue()).isEqualByComparingTo("0.000000");

    postSignal(o1, "DISPUTE", OCCURRED_AT);
    postSignal(o2, "RETURN", OCCURRED_AT);

    var filled = getJson("/report" + window(), "Bearer tenant-a");
    var d1 = metric(filled, "DISPUTE_RATE");
    assertThat(d1.get("numeratorCount").asLong()).isEqualTo(1L);
    assertThat(d1.get("denominatorCount").asLong()).isEqualTo(3L);
    assertThat(d1.get("rate").decimalValue()).isEqualByComparingTo("33.333333");
    var r1 = metric(filled, "RETURN_RATE");
    assertThat(r1.get("numeratorCount").asLong()).isEqualTo(1L);
    assertThat(r1.get("denominatorCount").asLong()).isEqualTo(3L);
    assertThat(r1.get("rate").decimalValue()).isEqualByComparingTo("33.333333");
  }

  @Test
  @DisplayName("分母为 0 时 rate 是 null，绝不是 0 也不是 100")
  void zeroDenominatorYieldsNullRate() throws Exception {
    var empty =
        getJson("/report?from=" + enc(EMPTY_FROM) + "&to=" + enc(EMPTY_TO), "Bearer tenant-a");
    for (var n : empty.node().at("/metrics")) {
      assertThat(n.get("denominatorCount").asLong())
          .as(n.get("metricCode").asText() + " 在空窗口里分母应为 0")
          .isEqualTo(0L);
      assertThat(n.get("rate").isNull())
          .as(
              n.get("metricCode").asText()
                  + " 分母为 0 时 rate 必须是 null（报 0 会被读成「及时率 0%」）")
          .isTrue();
    }
  }

  // ================================================================ 二、时间窗归一化

  @Test
  @DisplayName("窗口回显写回的时刻值逐字等于夹具的 +08:00 偏移，不偏 −28800s")
  void windowEchoPreservesInstantAndOffset() throws Exception {
    var report = getJson("/report" + window(), "Bearer tenant-a");
    assertThat(report.text("/window/from")).isEqualTo("2026-03-01T00:00:00+08:00");
    assertThat(report.text("/window/to")).isEqualTo("2026-03-10T00:00:00+08:00");
    assertThat(report.text("/window/defaulted")).isEqualTo("false");
  }

  @Test
  @DisplayName("不给窗口时回显 defaulted=true，且窗口真的是最近 30 天")
  void missingWindowFallsBackToThirtyDays() throws Exception {
    var report = getJson("/report", "Bearer tenant-a");
    assertThat(report.text("/window/defaulted")).isEqualTo("true");
    var from = OffsetDateTime.parse(report.text("/window/from"));
    var to = OffsetDateTime.parse(report.text("/window/to"));
    assertThat(java.time.Duration.between(from, to).toDays()).isEqualTo(30L);
  }

  @Test
  @DisplayName("from 不早于 to 直接 400，绝不静默交换窗口")
  void reversedWindowIsRejected() throws Exception {
    req("/report?from=" + enc(TO) + "&to=" + enc(FROM), "Bearer tenant-a")
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 三、明细下钻

  @Test
  @DisplayName("下钻能看到构成该指标的明细单：UNSET 那票也在清单里，只是 DENOMINATOR_ONLY")
  void drilldownShowsTheRowsBehindTheMetric() throws Exception {
    var page = getJson("/drilldown?metric=SHIP_ON_TIME_RATE" + andWindow(), "Bearer tenant-a");
    assertThat(page.at("/total")).isEqualTo(3L); // ON_TIME + OVERDUE + UNSET
    assertThat(page.at("/numeratorCount")).isEqualTo(1L);
    assertThat(page.at("/denominatorCount")).isEqualTo(2L);

    boolean sawUnset = false;
    boolean sawNumerator = false;
    for (var item : page.node().at("/items")) {
      if ("UNSET".equals(item.get("classification").asText())) {
        sawUnset = true;
        assertThat(item.get("metricRole").asText())
            .as("UNSET 的票不进分母，但必须看得见")
            .isEqualTo("DENOMINATOR_ONLY");
      }
      if ("NUMERATOR".equals(item.get("metricRole").asText())) {
        sawNumerator = true;
      }
    }
    assertThat(sawUnset).as("未登记发货时限的那票必须出现在明细里").isTrue();
    assertThat(sawNumerator).as("分子成员也必须在明细里").isTrue();
  }

  @Test
  @DisplayName("错发/漏发下钻把「登记的」与「推导的」分列，不合并成一个缺陷数")
  void drilldownSeparatesDeclaredFromDerivedDefect() throws Exception {
    postException(o2, "WRONG_ITEM", OCCURRED_AT);
    var page = getJson("/drilldown?metric=WRONG_OR_MISSING_RATE" + andWindow(), "Bearer tenant-a");
    assertThat(page.at("/numeratorCount")).isEqualTo(2L);
    assertThat(page.at("/denominatorCount")).isEqualTo(3L);
    boolean derived = false;
    boolean declared = false;
    for (var item : page.node().at("/items")) {
      if ("MISSING_ITEM_DERIVED".equals(item.get("classification").asText())) {
        derived = true;
      }
      if ("WRONG_ITEM".equals(item.get("classification").asText())) {
        declared = true;
      }
    }
    assertThat(derived).as("系统按「已发量 < 下单量」推导出的漏发要单列").isTrue();
    assertThat(declared).as("租户登记的错发要单列").isTrue();
  }

  @Test
  @DisplayName("下钻能按维度值收窄：从平台维度那一格点进来看到的就是那一格")
  void drilldownRespectsDimensionFilter() throws Exception {
    var page =
        getJson(
            "/drilldown?metric=CANCELLATION_RATE&dimensionBy=PLATFORM&dimensionValue=SHOPIFY"
                + andWindow(),
            "Bearer tenant-a");
    assertThat(page.at("/total")).isEqualTo(1L);
    assertThat(page.at("/numeratorCount")).isEqualTo(1L);
    for (var item : page.node().at("/items")) {
      assertThat(item.get("orderSource").asText()).isEqualTo("SHOPIFY");
    }
  }

  @Test
  @DisplayName("非法指标与非法维度值都是 400，不是「查不到数据」")
  void badEnumIsBadRequest() throws Exception {
    req("/drilldown?metric=NOT_A_METRIC", "Bearer tenant-a").andExpect(status().isBadRequest());
    req("/dimensions?dimensionBy=SHOP", "Bearer tenant-a").andExpect(status().isBadRequest());
    req("/drilldown?sort=NOPE", "Bearer tenant-a").andExpect(status().isBadRequest());
  }

  // ================================================================ 四、维度对比

  @Test
  @DisplayName("店铺维度当前是平台级 order_source 的真 GROUP BY（MANUAL / AMAZON / SHOPIFY）")
  void platformDimensionReallyGroups() throws Exception {
    var page = getJson("/dimensions?dimensionBy=PLATFORM" + andWindow(), "Bearer tenant-a");
    List<String> keys = new ArrayList<>();
    for (var row : page.node().at("/items")) {
      keys.add(row.get("dimensionKey").asText());
    }
    assertThat(keys).contains("MANUAL", "AMAZON", "SHOPIFY");
    assertThat(page.node().at("/items").get(0).get("metrics")).hasSize(6);
  }

  @Test
  @DisplayName("人员维度按 assignee_ref 聚合，没认领人的落独立桶、不并进任何人")
  void personDimensionGroupsByAssignee() throws Exception {
    var page = getJson("/dimensions?dimensionBy=PERSON" + andWindow(), "Bearer tenant-a");
    List<String> keys = new ArrayList<>();
    for (var row : page.node().at("/items")) {
      keys.add(row.get("dimensionKey").asText());
    }
    assertThat(keys).contains("OP-A", "OP-B", "UNASSIGNED");
  }

  @Test
  @DisplayName("能力声明显式报四处缺口，且都点名 ownerModule")
  void capabilitiesNameEveryGap() throws Exception {
    var caps = getJson("/capabilities", "Bearer tenant-a");
    List<String> unavailable = new ArrayList<>();
    for (var d : caps.node().at("/dimensions")) {
      if (!d.get("available").asBoolean()) {
        unavailable.add(d.get("code").asText());
        assertThat(d.get("ownerModule").asText())
            .as("不可用的维度必须点名 ownerModule：" + d.get("code").asText())
            .isNotBlank();
      }
    }
    assertThat(unavailable)
        .contains("WRONG_ITEM_IDENTITY", "AFTER_SALE_DATA", "SHOP_CODE", "ACCOUNT_HEALTH_LINKAGE");
    assertThat(caps.text("/shopDimensionStatus")).contains("shopCodeAvailable=false");
  }

  // ================================================================ 五、登记是真写入

  @Test
  @DisplayName("登记售后信号落真表且带来源与采集时刻")
  void registeringSignalIsRealWrite() throws Exception {
    postSignal(o1, "DISPUTE", OCCURRED_AT);
    var page = getJson("/after-sale-signals" + window(), "Bearer tenant-a");
    assertThat(page.at("/total")).isEqualTo(1L);
    assertThat(page.at("/disputeCount")).isEqualTo(1L);
    assertThat(page.node().at("/items").get(0).get("signalSource").asText())
        .isEqualTo("TENANT_MAINTAINED");
    assertThat(page.node().at("/items").get(0).get("capturedAt").asText()).isNotBlank();
  }

  @Test
  @DisplayName("同一订单同类信号重复登记不会把分母之外多插一行")
  void signalRegistrationIsIdempotentOnSameOccurrence() throws Exception {
    postSignal(o1, "DISPUTE", OCCURRED_AT);
    mvc.perform(post("/trade/fulfillment/quality/after-sale-signals")
            .header("Authorization", "Bearer tenant-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"orderId\":\"" + o1 + "\",\"occurredAt\":\"" + OCCURRED_AT
                    + "\",\"signalType\":\"DISPUTE\"}"))
        .andExpect(status().is4xxClientError());
    assertThat(getJson("/after-sale-signals" + window(), "Bearer tenant-a").at("/total"))
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("不存在或不属于自己的订单登记直接 404，不静默成功")
  void unknownOrderIsNotFound() throws Exception {
    mvc.perform(post("/trade/fulfillment/quality/after-sale-signals")
            .header("Authorization", "Bearer tenant-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"orderId\":\"" + UUID.randomUUID()
                    + "\",\"occurredAt\":\"" + OCCURRED_AT + "\",\"signalType\":\"DISPUTE\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("错发与漏发分列报数，不合并成一个缺陷数")
  void exceptionsReportTwoBuckets() throws Exception {
    postException(o2, "WRONG_ITEM", OCCURRED_AT);
    postException(o3, "MISSING_ITEM", OCCURRED_AT);
    var page = getJson("/exceptions" + window(), "Bearer tenant-a");
    assertThat(page.at("/total")).isEqualTo(2L);
    assertThat(page.at("/wrongItemCount")).isEqualTo(1L);
    assertThat(page.at("/missingItemCount")).isEqualTo(1L);
  }

  @Test
  @DisplayName("事件流如实留痕，不是空数组")
  void eventsAreRecorded() throws Exception {
    postException(o2, "WRONG_ITEM", OCCURRED_AT);
    var page = getJson("/events", "Bearer tenant-a");
    assertThat(page.at("/total")).isGreaterThanOrEqualTo(1L);
  }

  // ================================================================ 六、预警

  @Test
  @DisplayName("越限才留预警；重复评估幂等（同一规则+窗口+维度不堆第二条）")
  void alertsTriggerOnceWhenThresholdBreached() throws Exception {
    putRule("SHIP_ON_TIME_RATE", "80", "LT");
    putRule("RETURN_RATE", "90", "GT"); // 退货率真实值为 0 ⇒ 不越限

    var first = evaluate();
    assertThat(first.at("/evaluated")).isGreaterThanOrEqualTo(2L);
    assertThat(first.at("/triggered")).isEqualTo(1L);
    var alerts = getJson("/alerts", "Bearer tenant-a");
    assertThat(alerts.at("/total")).isEqualTo(1L);
    var row = alerts.node().at("/items").get(0);
    assertThat(row.get("metricCode").asText()).isEqualTo("SHIP_ON_TIME_RATE");
    // 越限时分子分母一并冻结：事后答得出「当时是几除以几」
    assertThat(row.get("numerator").asLong()).isEqualTo(1L);
    assertThat(row.get("denominator").asLong()).isEqualTo(2L);
    assertThat(row.get("observedValue").decimalValue()).isEqualByComparingTo("50.000000");

    evaluate();
    assertThat(getJson("/alerts", "Bearer tenant-a").at("/total")).isEqualTo(1L);
  }

  @Test
  @DisplayName("分母为 0 的指标不产生预警 —— 没有样本不是指标变差")
  void zeroDenominatorNeverTriggersAlert() throws Exception {
    putRule("DISPUTE_RATE", "1", "GT");
    var res = evaluate(EMPTY_FROM_TIME, EMPTY_TO_TIME);
    assertThat(res.at("/triggered")).isEqualTo(0L);
    assertThat(res.at("/skippedNoBasis")).isGreaterThanOrEqualTo(1L);
    assertThat(getJson("/alerts", "Bearer tenant-a").at("/total")).isEqualTo(0L);
  }

  @Test
  @DisplayName("阈值方向显式：同一条规则方向配反就越限 —— 及时率高越好却用「大于」会误报")
  void alertComparisonDirectionIsExplicit() throws Exception {
    // 及时率真实 50。方向配反成「大于 40」⇒ 50 > 40 ⇒ 越限（这条本不该报）
    putRule("SHIP_ON_TIME_RATE", "40", "GT");
    // 错发漏发率真实 33.333333。方向正确（越低越好，用「大于 50」）⇒ 不越限
    putRule("WRONG_OR_MISSING_RATE", "50", "GT");
    var res = evaluate();
    assertThat(res.at("/triggered"))
        .as("方向是显式声明的：配反了就会把「做得好」报成「越限」")
        .isEqualTo(1L);
    var alerts = getJson("/alerts", "Bearer tenant-a");
    assertThat(alerts.node().at("/items").get(0).get("metricCode").asText())
        .isEqualTo("SHIP_ON_TIME_RATE");
  }

  @Test
  @DisplayName("确认预警落事件、不改预警行（append-only）")
  void acknowledgeWritesEventNotUpdate() throws Exception {
    putRule("CANCELLATION_RATE", "10", "GT");
    evaluate();
    var before = getJson("/alerts", "Bearer tenant-a");
    assertThat(before.at("/total")).isEqualTo(1L);
    String id = before.node().at("/items").get(0).get("id").asText();

    mvc.perform(post("/trade/fulfillment/quality/alerts/{id}/acknowledge", id)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    var after = getJson("/alerts", "Bearer tenant-a");
    assertThat(after.at("/openCount")).isEqualTo(0L);
    assertThat(after.node().at("/items").get(0).get("acknowledged").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("阈值配置幂等：一个指标只有一条生效规则")
  void alertRuleUpsertIsIdempotent() throws Exception {
    putRule("DISPUTE_RATE", "5", "GT");
    putRule("DISPUTE_RATE", "7", "GT");
    var rules = getJson("/alert-rules", "Bearer tenant-a");
    int disputeRules = 0;
    for (var r : rules.node()) {
      if ("DISPUTE_RATE".equals(r.get("metricCode").asText())) {
        disputeRules++;
        assertThat(r.get("thresholdValue").decimalValue()).isEqualByComparingTo("7");
      }
    }
    assertThat(disputeRules).isEqualTo(1);
  }

  // ================================================================ 七、DB 级闸门

  @Test
  @DisplayName("采集时刻早于发生时刻被 DB 拒，不是服务层自觉")
  void captureBeforeOccurrenceIsRejected() {
    assertThatThrownBy(
            () ->
                asOwner(
                        "insert into fulfillment_quality_after_sale_signal"
                            + " (id,tenant_id,order_id,signal_type,occurred_at,signal_source,captured_at,"
                            + " reported_by_membership_id)"
                            + " values (gen_random_uuid(),'" + a + "','" + o1
                            + "','DISPUTE','2026-03-05T00:00:00+08:00','TENANT_MAINTAINED',"
                            + "'2026-03-01T00:00:00+08:00','" + member + "')")
                    .run())
        .hasMessageContaining("采集时刻");
  }

  @Test
  @DisplayName("同步来源必须带外部引用号：没有它事后追不出这条信号是怎么来的")
  void moduleSourceRequiresReference() {
    assertThatThrownBy(
            () ->
                asOwner(
                        "insert into fulfillment_quality_after_sale_signal"
                            + " (id,tenant_id,order_id,signal_type,occurred_at,signal_source,captured_at,"
                            + " reported_by_membership_id)"
                            + " values (gen_random_uuid(),'" + a + "','" + o1
                            + "','DISPUTE','2026-03-02T00:00:00+08:00','MODULE_11_DISPUTE',"
                            + "'2026-03-02T00:00:00+08:00','" + member + "')")
                    .run())
        .hasMessageContaining("fulfillment_quality_signal_source_ref");
  }

  @Test
  @DisplayName("缺陷登记行号与行 id 必须成对：只有行号的话行被删后指不到任何东西")
  void exceptionLinePairIsEnforced() {
    assertThatThrownBy(
            () ->
                asOwner(
                        "insert into fulfillment_quality_fulfillment_exception"
                            + " (id,tenant_id,order_id,line_no,exception_type,reported_at,"
                            + " reported_source,reported_by_membership_id)"
                            + " values (gen_random_uuid(),'" + a + "','" + o1 + "',1,'WRONG_ITEM',"
                            + "'2026-03-02T00:00:00+08:00','TENANT_MAINTAINED','" + member + "')")
                    .run())
        .hasMessageContaining("fulfillment_quality_exception_line_pair");
  }

  @Test
  @DisplayName("预警观测值脱离分母被 DB 拒：分母 0 却填了数 = 无样本时向 16 推一次「指标恶化」")
  void alertObservedValueNeedsDenominator() throws Exception {
    String rule =
        asOwnerReturn(
            "insert into fulfillment_quality_alert_rule"
                + " (id,tenant_id,metric_code,threshold_value,comparison,severity,enabled,"
                + " created_by_membership_id)"
                + " values (gen_random_uuid(),'" + a
                + "','SHIP_ON_TIME_RATE',50,'GT','WARN',true,'" + member
                + "') returning id");
    assertThatThrownBy(
            () ->
                asOwner(
                        "insert into fulfillment_quality_alert"
                            + " (id,tenant_id,rule_id,metric_code,observed_value,threshold_value,"
                            + " comparison,severity,numerator,denominator,window_from,window_to,"
                            + " triggered_at)"
                            + " values (gen_random_uuid(),'" + a + "','" + rule
                            + "','SHIP_ON_TIME_RATE',0.0,50,'GT','WARN',0,0,"
                            + "'2026-03-01T00:00:00+08:00','2026-03-02T00:00:00+08:00',now())")
                    .run())
        .hasMessageContaining("observed_value");
  }

  @Test
  @DisplayName("append-only 两层：运行角色改删凭据行被拒（权限 42501 + 触发器 23514）")
  void credentialRowsAreAppendOnly() throws Exception {
    postException(o2, "WRONG_ITEM", OCCURRED_AT);
    String id =
        getJson("/exceptions" + window(), "Bearer tenant-a")
            .node()
            .at("/items")
            .get(0)
            .get("id")
            .asText();
    assertThatThrownBy(
            () ->
                asTradeApp(
                        "update fulfillment_quality_fulfillment_exception set note='改' where id='"
                            + id + "'")
                    .run())
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () ->
                asOwner("delete from fulfillment_quality_fulfillment_exception where id='" + id + "'")
                    .run())
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () -> asTradeApp("delete from fulfillment_quality_after_sale_signal").run())
        .hasMessageContaining("permission denied");
  }

  // ================================================================ 八、RLS

  @Test
  @DisplayName("FORCE RLS 逐张打开")
  void forceRlsIsEnabledOnEveryTable() throws Exception {
    for (String table :
        List.of(
            "fulfillment_quality_after_sale_signal",
            "fulfillment_quality_fulfillment_exception",
            "fulfillment_quality_alert_rule",
            "fulfillment_quality_alert",
            "fulfillment_quality_event")) {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var st = c.createStatement()) {
        var rs =
            st.executeQuery(
                "select relforcerowsecurity, relrowsecurity from pg_class"
                    + " where relname = '" + table + "'");
        assertThat(rs.next()).isTrue();
        assertThat(rs.getBoolean(1)).as(table + " 必须 FORCE ROW LEVEL SECURITY").isTrue();
        assertThat(rs.getBoolean(2)).as(table + " 必须 ENABLE ROW LEVEL SECURITY").isTrue();
      }
    }
  }

  @Test
  @DisplayName("starcon_app 跨服务只读角色拿不到本模块任何一张表")
  void crossServiceRoleGetsNothing() {
    assertThatThrownBy(
            () -> asStarconApp("select count(*) from fulfillment_quality_after_sale_signal").run())
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () -> asStarconApp("select count(*) from fulfillment_quality_alert_rule").run())
        .hasMessageContaining("permission denied");
  }

  // ================================================================ 九、多租户

  @Test
  @DisplayName("多租户断言一：两租户互不可见")
  void twoTenantsCannotSeeEachOther() throws Exception {
    postException(o1, "WRONG_ITEM", OCCURRED_AT);
    assertThat(getJson("/exceptions" + window(), "Bearer tenant-a").at("/total")).isEqualTo(1L);
    assertThat(getJson("/exceptions" + window(), "Bearer tenant-b").at("/total"))
        .as("租户 A 登记的缺陷，租户 B 一条都看不到")
        .isEqualTo(0L);
  }

  @Test
  @DisplayName("统计模块特有断言：两租户各自的指标互不可见（聚合查询也必须受 RLS 保护）")
  void eachTenantSeesOnlyItsOwnMetrics() throws Exception {
    postSignal(o1, "DISPUTE", OCCURRED_AT);

    var a1 = getJson("/report" + window(), "Bearer tenant-a");
    var b1 = getJson("/report" + window(), "Bearer tenant-b");

    // A：单有一笔纠纷 → 1/3；B：一笔都没有 → 0/1（真 0，不是缺口）
    assertThat(metric(a1, "DISPUTE_RATE").get("denominatorCount").asLong()).isEqualTo(3L);
    assertThat(metric(a1, "DISPUTE_RATE").get("numeratorCount").asLong()).isEqualTo(1L);
    assertThat(metric(b1, "DISPUTE_RATE").get("denominatorCount").asLong()).isEqualTo(1L);
    assertThat(metric(b1, "DISPUTE_RATE").get("numeratorCount").asLong())
        .as("A 登记的那笔纠纷不许漏进 B 的分子")
        .isEqualTo(0L);

    // 维度对比同理
    var bDim = getJson("/dimensions?dimensionBy=PERSON" + andWindow(), "Bearer tenant-b");
    for (var row : bDim.node().at("/items")) {
      assertThat(row.get("dimensionKey").asText())
          .as("租户 B 的维度里不能出现租户 A 的认领人")
          .isIn("UNASSIGNED");
    }
  }

  @Test
  @DisplayName("多租户断言二：伪造 tenant header 无效")
  void forgedTenantHeaderIsIgnored() throws Exception {
    postException(o1, "WRONG_ITEM", OCCURRED_AT);
    var clean = req("/exceptions" + window(), "Bearer tenant-a").andReturn();
    String body =
        mvc.perform(get("/trade/fulfillment/quality/exceptions" + window())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .as("带伪造头的结果必须与不带完全一致（= 仍是租户 A 的数据）")
        .isEqualTo(clean.getResponse().getContentAsString());
  }

  @Test
  @DisplayName("多租户断言三：连接池归还后不串租户（池压到 1）")
  void pooledConnectionDoesNotLeakTenant() throws Exception {
    postException(o1, "WRONG_ITEM", OCCURRED_AT);
    for (int i = 0; i < 5; i++) {
      assertThat(getJson("/exceptions" + window(), "Bearer tenant-a").at("/total")).isEqualTo(1L);
      assertThat(getJson("/exceptions" + window(), "Bearer tenant-b").at("/total")).isEqualTo(0L);
    }
  }

  @Test
  @DisplayName("无权角色 403，只读角色能读不能写；未认证 401")
  void permissionsAreEnforced() throws Exception {
    req("/report", "Bearer read-only").andExpect(status().isOk());
    mvc.perform(post("/trade/fulfillment/quality/after-sale-signals")
            .header("Authorization", "Bearer read-only")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"orderId\":\"" + o1 + "\",\"occurredAt\":\"" + OCCURRED_AT
                    + "\",\"signalType\":\"DISPUTE\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/trade/fulfillment/quality/report")).andExpect(status().isUnauthorized());
  }

  // ================================================================ 直连辅助

  /**
   * 直连 SQL 的统一形状。
   *
   * <p>三个辅助都返回 {@link OwnerSql} 而**不是直接执行**：本测试里它们有两个
   * 互斥用法 —— 一半当 DB 级闸门用（要断言它抛），一半要拿返回值。
   * 返回函数式接口让两种用法都成立。代价是<b>必须显式 {@code .run()}</b>：
   * 只写 {@code asOwner(sql)} 等于构造了一个没人调用的 lambda，
   * 断言会以「没抛异常」通过 —— 闸门看起来在，其实什么都没验。
   */
  @FunctionalInterface
  interface OwnerSql {
    void run() throws Exception;
  }

  /** 以**表所有者**身份执行；FORCE RLS 对 owner 同样生效，所以显式绑定租户。 */
  static OwnerSql asOwner(String sqlText) {
    return () -> {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var st = c.createStatement()) {
        st.execute("select set_config('app.current_tenant','" + a + "',false)");
        st.execute(sqlText);
      }
    };
  }

  /** 执行并返回第一列（给需要拿 returning id 的场景用）。 */
  static String asOwnerReturn(String sqlText) throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var st = c.createStatement()) {
      st.execute("select set_config('app.current_tenant','" + a + "',false)");
      try (var rs = st.executeQuery(sqlText)) {
        assertThat(rs.next()).as("查询没有返回行：" + sqlText).isTrue();
        return rs.getString(1);
      }
    }
  }

  static OwnerSql asTradeApp(String sqlText) {
    return () -> {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "trade_app", "test-only");
          var st = c.createStatement()) {
        st.execute("select set_config('app.current_tenant','" + a + "',false)");
        st.execute(sqlText);
      }
    };
  }

  static OwnerSql asStarconApp(String sqlText) {
    return () -> {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var st = c.createStatement()) {
        st.execute("grant starcon_app to " + pg.getUsername());
      }
      // 角色切换必须在建立连接**之后**才生效
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var st = c.createStatement()) {
        st.execute("set role starcon_app");
        st.executeQuery(sqlText).close();
      }
    };
  }
}
