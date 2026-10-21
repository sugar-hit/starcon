package com.starcon.warehouse;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.4.1.2 调整审批。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本测试的核心是「阈值判定接在批准前面」这一条</b>：蓝图说「**按金额/数量阈值审批调整单，
 * 审批通过后才执行库存分录**」—— 如果判定形同虚设（直接调 7.4.1.1 的批准端点就能批），
 * 那整套阈值规则就只是界面上的装饰。所以必须**真的**去数据库里硬改状态，验证 V267 的闸门会当场拒绝。
 *
 * <p>测���的口径（每条都对应蓝图里的一句）：
 *
 * <ul>
 *   <li>命中哪一档由**调整单类型 + 币种 + 金额区间 + 数量区间**共同决定；
 *   <li>判定结果在**提交那一刻冻结**（事后改规则不改档）；
 *   <li>审批人数没凑齐 ⇒ 数据库不让批；
 *   <li>职责分离（提交人 ≠ 审批人）；
 *   <li>批量逐条给结论；
 *   <li>会计影响：登记额 / 生效额分两列，过账后生效额 = 登记额。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryAdjustmentApprovalIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID();
  static final UUID submitter = UUID.randomUUID();
  static final UUID approverOne = UUID.randomUUID();
  static final UUID approverTwo = UUID.randomUUID();

  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(
              p ->
                  Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static final ObjectMapper JSON = new ObjectMapper();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-adjustment-approval-" + tenant + "-" + ordinal).getBytes());
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
        s.execute("create role warehouse_app login password 'test-only' nosuperuser nobypassrls");
      }
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
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer submitter", actor(submitter, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer approver-one", actor(approverOne, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer approver-two", actor(approverTwo, a, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer reader",
          actor(submitter, a, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "warehouse_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    r.add("TRADE_URL", () -> "http://127.0.0.1:1");
  }

  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorSubmitter;
  AccessService.Access actorApproverOne;
  AccessService.Access actorApproverTwo;

  @BeforeEach
  void seed() throws Exception {
    actorSubmitter = actors.get("Bearer submitter");
    actorApproverOne = actors.get("Bearer approver-one");
    actorApproverTwo = actors.get("Bearer approver-two");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate inventory_adjustment_approval_record, inventory_adjustment_approval_case,"
              + " inventory_adjustment_valuation, inventory_adjustment_approval_approver,"
              + " inventory_adjustment_approval_rule, inventory_adjustment_attachment,"
              + " inventory_adjustment_order_line, inventory_adjustment_audit,"
              + " inventory_adjustment_order, inventory_entry, inventory_transaction, stock_bucket,"
              + " inventory_balance, warehouse_location, inventory_lot, inventory_quality_status,"
              + " warehouse, sku_ref, legal_entity_ref restart identity cascade");
    }
    access.run(
        actorSubmitter,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
              idFor(a, 1),
              a,
              "LE-1",
              "主体一");
          sql.execute(
              "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
              idFor(a, 2),
              a,
              "SKU-1",
              "商品一");
          sql.execute(
              "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
              idFor(a, 3),
              a,
              "WH-ONE",
              "一仓",
              "CN",
              "Asia/Shanghai");
          sql.execute(
              "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
              idFor(a, 4),
              a,
              "SELLABLE",
              "可售",
              "SELLABLE");
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
              idFor(a, 5),
              a,
              idFor(a, 3),
              "A-01",
              "A 区一排");
          // 一条 balance + 一个 bucket：inventory_balance 对六维有唯一约束
          // （tenant × sku × 仓 × 法人 × 质量状态），所以一个租户内只能有一条 ——
          // 每次建单都新建会撞 23505。调整单按 bucket 六维定位，复用同一条是真实用法。
          balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
              balanceId,
              a,
              idFor(a, 2),
              idFor(a, 3),
              idFor(a, 1),
              idFor(a, 4),
              new BigDecimal("100"),
              BigDecimal.ZERO);
          bucketId = UUID.randomUUID();
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
                  + "quality_status_id,balance_id,on_hand,reserved,last_movement_at) "
                  + "values(?,?,?,?,?,?,?,?,?,0::numeric,?::timestamptz)",
              bucketId,
              a,
              idFor(a, 1),
              idFor(a, 2),
              idFor(a, 3),
              idFor(a, 5),
              idFor(a, 4),
              balanceId,
              new BigDecimal("100"),
              now());
          return null;
        });
  }

  UUID balanceId;
  UUID bucketId;

  // ================================================================ 夹具

  static OffsetDateTime now() {
    return OffsetDateTime.now();
  }

  /**
   * 建一张带一行的草稿调整单（直接落库，好精确控制原因码与数量）。
   *
   * @param reasonCode 单据级原因码（= 调整单类型）
   * @param qtyDelta 带符号数量（正=盘盈，负=盘亏）
   */
  UUID newOrder(String reasonCode, BigDecimal qtyDelta) {
    return access.run(
        actorSubmitter,
        "warehouse.inventory.read",
        () -> {
          UUID orderId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_adjustment_order(id,tenant_id,code,status,reason_code,note,"
                  + "created_by_user_id,created_at) values(?,?,?,'DRAFT',?,'',?,?::timestamptz)",
              orderId,
              a,
              "ADJ-INV-APR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
              reasonCode,
              submitter,
              now());
          sql.execute(
              "insert into inventory_adjustment_order_line(id,tenant_id,order_id,bucket_id,balance_id,"
                  + "legal_entity_id,sku_id,warehouse_id,location_id,quality_status_id,"
                  + "sku_code,sku_name,warehouse_code,location_code,lot_code,quality_status_code,"
                  + "qty_delta,reason_code,note,expected_version,line_no,created_at) "
                  + "values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
              UUID.randomUUID(),
              a,
              orderId,
              bucketId,
              balanceId,
              idFor(a, 1),
              idFor(a, 2),
              idFor(a, 3),
              idFor(a, 5),
              idFor(a, 4),
              "SKU-1",
              "商品一",
              "WH-ONE",
              "A-01",
              "",
              "SELLABLE",
              qtyDelta,
              reasonCode,
              "",
              0L,
              1,
              now());
          return orderId;
        });
  }

  /** 建一条阈值规则。 */
  MvcResult createRule(
      String ruleNo,
      String reasonCode,
      String currency,
      String minAmount,
      String maxAmount,
      String minQty,
      String maxQty,
      String tier,
      int approvals) throws Exception {
    return http.perform(
            post("/warehouse/inventory/adjustment-approvals/rules")
                .header("Authorization", "Bearer submitter")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        body(
                            "ruleNo", ruleNo,
                            "ruleName", "规则 " + ruleNo,
                            "reasonCode", reasonCode,
                            "currency", currency,
                            "minAmount", new BigDecimal(minAmount),
                            "maxAmount", maxAmount == null ? null : new BigDecimal(maxAmount),
                            "minQty", new BigDecimal(minQty),
                            "maxQty", maxQty == null ? null : new BigDecimal(maxQty),
                            "requiredTier", tier,
                            "requiredApprovals", approvals,
                            "priority", 100))))
        .andExpect(status().isOk())
        .andReturn();
  }

  /** 登记一个审批人授权。 */
  MvcResult createApprover(
      UUID userId, String tier, String currency, String minAmount, String maxAmount, String maxQty)
      throws Exception {
    return http.perform(
            post("/warehouse/inventory/adjustment-approvals/approvers")
                .header("Authorization", "Bearer submitter")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        body(
                            "userId", userId.toString(),
                            "userName", "审批人",
                            "approvalTier", tier,
                            "currency", currency,
                            "minAmount", new BigDecimal(minAmount),
                            "maxAmount", maxAmount == null ? null : new BigDecimal(maxAmount),
                            "maxQty", maxQty == null ? null : new BigDecimal(maxQty)))))
        .andExpect(status().isOk())
        .andReturn();
  }

  /** 提交待审（带申报金额）。 */
  MvcResult submit(UUID orderId, String amount, String currency) throws Exception {
    return http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + orderId + "/submit")
                .header("Authorization", "Bearer submitter")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        body("amount", new BigDecimal(amount), "currency", currency, "note", "提交"))))
        .andExpect(status().isOk())
        .andReturn();
  }

  String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString();
  }

  /**
   * 造一个**允许 null 值**的 JSON 对象。
   *
   * <p>{@code Map.of} 遇到 null 值会抛 NPE，而「上限留空 = 无上限」正是本模块的
   * 正常业务语义（maxAmount / maxQty 都要能传 null）⇒ 这里用 LinkedHashMap。
   */
  static Map<String, Object> body(Object... pairs) {
    Map<String, Object> out = new java.util.LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      out.put((String) pairs[i], pairs[i + 1]);
    }
    return out;
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明_阈值审批已交付且通用审批流如实标不可用() throws Exception {
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/capabilities")
                .header("Authorization", "Bearer submitter"))
        .andExpect(status().isOk())
        // 7.4.1.1 的交接点：thresholdApproval 由 false 翻成 true。
        .andExpect(jsonPath("$.capabilities[?(@.key=='thresholdApproval')].available").value(true))
        .andExpect(jsonPath("$.capabilities[?(@.key=='thresholdApproval')].ownerModule").value("7.4.1.2"))
        // 17 组织的通用审批流：如实标不可用并点名原因。
        .andExpect(jsonPath("$.capabilities[?(@.key=='identityApproval')].available").value(false))
        // 金额为什么是申报值：如实说清。
        .andExpect(jsonPath("$.capabilities[?(@.key=='inventoryCostBasis')].available").value(false));

    // 文案本身也要逐条说到位（能力声明的价值全在「讲清楚」上）。
    var capabilityBody =
        body(
            http.perform(
                    get("/warehouse/inventory/adjustment-approvals/capabilities")
                        .header("Authorization", "Bearer submitter"))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(capabilityBody).contains("数量阈值");
    assertThat(capabilityBody).contains("调整单类型");
    assertThat(capabilityBody).contains("登记额");
  }

  @Test
  void 上游能力声明_阈值审批位已翻成可用() throws Exception {
    http.perform(
            get("/warehouse/inventory/adjustments/capabilities")
                .header("Authorization", "Bearer submitter"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.dependencies[?(@.key=='thresholdApproval')].available").value(true));
  }

  // ================================================================ 阈值判定

  @Test
  void 阈值判定_按金额区间分档() throws Exception {
    createRule("R-SMALL", "ANY", "ANY", "0", "1000", "0", null, "L1", 1);
    createRule("R-BIG", "ANY", "ANY", "1000", null, "0", null, "L2", 2);

    var small = newOrder("DAMAGE", new BigDecimal("-2"));
    submit(small, "500", "USD");
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/" + small)
                .header("Authorization", "Bearer submitter"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalCase.ruleMatch").value("RULE"))
        .andExpect(jsonPath("$.approvalCase.requiredTier").value("L1"))
        .andExpect(jsonPath("$.approvalCase.requiredApprovals").value(1))
        .andExpect(jsonPath("$.approvalCase.orderAmount").value(500.0000));

    var big = newOrder("DAMAGE", new BigDecimal("-2"));
    submit(big, "5000", "USD");
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/" + big)
                .header("Authorization", "Bearer submitter"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalCase.requiredTier").value("L2"))
        .andExpect(jsonPath("$.approvalCase.requiredApprovals").value(2));
  }

  @Test
  void 阈值判定_按数量区间分档且绝对量盘盈盘亏都计入() throws Exception {
    // 量纲 = Σ|qty_delta|：一张 -2 的单绝对量是 2。
    createRule("R-QTY-SMALL", "ANY", "ANY", "0", null, "0", "10", "L1", 1);
    createRule("R-QTY-BIG", "ANY", "ANY", "0", null, "10", null, "L3", 3);

    var small = newOrder("SHORTAGE", new BigDecimal("-2"));
    submit(small, "100", "USD");
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/" + small)
                .header("Authorization", "Bearer submitter"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalCase.requiredTier").value("L1"))
        .andExpect(jsonPath("$.approvalCase.orderQty").value(2.0000));

    var big = newOrder("SHORTAGE", new BigDecimal("-20"));
    submit(big, "100", "USD");
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/" + big)
                .header("Authorization", "Bearer submitter"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalCase.requiredTier").value("L3"))
        .andExpect(jsonPath("$.approvalCase.requiredApprovals").value(3))
        .andExpect(jsonPath("$.approvalCase.orderQty").value(20.0000));
  }

  @Test
  void 阈值判定_按调整单类型分档() throws Exception {
    createRule("R-SCRAP", "SCRAP", "ANY", "0", null, "0", null, "L3", 2);
    createRule("R-OTHER", "ANY", "ANY", "0", null, "0", null, "L1", 1);

    var scrap = newOrder("SCRAP", new BigDecimal("-3"));
    submit(scrap, "100", "USD");
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/" + scrap)
                .header("Authorization", "Bearer submitter"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalCase.requiredTier").value("L3"))
        .andExpect(jsonPath("$.approvalCase.requiredApprovals").value(2));

    var damage = newOrder("DAMAGE", new BigDecimal("-3"));
    submit(damage, "100", "USD");
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/" + damage)
                .header("Authorization", "Bearer submitter"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalCase.requiredTier").value("L1"));
  }

  @Test
  void 一条都没命中时走默认档_最低层级一人_绝不当作已批() throws Exception {
    var order = newOrder("DAMAGE", new BigDecimal("-1"));
    submit(order, "100", "USD");
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/" + order)
                .header("Authorization", "Bearer submitter"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalCase.ruleMatch").value("DEFAULT"))
        .andExpect(jsonPath("$.approvalCase.requiredTier").value("L1"))
        .andExpect(jsonPath("$.approvalCase.requiredApprovals").value(1))
        .andExpect(jsonPath("$.approvalCase.status").value("PENDING"));
  }

  @Test
  void 提交待审_判定被冻结且余额此刻未动() throws Exception {
    createRule("R-BIG", "ANY", "ANY", "1000", null, "0", null, "L2", 2);
    var order = newOrder("DAMAGE", new BigDecimal("-2"));
    submit(order, "5000", "USD");

    // 余额未动：过账之前，bucket 的在手量还是原值。
    var bucket =
        access.run(
            actorSubmitter,
            "warehouse.inventory.read",
            () ->
                sql.fetchOne(
                    "select on_hand from stock_bucket where tenant_id = ? and location_id = ?",
                    a,
                    idFor(a, 5)));
    assertThat(bucket.get("on_hand", BigDecimal.class)).isEqualByComparingTo("100");

    // 判定被冻结：事后改规则不改已冻结的档位。
    createRule("R-CHANGED", "ANY", "ANY", "0", null, "0", null, "L1", 1);
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/" + order)
                .header("Authorization", "Bearer submitter"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalCase.requiredTier").value("L2"))
        .andExpect(jsonPath("$.approvalCase.requiredApprovals").value(2));
  }

  // ================================================================ 审批闸门

  @Test
  void 审批人数没凑齐时批不动() throws Exception {
    createRule("R-BIG", "ANY", "ANY", "1000", null, "0", null, "L2", 2);
    createApprover(approverOne, "L2", "ANY", "0", null, null);
    createApprover(approverTwo, "L2", "ANY", "0", null, null);
    var order = newOrder("DAMAGE", new BigDecimal("-2"));
    submit(order, "5000", "USD");

    http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + order + "/approve")
                .header("Authorization", "Bearer approver-one")
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("note", "第一个人批"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalCase.approvedCount").value(1))
        .andExpect(jsonPath("$.approvalCase.status").value("PENDING"));

    // 第一个人的审批**没有**把调整单批成已批。
    http.perform(
            get("/warehouse/inventory/adjustments/" + order)
                .header("Authorization", "Bearer submitter"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.status").value("PENDING_APPROVAL"));
  }

  @Test
  void 绕过阈值直接调上游批准端点被数据库闸门拒绝() throws Exception {
    createRule("R-BIG", "ANY", "ANY", "0", null, "0", null, "L2", 2);
    createApprover(approverOne, "L2", "ANY", "0", null, null);
    var order = newOrder("DAMAGE", new BigDecimal("-2"));
    submit(order, "5000", "USD");

    // 走 7.4.1.1 的批准端点：审批单还在 PENDING ⇒ 数据库闸门当场拒绝。
    http.perform(
            post("/warehouse/inventory/adjustments/" + order + "/approve")
                .header("Authorization", "Bearer approver-one")
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("note", "绕过阈值"))))
        .andExpect(status().isConflict());

    // 拿连接直接改状态也一样被拒（这才是真正的闸门）。
    assertThatThrownBy(
            () ->
                access.run(
                    actorApproverOne,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "update inventory_adjustment_order set status='APPROVED',"
                              + " decided_by_user_id=?, decided_at=?::timestamptz, decision_note='硬改'"
                              + " where tenant_id = ? and id = ?",
                          approverOne,
                          now(),
                          a,
                          order);
                      return null;
                    }))
        .hasStackTraceContaining("凑齐");
  }

  @Test
  void 人数凑齐后自动放行为已批且能过账() throws Exception {
    createRule("R-BIG", "ANY", "ANY", "1000", null, "0", null, "L2", 2);
    createApprover(approverOne, "L2", "ANY", "0", null, null);
    createApprover(approverTwo, "L2", "ANY", "0", null, null);
    var order = newOrder("DAMAGE", new BigDecimal("-2"));
    submit(order, "5000", "USD");

    http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + order + "/approve")
                .header("Authorization", "Bearer approver-one")
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("note", "第一个人批"))))
        .andExpect(status().isOk());
    http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + order + "/approve")
                .header("Authorization", "Bearer approver-two")
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("note", "第二个人批"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalCase.approvedCount").value(2))
        .andExpect(jsonPath("$.approvalCase.status").value("APPROVED"));

    http.perform(
            get("/warehouse/inventory/adjustments/" + order)
                .header("Authorization", "Bearer submitter"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.status").value("APPROVED"));
  }

  @Test
  void 提交人不能批自己提的单() throws Exception {
    createRule("R-ANY", "ANY", "ANY", "0", null, "0", null, "L1", 1);
    createApprover(submitter, "L1", "ANY", "0", null, null);
    var order = newOrder("DAMAGE", new BigDecimal("-2"));
    submit(order, "100", "USD");

    http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + order + "/approve")
                .header("Authorization", "Bearer submitter")
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("note", "自提自批"))))
        .andExpect(status().isConflict());
  }

  @Test
  void 审批人授权区间之外不能批() throws Exception {
    createRule("R-BIG", "ANY", "ANY", "1000", null, "0", null, "L2", 1);
    // 只授权到 1000：这单 5000 超出授权区间。
    createApprover(approverOne, "L2", "ANY", "0", "1000", null);
    var order = newOrder("DAMAGE", new BigDecimal("-2"));
    submit(order, "5000", "USD");

    http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + order + "/approve")
                .header("Authorization", "Bearer approver-one")
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("note", "超区间"))))
        .andExpect(status().isConflict());
  }

  @Test
  void 批准必须写理由() throws Exception {
    createRule("R-ANY", "ANY", "ANY", "0", null, "0", null, "L1", 1);
    createApprover(approverOne, "L1", "ANY", "0", null, null);
    var order = newOrder("DAMAGE", new BigDecimal("-2"));
    submit(order, "100", "USD");

    http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + order + "/approve")
                .header("Authorization", "Bearer approver-one")
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("note", "  "))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 驳回必须写意见且意见进审批链路() throws Exception {
    createRule("R-ANY", "ANY", "ANY", "0", null, "0", null, "L1", 1);
    createApprover(approverOne, "L1", "ANY", "0", null, null);
    var order = newOrder("DAMAGE", new BigDecimal("-2"));
    submit(order, "100", "USD");

    http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + order + "/reject")
                .header("Authorization", "Bearer approver-one")
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("note", ""))))
        .andExpect(status().isBadRequest());

    http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + order + "/reject")
                .header("Authorization", "Bearer approver-one")
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("note", "凭证不足，请补照片"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approvalCase.status").value("REJECTED"))
        .andExpect(jsonPath("$.approvalCase.rejectReason").value("凭证不足，请补照片"))
        .andExpect(jsonPath("$.records[?(@.action=='REJECTED')].detail").exists());
  }

  @Test
  void 审批链路记录是追加式的() throws Exception {
    createRule("R-ANY", "ANY", "ANY", "0", null, "0", null, "L1", 1);
    createApprover(approverOne, "L1", "ANY", "0", null, null);
    var order = newOrder("DAMAGE", new BigDecimal("-2"));
    submit(order, "100", "USD");

    // 两道防线都要在：
    // ① REVOKE（运行角色连改都不许）—— 用应用连接；
    assertThatThrownBy(
            () ->
                access.run(
                    actorApproverOne,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "update inventory_adjustment_approval_record set detail='改写一下'");
                      return null;
                    }))
        .hasStackTraceContaining("permission denied");
    // ② 触发器（超管也绕不过去）—— 用超管连接，证明「即使拿到最高权限也改不动审批留痕」。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(() -> s.execute("update inventory_adjustment_approval_record set detail='改写一下'"))
          .hasStackTraceContaining("append-only");
    }
  }

  // ================================================================ 批量

  @Test
  void 批量审批逐条给结论_一张失败不影响其它张() throws Exception {
    createRule("R-ANY", "ANY", "ANY", "0", null, "0", null, "L1", 1);
    createApprover(approverOne, "L1", "ANY", "0", null, null);
    var ok = newOrder("DAMAGE", new BigDecimal("-2"));
    var alsoOk = newOrder("DAMAGE", new BigDecimal("-3"));
    submit(ok, "100", "USD");
    submit(alsoOk, "100", "USD");

    var result =
        http.perform(
                post("/warehouse/inventory/adjustment-approvals/batch/approve")
                    .header("Authorization", "Bearer approver-one")
                    .contentType("application/json")
                    .content(
                        JSON.writeValueAsString(
                            Map.of(
                                "orderIds",
                                List.of(ok.toString(), alsoOk.toString(), UUID.randomUUID().toString()),
                                "note", "批量批"))))
            .andExpect(status().isOk())
            .andReturn();

    var json = body(result);
    assertThat(json).contains("\"successCount\":2");
    assertThat(json).contains("\"failureCount\":1");
    // 失败的那条**不能**被静默丢弃：必须逐条给出原因。
    assertThat(json).contains("不存在");
  }

  @Test
  void 批量驳回必须附意见() throws Exception {
    http.perform(
            post("/warehouse/inventory/adjustment-approvals/batch/reject")
                .header("Authorization", "Bearer approver-one")
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("orderIds", List.of(UUID.randomUUID().toString())))))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 会计影响

  @Test
  void 会计影响_登记额与生效额分两列_过账后生效额等于登记额() throws Exception {
    createRule("R-ANY", "ANY", "ANY", "0", null, "0", null, "L1", 1);
    createApprover(approverOne, "L1", "ANY", "0", null, null);
    var order = newOrder("DAMAGE", new BigDecimal("-2"));
    submit(order, "123.45", "USD");

    // 登记了但还没过账 ⇒ 生效额恒为 0。
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/valuations")
                .header("Authorization", "Bearer submitter"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].registeredAmount").value(123.4500))
        .andExpect(jsonPath("$.items[0].effectiveAmount").value(0.0000))
        .andExpect(jsonPath("$.items[0].status").value("REGISTERED"));

    // 未过账时把生效额硬改成非 0 ⇒ 表级 CHECK 拒绝。
    assertThatThrownBy(
            () ->
                access.run(
                    actorApproverOne,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "update inventory_adjustment_valuation set effective_amount=999");
                      return null;
                    }))
        .hasStackTraceContaining("effective_amount");

    // 批 ⇒ 过账 ⇒ 生效额 = 登记额。
    http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + order + "/approve")
                .header("Authorization", "Bearer approver-one")
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("note", "同意"))))
        .andExpect(status().isOk());
    http.perform(
            post("/warehouse/inventory/adjustments/" + order + "/post")
                .header("Authorization", "Bearer submitter")
                .contentType("application/json")
                .content(JSON.writeValueAsString(Map.of("note", "过账"))))
        .andExpect(status().isOk());

    http.perform(
            get("/warehouse/inventory/adjustment-approvals/valuations")
                .header("Authorization", "Bearer submitter"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].registeredAmount").value(123.4500))
        .andExpect(jsonPath("$.items[0].effectiveAmount").value(123.4500))
        .andExpect(jsonPath("$.items[0].status").value("EFFECTIVE"));
  }

  // ================================================================ 入参校验

  @Test
  void 申报金额必须大于零且不得带超过四位小数() throws Exception {
    var zero = newOrder("DAMAGE", new BigDecimal("-2"));
    http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + zero + "/submit")
                .header("Authorization", "Bearer submitter")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        Map.of("amount", new BigDecimal("0"), "currency", "USD"))))
        .andExpect(status().isBadRequest());

    var tooPrecise = newOrder("DAMAGE", new BigDecimal("-2"));
    http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + tooPrecise + "/submit")
                .header("Authorization", "Bearer submitter")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        Map.of("amount", new BigDecimal("1.00001"), "currency", "USD"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 币种必须是三个大写字母() throws Exception {
    var order = newOrder("DAMAGE", new BigDecimal("-2"));
    http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + order + "/submit")
                .header("Authorization", "Bearer submitter")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        Map.of("amount", new BigDecimal("10"), "currency", "人民币"))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 只读角色不能提交待审() throws Exception {
    var order = newOrder("DAMAGE", new BigDecimal("-2"));
    http.perform(
            post("/warehouse/inventory/adjustment-approvals/" + order + "/submit")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        Map.of("amount", new BigDecimal("10"), "currency", "USD"))))
        .andExpect(status().isForbidden());
  }

  // ================================================================ 待我审批

  @Test
  void 待我审批_带能不能批与原因_不该由我批的也显示出来() throws Exception {
    createRule("R-BIG", "ANY", "ANY", "0", null, "0", null, "L2", 2);
    createApprover(approverOne, "L1", "ANY", "0", null, null);
    var order = newOrder("DAMAGE", new BigDecimal("-2"));
    submit(order, "5000", "USD");

    http.perform(
            get("/warehouse/inventory/adjustment-approvals/pending")
                .header("Authorization", "Bearer approver-one"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].approvable").value(false))
        .andExpect(jsonPath("$.items[0].approvableReason")
            .value(org.hamcrest.Matchers.containsString("审批层级不够")));
  }

  @Test
  void 阈值判定试算_不落库也能看到会命中哪一档() throws Exception {
    createRule("R-BIG", "ANY", "ANY", "1000", null, "0", null, "L2", 2);
    var order = newOrder("DAMAGE", new BigDecimal("-2"));

    http.perform(
            post("/warehouse/inventory/adjustment-approvals/evaluate")
                .header("Authorization", "Bearer submitter")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "orderId", order.toString(),
                            "amount", new BigDecimal("5000"),
                            "currency", "USD"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ruleMatch").value("RULE"))
        .andExpect(jsonPath("$.requiredTier").value("L2"))
        .andExpect(jsonPath("$.requiredApprovals").value(2))
        .andExpect(jsonPath("$.orderQty").value(2.0000));

    // 试算不落库：这张单还没有审批单。
    http.perform(
            get("/warehouse/inventory/adjustment-approvals/" + order)
                .header("Authorization", "Bearer submitter"))
        .andExpect(status().isNotFound());
  }
}
