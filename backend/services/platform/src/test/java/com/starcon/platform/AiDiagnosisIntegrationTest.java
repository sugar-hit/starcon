package com.starcon.platform;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.platform.AiDiagnosisModels.*;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * 18.4.2.3「异常诊断」的**功能**集成测试，跑在真实 PostgreSQL 16 上。
 *
 * <p><b>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。</b>
 *
 * <p>本测试盯住八件事：
 *
 * <ol>
 *   <li><b>能力声明如实</b>：AI 推理引擎 available=false、工作台待办 available=false、
 *       五类异常各��规则都带「触发条件 + 归因依据 + 处置建议」；
 *   <li><b>五类异常都能出真结论</b>，且结论的 {@code ruleCode} 命中对应类别；
 *   <li><b>🔴 每条结论都带可下钻的依据</b>：事实表 + 字段 + 数值 + 快照时间 + 下钻引用；
 *   <li><b>依据落在真表上</b>：库存结论引用 {@code inventory_balance}、
 *       利润结论引用 {@code purchase_analytics_fact}、发货结论引用
 *       {@code order_timeliness_fact}、同步失败引用 platform 自有的
 *       {@code platform_api_call_log} / {@code platform_delivery_record}、
 *       广告结论引用 {@code ad_campaign_daily_metric}；
 *   <li><b>处置建议不能省</b>，且要指向处理页、点名归谁；
 *   <li><b>上游读不到就 503</b>（不用空集合冒充「上游没有数据」）；
 *   <li><b>内部失败源同步幂等</b>：同一个失败源事实只登记一次；
 *   <li><b>事件列表真分页 + 真筛选 + 真搜索</b>。
 * </ol>
 *
 * <p>租户隔离的三条断言在 {@code AiDiagnosisTenancyIntegrationTest} 里（同一批表、同一套夹具）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AiDiagnosisIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static HttpServer identity;
  static HttpServer reporting;
  static HttpServer warehouse;
  static HttpServer trade;
  static HttpServer channel;

  /** 🔴 **上游开不开**由测试自己控制**：读不到上游的那几格必须表现得像真的读不到。 */
  static volatile boolean reportingUp = true;
  static volatile boolean warehouseUp = true;
  static volatile boolean tradeUp = true;
  static volatile boolean channelUp = true;

  static final ObjectMapper json =
      new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /** 被诊断的 SKU / 订单 / 活动。 */
  static final String SKU = "SKU-DIAG";
  static final String ORDER = "SO-DIAG";
  static final String CAMPAIGN = "CAMP-DIAG";
  static final String ENDPOINT = "orders.list";

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute("create role platform_app login password 'test-only' nosuperuser nobypassrls");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    var permissions =
        List.of(
            "platform.ai_diagnosis.read",
            "platform.ai_diagnosis.create",
            "platform.ai_diagnosis.update");
    actors.put(
        "session-a",
        new AccessService.Access(
            UUID.randomUUID(),
            UUID.randomUUID(),
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

      // ---- 13 分析（reporting）：销量与成交金额 ----
      reporting = HttpServer.create(new InetSocketAddress(0), 0);
      reporting.createContext(
          "/reporting/sales-trend",
          e -> {
            if (!reportingUp) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            var query = e.getRequestURI().getRawQuery() == null ? "" : e.getRequestURI().getRawQuery();
            var from = query.contains("from=") ? query.substring(query.indexOf("from=") + 5).split("&")[0] : "";
            var to = query.contains("to=") ? query.substring(query.indexOf("to=") + 3).split("&")[0] : "";
            var start = LocalDate.parse(from);
            var end = LocalDate.parse(to);
            // 近 7 天：日销 20 件、单价 10；前 7 天：日销 5 件、单价 20。
            // ⇒ 销量突增（4 倍）+ 成交均价腰斩。
            var recentFrom = LocalDate.now(ZoneOffset.UTC).minusDays(6);
            var units = new ArrayList<Map<String, Object>>();
            var amounts = new ArrayList<Map<String, Object>>();
            for (var day = start; !day.isAfter(end); day = day.plusDays(1)) {
              var recent = !day.isBefore(recentFrom);
              var qty = recent ? 20 : 5;
              var amount = recent ? 200 : 100;
              units.add(Map.of("bucketStart", day.toString(), "value", qty));
              amounts.add(Map.of("bucketStart", day.toString(), "value", amount));
            }
            byte[] body =
                json.writeValueAsBytes(
                    Map.of(
                        "from", start.toString(),
                        "to", end.toString(),
                        "granularity", "DAY",
                        "status", "OK",
                        "series",
                            List.of(
                                Map.of(
                                    "metricCode", "PRODUCT_SALES_QUANTITY",
                                    "dimensionType", "PRODUCT",
                                    "dimensionKey", SKU,
                                    "dimensionLabel", "诊断测试款",
                                    "currency", "",
                                    "points", units),
                                Map.of(
                                    "metricCode", "PRODUCT_SALES_AMOUNT",
                                    "dimensionType", "PRODUCT",
                                    "dimensionKey", SKU,
                                    "dimensionLabel", "诊断测试款",
                                    "currency", "USD",
                                    "points", amounts))));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      reporting.setExecutor(Executors.newFixedThreadPool(2));
      reporting.start();

      // ---- 7 库存（warehouse）：库存头寸 ----
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/inventory/overview",
          e -> {
            if (!warehouseUp) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body =
                json.writeValueAsBytes(
                    Map.of(
                        "rows",
                            List.of(
                                Map.ofEntries(
                                    Map.entry("balanceId", UUID.randomUUID().toString()),
                                    Map.entry("skuId", UUID.randomUUID().toString()),
                                    Map.entry("skuCode", SKU),
                                    Map.entry("skuName", "诊断测试款"),
                                    Map.entry("warehouseCode", "WH-1"),
                                    Map.entry("onHand", 40),
                                    Map.entry("reserved", 38),
                                    Map.entry("available", 2),
                                    Map.entry("thresholdConfigured", true),
                                    Map.entry("maxOnHand", 30),
                                    Map.entry("overstock", true)),
                                Map.ofEntries(
                                    Map.entry("balanceId", UUID.randomUUID().toString()),
                                    Map.entry("skuId", UUID.randomUUID().toString()),
                                    Map.entry("skuCode", "SKU-OUT"),
                                    Map.entry("skuName", "断货测试款"),
                                    Map.entry("warehouseCode", "WH-1"),
                                    Map.entry("onHand", 0),
                                    Map.entry("reserved", 0),
                                    Map.entry("available", 0),
                                    Map.entry("thresholdConfigured", false),
                                    Map.entry("overstock", false))),
                        "total", 2,
                        "page", 0,
                        "size", 200));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      warehouse.setExecutor(Executors.newFixedThreadPool(2));
      warehouse.start();

      // ---- 6 采购 / 9 履约（trade）：采购成本 + 发货时效 ----
      trade = HttpServer.create(new InetSocketAddress(0), 0);
      trade.createContext(
          "/trade/purchase-analytics/facts",
          e -> {
            if (!tradeUp) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            var query = e.getRequestURI().getRawQuery() == null ? "" : e.getRequestURI().getRawQuery();
            var from = query.contains("from=") ? query.substring(query.indexOf("from=") + 5).split("&")[0] : "";
            var to = query.contains("to=") ? query.substring(query.indexOf("to=") + 3).split("&")[0] : "";
            var start = LocalDate.parse(from);
            var end = LocalDate.parse(to);
            var recentFrom = LocalDate.now(ZoneOffset.UTC).minusDays(6);
            var items = new ArrayList<Map<String, Object>>();
            for (var day = start; !day.isAfter(end); day = day.plusDays(1)) {
              // 近 7 天：采购单价 30；前 7 天：采购单价 20 ⇒ 上涨 50%。
              var amount = !day.isBefore(recentFrom) ? 300 : 200;
              items.add(
                  Map.ofEntries(
                      Map.entry("id", UUID.randomUUID().toString()),
                      Map.entry("factKind", "COST"),
                      Map.entry("sourceType", "PURCHASE_ORDER"),
                      Map.entry("sourceDocNo", "PO-" + day),
                      Map.entry("occurredDate", day.toString()),
                      Map.entry("skuCode", SKU),
                      Map.entry("skuName", "诊断测试款"),
                      Map.entry("quantity", 10),
                      Map.entry("unitPrice", amount / 10),
                      Map.entry("amount", amount),
                      Map.entry("currency", "USD")));
            }
            byte[] body =
                json.writeValueAsBytes(
                    Map.of(
                        "items", items,
                        "total", items.size(),
                        "page", 0,
                        "size", 200,
                        "note", "采购成本事实"));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      trade.createContext(
          "/trade/fulfillment/timeliness/facts",
          e -> {
            if (!tradeUp) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body =
                json.writeValueAsBytes(
                    Map.of(
                        "items",
                            List.of(
                                Map.ofEntries(
                                    Map.entry("id", UUID.randomUUID().toString()),
                                    Map.entry("factNo", "TL-DIAG"),
                                    Map.entry("salesOrderNo", ORDER),
                                    Map.entry("warehouseRef", "WH-1"),
                                    Map.entry("carrierCode", "SF"),
                                    Map.entry("orderToPackSeconds", 100000),
                                    Map.entry("packToHandoverSeconds", 60000),
                                    Map.entry("orderToHandoverSeconds", 160000),
                                    Map.entry("shipSlaState", "OVERDUE"),
                                    Map.entry("shipOverdueSeconds", 7200),
                                    Map.entry("promiseVsDeliveryState", "LATE"),
                                    Map.entry("promiseVsDeliverySeconds", 3600))),
                        "page", 0,
                        "size", 200,
                        "total", 1,
                        "totalPages", 1));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      trade.setExecutor(Executors.newFixedThreadPool(4));
      trade.start();

      // ---- 14 广告（channel）：活动日报表 ----
      channel = HttpServer.create(new InetSocketAddress(0), 0);
      channel.createContext(
          "/channel/ad-reports/rows",
          e -> {
            if (!channelUp) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            byte[] body =
                json.writeValueAsBytes(
                    Map.of(
                        "items",
                            List.of(
                                Map.ofEntries(
                                    Map.entry("dimension", "CAMPAIGN"),
                                    Map.entry("dimensionKey", CAMPAIGN),
                                    Map.entry("dimensionLabel", "诊断测试活动"),
                                    Map.entry("currency", "USD"),
                                    Map.entry("impressions", 10000),
                                    Map.entry("clicks", 100),
                                    Map.entry("orders", 5),
                                    Map.entry("spend", 1000),
                                    Map.entry("sales", 2000),
                                    Map.entry("ctr", 0.01),
                                    Map.entry("acos", 0.5),
                                    Map.entry("roas", 2.0),
                                    Map.entry(
                                        "metricFrom",
                                        LocalDate.now(ZoneOffset.UTC).minusDays(13).toString()),
                                    Map.entry(
                                        "metricTo", LocalDate.now(ZoneOffset.UTC).toString())),
                                Map.ofEntries(
                                    Map.entry("dimension", "CAMPAIGN"),
                                    Map.entry("dimensionKey", "CAMP-LOSS"),
                                    Map.entry("dimensionLabel", "亏损测试活动"),
                                    Map.entry("currency", "USD"),
                                    Map.entry("impressions", 20000),
                                    Map.entry("clicks", 30),
                                    Map.entry("orders", 1),
                                    Map.entry("spend", 800),
                                    Map.entry("sales", 400),
                                    Map.entry("ctr", 0.0015),
                                    Map.entry("acos", 2.0),
                                    Map.entry("roas", 0.5),
                                    Map.entry(
                                        "metricFrom",
                                        LocalDate.now(ZoneOffset.UTC).minusDays(13).toString()),
                                    Map.entry(
                                        "metricTo", LocalDate.now(ZoneOffset.UTC).toString()))),
                        "total", 2,
                        "page", 0,
                        "size", 200,
                        "granularity", "DAY",
                        "dimension", "CAMPAIGN"));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      channel.setExecutor(Executors.newFixedThreadPool(2));
      channel.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "platform_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://127.0.0.1:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("REPORTING_URL", () -> "http://127.0.0.1:" + reporting.getAddress().getPort());
    r.add("WAREHOUSE_URL", () -> "http://127.0.0.1:" + warehouse.getAddress().getPort());
    r.add("TRADE_URL", () -> "http://127.0.0.1:" + trade.getAddress().getPort());
    r.add("CHANNEL_URL", () -> "http://127.0.0.1:" + channel.getAddress().getPort());
  }

  @Autowired DSLContext sql;
  @Autowired TenantTransactions tenants;
  @Autowired MockMvc http;

  // ================================================================ 夹具

  @BeforeEach
  void resetThenSeed() throws Exception {
    reportingUp = true;
    warehouseUp = true;
    tradeUp = true;
    channelUp = true;
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate table platform_diagnosis_evidence, platform_diagnosis_conclusion,"
              + " platform_diagnosis_run, platform_diagnosis_event,"
              + " platform_automation_rule, platform_app restart identity cascade");
      s.execute("alter sequence platform_diagnosis_event_no_seq restart with 1");
      s.execute("alter sequence platform_diagnosis_run_no_seq restart with 1");
    }
    seedPlatformFailureSources();
  }

  /** 在平台自己的失败源表里造事实（**只读上游**：这里只造数据，不改上游逻辑）。 */
  void seedPlatformFailureSources() {
    var tenant = actors.get("session-a").tenantId();
    tenants.run(
        tenant,
        () -> {
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          var appId = UUID.randomUUID();
          sql.execute(
              "insert into platform_app(id,tenant_id,app_code,app_name,app_type,status,created_at)"
                  + " values(?,?,?,?,'INTERNAL','ACTIVE',?::timestamptz)",
              appId,
              tenant,
              "diag-app",
              "诊断测试应用",
              now);
          var subscriptionId = UUID.randomUUID();
          sql.execute(
              "insert into platform_webhook_subscription(id,tenant_id,app_id,name,target_url,status,"
                  + " created_at) values(?,?,?,?,?,'ACTIVE',?::timestamptz)",
              subscriptionId,
              tenant,
              appId,
              "诊断测试订阅",
              "https://example.invalid/hook",
              now);
          sql.execute(
              "insert into platform_delivery_record(id,tenant_id,event_id,event_type,schema_version,"
                  + " subscription_id,app_id,target_url,status,status_code,attempt_count,event_payload,"
                  + " body_bytes,last_error,created_at,updated_at)"
                  + " values(?,?,?,?,1,?,?,?,'DEAD_LETTER',500,5,'{}'::jsonb,'e30=',?,"
                  + "?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              UUID.randomUUID(),
              "order.created",
              subscriptionId,
              appId,
              "https://example.invalid/hook",
              "连续 5 次投递失败：连接被拒",
              now,
              now);
          sql.execute(
              "insert into platform_api_call_log(id,tenant_id,app_id,endpoint_code,http_method,"
                  + " request_path,status_code,outcome,error_code,rate_limited,duration_ms,called_at)"
                  + " values(?,?,?,?,?,?,429,'RATE_LIMITED','',true,12,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              appId,
              ENDPOINT,
              "GET",
              "/open/orders",
              now);
          sql.execute(
              "insert into platform_api_call_log(id,tenant_id,app_id,endpoint_code,http_method,"
                  + " request_path,status_code,outcome,error_code,rate_limited,duration_ms,called_at)"
                  + " values(?,?,?,?,?,?,401,'AUTH_FAILED','SECRET_MISMATCH',false,8,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              appId,
              ENDPOINT,
              "POST",
              "/open/orders",
              now);
          var ruleId = UUID.randomUUID();
          sql.execute(
              "insert into platform_automation_rule(id,tenant_id,name,trigger_event_type,created_at)"
                  + " values(?,?,?,?,?::timestamptz)",
              ruleId,
              tenant,
              "诊断测试规则",
              "order.created",
              now);
          sql.execute(
              "insert into platform_automation_rule_run(id,tenant_id,rule_id,rule_name,event_id,"
                  + " idempotency_key,outcome,source,failure_class,failure_reason,error_code,"
                  + " error_message,duration_ms,started_at,finished_at,created_at)"
                  + " values(?,?,?,?,?,?,'FAILED','MANUAL','ACTION_FAILED','动作调用失败',"
                  + "'E_ACTION','下游返回 500',1234,?::timestamptz,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              ruleId,
              "诊断测试规则",
              "evt-diag-1",
              "evt:evt-diag-1",
              now.minusSeconds(10),
              now,
              now);
          return null;
        });
  }

  UUID tenantId() {
    return actors.get("session-a").tenantId();
  }

  long count(String sqlText, Object... params) {
    return sql.fetchOne(sqlText, params).get(0, Long.class);
  }

  // ================================================================ 小工具

  /** 提交一个异常事件，返回事件 id。 */
  UUID submitEvent(String anomalyType, String subjectType, String subjectRef) throws Exception {
    var body =
        """
        {"anomalyType":"%s","subjectType":"%s","subjectRef":"%s","note":"集成测试登记"}
        """
            .formatted(anomalyType, subjectType, subjectRef);
    var created =
        json.readValue(
            http.perform(
                    post("/platform/ai/diagnosis/events")
                        .header("Authorization", "session-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiDiagnosisEventRow.class);
    return created.id();
  }

  /** 跑一次诊断，返回运行详情。 */
  AiDiagnosisRunDetail diagnose(UUID eventId) throws Exception {
    return json.readValue(
        http.perform(
                post("/platform/ai/diagnosis/events/{id}/diagnose", eventId)
                    .header("Authorization", "session-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(),
        AiDiagnosisRunDetail.class);
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明如实写明未接通项且五类异常都有规则() throws Exception {
    var caps =
        json.readValue(
            http.perform(get("/platform/ai/diagnosis/capabilities").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiDiagnosisCapabilities.class);

    // 五类异常每类都要有规则。
    assertThat(caps.rules()).extracting(AiDiagnosisRule::anomalyType)
        .contains("INVENTORY", "PROFIT", "SHIPPING_DELAY", "SYNC_FAILURE", "AD_LOSS");

    // 每条规则都必须有「触发条件 + 归因依据 + 处置建议」——缺一条就是没做完。
    assertThat(caps.rules()).allSatisfy(
        r -> {
          assertThat(r.trigger()).as(r.code() + " 必须写清触发条件").isNotBlank();
          assertThat(r.basis()).as(r.code() + " 必须写清归因依据（哪张表哪个字段）").isNotBlank();
          assertThat(r.recommendation()).as(r.code() + " 必须写清处置建议").isNotBlank();
          assertThat(r.actionOwner()).as(r.code() + " 必须点名归谁执行").isNotBlank();
        });

    // 依据必须点名**真表名**（能下钻到具体事实行）。
    assertThat(caps.rules()).extracting(AiDiagnosisRule::basis)
        .anySatisfy(b -> assertThat(b).contains("inventory_balance"));
    assertThat(caps.rules()).extracting(AiDiagnosisRule::basis)
        .anySatisfy(b -> assertThat(b).contains("purchase_analytics_fact"));
    assertThat(caps.rules()).extracting(AiDiagnosisRule::basis)
        .anySatisfy(b -> assertThat(b).contains("order_timeliness_fact"));
    assertThat(caps.rules()).extracting(AiDiagnosisRule::basis)
        .anySatisfy(b -> assertThat(b).contains("ad_campaign_daily_metric"));

    // 🔴 **AI 推理引擎必须如实答「没有」**，并写明归谁。
    var ai =
        caps.capabilities().stream()
            .filter(c -> c.name().contains("AI 大模型"))
            .findFirst()
            .orElse(null);
    assertThat(ai).as("AI 推理引擎未接通这一维必须出现在能力声明里").isNotNull();
    assertThat(ai.available()).isFalse();
    assertThat(ai.ownerModule()).isEqualTo("18.4");

    // 🔴 **工作台待办表不存在**必须如实答「没有」，并写明归属模块。
    var todo =
        caps.capabilities().stream()
            .filter(c -> c.name().contains("工作台待办"))
            .findFirst()
            .orElse(null);
    assertThat(todo).as("工作台待办未接通这一维必须出现在能力声明里").isNotNull();
    assertThat(todo.available()).isFalse();
    assertThat(todo.ownerModule()).isEqualTo("1");

    // 事实源清单：AI 模型那一维 available=false。
    assertThat(caps.sources()).extracting(AiDiagnosisSource::code)
        .contains(
            "WAREHOUSE_INVENTORY",
            "REPORTING_SALES",
            "TRADE_PURCHASE_COST",
            "TRADE_TIMELINESS",
            "CHANNEL_AD_REPORT",
            "PLATFORM_INTERNAL_FAILURES",
            "AI_MODEL");
    var aiSource =
        caps.sources().stream()
            .filter(s -> "AI_MODEL".equals(s.code()))
            .findFirst()
            .orElse(null);
    assertThat(aiSource).isNotNull();
    assertThat(aiSource.connected()).isFalse();
  }

  // ================================================================ 库存异常

  @Test
  void 库存异常能归因出可用库存超储与预占且依据可下钻() throws Exception {
    var eventId = submitEvent("INVENTORY", "SKU", SKU);
    var run = diagnose(eventId);

    var codes = run.conclusions().stream().map(c -> c.conclusion().ruleCode()).toList();
    assertThat(codes).contains("INVENTORY_OVERSTOCK", "INVENTORY_RESERVED_BLOCKED");

    var overstock =
        run.conclusions().stream()
            .filter(c -> "INVENTORY_OVERSTOCK".equals(c.conclusion().ruleCode()))
            .findFirst()
            .orElse(null);
    assertThat(overstock).isNotNull();

    // 🔴 每条结论都要有依据（事实表 + 字段 + 数值 + 快照时间 + 下钻引用）。
    assertThat(overstock.evidences()).isNotEmpty();
    assertThat(overstock.evidences())
        .allSatisfy(
            e -> {
              assertThat(e.factTable()).isNotBlank();
              assertThat(e.factField()).isNotBlank();
              assertThat(e.snapshotAt()).as("依据必须带快照时间").isNotNull();
              assertThat(e.factService()).isIn("warehouse", "trade", "reporting", "channel", "platform");
            });
    // 依据落在**真表**上：inventory_policy.max_on_hand 与 inventory_balance.on_hand。
    assertThat(overstock.evidences()).extracting(AiDiagnosisEvidenceRow::factTable)
        .contains("inventory_policy", "inventory_balance");
    assertThat(overstock.evidences()).extracting(AiDiagnosisEvidenceRow::factField)
        .contains("max_on_hand", "on_hand");
    // 数值必须真的落库（不是只有一段说明文字）。
    var maxOnHand =
        overstock.evidences().stream()
            .filter(e -> "max_on_hand".equals(e.factField()))
            .findFirst()
            .orElse(null);
    assertThat(maxOnHand).isNotNull();
    assertThat(maxOnHand.factValue()).isEqualByComparingTo(new BigDecimal("30"));

    // 处置建议必填，且要指向处理页、点名归谁。
    assertThat(overstock.conclusion().recommendation()).isNotBlank();
    assertThat(overstock.conclusion().actionRef()).isNotBlank();
    assertThat(overstock.conclusion().actionOwner()).isNotBlank();
  }

  @Test
  void 库存可用量归零时给出断货结论() throws Exception {
    var eventId = submitEvent("INVENTORY", "SKU", "SKU-OUT");
    var run = diagnose(eventId);
    var codes = run.conclusions().stream().map(c -> c.conclusion().ruleCode()).toList();
    assertThat(codes).contains("INVENTORY_STOCKOUT");
    var stockout =
        run.conclusions().stream()
            .filter(c -> "INVENTORY_STOCKOUT".equals(c.conclusion().ruleCode()))
            .findFirst()
            .orElse(null);
    assertThat(stockout.evidences()).extracting(AiDiagnosisEvidenceRow::factField).contains("available");
    assertThat(stockout.evidences()).extracting(AiDiagnosisEvidenceRow::factValue)
        .anySatisfy(v -> assertThat(v).isEqualByComparingTo(BigDecimal.ZERO));
  }

  // ================================================================ 利润异常

  @Test
  void 利润异常能归因出采购成本上涨与成交均价下跌() throws Exception {
    var eventId = submitEvent("PROFIT", "SKU", SKU);
    var run = diagnose(eventId);

    var codes = run.conclusions().stream().map(c -> c.conclusion().ruleCode()).toList();
    assertThat(codes).contains("PROFIT_COST_SPIKE", "PROFIT_PRICE_DROP");

    var spike =
        run.conclusions().stream()
            .filter(c -> "PROFIT_COST_SPIKE".equals(c.conclusion().ruleCode()))
            .findFirst()
            .orElse(null);
    // 依据落在**真表** purchase_analytics_fact / unit_price 上。
    assertThat(spike.evidences()).extracting(AiDiagnosisEvidenceRow::factTable)
        .contains("purchase_analytics_fact");
    assertThat(spike.evidences()).extracting(AiDiagnosisEvidenceRow::factField).contains("unit_price");
    assertThat(spike.evidences()).extracting(AiDiagnosisEvidenceRow::factService)
        .contains("trade");
    // 百分比走**文本**通道（禁浮点）。
    assertThat(spike.conclusion().impactText()).contains("%");

    var priceDrop =
        run.conclusions().stream()
            .filter(c -> "PROFIT_PRICE_DROP".equals(c.conclusion().ruleCode()))
            .findFirst()
            .orElse(null);
    assertThat(priceDrop.evidences()).extracting(AiDiagnosisEvidenceRow::factTable)
        .contains("reporting_sales_trend_fact");
  }

  // ================================================================ 发货延迟

  @Test
  void 发货延迟能归因出sla超时与各段耗时() throws Exception {
    var eventId = submitEvent("SHIPPING_DELAY", "ORDER", ORDER);
    var run = diagnose(eventId);

    var codes = run.conclusions().stream().map(c -> c.conclusion().ruleCode()).toList();
    assertThat(codes)
        .contains(
            "SHIPPING_SLA_OVERDUE",
            "SHIPPING_ORDER_TO_PACK_SLOW",
            "SHIPPING_PACK_TO_HANDOVER_SLOW",
            "SHIPPING_PROMISE_LATE");

    var sla =
        run.conclusions().stream()
            .filter(c -> "SHIPPING_SLA_OVERDUE".equals(c.conclusion().ruleCode()))
            .findFirst()
            .orElse(null);
    // 依据落在**真表** order_timeliness_fact 上，且状态型取值走文字通道。
    assertThat(sla.evidences()).extracting(AiDiagnosisEvidenceRow::factTable)
        .containsOnly("order_timeliness_fact");
    assertThat(sla.evidences()).extracting(AiDiagnosisEvidenceRow::factField)
        .contains("ship_overdue_seconds", "ship_sla_state");
    assertThat(sla.evidences()).extracting(AiDiagnosisEvidenceRow::factValueText)
        .contains("OVERDUE");
  }

  // ================================================================ 同步失败

  @Test
  void 同步失败能归因出限流鉴权失败死信与规则执行失败() throws Exception {
    // ① 平台内部失败源聚合 ⇒ 登记异常事件。
    var sync =
        json.readValue(
            http.perform(post("/platform/ai/diagnosis/events/sync").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiDiagnosisSyncResult.class);
    assertThat(sync.created()).as("四条失败源事实应登记四个事件").isEqualTo(4);
    assertThat(sync.scanned()).isEqualTo(4);

    // ② 幂等：再同步一次不新增。
    var again =
        json.readValue(
            http.perform(post("/platform/ai/diagnosis/events/sync").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiDiagnosisSyncResult.class);
    assertThat(again.created()).as("同一失败源事实只登记一次").isZero();
    assertThat(again.skipped()).isEqualTo(4);

    // ③ 死信 ⇒ 结论引用 platform_delivery_record。
    var deadLetterEvent =
        json.readValue(
            http.perform(get("/platform/ai/diagnosis/events").header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiDiagnosisEventPage.class);
    var subscriptionEvent =
        deadLetterEvent.items().stream()
            .filter(e -> "SUBSCRIPTION".equals(e.subjectType()))
            .findFirst()
            .orElse(null);
    assertThat(subscriptionEvent).as("死信应登记成一个订阅维度的异常事件").isNotNull();

    var run = diagnose(subscriptionEvent.id());
    var codes = run.conclusions().stream().map(c -> c.conclusion().ruleCode()).toList();
    assertThat(codes).contains("SYNC_DELIVERY_DEAD_LETTER");
    var dead =
        run.conclusions().stream()
            .filter(c -> "SYNC_DELIVERY_DEAD_LETTER".equals(c.conclusion().ruleCode()))
            .findFirst()
            .orElse(null);
    // 依据落在 platform 自己的**真表**上。
    assertThat(dead.evidences()).extracting(AiDiagnosisEvidenceRow::factTable)
        .contains("platform_delivery_record");
    assertThat(dead.evidences()).extracting(AiDiagnosisEvidenceRow::factField)
        .contains("status", "attempt_count");

    // ④ 限流与鉴权失败 ⇒ 引用 platform_api_call_log。
    var endpointEvent =
        deadLetterEvent.items().stream()
            .filter(e -> "ENDPOINT".equals(e.subjectType()))
            .findFirst()
            .orElse(null);
    assertThat(endpointEvent).isNotNull();
    var apiRun = diagnose(endpointEvent.id());
    var apiCodes = apiRun.conclusions().stream().map(c -> c.conclusion().ruleCode()).toList();
    assertThat(apiCodes).contains("SYNC_API_RATE_LIMITED", "SYNC_API_AUTH_FAILED");
    var rateLimited =
        apiRun.conclusions().stream()
            .filter(c -> "SYNC_API_RATE_LIMITED".equals(c.conclusion().ruleCode()))
            .findFirst()
            .orElse(null);
    assertThat(rateLimited.evidences()).extracting(AiDiagnosisEvidenceRow::factTable)
        .contains("platform_api_call_log");

    // ⑤ 规则执行失败 ⇒ 引用 platform_automation_rule_run。
    var ruleEvent =
        deadLetterEvent.items().stream()
            .filter(e -> "RULE".equals(e.subjectType()))
            .findFirst()
            .orElse(null);
    assertThat(ruleEvent).isNotNull();
    var ruleRun = diagnose(ruleEvent.id());
    var ruleCodes = ruleRun.conclusions().stream().map(c -> c.conclusion().ruleCode()).toList();
    assertThat(ruleCodes).contains("SYNC_RULE_RUN_FAILED");
    var ruleFinding =
        ruleRun.conclusions().stream()
            .filter(c -> "SYNC_RULE_RUN_FAILED".equals(c.conclusion().ruleCode()))
            .findFirst()
            .orElse(null);
    assertThat(ruleFinding.evidences()).extracting(AiDiagnosisEvidenceRow::factTable)
        .contains("platform_automation_rule_run");
  }

  // ================================================================ 广告亏损

  @Test
  void 广告亏损能归因出acos超标与roas低于盈亏平衡() throws Exception {
    var eventId = submitEvent("AD_LOSS", "CAMPAIGN", "CAMP-LOSS");
    var run = diagnose(eventId);

    var codes = run.conclusions().stream().map(c -> c.conclusion().ruleCode()).toList();
    assertThat(codes).contains("AD_ACOS_ABOVE_TARGET", "AD_ROAS_BELOW_BREAKEVEN", "AD_LOW_CTR");

    var acos =
        run.conclusions().stream()
            .filter(c -> "AD_ACOS_ABOVE_TARGET".equals(c.conclusion().ruleCode()))
            .findFirst()
            .orElse(null);
    // 依据落在 **ad_campaign_daily_metric** 的 acos / spend / sales 上。
    assertThat(acos.evidences()).extracting(AiDiagnosisEvidenceRow::factTable)
        .contains("ad_campaign_daily_metric");
    assertThat(acos.evidences()).extracting(AiDiagnosisEvidenceRow::factField)
        .contains("acos", "spend", "sales");
    assertThat(acos.evidences()).extracting(AiDiagnosisEvidenceRow::factService).contains("channel");
  }

  // ================================================================ 上游读不到

  @Test
  void 上游读不到就503而不是空集合() throws Exception {
    var eventId = submitEvent("INVENTORY", "SKU", SKU);
    warehouseUp = false;
    try {
      http.perform(
              post("/platform/ai/diagnosis/events/{id}/diagnose", eventId)
                  .header("Authorization", "session-a"))
          .andExpect(status().isServiceUnavailable());
    } finally {
      warehouseUp = true;
    }
    // 🔴 失败时**一个字都不写**：没有运行记录、没有结论、没有依据。
    var runs = count("select count(*) from platform_diagnosis_run where event_id = ?", eventId);
    assertThat(runs).as("上游读不到时不得留下「看起来诊断过」的记录").isZero();
  }

  // ================================================================ 列表与检索

  @Test
  void 事件列表真分页真筛选真搜索() throws Exception {
    submitEvent("INVENTORY", "SKU", SKU);
    submitEvent("PROFIT", "SKU", "SKU-PROFIT");
    submitEvent("AD_LOSS", "CAMPAIGN", CAMPAIGN);

    var page =
        json.readValue(
            http.perform(
                    get("/platform/ai/diagnosis/events")
                        .header("Authorization", "session-a")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiDiagnosisEventPage.class);
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.items()).hasSize(2);
    assertThat(page.totalPages()).isEqualTo(2);

    var filtered =
        json.readValue(
            http.perform(
                    get("/platform/ai/diagnosis/events")
                        .header("Authorization", "session-a")
                        .param("anomalyType", "PROFIT"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiDiagnosisEventPage.class);
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.items().get(0).anomalyType()).isEqualTo("PROFIT");
    assertThat(filtered.items().get(0).anomalyTypeLabel()).isEqualTo("利润异常");

    var searched =
        json.readValue(
            http.perform(
                    get("/platform/ai/diagnosis/events")
                        .header("Authorization", "session-a")
                        .param("keyword", "SKU-PROFIT"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiDiagnosisEventPage.class);
    assertThat(searched.total()).isEqualTo(1);
    assertThat(searched.items().get(0).subjectRef()).isEqualTo("SKU-PROFIT");
  }

  // ================================================================ 处置

  @Test
  void 人工标记已处置必须写处置说明() throws Exception {
    var eventId = submitEvent("INVENTORY", "SKU", SKU);
    http.perform(
            post("/platform/ai/diagnosis/events/{id}/resolve", eventId)
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    http.perform(
            post("/platform/ai/diagnosis/events/{id}/resolve", eventId)
                .header("Authorization", "session-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"已联系仓库补货\"}"))
        .andExpect(status().isOk());
    var detail =
        json.readValue(
            http.perform(
                    get("/platform/ai/diagnosis/events/{id}", eventId)
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiDiagnosisEventDetail.class);
    assertThat(detail.event().status()).isEqualTo("RESOLVED");
    assertThat(detail.event().note()).isEqualTo("已联系仓库补货");
  }

  // ================================================================ 事件详情 / 运行详情

  /**
   * 事件详情给「事件 + 历史运行」，结论与依据走**运行详情**。
   *
   * <p>🔴 这条用例钉住两件事：
   *
   * <ol>
   *   <li><b>还没诊断过的事件，事件详情照样 200</b>（运行列表为空）——
   *       「没诊断过」不是「查不到」，更不该是 500（上一任 worker 的实现在这里 NPE）。</li>
   *   <li><b>诊断过的运行能从运行详情端点取到结论与依据</b> ——
   *       依据不是内嵌在事件详情里的可空对象（契约表达不了），
   *       它有自己的一条路径，且必须真的带事实表 / 字段 / 快照时间。</li>
   * </ol>
   */
  @Test
  void 事件详情给运行列表且运行详情带结论与依据() throws Exception {
    var eventId = submitEvent("INVENTORY", "SKU", SKU);

    // ---- 还没诊断过：运行列表为空，但端点必须正常返回 ----
    var before =
        json.readValue(
            http.perform(
                    get("/platform/ai/diagnosis/events/{id}", eventId)
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiDiagnosisEventDetail.class);
    assertThat(before.event().id()).isEqualTo(eventId);
    assertThat(before.runs()).isEmpty();

    // ---- 诊断过：运行列表有一行，运行详情带结论与依据 ----
    var run = diagnose(eventId);
    var after =
        json.readValue(
            http.perform(
                    get("/platform/ai/diagnosis/events/{id}", eventId)
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiDiagnosisEventDetail.class);
    assertThat(after.runs()).hasSize(1);
    assertThat(after.runs().get(0).id()).isEqualTo(run.run().id());
    assertThat(after.runs().get(0).conclusionCount()).isEqualTo(run.conclusions().size());

    var runDetail =
        json.readValue(
            http.perform(
                    get("/platform/ai/diagnosis/runs/{id}", run.run().id())
                        .header("Authorization", "session-a"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            AiDiagnosisRunDetail.class);
    assertThat(runDetail.conclusions()).isNotEmpty();
    assertThat(runDetail.conclusions())
        .allSatisfy(
            c -> {
              assertThat(c.evidences()).as("每条结论至少一条依据").isNotEmpty();
              assertThat(c.evidences()).allSatisfy(e -> {
                assertThat(e.factTable()).isNotBlank();
                assertThat(e.factField()).isNotBlank();
                assertThat(e.snapshotAt()).isNotNull();
              });
            });
  }

  // ================================================================ 库层不变量

  @Test
  void 每条结论至少一条依据在库层成立() throws Exception {
    var eventId = submitEvent("INVENTORY", "SKU", SKU);
    diagnose(eventId);
    var orphans =
        count(
            "select count(*) from platform_diagnosis_conclusion c"
                + " where not exists (select 1 from platform_diagnosis_evidence e"
                + " where e.conclusion_id = c.id)");
    assertThat(orphans).as("结论不允许没有依据").isZero();
    // 依据必须带真表名 + 真字段名 + 快照时间。
    var bad =
        count(
            "select count(*) from platform_diagnosis_evidence"
                + " where btrim(fact_table) = '' or btrim(fact_field) = '' or snapshot_at is null"
                + " or (fact_value is null and btrim(fact_value_text) = '')");
    assertThat(bad).as("依据必须带事实表、字段、快照时间与数值").isZero();
  }
}
