package com.starcon.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 6.2.2.1 采购申请的**共享底座**。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身</b>。跨服务一律走**真的本地 HTTP
 * 服务**（identity 的审批端点、warehouse 的仓 / 法人参照、catalog 的供应商清单）——
 * 替身只按上游契约形状返回数，绝不替业务逻辑做决定。
 *
 * <p><b>三个替身各自回答一件真事</b>：
 *
 * <ul>
 *   <li><b>identity</b>：①「这次要不要审」（{@code POST /identity/approval-rules/evaluate}）
 *       ②「把它挂到哪条待办上」（{@code POST /identity/approval-instances}，含 409 幂等）
 *       ③「那边批了没有」（{@code GET /identity/approval-records/{id}}）。</li>
 *   <li><b>warehouse</b>：目标仓与法人参照清单。</li>
 *   <li><b>catalog</b>：供应商清单（6.1.1.x 的 {@code GET /catalog/supply/vendors}）。</li>
 * </ul>
 *
 * <p><b>上游事实怎么来的</b>：6.2.1.3 的采购计划与 6.2.1.2 的补货建议直接种进**它们自己的表** ——
 * 本模块是它们的下游消费者，读它们的事实表正是本模块要证明的事；
 * 种数据用迁移用户（超级用户），生产运行角色 {@code trade_app} 对那些表只有上游授予的权限，
 * 本模块**不新增**任何权限。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class PurchaseRequestFixture {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tenantA = UUID.randomUUID(), tenantB = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity, warehouse, catalog;
  static final ObjectMapper json =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .enable(
              com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  // ---- 参照数据 ----
  static final UUID warehouseId = UUID.randomUUID();
  static final UUID legalEntityId = UUID.randomUUID();
  static final UUID supplierRoleId = UUID.randomUUID();

  static final LocalDate PERIOD_START = LocalDate.of(2026, 11, 1);
  static final LocalDate PERIOD_END = LocalDate.of(2026, 11, 30);
  static final LocalDate ARRIVAL = LocalDate.of(2026, 12, 5);

  static final List<String> allPermissions =
      List.of("trade.purchase-request.read", "trade.purchase-request.update");

  // ---- 审批替身的状态 ----

  /** 求值模式：这次提交要不要走审批。 */
  enum Evaluation {
    REQUIRE_APPROVAL,
    EXEMPT,
    UNCONTROLLED
  }

  record StubInstance(
      UUID id,
      String instanceNo,
      UUID flowId,
      String sourceReferenceId,
      String status,
      Instant submittedAt) {}

  static final UUID FLOW_ID = UUID.randomUUID();
  static final UUID RULE_ID = UUID.randomUUID();
  static final AtomicReference<Evaluation> EVALUATION =
      new AtomicReference<>(Evaluation.REQUIRE_APPROVAL);
  /** 置位后审批替身一律回 503（模拟「审批服务不可达」）。 */
  static final AtomicBoolean identityDown = new AtomicBoolean();
  /** 替身里在办的实例：sourceReferenceId → 实例。 */
  static final Map<String, StubInstance> INSTANCES = new ConcurrentHashMap<>();
  /** 替身收到的 X-Internal-Key 头（断言服务间授权真的带上了）。 */
  static final AtomicReference<String> lastInternalKey = new AtomicReference<>();
  /** 替身收到的最近一次 Authorization 头（断言租户不是靠自定义头传过去的）。 */
  static final AtomicReference<String> lastApprovalBearer = new AtomicReference<>();
  /** 替身收到的最近一次求值请求体（断言金额 / 币种原样传过去了）。 */
  static final AtomicReference<Map<String, Object>> lastEvaluationBody = new AtomicReference<>();
  static final AtomicInteger INSTANCE_SEQ = new AtomicInteger();

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

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static void write(HttpServer server, HttpExchange e, int code, Object body) throws IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
  }

  static UUID tenantOf(String bearer) {
    var access = actors.get(bearer);
    return access == null ? null : access.tenantId();
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> readJson(HttpExchange e) throws IOException {
    var body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    if (body.isBlank()) return Map.of();
    return new ObjectMapper().findAndRegisterModules().readValue(body, Map.class);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，隔离断言全部变成空断言。
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }

      // ---- identity：权限替身 + 审批端点 ----
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
      identity.createContext("/identity", PurchaseRequestFixture::handleIdentity);
      actors.put("Bearer tenant-a", actor(user, tenantA, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, tenantB, List.of(grant("ALL", allPermissions))));
      identity.start();

      // ---- warehouse：仓 / 法人参照 ----
      warehouse = HttpServer.create(new InetSocketAddress(0), 0);
      warehouse.createContext(
          "/warehouse/warehouses",
          e -> {
            if (tenantOf(e.getRequestHeaders().getFirst("Authorization")) == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            write(
                warehouse,
                e,
                200,
                List.of(
                    Map.of(
                        "id", warehouseId.toString(),
                        "code", "WH-LA-01",
                        "name", "洛杉矶仓",
                        "countryCode", "US",
                        "status", "ACTIVE")));
          });
      warehouse.createContext(
          "/warehouse/legal-entities",
          e -> {
            if (tenantOf(e.getRequestHeaders().getFirst("Authorization")) == null) {
              write(warehouse, e, 401, Map.of("message", "未认证"));
              return;
            }
            write(
                warehouse,
                e,
                200,
                List.of(
                    Map.of(
                        "id", legalEntityId.toString(),
                        "code", "US-LLC",
                        "name", "美国子公司",
                        "status", "ACTIVE")));
          });
      warehouse.start();

      // ---- catalog：供应商清单（6.1.1.x 的只读接口） ----
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/supply/vendors",
          e -> {
            if (tenantOf(e.getRequestHeaders().getFirst("Authorization")) == null) {
              write(catalog, e, 401, Map.of("message", "未认证"));
              return;
            }
            String path = e.getRequestURI().getPath();
            if (path.equals("/catalog/supply/vendors")) {
              write(
                  catalog,
                  e,
                  200,
                  Map.of(
                      "items",
                      List.of(
                          Map.of(
                              "roleId", supplierRoleId.toString(),
                              "partyId", UUID.randomUUID().toString(),
                              "partyCode", "SUP-001",
                              "partyName", "深圳华强电子",
                              "partyStatus", "ACTIVE")),
                      "total", 1,
                      "page", 0,
                      "size", 200));
              return;
            }
            String roleId = path.substring("/catalog/supply/vendors/".length());
            if (!supplierRoleId.toString().equals(roleId)) {
              write(catalog, e, 404, Map.of("message", "该供应商不存在"));
              return;
            }
            write(
                catalog,
                e,
                200,
                Map.of(
                    "summary",
                    Map.of(
                        "roleId", supplierRoleId.toString(),
                        "partyId", UUID.randomUUID().toString(),
                        "partyCode", "SUP-001",
                        "partyName", "深圳华强电子",
                        "partyStatus", "ACTIVE")));
          });
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** identity 审批替身：形状与 identity 的真实端点逐字一致。 */
  private static void handleIdentity(HttpExchange e) throws IOException {
    lastApprovalBearer.set(e.getRequestHeaders().getFirst("Authorization"));
    if (identityDown.get()) {
      write(identity, e, 503, Map.of("message", "审批服务暂时不可达"));
      return;
    }
    String path = e.getRequestURI().getPath();
    String method = e.getRequestMethod();

    if ("POST".equals(method) && path.equals("/identity/approval-rules/evaluate")) {
      lastEvaluationBody.set(readJson(e));
      write(identity, e, 200, evaluation());
      return;
    }
    if ("POST".equals(method) && path.equals("/identity/approval-instances")) {
      lastInternalKey.set(e.getRequestHeaders().getFirst("X-Internal-Key"));
      var body = readJson(e);
      String sourceReferenceId = String.valueOf(body.get("sourceReferenceId"));
      var existing = INSTANCES.get(sourceReferenceId);
      if (existing != null && "PENDING".equals(existing.status())) {
        // 🔴 幂等：同一来源单据存活期间只能有一条实例 ⇒ 409。
        write(identity, e, 409, Map.of("message", "该来源单据已有一条在办的审批实例，请勿重复提交"));
        return;
      }
      var instance =
          new StubInstance(
              UUID.randomUUID(),
              "AI-2026-" + String.format("%04d", INSTANCE_SEQ.incrementAndGet()),
              UUID.fromString(String.valueOf(body.get("flowId"))),
              sourceReferenceId,
              "PENDING",
              Instant.now());
      INSTANCES.put(sourceReferenceId, instance);
      write(
          identity,
          e,
          200,
          Map.of(
              "id", instance.id(),
              "instanceNo", instance.instanceNo(),
              "flowId", instance.flowId(),
              "currentNodeKey", "MANAGER",
              "currentNodeName", "经理审批",
              "applicantId", user,
              "routedBy", "AMOUNT_BRANCH",
              "submittedAt", instance.submittedAt().toString()));
      return;
    }
    if ("GET".equals(method) && path.startsWith("/identity/approval-records/")) {
      UUID id = UUID.fromString(path.substring("/identity/approval-records/".length()));
      var found = INSTANCES.values().stream().filter(i -> i.id().equals(id)).findFirst();
      if (found.isEmpty()) {
        write(identity, e, 404, Map.of("message", "审批记录不存在或不属于当前租户"));
        return;
      }
      write(identity, e, 200, Map.of("instance", instanceView(found.get()), "timeline", List.of()));
      return;
    }
    if ("GET".equals(method) && path.equals("/identity/approval-records")) {
      var items = new ArrayList<Object>();
      for (var instance : INSTANCES.values()) items.add(instanceView(instance));
      write(
          identity,
          e,
          200,
          Map.of(
              "items", items,
              "total", items.size(),
              "page", 0,
              "size", 200,
              "summary",
              Map.of("total", items.size(), "pendingTotal", items.size(), "longestDwellSeconds", 0)));
      return;
    }
    write(identity, e, 404, Map.of("message", "审批替身没有这个端点：" + method + " " + path));
  }

  /** 求值响应（形状 = identity 的 ApprovalEvaluationView）。 */
  private static Map<String, Object> evaluation() {
    var body = new LinkedHashMap<String, Object>();
    body.put("documentTypeCode", "PURCHASE_ORDER");
    switch (EVALUATION.get()) {
      case REQUIRE_APPROVAL -> {
        body.put("source", "RULE");
        body.put("decision", "SUBMIT_FOR_APPROVAL");
        body.put("matchedRuleId", RULE_ID);
        body.put("matchedRuleName", "大额采购需审批");
        body.put("matchedPriority", 10);
        body.put("targetFlowId", FLOW_ID);
        body.put("targetFlowName", "采购审批流");
        body.put("exemptionCeilingAmount", null);
        body.put("exemptionCeilingCurrency", null);
        body.put("reason", "命中「大额采购需审批」规则");
      }
      case EXEMPT -> {
        body.put("source", "RULE");
        body.put("decision", "EXEMPT");
        body.put("matchedRuleId", RULE_ID);
        body.put("matchedRuleName", "小额免审");
        body.put("matchedPriority", 20);
        body.put("targetFlowId", null);
        body.put("targetFlowName", null);
        body.put("exemptionCeilingAmount", new BigDecimal("100"));
        body.put("exemptionCeilingCurrency", "USD");
        body.put("reason", "命中「小额免审」规则，免审额度 100 USD");
      }
      default -> {
        body.put("source", "UNCONTROLLED");
        body.put("decision", "EXEMPT");
        body.put("matchedRuleId", null);
        body.put("matchedRuleName", null);
        body.put("matchedPriority", null);
        body.put("targetFlowId", null);
        body.put("targetFlowName", null);
        body.put("exemptionCeilingAmount", null);
        body.put("exemptionCeilingCurrency", null);
        body.put("reason", "本租户未为单据类型「PURCHASE_ORDER」配置生效规则集，按默认放行处理");
      }
    }
    return body;
  }

  /** 实例视图（形状 = identity 的 ApprovalRecordView 的一部分）。 */
  private static Map<String, Object> instanceView(StubInstance instance) {
    var body = new LinkedHashMap<String, Object>();
    body.put("id", instance.id());
    body.put("instanceNo", instance.instanceNo());
    body.put("documentTypeCode", "PURCHASE_ORDER");
    body.put("documentTypeName", "采购订单");
    body.put("flowName", "采购审批流");
    body.put("subjectTitle", "采购申请 " + instance.sourceReferenceId());
    body.put("amount", new BigDecimal("100"));
    body.put("currencyCode", "USD");
    body.put("applicantId", user);
    body.put("applicantName", "tester");
    body.put("currentNodeKey", "MANAGER");
    body.put("currentNodeName", "经理审批");
    body.put("status", instance.status());
    body.put("sourceSystem", "trade");
    body.put("sourceReferenceId", instance.sourceReferenceId());
    body.put("submittedAt", instance.submittedAt().toString());
    body.put("finishedAt", instance.status().equals("PENDING") ? null : instance.submittedAt().toString());
    body.put("dwellSeconds", 0);
    body.put("actionCount", 0);
    body.put("rejectCount", 0);
    body.put("transferCount", 0);
    body.put("originalInstanceId", null);
    body.put("originalInstanceNo", null);
    body.put("resubmittedCount", 0);
    return body;
  }

  /** 把一条在办实例改成已批 / 已驳回（模拟审批人在 identity 那边做了决定）。 */
  static void decide(String sourceReferenceId, String status) {
    var instance = INSTANCES.get(sourceReferenceId);
    if (instance == null) throw new IllegalStateException("没有这条审批实例：" + sourceReferenceId);
    INSTANCES.put(
        sourceReferenceId,
        new StubInstance(
            instance.id(),
            instance.instanceNo(),
            instance.flowId(),
            instance.sourceReferenceId(),
            status,
            instance.submittedAt()));
  }

  /** 替身收到的审批实例条数（断言「不产生第二条实例」）。 */
  static int instanceCount() {
    return INSTANCES.size();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @AfterAll
  static void noteSharedResources() {
    // 🔴 **刻意不在这里停容器与替身服务**：它们被两个测试类共享，
    // 先结束的测试类把共享资源停掉会让后面的类全部失败。
    // 改由 JVM 退出时由 Testcontainers 的 Ryuk 统一回收（与 catalog 的 BatchCalcFixture 同口径）。
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  // ================================================================ 数据准备

  /** 用迁移用户（超级用户）连接：绕开 RLS，只用来清表与种上游事实。 */
  static Connection admin() throws Exception {
    return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
  }

  /** 清掉本模块的表与上游事实表。 */
  static void truncateAll() throws Exception {
    try (var c = admin();
        var s = c.createStatement()) {
      s.execute(
          "truncate purchase_request_conversion, purchase_request_adjustment, purchase_request_line,"
              + " purchase_request, purchase_plan_adjustment, purchase_plan_lock_log,"
              + " purchase_plan_line, purchase_plan, purchase_plan_campaign,"
              + " purchase_plan_lock_policy, replenishment_suggestion_decision,"
              + " replenishment_suggestion_step, replenishment_suggestion,"
              + " replenishment_suggestion_batch cascade");
    }
  }

  /** 种一张 6.2.1.3 的采购计划（返回 plan id）。 */
  static UUID seedPlan(UUID tenant) throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_plan(id,tenant_id,plan_no,plan_type,period_type,period_start,"
                    + " period_end,target_warehouse_id,target_warehouse_code,target_warehouse_name,"
                    + " legal_entity_id,legal_entity_code,legal_entity_name,status,priority,note,"
                    + " created_by,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::varchar,'ROUTINE','MONTHLY',?::date,?::date,"
                    + " ?::uuid,'WH-LA-01','洛杉矶仓',?::uuid,'US-LLC','美国子公司','CONFIRMED','NORMAL',"
                    + " '上游事实',?::uuid,now(),now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, "PP-TEST-" + id.toString().replace("-", "").substring(0, 8));
      ps.setDate(4, java.sql.Date.valueOf(PERIOD_START));
      ps.setDate(5, java.sql.Date.valueOf(PERIOD_END));
      ps.setObject(6, warehouseId);
      ps.setObject(7, legalEntityId);
      ps.setObject(8, user);
      ps.executeUpdate();
    }
    return id;
  }

  /** 种一条 6.2.1.3 的计划行（返回 line id）。 */
  static UUID seedPlanLine(
      UUID tenant, UUID planId, String skuCode, String quantity, LocalDate arrival, UUID lineWarehouse)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into purchase_plan_line(id,tenant_id,plan_id,sku_id,sku_code,sku_name,"
                    + " plan_quantity,quantity_basis,target_warehouse_id,target_warehouse_code,"
                    + " target_warehouse_name,expected_arrival_date,priority,status,lock_status,"
                    + " note,sort_rank,created_at,updated_at)"
                    + " values (?::uuid,?::uuid,?::uuid,?::uuid,?,?,?::numeric,'上游事实',?::uuid,"
                    + " 'WH-LA-01','洛杉矶仓',?::date,'NORMAL','PLANNED','NOT_REQUESTED','',0,now(),now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setObject(3, planId);
      ps.setObject(4, UUID.nameUUIDFromBytes(skuCode.getBytes()));
      ps.setString(5, skuCode);
      ps.setString(6, skuCode + " 名称");
      ps.setBigDecimal(7, new BigDecimal(quantity));
      ps.setObject(8, lineWarehouse == null ? warehouseId : lineWarehouse);
      ps.setDate(9, java.sql.Date.valueOf(arrival));
      ps.executeUpdate();
    }
    return id;
  }

  /** 种一个 6.2.1.2 的补货建议批次（返回 batch id）。 */
  static UUID seedBatch(UUID tenant) throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into replenishment_suggestion_batch(id,tenant_id,batch_no,as_of_date,"
                    + " computed_at,default_coverage_days,item_count,replenish_count,created_by,note)"
                    + " values (?::uuid,?::uuid,?::varchar,?::date,now(),30,1,1,?::uuid,'上游事实')")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setString(3, "RS-TEST-" + id.toString().replace("-", "").substring(0, 8));
      ps.setDate(4, java.sql.Date.valueOf(PERIOD_START));
      ps.setObject(5, user);
      ps.executeUpdate();
    }
    return id;
  }

  /**
   * 种一条 6.2.1.2 的补货建议。
   *
   * @param decided 处置后的数量（null = 未处置 ⇒ 用建议量）
   * @param suggested 建议量
   */
  static UUID seedSuggestion(
      UUID tenant,
      UUID batchId,
      String skuCode,
      String suggested,
      String decided,
      LocalDate arrival,
      String currency)
      throws Exception {
    return seedSuggestion(tenant, batchId, skuCode, suggested, decided, arrival, currency, "REPLENISH");
  }

  /**
   * 种一条 6.2.1.2 的补货建议。
   *
   * @param advice 建议类别（REPLENISH 真建议 / HOLD 不需要补 / NO_QUOTE 缺有效报价 …）
   */
  static UUID seedSuggestion(
      UUID tenant,
      UUID batchId,
      String skuCode,
      String suggested,
      String decided,
      LocalDate arrival,
      String currency,
      String advice)
      throws Exception {
    UUID id = UUID.randomUUID();
    try (var c = admin();
        var ps =
            c.prepareStatement(
                "insert into replenishment_suggestion(id,tenant_id,batch_id,sku_id,sku_code,sku_name,"
                    + " coverage_days,coverage_source,available_quantity,in_transit_quantity,"
                    + " suggested_quantity,expected_arrival_date,advice,advice_basis,status,"
                    + " decided_quantity,decision_by,decision_at,currency,sort_rank,created_at)"
                    + " values (?::uuid,?::uuid,?::uuid,?::uuid,?,?,30,'DEFAULT',0,0,?::numeric,"
                    + " ?::date,?::varchar,'上游事实',?::varchar,?::numeric,?::uuid,?::timestamptz,"
                    + " ?::varchar,0,now())")) {
      ps.setObject(1, id);
      ps.setObject(2, tenant);
      ps.setObject(3, batchId);
      ps.setObject(4, UUID.nameUUIDFromBytes(skuCode.getBytes()));
      ps.setString(5, skuCode);
      ps.setString(6, skuCode + " 名称");
      if (suggested == null) {
        ps.setNull(7, java.sql.Types.NUMERIC);
      } else {
        ps.setBigDecimal(7, new BigDecimal(suggested));
      }
      if (arrival == null) {
        ps.setNull(8, java.sql.Types.DATE);
      } else {
        ps.setDate(8, java.sql.Date.valueOf(arrival));
      }
      ps.setString(9, advice);
      String status = decided == null ? "SUGGESTED" : "ADJUSTED";
      ps.setString(10, status);
      if (decided == null) {
        ps.setNull(11, java.sql.Types.NUMERIC);
        ps.setNull(12, java.sql.Types.OTHER);
        ps.setNull(13, java.sql.Types.TIMESTAMP);
      } else {
        ps.setBigDecimal(11, new BigDecimal(decided));
        ps.setObject(12, user);
        ps.setTimestamp(13, new java.sql.Timestamp(System.currentTimeMillis()));
      }
      if (currency == null) {
        ps.setNull(14, java.sql.Types.VARCHAR);
      } else {
        ps.setString(14, currency);
      }
      ps.executeUpdate();
    }
    return id;
  }
}
