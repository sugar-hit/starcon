package com.starcon.trade;

import static com.starcon.trade.WarehouseAllocationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 9.3.2.1 分仓规则。
 *
 * <p>本测试是 <b>V116 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论， 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不是只直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>三条最要紧的断言各自单列一节</b>：
 *
 * <ol>
 *   <li><b>货权参与判定</b>：法人不符的仓**在写入路径上**选不中（绕过服务层写 SQL 也被挡）， 且跨法人必须显式兜底并被标记；
 *   <li><b>挂起是硬输出</b>：无法分配时逐条列出排除原因，没有挂起记录提交不了， 挂起态与选中候选互斥（绝不静默兜底）；
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WarehouseAllocationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("fulfillment")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  /** 订单货权主体。 */
  static final String ENT_A = "CN-ENT-A";

  /** 另一个法人的仓 —— 它更便宜，正是「不能只看运费便宜」要挡住的情形。 */
  static final String ENT_B = "US-ENT-B";

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-trade-allocation-test-" + tenant + "-" + ordinal).getBytes());
  }

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
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("trade.fulfillment.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired WarehouseAllocationService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.fulfillment.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.fulfillment.update", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  /**
   * 每个租户一整套夹具：三个仓（两个属本租户货权法人 A、一个属法人 B）、各仓可用量、 发往 US 的运费与时效估算，以及一条默认规则。
   *
   * <p>三个仓的**定价刻意让法人 B 的仓最便宜** —— 只有这样，「为什么没选更便宜的仓」 才真的验证了货权参与判定，而不是碰巧选中了最便宜的。
   */
  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate fulfillment_warehouse_allocation_event,"
              + " fulfillment_warehouse_allocation_hold,"
              + " fulfillment_warehouse_allocation_candidate,"
              + " fulfillment_warehouse_allocation_decision,"
              + " fulfillment_warehouse_allocation_shipping_estimate,"
              + " fulfillment_warehouse_allocation_stock_estimate,"
              + " fulfillment_warehouse_allocation_warehouse,"
              + " fulfillment_warehouse_allocation_rule cascade");
    }
    for (var tenant : List.of(a, b)) {
      seedTenant(tenant);
    }
  }

  private void seedTenant(UUID tenant) {
    as(
        tenant,
        () -> {
          warehouse(tenant, 1, "WH-CN-SH", "上海仓", ENT_A, "CN-EAST", 10);
          warehouse(tenant, 2, "WH-CN-BJ", "北京仓", ENT_A, "CN-NORTH", 20);
          warehouse(tenant, 3, "WH-US-LAX", "洛杉矶仓", ENT_B, "US-WEST", 30);

          stock(tenant, 11, "WH-CN-SH", "200");
          stock(tenant, 12, "WH-CN-BJ", "200");
          stock(tenant, 13, "WH-US-LAX", "500");

          // 定价：法人 B 的仓最便宜 —— 货权闸门的价值就在这里。
          shipping(tenant, 21, "WH-CN-SH", "18.0000", "5.00");
          shipping(tenant, 22, "WH-CN-BJ", "15.0000", "3.00");
          shipping(tenant, 23, "WH-US-LAX", "6.5000", "2.00");

          rule(
              tenant,
              31,
              "RULE-DEFAULT",
              10,
              "HIGHEST_SCORE",
              "SUSPEND",
              "0.2500",
              "0.2500",
              "0.1500",
              "0.2500",
              "0.1000",
              true,
              "ORDER_OWNER_FIRST",
              "");
          return null;
        });
  }

  void warehouse(
      UUID tenant, int ord, String ref, String name, String entity, String region, int priority) {
    sql.execute(
        "insert into fulfillment_warehouse_allocation_warehouse"
            + "(id,tenant_id,warehouse_ref,warehouse_name,legal_entity_code,region_code,"
            + "default_priority,active,created_by_membership_id)"
            + " values (?,?,?,?,?,?,?,true,?)",
        idFor(tenant, ord),
        tenant,
        ref,
        name,
        entity,
        region,
        priority,
        user);
  }

  void stock(UUID tenant, int ord, String ref, String qty) {
    sql.execute(
        "insert into fulfillment_warehouse_allocation_stock_estimate"
            + "(id,tenant_id,warehouse_ref,sku_ref,available_qty,collected_at,stock_source,"
            + "source_reference,created_by_membership_id)"
            + " values (?,?,?,'SKU-1',?,?::timestamptz,'TENANT_MAINTAINED',?,?)",
        idFor(tenant, ord),
        tenant,
        ref,
        new BigDecimal(qty),
        at(-1),
        ref,
        user);
  }

  void shipping(UUID tenant, int ord, String ref, String cost, String lead) {
    sql.execute(
        "insert into fulfillment_warehouse_allocation_shipping_estimate"
            + "(id,tenant_id,warehouse_ref,sku_ref,destination_country_code,shipping_cost,"
            + "lead_time_days,currency_code,cost_source,source_reference,effective_from,"
            + "created_by_membership_id)"
            + " values (?,?,?,'SKU-1','US',?,?,'USD','TENANT_MAINTAINED',?,"
            + "?::timestamptz,?)",
        idFor(tenant, ord),
        tenant,
        ref,
        new BigDecimal(cost),
        new BigDecimal(lead),
        ref,
        at(-30),
        user);
  }

  void rule(
      UUID tenant,
      int ord,
      String code,
      int priority,
      String selection,
      String fallback,
      String wStock,
      String wCost,
      String wLead,
      String wLegal,
      String wRegion,
      boolean requireMatch,
      String legalPolicy,
      String preferred) {
    sql.execute(
        "insert into fulfillment_warehouse_allocation_rule"
            + "(id,tenant_id,rule_code,rule_name,priority,scope_platform_code,"
            + "scope_destination_country_code,selection_policy,fallback_policy,weight_stock,"
            + "weight_cost,weight_lead_time,weight_legal_entity,weight_region,"
            + "require_legal_entity_match,legal_entity_policy,preferred_legal_entity_code,"
            + "currency_code,excluded_warehouse_refs,effective_from,created_by_membership_id)"
            + " values (?,?,?,?,?,'*','*',?,?,?,?,?,?,?,?,?,?,'USD','',?::timestamptz,?)",
        idFor(tenant, ord),
        tenant,
        code,
        code,
        priority,
        selection,
        fallback,
        new BigDecimal(wStock),
        new BigDecimal(wCost),
        new BigDecimal(wLead),
        new BigDecimal(wLegal),
        new BigDecimal(wRegion),
        requireMatch,
        legalPolicy,
        preferred,
        at(-30),
        user);
  }

  static OffsetDateTime at(int daysFromNow) {
    return OffsetDateTime.now(ZoneOffset.ofHours(8))
        .plusDays(daysFromNow)
        .withNano(0)
        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
  }

  /**
   * 沿 cause 链找最里面那条 SQLException —— DB 约束拒绝的**原文**在那里。
   *
   * <p>为什么不用外层异常：jOOQ 会把消息换成「bad SQL grammar [原 SQL]」， Spring 再包一层。真正说明「为什么被拒」的是最里面的 SQLException，
   * 断言外层消息等于断言一句与真实原因无关的话。
   */
  static java.sql.SQLException rootSqlException(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof java.sql.SQLException sql) {
        return sql;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    throw new AssertionError("异常链里没有 SQLException，说明不是 DB 拒绝：", error);
  }

  /**
   * 断言一段工作被 DB 拒绝。
   *
   * @param acceptableStates 可接受的 SQLState（23514 = 触发器/check 约束，42501 = 权限不足）
   * @param expectedFragment 拒绝理由里应当出现的片段；仅当走的是 23514 时断言 （权限不足那条是 PostgreSQL 自己发的，没有自定义文案）
   */
  static void assertDbRefused(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable work,
      String expectedFragment,
      String... acceptableStates) {
    var error = catchThrowable(work);
    var sql = rootSqlException(error);
    assertThat(sql.getSQLState())
        .as("必须被 DB 拒绝，实际 SQLState=%s，消息=%s", sql.getSQLState(), sql.getMessage())
        .isIn(acceptableStates);
    if ("23514".equals(sql.getSQLState())) {
      assertThat(sql.getMessage()).as("拒绝理由必须可读").contains(expectedFragment);
    }
  }

  /** 以表所有者身份直连执行一条 SQL（可抛受检异常）。 */
  @FunctionalInterface
  interface OwnerSql {
    void run() throws Exception;
  }

  /**
   * 以**表所有者**（迁移用户）身份直连执行一条 SQL。
   *
   * <p>存在的理由：append-only 有两层 —— 对运行角色 REVOKE 权限（42501）， 以及 BEFORE 触发器（23514）。只测前者的话触发器就成了永远跑不到的死代码；
   * 要证明它真的有效，必须绕开权限、以 owner 身份再撞一次。 FORCE RLS 对 owner 同样生效，所以这里仍要显式绑定 app.current_tenant。
   */
  static OwnerSql asTableOwner(String sql) {
    return () -> {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var st = c.createStatement()) {
        st.execute("select set_config('app.current_tenant','" + a + "',false)");
        st.execute(sql);
      }
    };
  }

  // ==========================================================================
  // 夹具构造
  // ==========================================================================
  WarehouseAllocationSimulationRequest simulation(String orderRef, String qty, String orderEntity) {
    return new WarehouseAllocationSimulationRequest(
        orderRef,
        "L-1",
        "AMAZON",
        "SKU-1",
        new BigDecimal(qty),
        "US",
        "CN-EAST",
        orderEntity,
        null,
        null);
  }

  WarehouseAllocationDecisionRequest decision(
      String orderRef, String qty, String orderEntity, String ruleCode) {
    return new WarehouseAllocationDecisionRequest(
        orderRef,
        "L-1",
        "AMAZON",
        "SKU-1",
        new BigDecimal(qty),
        "US",
        "CN-EAST",
        orderEntity,
        ruleCode,
        null,
        null);
  }

  // ==========================================================================
  // 迁移落地与 RLS 强制
  // ==========================================================================
  @Test
  void 迁移落地且八张表全部强制rls() {
    for (var table :
        List.of(
            "fulfillment_warehouse_allocation_rule",
            "fulfillment_warehouse_allocation_warehouse",
            "fulfillment_warehouse_allocation_stock_estimate",
            "fulfillment_warehouse_allocation_shipping_estimate",
            "fulfillment_warehouse_allocation_decision",
            "fulfillment_warehouse_allocation_candidate",
            "fulfillment_warehouse_allocation_hold",
            "fulfillment_warehouse_allocation_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity,relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V116 必须建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住运行角色被误授 owner").isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as(table + "：trade_app 必须真的能读")
          .isTrue();
    }
  }

  /** 本迁移**零 ALTER** V92 的七张表：它们是 9.3.2.2 的唯一写入所有者。 */
  @Test
  void 零alter兄弟车道的七张表() {
    for (var table :
        List.of(
            "fulfillment_mcf_authorization",
            "fulfillment_path_cost_profile",
            "fulfillment_platform_stock_snapshot",
            "fulfillment_routing_decision",
            "fulfillment_routing_candidate",
            "fulfillment_routing_switch",
            "fulfillment_routing_event")) {
      assertThat(sql.fetchOne("select 1 from pg_class where relname = ?", table))
          .as("V92 的 " + table + " 必须仍然存在")
          .isNotNull();
    }
    // 本模块的表不得混进 routing 命名空间：一张都没有。
    // relkind='r' 只取表：pg_class 里还有 fulfillment_routing_* 的索引，
    // 不限定 relkind 会把兄弟车道自己的索引误判成「新表」。
    assertThat(
            sql.fetch(
                "select relname from pg_class where relname like 'fulfillment_routing_%'"
                    + " and relkind = 'r'"
                    + " and relname not in ('fulfillment_routing_decision',"
                    + "'fulfillment_routing_candidate','fulfillment_routing_switch',"
                    + "'fulfillment_routing_event')"))
        .as("不得在兄弟车道的命名空间里新增表")
        .isEmpty();
  }

  @Test
  void 能力声明把上游不可用如实报出来() {
    var caps = service.capabilities();
    assertThat(caps.crossServiceReadAvailable()).isFalse();
    assertThat(caps.reservesUpstreamStock()).isFalse();
    assertThat(caps.dimensions())
        .extracting(WarehouseAllocationCapabilities.WarehouseAllocationDimension::code)
        .contains(
            "WAREHOUSE_STOCK",
            "SHIPPING_COST",
            "LEGAL_ENTITY",
            "ORDER_DESTINATION",
            "OUTBOUND_HANDOFF");
    var orderDimension =
        caps.dimensions().stream()
            .filter(d -> d.code().equals("ORDER_DESTINATION"))
            .findFirst()
            .orElseThrow();
    assertThat(orderDimension.available()).as("订单表未交付，必须显式报不可用").isFalse();
    assertThat(orderDimension.unavailableReason()).isNotBlank();
    assertThat(caps.legalEntityNote()).contains("资格闸").contains("排序权重");
  }

  // ==========================================================================
  // 货权参与判定（蓝图加粗句的验收点）
  // ==========================================================================
  @Test
  void 货权硬闸挡住更便宜的别的法人的仓() {
    var sim = asA(() -> service.simulate(simulation("SO-LEGAL-1", "50", ENT_A)));
    // 三个仓都够货，但只有法人 A 的两个仓可选；LAX 仓便宜一半却被挡住。
    assertThat(sim.chosenWarehouseRef()).isIn("WH-CN-SH", "WH-CN-BJ");
    assertThat(sim.chosenLegalEntityCode()).isEqualTo(ENT_A);
    assertThat(sim.crossLegalEntity()).isFalse();
    var lax =
        sim.candidates().stream()
            .filter(c -> c.warehouseRef().equals("WH-US-LAX"))
            .findFirst()
            .orElseThrow();
    assertThat(lax.feasible()).as("法人 B 的仓不得可行").isFalse();
    assertThat(lax.infeasibleReasonCode()).isEqualTo("LEGAL_ENTITY_MISMATCH");
    assertThat(lax.infeasibleReason()).contains(ENT_B).contains(ENT_A);
    // 便宜这个事实要被如实报出来，而不是悄悄抹掉。
    assertThat(lax.shippingCost()).isEqualByComparingTo("6.5000");
    // 选中仓的货权得分满分，且理由句里能看出货权的贡献。
    var sh =
        sim.candidates().stream()
            .filter(c -> c.warehouseRef().equals(sim.chosenWarehouseRef()))
            .findFirst()
            .orElseThrow();
    assertThat(sh.scoreLegalEntity()).isEqualByComparingTo("1.0000");
    assertThat(sim.reason()).contains("货权法人").contains("权重 0.2500");
  }

  @Test
  void 货权作为权重在同门内参与排序() {
    // 关掉硬闸、把权重压到成本上：这时「更便宜」应该赢。
    asA(
        () -> {
          rule(
              a,
              32,
              "RULE-COST-HEAVY",
              5,
              "HIGHEST_SCORE",
              "SUSPEND",
              "0.1000",
              "0.6000",
              "0.1000",
              "0.1000",
              "0.1000",
              false,
              "ORDER_OWNER_FIRST",
              "");
          return null;
        });
    var costHeavy =
        asA(
            () ->
                service.simulate(
                    new WarehouseAllocationSimulationRequest(
                        "SO-W-COST",
                        "L-1",
                        "AMAZON",
                        "SKU-1",
                        new BigDecimal("50"),
                        "US",
                        "CN-EAST",
                        ENT_A,
                        "RULE-COST-HEAVY",
                        null)));
    assertThat(costHeavy.chosenWarehouseRef())
        .as("成本权重 0.6 > 货权权重 0.1 时应选更便宜的 LAX 仓")
        .isEqualTo("WH-US-LAX");

    // 换成货权权重压倒成本：同一份数据，结论必须反过来。
    asA(
        () -> {
          rule(
              a,
              33,
              "RULE-LEGAL-HEAVY",
              4,
              "HIGHEST_SCORE",
              "SUSPEND",
              "0.1000",
              "0.1000",
              "0.1000",
              "0.6000",
              "0.1000",
              false,
              "ORDER_OWNER_FIRST",
              "");
          return null;
        });
    var legalHeavy =
        asA(
            () ->
                service.simulate(
                    new WarehouseAllocationSimulationRequest(
                        "SO-W-LEGAL",
                        "L-1",
                        "AMAZON",
                        "SKU-1",
                        new BigDecimal("50"),
                        "US",
                        "CN-EAST",
                        ENT_A,
                        "RULE-LEGAL-HEAVY",
                        null)));
    assertThat(legalHeavy.chosenWarehouseRef())
        .as("货权权重 0.6 时应选与订单主体同法人的仓，即使它更贵")
        .isEqualTo("WH-CN-SH");
    var shRow =
        legalHeavy.candidates().stream()
            .filter(c -> c.warehouseRef().equals("WH-CN-SH"))
            .findFirst()
            .orElseThrow();
    var laxRow =
        legalHeavy.candidates().stream()
            .filter(c -> c.warehouseRef().equals("WH-US-LAX"))
            .findFirst()
            .orElseThrow();
    assertThat(shRow.scoreLegalEntity()).isEqualByComparingTo("1.0000");
    assertThat(laxRow.scoreLegalEntity()).isEqualByComparingTo("0.0000");
    assertThat(shRow.totalScore()).isGreaterThan(laxRow.totalScore());
  }

  @Test
  void 指定法人优先策略要求给出目标法人() {
    assertThatThrownBy(
            () -> asA(() -> service.saveRule(ruleInput("RULE-NO-PREF", 20, "PREFERRED_FIRST", ""))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须给出目标法人");
  }

  @Test
  void 跨法人必须显式兜底且被如实标记() {
    asA(
        () -> {
          rule(
              a,
              34,
              "RULE-CROSS",
              3,
              "LOWEST_COST",
              "CROSS_LEGAL_ENTITY",
              "0.2000",
              "0.5000",
              "0.2000",
              "0.0000",
              "0.1000",
              false,
              "NEUTRAL",
              "");
          return null;
        });
    var saved =
        asA(
            () ->
                service.allocate(
                    new WarehouseAllocationDecisionRequest(
                        "SO-CROSS",
                        "L-1",
                        "AMAZON",
                        "SKU-1",
                        new BigDecimal("50"),
                        "US",
                        "CN-EAST",
                        ENT_A,
                        "RULE-CROSS",
                        null,
                        null),
                    user));
    assertThat(saved.crossLegalEntity()).as("跨法人必须被标记").isTrue();
    assertThat(saved.chosenLegalEntityCode()).isEqualTo(ENT_B);
    assertThat(saved.status()).isEqualTo(WarehouseAllocationStatus.ALLOCATED);
    assertThat(saved.message()).contains("跨法人").contains(ENT_B);
  }

  /** 绕过服务层直接写 SQL：货权不符的仓**同样**选不中。 */
  @Test
  void 绕过服务层写sql也选不中货权不符的仓() {
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into fulfillment_warehouse_allocation_decision"
                              + "(id,tenant_id,order_ref,order_line_ref,sku_ref,requested_qty,"
                              + "rule_id,selection_policy,fallback_policy,legal_entity_policy,"
                              + "require_legal_entity_match,destination_country_code,"
                              + "destination_region_code,order_legal_entity_code,status,"
                              + "chosen_warehouse_ref,chosen_legal_entity_code,cross_legal_entity,"
                              + "allocated_qty,currency_code,reason_code,reason,decided_at,"
                              + "created_by_membership_id)"
                              + " values (?::uuid,?,'SO-SQL-BYPASS','L-1','SKU-1',50,"
                              + " ?::uuid,'HIGHEST_SCORE','SUSPEND','ORDER_OWNER_FIRST',true,'US',"
                              + "'CN-EAST',?,'ALLOCATED','WH-US-LAX',?,false,50,'USD',"
                              + "'SCORE_BASED','绕过服务层',?::timestamptz,?)",
                          UUID.randomUUID(),
                          a,
                          idFor(a, 31),
                          ENT_A,
                          ENT_B,
                          at(0),
                          user);
                      // 货权法人 B ≠ 订单主体 A，且规则未放开 CROSS_LEGAL_ENTITY。
                      sql.execute(
                          "insert into fulfillment_warehouse_allocation_candidate"
                              + "(id,tenant_id,decision_id,warehouse_ref,legal_entity_code,"
                              + "region_code,warehouse_priority,available_qty,legal_entity_match,"
                              + "feasible,total_score,selected)"
                              + " values (?::uuid,?,(select id from"
                              + " fulfillment_warehouse_allocation_decision where order_ref='SO-SQL-BYPASS'),"
                              + "'WH-US-LAX',?,'US-WEST',30,500,false,true,1.0,true)",
                          UUID.randomUUID(),
                          a,
                          ENT_B);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("不能只看运费便宜");
    assertThat(
            asA(
                () ->
                    service
                        .pageDecisions(
                            new WarehouseAllocationDecisionQuery(
                                "SO-SQL-BYPASS", null, null, null, null, null, null, null, 0, 10))
                        .total()))
        .as("整笔事务必须回滚，不得留下半条分配")
        .isZero();
  }

  // ==========================================================================
  // 无法分配 = 挂起（绝不静默兜底）
  // ==========================================================================
  @Test
  void 货权全不符时挂起并逐条列出排除原因() {
    var saved =
        asA(() -> service.allocate(decision("SO-HOLD-LEGAL", "50", "CN-ENT-ZZZ", null), user));
    assertThat(saved.status()).as("挂起是终态，不是回落到默认仓").isEqualTo(WarehouseAllocationStatus.SUSPENDED);
    assertThat(saved.chosenWarehouseRef()).isNull();
    assertThat(saved.allocatedQty()).isNull();
    assertThat(saved.reasonCode()).isEqualTo(WarehouseAllocationReasonCode.NO_ELIGIBLE_WAREHOUSE);
    assertThat(saved.holdReason()).isEqualTo(WarehouseHoldReason.BLOCKED_BY_LEGAL_ENTITY);
    assertThat(saved.holdId()).as("挂起必须有载体").isNotNull();

    var detail = asA(() -> service.decision(saved.decisionId()));
    assertThat(detail.holds()).hasSize(1);
    var hold = detail.holds().get(0);
    assertThat(hold.excludedWarehouseCount()).isEqualTo(3);
    // 逐条列举：每个仓一句，不是「无可行仓」四个字。
    assertThat(hold.exclusionDetail())
        .contains("WH-CN-SH=")
        .contains("WH-CN-BJ=")
        .contains("WH-US-LAX=");
    assertThat(hold.exclusionDetail()).contains("收入记在");
    // 一条选中候选都没有 —— 这才是「没有悄悄回落」的证明。
    assertThat(detail.candidates()).noneMatch(WarehouseAllocationCandidateRow::chosen);
    assertThat(detail.candidates()).allMatch(c -> !c.feasible());
    assertThat(detail.candidates()).allMatch(c -> !c.infeasibleReasonCode().isBlank());
    assertThat(detail.events())
        .extracting(WarehouseAllocationEventRow::eventKind)
        .contains("SUSPENDED");
  }

  @Test
  void 挂起清单可按原因与关键字检索() {
    asA(() -> service.allocate(decision("SO-HOLD-FEED-1", "50", "CN-ENT-ZZZ", null), user));
    asA(() -> service.allocate(decision("SO-HOLD-FEED-2", "20", "CN-ENT-ZZZ", null), user));
    var page =
        asA(
            () ->
                service.pageHolds(
                    new WarehouseAllocationHoldQuery(
                        null, null, "BLOCKED_BY_LEGAL_ENTITY", null, 0, 50)));
    assertThat(page.total()).isEqualTo(2);
    var search =
        asA(
            () ->
                service.pageHolds(
                    new WarehouseAllocationHoldQuery("SO-HOLD-FEED-2", null, null, null, 0, 50)));
    assertThat(search.total()).isEqualTo(1);
    assertThat(search.items().get(0).orderRef()).isEqualTo("SO-HOLD-FEED-2");
  }

  /** 没有挂起记录的 SUSPENDED 决策提交不了 —— DB 直接拒。 */
  @Test
  void 挂起但没有挂起记录提交不了() {
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into fulfillment_warehouse_allocation_decision"
                              + "(id,tenant_id,order_ref,order_line_ref,sku_ref,requested_qty,"
                              + "selection_policy,fallback_policy,legal_entity_policy,"
                              + "require_legal_entity_match,destination_country_code,"
                              + "destination_region_code,order_legal_entity_code,status,"
                              + "cross_legal_entity,currency_code,reason_code,reason,decided_at,"
                              + "created_by_membership_id)"
                              + " values (?::uuid,?,'SO-NO-HOLD','L-1','SKU-1',50,"
                              + "'HIGHEST_SCORE','SUSPEND','ORDER_OWNER_FIRST',true,'US','CN-EAST',"
                              + "?, 'SUSPENDED',false,'USD','NO_ELIGIBLE_WAREHOUSE','手动挂起',"
                              + "?::timestamptz,?)",
                          UUID.randomUUID(),
                          a,
                          ENT_A,
                          at(0),
                          user);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("挂起");
  }

  /** 挂起态却塞一条选中候选 —— 直接 23514，杜绝「这单没货」显示成「已决策走 A 仓」。 */
  @Test
  void 挂起态与选中候选互斥() {
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into fulfillment_warehouse_allocation_decision"
                              + "(id,tenant_id,order_ref,order_line_ref,sku_ref,requested_qty,"
                              + "selection_policy,fallback_policy,legal_entity_policy,"
                              + "require_legal_entity_match,destination_country_code,"
                              + "destination_region_code,order_legal_entity_code,status,"
                              + "cross_legal_entity,currency_code,reason_code,reason,decided_at,"
                              + "created_by_membership_id)"
                              + " values (?::uuid,?,'SO-HOLD-PICK','L-1','SKU-1',50,"
                              + "'HIGHEST_SCORE','SUSPEND','ORDER_OWNER_FIRST',true,'US','CN-EAST',"
                              + "?, 'SUSPENDED',false,'USD','NO_ELIGIBLE_WAREHOUSE','手动挂起',"
                              + "?::timestamptz,?)",
                          UUID.randomUUID(),
                          a,
                          ENT_A,
                          at(0),
                          user);
                      sql.execute(
                          "insert into fulfillment_warehouse_allocation_hold"
                              + "(id,tenant_id,decision_id,hold_reason,reason,exclusion_detail,"
                              + "excluded_warehouse_count,held_at,created_by_membership_id)"
                              + " values (?::uuid,?,(select id from"
                              + " fulfillment_warehouse_allocation_decision where order_ref='SO-HOLD-PICK'),"
                              + "'NO_ELIGIBLE_WAREHOUSE','手动挂起','WH-CN-SH=手工编造',1,"
                              + "?::timestamptz,?)",
                          UUID.randomUUID(),
                          a,
                          at(0),
                          user);
                      // 挂起了却偷偷选了一个仓 —— 必须被挡。
                      sql.execute(
                          "insert into fulfillment_warehouse_allocation_candidate"
                              + "(id,tenant_id,decision_id,warehouse_ref,legal_entity_code,"
                              + "region_code,warehouse_priority,available_qty,legal_entity_match,"
                              + "feasible,total_score,selected)"
                              + " values (?::uuid,?,(select id from"
                              + " fulfillment_warehouse_allocation_decision where order_ref='SO-HOLD-PICK'),"
                              + "'WH-CN-SH',?,'CN-EAST',10,200,true,true,1.0,true)",
                          UUID.randomUUID(),
                          a,
                          ENT_A);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("挂起");
  }

  /** 已分配却没有任何候选依据 —— DB 直接拒。 */
  @Test
  void 已分配但没有候选依据提交不了() {
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into fulfillment_warehouse_allocation_decision"
                              + "(id,tenant_id,order_ref,order_line_ref,sku_ref,requested_qty,"
                              + "selection_policy,fallback_policy,legal_entity_policy,"
                              + "require_legal_entity_match,destination_country_code,"
                              + "destination_region_code,order_legal_entity_code,status,"
                              + "chosen_warehouse_ref,chosen_legal_entity_code,cross_legal_entity,"
                              + "allocated_qty,currency_code,reason_code,reason,decided_at,"
                              + "created_by_membership_id)"
                              + " values (?::uuid,?,'SO-NO-CAND','L-1','SKU-1',50,"
                              + "'HIGHEST_SCORE','SUSPEND','ORDER_OWNER_FIRST',true,'US','CN-EAST',"
                              + "?, 'ALLOCATED','WH-CN-SH',?,false,50,'USD','SCORE_BASED',"
                              + "'凭空写的分配',?::timestamptz,?)",
                          UUID.randomUUID(),
                          a,
                          ENT_A,
                          ENT_A,
                          at(0),
                          user);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("候选");
  }

  // ==========================================================================
  // 兜底策略
  // ==========================================================================
  @Test
  void 非部分分配兜底时库存不足就是不可行不隐式放宽() {
    asA(
        () -> {
          sql.execute(
              "update fulfillment_warehouse_allocation_stock_estimate set available_qty = 30"
                  + " where warehouse_ref = 'WH-CN-SH'");
          sql.execute(
              "update fulfillment_warehouse_allocation_stock_estimate set available_qty = 0"
                  + " where warehouse_ref = 'WH-CN-BJ'");
          return null;
        });
    var saved = asA(() -> service.allocate(decision("SO-NO-PARTIAL", "50", ENT_A, null), user));
    assertThat(saved.status())
        .as("兜底策略为 SUSPEND 时，库存不足必须挂起而不是悄悄少发")
        .isEqualTo(WarehouseAllocationStatus.SUSPENDED);
    // 三个仓各自被排除的原因不同（库存不足 / 库存为 0 / 货权不符），
    // 整体归纳就是「无可行仓」—— 这才是诚实的整体结论，
    // 把混合原因硬说成「库存不足」才是对运维撒谎。
    assertThat(saved.holdReason()).isEqualTo(WarehouseHoldReason.NO_ELIGIBLE_WAREHOUSE);
    assertThat(saved.holdId()).isNotNull();
    var detail = asA(() -> service.decision(saved.decisionId()));
    assertThat(detail.holds().get(0).exclusionDetail())
        .contains("WH-CN-SH=")
        .contains("WH-CN-BJ=")
        .contains("WH-US-LAX=");
    assertThat(detail.candidates()).noneMatch(WarehouseAllocationCandidateRow::chosen);
  }

  @Test
  void 部分分配兜底把差额挂起而不是显示成已全部分配() {
    asA(
        () -> {
          rule(
              a,
              35,
              "RULE-PARTIAL",
              2,
              "HIGHEST_SCORE",
              "ALLOW_PARTIAL",
              "0.2500",
              "0.2500",
              "0.1500",
              "0.2500",
              "0.1000",
              true,
              "ORDER_OWNER_FIRST",
              "");
          sql.execute(
              "update fulfillment_warehouse_allocation_stock_estimate set available_qty = 30"
                  + " where warehouse_ref = 'WH-CN-SH'");
          sql.execute(
              "update fulfillment_warehouse_allocation_stock_estimate set available_qty = 0"
                  + " where warehouse_ref = 'WH-CN-BJ'");
          return null;
        });
    var saved =
        asA(
            () ->
                service.allocate(
                    new WarehouseAllocationDecisionRequest(
                        "SO-PARTIAL",
                        "L-1",
                        "AMAZON",
                        "SKU-1",
                        new BigDecimal("50"),
                        "US",
                        "CN-EAST",
                        ENT_A,
                        "RULE-PARTIAL",
                        null,
                        null),
                    user));
    assertThat(saved.status()).isEqualTo(WarehouseAllocationStatus.PARTIAL);
    assertThat(saved.allocatedQty()).isEqualByComparingTo("30.000000");
    assertThat(saved.reasonCode()).isEqualTo(WarehouseAllocationReasonCode.PARTIAL_ALLOCATION);
    assertThat(saved.holdReason()).isEqualTo(WarehouseHoldReason.PARTIAL_SHORTFALL);

    var detail = asA(() -> service.decision(saved.decisionId()));
    assertThat(detail.holds()).hasSize(1);
    assertThat(detail.holds().get(0).shortfallQty()).isEqualByComparingTo("20.000000");
    assertThat(detail.holds().get(0).holdCandidateId()).as("差额要指回选中候选，便于从缺口跳到依据").isNotNull();
  }

  // ==========================================================================
  // 分配落库与读
  // ==========================================================================
  @Test
  void 分配落库并把每个仓的依据抄进候选() {
    var saved = asA(() -> service.allocate(decision("SO-ALLOC-1", "50", ENT_A, null), user));
    assertThat(saved.status()).isEqualTo(WarehouseAllocationStatus.ALLOCATED);
    assertThat(saved.allocatedQty()).isEqualByComparingTo("50.000000");
    assertThat(saved.crossLegalEntity()).isFalse();
    assertThat(saved.candidateCount()).isEqualTo(3);

    var detail = asA(() -> service.decision(saved.decisionId()));
    assertThat(detail.decision().orderRef()).isEqualTo("SO-ALLOC-1");
    assertThat(detail.decision().orderLegalEntityCode()).isEqualTo(ENT_A);
    // 三个仓都要有候选行：不可行的也要落，「为什么没选它」才答得出来。
    assertThat(detail.candidates()).hasSize(3);
    assertThat(detail.candidates()).filteredOn(WarehouseAllocationCandidateRow::chosen).hasSize(1);
    var chosen =
        detail.candidates().stream()
            .filter(WarehouseAllocationCandidateRow::chosen)
            .findFirst()
            .orElseThrow();
    assertThat(chosen.allocatedQty()).isEqualByComparingTo("50.000000");
    assertThat(chosen.legalEntityMatch()).isTrue();
    assertThat(chosen.totalScore()).isGreaterThan(BigDecimal.ZERO);
    assertThat(detail.holds()).isEmpty();
  }

  @Test
  void 试算不落库() {
    var before =
        asA(
            () ->
                service
                    .pageDecisions(
                        new WarehouseAllocationDecisionQuery(
                            "SO-SIM-ONLY", null, null, null, null, null, null, null, 0, 10))
                    .total());
    asA(() -> service.simulate(simulation("SO-SIM-ONLY", "50", ENT_A)));
    var after =
        asA(
            () ->
                service
                    .pageDecisions(
                        new WarehouseAllocationDecisionQuery(
                            "SO-SIM-ONLY", null, null, null, null, null, null, null, 0, 10))
                    .total());
    assertThat(after).as("试算是只读预演，不能留下决策行").isEqualTo(before).isZero();
  }

  @Test
  void 金额按halfUp到分且数量与金额刻度对齐() {
    asA(
        () -> {
          sql.execute(
              "update fulfillment_warehouse_allocation_shipping_estimate"
                  + " set shipping_cost = 12.345 where warehouse_ref = 'WH-CN-SH'");
          sql.execute(
              "update fulfillment_warehouse_allocation_stock_estimate"
                  + " set available_qty = 1234.5 where warehouse_ref = 'WH-CN-SH'");
          return null;
        });
    var sim =
        asA(
            () ->
                service.simulate(
                    new WarehouseAllocationSimulationRequest(
                        "SO-SCALE",
                        "L-1",
                        "AMAZON",
                        "SKU-1",
                        new BigDecimal("50"),
                        "US",
                        "CN-EAST",
                        ENT_A,
                        null,
                        null)));
    assertThat(sim.unitShippingCost()).as("结算口径 HALF_UP 到分").isEqualByComparingTo("12.35");
    assertThat(sim.unitShippingCost().scale()).isEqualTo(2);

    var saved =
        asA(
            () ->
                service.allocate(
                    new WarehouseAllocationDecisionRequest(
                        "SO-SCALE",
                        "L-1",
                        "AMAZON",
                        "SKU-1",
                        new BigDecimal("50"),
                        "US",
                        "CN-EAST",
                        ENT_A,
                        null,
                        null,
                        null),
                    user));
    var candidate =
        asA(() -> service.decision(saved.decisionId())).candidates().stream()
            .filter(c -> c.warehouseRef().equals("WH-CN-SH"))
            .findFirst()
            .orElseThrow();
    assertThat(candidate.shippingCost()).as("读回 scale 必须是 4").isEqualByComparingTo("12.3450");
    assertThat(candidate.shippingCost().scale()).isEqualTo(4);
    assertThat(candidate.availableQty()).as("数量 scale 6").isEqualByComparingTo("1234.500000");
    assertThat(candidate.availableQty().scale()).isEqualTo(6);
  }

  @Test
  void 时间往返零漂移() {
    OffsetDateTime decided = at(0);
    var saved =
        asA(
            () ->
                service.allocate(
                    new WarehouseAllocationDecisionRequest(
                        "SO-TIME",
                        "L-1",
                        "AMAZON",
                        "SKU-1",
                        new BigDecimal("50"),
                        "US",
                        "CN-EAST",
                        ENT_A,
                        null,
                        null,
                        decided),
                    user));
    var readBack = asA(() -> service.decision(saved.decisionId())).decision().decidedAt();
    // 写成 +08:00 的时刻，读回来必须是**同一个瞬间**：
    // 若用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，这里断言的就是这一点。
    assertThat(readBack.toInstant()).isEqualTo(decided.toInstant());
    var eventTime = asA(() -> service.decision(saved.decisionId())).events().get(0).occurredAt();
    assertThat(eventTime.toInstant()).isEqualTo(decided.toInstant());
  }

  @Test
  void 同样输入重复判定给出同一结论() {
    var first = asA(() -> service.simulate(simulation("SO-DET", "50", ENT_A)));
    var second = asA(() -> service.simulate(simulation("SO-DET-2", "50", ENT_A)));
    assertThat(second.chosenWarehouseRef()).isEqualTo(first.chosenWarehouseRef());
    assertThat(second.chosenTotalScore()).isEqualByComparingTo(first.chosenTotalScore());
  }

  // ==========================================================================
  // 状态机与 append-only
  // ==========================================================================
  @Test
  void 状态机按确认到下发推进() {
    var saved = asA(() -> service.allocate(decision("SO-STATE", "50", ENT_A, null), user));
    var confirmed =
        asA(
            () ->
                service.advanceStatus(
                    saved.decisionId(),
                    new WarehouseAllocationStatusInput("CONFIRMED", null),
                    user));
    assertThat(confirmed.status()).isEqualTo(WarehouseAllocationStatus.CONFIRMED);
    var released =
        asA(
            () ->
                service.advanceStatus(
                    saved.decisionId(),
                    new WarehouseAllocationStatusInput("RELEASED", "MODULE_9_3_3_1"),
                    user));
    assertThat(released.status()).isEqualTo(WarehouseAllocationStatus.RELEASED);
    assertThat(asA(() -> service.decision(saved.decisionId())).decision().releasedAt()).isNotNull();
    // 已下发是终态
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            saved.decisionId(),
                            new WarehouseAllocationStatusInput("CONFIRMED", null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("终态");
  }

  @Test
  void 下发必须说明下发给哪个模块且必须先确认() {
    var saved = asA(() -> service.allocate(decision("SO-REL", "50", ENT_A, null), user));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            saved.decisionId(),
                            new WarehouseAllocationStatusInput("RELEASED", null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须显式说明");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            saved.decisionId(),
                            new WarehouseAllocationStatusInput("RELEASED", "MODULE_8_OUTBOUND"),
                            user)))
        .as("必须先确认才能下发")
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须先确认");
  }

  @Test
  void 挂起是终态不允许改状态() {
    var saved =
        asA(() -> service.allocate(decision("SO-HOLD-TERM", "50", "CN-ENT-ZZZ", null), user));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            saved.decisionId(),
                            new WarehouseAllocationStatusInput("CONFIRMED", null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("终态");
  }

  @Test
  void 决策候选挂起事件一律不可改写或删除() {
    var saved = asA(() -> service.allocate(decision("SO-APPEND", "50", ENT_A, null), user));
    // 注意：这里必须在租户事务里查 —— 事务外没有 app.current_tenant，
    // RLS 会正确地把所有行挡住（这正是多租户断言 2 依赖的行为）。
    var candidateRow =
        asA(
            () ->
                sql.fetchOne(
                    "select id from fulfillment_warehouse_allocation_candidate"
                        + " where decision_id = ? order by warehouse_ref limit 1",
                    saved.decisionId()));
    assertThat(candidateRow).as("分配必须留下候选依据").isNotNull();
    var candidateId = candidateRow.get(0, UUID.class);
    // 第 1 层：运行角色 trade_app 已被 REVOKE UPDATE/DELETE —— 权限不足直接拒绝。
    assertDbRefused(
        () ->
            asA(
                () ->
                    sql.execute(
                        "update fulfillment_warehouse_allocation_candidate"
                            + " set feasible = false where id = ?",
                        candidateId)),
        "不可改写",
        "42501",
        "23514");
    // 决策表用的是「举证凭据」那条触发器（另有状态流转开窗），与留痕表的措辞不同，
    // 所以这里断言的是它自己的那句 —— 两类表的提示语本来就该不一样。
    assertDbRefused(
        () ->
            asA(
                () ->
                    sql.execute(
                        "delete from fulfillment_warehouse_allocation_decision where id = ?",
                        saved.decisionId())),
        "不允许改写或删除",
        "42501",
        "23514");
    assertDbRefused(
        () ->
            asA(
                () ->
                    sql.execute(
                        "update fulfillment_warehouse_allocation_decision"
                            + " set reason = '改得好看一点' where id = ?",
                        saved.decisionId())),
        "举证凭据",
        "42501",
        "23514");

    // 第 2 层：绕开权限，以**表所有者**身份再撞一次 —— 证明触发器不是死代码。
    // 这一格若拿掉，append-only 就只剩「运行角色没权限」这一句，触发器可以整段删掉。
    assertDbRefused(
        () ->
            asTableOwner(
                    "update fulfillment_warehouse_allocation_candidate"
                        + " set feasible = false where id = '"
                        + candidateId
                        + "'")
                .run(),
        "不可改写",
        "23514");
  }

  // ==========================================================================
  // 规则配置校验
  // ==========================================================================
  @Test
  void 权重合计必须为1() {
    var bad =
        new WarehouseAllocationRuleInput(
            "RULE-BAD-SUM",
            "权重合计不对",
            40,
            "*",
            "US",
            "HIGHEST_SCORE",
            "SUSPEND",
            new BigDecimal("0.5"),
            new BigDecimal("0.5"),
            new BigDecimal("0.5"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            true,
            "NEUTRAL",
            null,
            null,
            null,
            null,
            null,
            null);
    assertThatThrownBy(() -> asA(() -> service.saveRule(bad)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("合计必须等于 1.0000");
  }

  @Test
  void 规则优先级不可重复() {
    var dup =
        new WarehouseAllocationRuleInput(
            "RULE-DUP-PRIORITY",
            "优先级撞了",
            10,
            "*",
            "US",
            "HIGHEST_SCORE",
            "SUSPEND",
            new BigDecimal("0.25"),
            new BigDecimal("0.25"),
            new BigDecimal("0.15"),
            new BigDecimal("0.25"),
            new BigDecimal("0.10"),
            true,
            "NEUTRAL",
            null,
            null,
            null,
            null,
            null,
            null);
    assertThatThrownBy(() -> asA(() -> service.saveRule(dup)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("优先级");
  }

  @Test
  void 规则按优先级自动命中() {
    asA(
        () -> {
          rule(
              a,
              36,
              "RULE-HIGH-PRIO",
              1,
              "LOWEST_COST",
              "SUSPEND",
              "0.2500",
              "0.2500",
              "0.1500",
              "0.2500",
              "0.1000",
              true,
              "ORDER_OWNER_FIRST",
              "");
          return null;
        });
    var sim = asA(() -> service.simulate(simulation("SO-RULE-HIT", "50", ENT_A)));
    assertThat(sim.ruleCode()).isEqualTo("RULE-HIGH-PRIO");
    // LOWEST_COST 下在两个法人 A 的仓里选更便宜的北京仓。
    assertThat(sim.chosenWarehouseRef()).isEqualTo("WH-CN-BJ");
    assertThat(sim.reasonCode()).isEqualTo(WarehouseAllocationReasonCode.LOWEST_COST);
  }

  @Test
  void 规则显式指定时不受优先级影响() {
    asA(
        () -> {
          rule(
              a,
              37,
              "RULE-EXPLICIT",
              50,
              "CLOSEST_LEAD_TIME",
              "SUSPEND",
              "0.2500",
              "0.2500",
              "0.1500",
              "0.2500",
              "0.1000",
              true,
              "ORDER_OWNER_FIRST",
              "");
          return null;
        });
    var sim =
        asA(
            () ->
                service.simulate(
                    new WarehouseAllocationSimulationRequest(
                        "SO-RULE-EXPLICIT",
                        "L-1",
                        "AMAZON",
                        "SKU-1",
                        new BigDecimal("50"),
                        "US",
                        "CN-EAST",
                        ENT_A,
                        "RULE-EXPLICIT",
                        null)));
    assertThat(sim.ruleCode()).isEqualTo("RULE-EXPLICIT");
    assertThat(sim.reasonCode()).isEqualTo(WarehouseAllocationReasonCode.CLOSEST_LEAD_TIME);
    assertThat(sim.chosenWarehouseRef()).isEqualTo("WH-CN-BJ");
  }

  WarehouseAllocationRuleInput ruleInput(
      String code, int priority, String legalPolicy, String preferred) {
    return new WarehouseAllocationRuleInput(
        code,
        code,
        priority,
        "*",
        "US",
        "HIGHEST_SCORE",
        "SUSPEND",
        new BigDecimal("0.25"),
        new BigDecimal("0.25"),
        new BigDecimal("0.15"),
        new BigDecimal("0.25"),
        new BigDecimal("0.10"),
        true,
        legalPolicy,
        preferred,
        null,
        null,
        null,
        null,
        null);
  }

  // ==========================================================================
  // 输入校验与前置条件
  // ==========================================================================
  @Test
  void 没有任何仓库档案时直接报错而不是挂起() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "delete from fulfillment_warehouse_allocation_shipping_estimate;"
              + " delete from fulfillment_warehouse_allocation_stock_estimate;"
              + " delete from fulfillment_warehouse_allocation_warehouse;");
    }
    assertThatThrownBy(() -> asA(() -> service.simulate(simulation("SO-NO-WH", "50", ENT_A))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("还没有任何仓库档案");
  }

  @Test
  void 未维护的输入不能建档() {
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.saveStockEstimate(
                            new WarehouseAllocationStockInput(
                                "WH-NOT-EXIST",
                                "SKU-1",
                                new BigDecimal("10"),
                                at(-1),
                                null,
                                null,
                                null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不在档案里");
    // 标成模块来源却没有出处单号 → 拒绝
    assertThat(
            asA(
                () ->
                    service.saveStockEstimate(
                        new WarehouseAllocationStockInput(
                            "WH-CN-SH",
                            "SKU-2",
                            new BigDecimal("10"),
                            at(-1),
                            "MODULE_7_1_1_3",
                            "MODULE-REF-1",
                            null),
                        user)))
        .as("有出处单号的模块来源应当被接受")
        .isNotNull();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.saveShippingEstimate(
                            new WarehouseAllocationShippingInput(
                                "WH-CN-SH",
                                "SKU-2",
                                "US",
                                new BigDecimal("1.0"),
                                null,
                                "MODULE_10_1_2_1",
                                "",
                                null,
                                null,
                                null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("出处单号");
  }

  @Test
  void 负数与非法枚举被拒() {
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.saveStockEstimate(
                            new WarehouseAllocationStockInput(
                                "WH-CN-SH",
                                "SKU-3",
                                new BigDecimal("-1"),
                                at(-1),
                                null,
                                null,
                                null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能为负");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.saveRule(
                            new WarehouseAllocationRuleInput(
                                "RULE-BAD-POLICY",
                                "非法策略",
                                60,
                                "*",
                                "US",
                                "NOT_A_POLICY",
                                null,
                                new BigDecimal("0.25"),
                                new BigDecimal("0.25"),
                                new BigDecimal("0.15"),
                                new BigDecimal("0.25"),
                                new BigDecimal("0.10"),
                                true,
                                "NEUTRAL",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("未知的选仓策略");
    assertThatThrownBy(() -> asA(() -> service.allocate(decision("SO-Q0", "0", ENT_A, null), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须大于 0");
  }

  @Test
  void 规则排除清单生效() {
    asA(
        () -> {
          sql.execute(
              "update fulfillment_warehouse_allocation_rule set excluded_warehouse_refs ="
                  + " ' WH-CN-BJ , ' where rule_code = 'RULE-DEFAULT'");
          return null;
        });
    var sim = asA(() -> service.simulate(simulation("SO-EXCLUDED", "50", ENT_A)));
    assertThat(sim.chosenWarehouseRef()).isEqualTo("WH-CN-SH");
    var bj =
        sim.candidates().stream()
            .filter(c -> c.warehouseRef().equals("WH-CN-BJ"))
            .findFirst()
            .orElseThrow();
    assertThat(bj.infeasibleReasonCode()).isEqualTo("WAREHOUSE_EXCLUDED");
  }

  @Test
  void 未来采集时间的库存估算不参与判定() {
    asA(
        () -> {
          sql.execute(
              "update fulfillment_warehouse_allocation_stock_estimate set collected_at ="
                  + " ?::timestamptz where warehouse_ref = 'WH-CN-SH'",
              at(5));
          return null;
        });
    var sim = asA(() -> service.simulate(simulation("SO-FUTURE", "50", ENT_A)));
    var sh =
        sim.candidates().stream()
            .filter(c -> c.warehouseRef().equals("WH-CN-SH"))
            .findFirst()
            .orElseThrow();
    assertThat(sh.feasible()).as("未来数据不等于有货").isFalse();
    assertThat(sh.infeasibleReasonCode()).isEqualTo("NO_STOCK_RECORD");
    assertThat(sh.infeasibleReason()).contains("未来数据");
  }

  // ==========================================================================
  // 分页与搜索
  // ==========================================================================
  @Test
  void 真分页且汇总不受分页影响() {
    for (int i = 0; i < 7; i++) {
      // entity 必须 effectively final 才能进 lambda：逐个复制成局部常量。
      var entity = i == 6 ? "CN-ENT-ZZZ" : ENT_A;
      var orderRef = "SO-PAGE-" + i;
      asA(() -> service.allocate(decision(orderRef, "50", entity, null), user));
    }
    var all =
        asA(
            () ->
                service.pageDecisions(
                    new WarehouseAllocationDecisionQuery(
                        null, null, null, null, null, null, null, null, 0, 100)));
    assertThat(all.total()).isEqualTo(7);
    assertThat(all.items()).hasSize(7);
    assertThat(all.suspendedCount()).isEqualTo(1);
    assertThat(all.allocatedCount()).isEqualTo(6);

    var first =
        asA(
            () ->
                service.pageDecisions(
                    new WarehouseAllocationDecisionQuery(
                        null, null, null, null, null, null, null, null, 0, 3)));
    assertThat(first.items()).hasSize(3);
    assertThat(first.total()).as("total 不受分页影响").isEqualTo(7);
    assertThat(first.suspendedCount()).as("汇总也不受分页影响").isEqualTo(1);
    var second =
        asA(
            () ->
                service.pageDecisions(
                    new WarehouseAllocationDecisionQuery(
                        null, null, null, null, null, null, null, null, 1, 3)));
    assertThat(second.items()).hasSize(3);
    var third =
        asA(
            () ->
                service.pageDecisions(
                    new WarehouseAllocationDecisionQuery(
                        null, null, null, null, null, null, null, null, 2, 3)));
    assertThat(third.items()).hasSize(1);
  }

  @Test
  void 关键字搜索与按仓按状态筛选() {
    asA(() -> service.allocate(decision("SO-SEARCH-A", "50", ENT_A, null), user));
    asA(() -> service.allocate(decision("SO-SEARCH-B", "50", ENT_A, null), user));
    var byKeyword =
        asA(
            () ->
                service.pageDecisions(
                    new WarehouseAllocationDecisionQuery(
                        null, null, null, null, null, null, null, "SEARCH-A", 0, 50)));
    assertThat(byKeyword.total()).isEqualTo(1);
    var bySku =
        asA(
            () ->
                service.pageDecisions(
                    new WarehouseAllocationDecisionQuery(
                        null, "SKU-1", null, null, null, null, null, null, 0, 50)));
    assertThat(bySku.total()).isEqualTo(2);
    // 按发货仓筛：先从详情取出实际选中的仓，再拿它回筛，结果必须自洽。
    var decidedWarehouse =
        asA(() -> service.decision(allDecisions(0, 50).items().get(0).id()))
            .decision()
            .chosenWarehouseRef();
    var byWarehouse =
        asA(
            () ->
                service.pageDecisions(
                    new WarehouseAllocationDecisionQuery(
                        null, null, decidedWarehouse, null, null, null, null, null, 0, 50)));
    assertThat(byWarehouse.total()).isEqualTo(2);
    var byStatus =
        asA(
            () ->
                service.pageDecisions(
                    new WarehouseAllocationDecisionQuery(
                        null, null, null, null, "ALLOCATED", null, null, null, 0, 50)));
    assertThat(byStatus.total()).isEqualTo(2);
    var byCross =
        asA(
            () ->
                service.pageDecisions(
                    new WarehouseAllocationDecisionQuery(
                        null, null, null, null, null, null, true, null, 0, 50)));
    assertThat(byCross.total()).isZero();
  }

  // ==========================================================================
  // HTTP 边界
  // ==========================================================================
  @Test
  void 端点经http可达且权限受控() throws Exception {
    http.perform(
            get("/trade/fulfillment/allocation/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.crossServiceReadAvailable").value(false))
        .andExpect(jsonPath("$.reservesUpstreamStock").value(false))
        .andExpect(jsonPath("$.legalEntityNote").isNotEmpty());

    http.perform(get("/trade/fulfillment/allocation/capabilities"))
        .andExpect(status().isUnauthorized());

    // 只读角色不能执行分配
    http.perform(
            post("/trade/fulfillment/allocation/allocations")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"orderRef":"SO-RO","platformCode":"AMAZON","skuRef":"SKU-1","requestedQty":50,
                     "destinationCountryCode":"US","destinationRegionCode":"CN-EAST",
                     "orderLegalEntityCode":"CN-ENT-A"}"""))
        .andExpect(status().isForbidden());
  }

  @Test
  void 试算与分配端点经http给出货权相关结论() throws Exception {
    http.perform(
            post("/trade/fulfillment/allocation/simulations")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"orderRef":"SO-HTTP-SIM","platformCode":"AMAZON","skuRef":"SKU-1",
                     "requestedQty":50,"destinationCountryCode":"US",
                     "destinationRegionCode":"CN-EAST","orderLegalEntityCode":"CN-ENT-A"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.chosenLegalEntityCode").value(ENT_A))
        .andExpect(jsonPath("$.crossLegalEntity").value(false))
        .andExpect(jsonPath("$.candidates.length()").value(3));

    http.perform(
            post("/trade/fulfillment/allocation/allocations")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"orderRef":"SO-HTTP-HOLD","platformCode":"AMAZON","skuRef":"SKU-1",
                     "requestedQty":50,"destinationCountryCode":"US",
                     "destinationRegionCode":"CN-EAST","orderLegalEntityCode":"CN-ENT-ZZZ"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUSPENDED"))
        .andExpect(jsonPath("$.chosenWarehouseRef").doesNotExist())
        .andExpect(jsonPath("$.holdReason").value("BLOCKED_BY_LEGAL_ENTITY"));
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    var savedA = asA(() -> service.allocate(decision("SO-A-ONLY", "50", ENT_A, null), user));
    var savedB = asB(() -> service.allocate(decision("SO-B-ONLY", "50", ENT_A, null), user));

    var pageA = allDecisions(0, 50);
    var pageB =
        asB(
            () ->
                service.pageDecisions(
                    new WarehouseAllocationDecisionQuery(
                        null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(pageA.items())
        .extracting(WarehouseAllocationDecisionRow::orderRef)
        .contains("SO-A-ONLY")
        .doesNotContain("SO-B-ONLY");
    assertThat(pageB.items())
        .extracting(WarehouseAllocationDecisionRow::orderRef)
        .contains("SO-B-ONLY")
        .doesNotContain("SO-A-ONLY");

    // 更强的一格：按 id 直查对方的分配也拿不到（不只是列表过滤）
    assertThatThrownBy(() -> asA(() -> service.decision(savedB.decisionId())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在");
    assertThatThrownBy(() -> asB(() -> service.decision(savedA.decisionId())))
        .isInstanceOf(ResponseStatusException.class);

    // 挂起记录与候选同样隔离
    asA(() -> service.allocate(decision("SO-A-HOLD", "50", "CN-ENT-ZZZ", null), user));
    var holdsA =
        asA(
            () ->
                service.pageHolds(new WarehouseAllocationHoldQuery(null, null, null, null, 0, 50)));
    var holdsB =
        asB(
            () ->
                service.pageHolds(new WarehouseAllocationHoldQuery(null, null, null, null, 0, 50)));
    assertThat(holdsA.total()).isEqualTo(1);
    assertThat(holdsB.total()).isZero();

    // 仓库档案也隔离：A 停用一个仓不影响 B
    asA(
        () -> {
          sql.execute(
              "update fulfillment_warehouse_allocation_warehouse set active = false"
                  + " where warehouse_ref = 'WH-CN-BJ'");
          return null;
        });
    var warehousesB =
        asB(
            () ->
                service.pageWarehouses(
                    new WarehouseAllocationWarehouseQuery(null, null, null, null, 0, 50)));
    assertThat(warehousesB.items())
        .filteredOn(r -> r.warehouseRef().equals("WH-CN-BJ"))
        .singleElement()
        .extracting(WarehouseAllocationWarehouseRow::active)
        .isEqualTo(true);
  }

  WarehouseAllocationDecisionPage allDecisions(int page, int size) {
    return asA(
        () ->
            service.pageDecisions(
                new WarehouseAllocationDecisionQuery(
                    null, null, null, null, null, null, null, null, page, size)));
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    // (1) 服务在事务外没有「环境租户」：任何租户的行都看不见。
    assertThat(sql.fetch("select id from fulfillment_warehouse_allocation_decision").size())
        .as("未绑定租户时不应看到任何行")
        .isZero();

    var savedA = asA(() -> service.allocate(decision("SO-FAKE-HEADER", "50", ENT_A, null), user));
    http.perform(
            get("/trade/fulfillment/allocation/allocations/{id}", savedA.decisionId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision.orderRef").value("SO-FAKE-HEADER"));

    // B 的会话 + 伪造指向 A 的头 → 依然 404
    http.perform(
            get("/trade/fulfillment/allocation/allocations/{id}", savedA.decisionId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // 写入路径同样不认头：B 会话伪造 A 的头，落库仍属 B
    http.perform(
            post("/trade/fulfillment/allocation/allocations")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"orderRef":"SO-FAKE-WRITE","platformCode":"AMAZON","skuRef":"SKU-1",
                     "requestedQty":50,"destinationCountryCode":"US",
                     "destinationRegionCode":"CN-EAST","orderLegalEntityCode":"CN-ENT-A"}"""))
        .andExpect(status().isOk());
    var inA =
        asA(
            () ->
                service.pageDecisions(
                    new WarehouseAllocationDecisionQuery(
                        "SO-FAKE-WRITE", null, null, null, null, null, null, null, 0, 10)));
    var inB =
        asB(
            () ->
                service.pageDecisions(
                    new WarehouseAllocationDecisionQuery(
                        "SO-FAKE-WRITE", null, null, null, null, null, null, null, 0, 10)));
    assertThat(inA.total()).as("伪造头不得把数据写进别的租户").isZero();
    assertThat(inB.total()).isEqualTo(1);

    // (2) 提交后租户上下文必须已清空。
    assertThat(sql.fetch("select id from fulfillment_warehouse_allocation_decision").size())
        .isZero();
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    asA(() -> service.allocate(decision("SO-POOL", "50", ENT_A, null), user));
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证第 5 次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      var pageA = allDecisions(0, 50);
      var pageB =
          asB(
              () ->
                  service.pageDecisions(
                      new WarehouseAllocationDecisionQuery(
                          null, null, null, null, null, null, null, null, 0, 50)));
      assertThat(pageA.items())
          .extracting(WarehouseAllocationDecisionRow::orderRef)
          .as("第 %d 轮 A", i)
          .containsExactly("SO-POOL");
      assertThat(pageB.total()).as("第 %d 轮 B", i).isZero();
      assertThat(sql.fetch("select id from fulfillment_warehouse_allocation_decision").size())
          .as("第 %d 轮事务外", i)
          .isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(
            asB(() ->
                    service.pageDecisions(
                        new WarehouseAllocationDecisionQuery(
                            null, null, null, null, null, null, null, null, 0, 50)))
                .total())
        .isZero();
    assertThat(allDecisions(0, 50).total()).isEqualTo(1);
  }
}
