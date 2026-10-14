package com.starcon.trade;

import static com.starcon.trade.FulfillmentRoutingModels.*;
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
 * 9.3.2.2 FBA 与自发货选择。
 *
 * <p>本测试是 <b>V92 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论， 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不是直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>三条最要紧的断言各自单列一节：
 *
 * <ol>
 *   <li><b>缺货切换记录是硬输出</b>：非平台仓决策没有切换记录提交不了（DB 拒绝）；
 *   <li><b>无资质不许切自发货</b>：绕过服务层直接写 SQL 同样被挡；
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class FulfillmentRoutingIntegrationTest {
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

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-trade-routing-test-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired FulfillmentRoutingService service;
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

  /** 在租户 A 的可信事务里执行一段工作（夹具写入的统一入口）。 */
  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.fulfillment.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.fulfillment.update", work);
  }

  /** 按租户挑执行身份（夹具逐租户播种用）。 */
  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  /**
   * 每个租户一整套夹具：MCF 资质（已授权）、三条路径的成本参数、三条路径的库存快照。
   *
   * <p>库存与成本是本模块的**输入数据**（蓝图：「输入：各渠道库存；平台多渠道配送权限； 成本与时效对比」），所以必须落库而不是写死在代码里 —— 两个租户各配一套，
   * 才能顺带验证它们也按租户隔离。
   */
  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate fulfillment_routing_event, fulfillment_routing_switch,"
              + " fulfillment_routing_candidate, fulfillment_routing_decision,"
              + " fulfillment_platform_stock_snapshot, fulfillment_path_cost_profile,"
              + " fulfillment_mcf_authorization cascade");
    }
    for (var tenant : List.of(a, b)) {
      seedTenant(tenant);
    }
  }

  private void seedTenant(UUID tenant) {
    as(
        tenant,
        () -> {
          // MCF 资质：已授权 + 一年内有效。
          sql.execute(
              "insert into fulfillment_mcf_authorization"
                  + "(id,tenant_id,platform_code,shop_code,status,authorization_source,"
                  + "source_reference,verified_at,verified_by_membership_id,expires_at,note,"
                  + "created_by_membership_id)"
                  + " values (?,?,?,'SHOP-US','AUTHORIZED','TENANT_MAINTAINED','MCF-REF-1',"
                  + "  ?::timestamptz,?,?::timestamptz,'平台后台已开启多渠道配送',?)",
              idFor(tenant, 1),
              tenant,
              "AMAZON",
              at(-30),
              user,
              at(300),
              user);
          // 成本参数：平台仓比自发货贵（让 COST_FIRST 与 PLATFORM_FIRST 能给出不同结论）。
          sql.execute(
              "insert into fulfillment_path_cost_profile"
                  + "(id,tenant_id,platform_code,path_kind,destination_country_code,unit_cost,"
                  + "lead_time_days,platform_fee,currency_code,effective_from,updated_by_membership_id)"
                  + " values (?,?,?,'PLATFORM_FBA','*',6.5,2.0,0.0,'USD',?::timestamptz,?)",
              idFor(tenant, 10),
              tenant,
              "AMAZON",
              at(-30),
              user);
          sql.execute(
              "insert into fulfillment_path_cost_profile"
                  + "(id,tenant_id,platform_code,path_kind,destination_country_code,unit_cost,"
                  + "lead_time_days,platform_fee,currency_code,effective_from,updated_by_membership_id)"
                  + " values (?,?,?,'MERCHANT_OWN','*',4.25,6.0,0.0,'USD',?::timestamptz,?)",
              idFor(tenant, 11),
              tenant,
              "AMAZON",
              at(-30),
              user);
          sql.execute(
              "insert into fulfillment_path_cost_profile"
                  + "(id,tenant_id,platform_code,path_kind,destination_country_code,unit_cost,"
                  + "lead_time_days,platform_fee,currency_code,effective_from,updated_by_membership_id)"
                  + " values (?,?,?,'THIRD_PARTY','*',5.00,4.0,0.0,'USD',?::timestamptz,?)",
              idFor(tenant, 12),
              tenant,
              "AMAZON",
              at(-30),
              user);
          // 库存快照：平台仓 100 件充足，自有仓 500 件充足，第三方仓充足。
          sql.execute(
              "insert into fulfillment_platform_stock_snapshot"
                  + "(id,tenant_id,platform_code,shop_code,sku_ref,path_kind,warehouse_ref,"
                  + "warehouse_name,available_qty,collected_at,stock_source,source_reference,"
                  + "created_by_membership_id)"
                  + " values (?,?,?,'SHOP-US','SKU-1','PLATFORM_FBA','FBA-US-WEST-1','美国西部 FBA 仓',"
                  + " 100,?::timestamptz,'MODULE_7_1_3_1','FBA-REPORT-1',?)",
              idFor(tenant, 20),
              tenant,
              "AMAZON",
              at(-1),
              user);
          sql.execute(
              "insert into fulfillment_platform_stock_snapshot"
                  + "(id,tenant_id,platform_code,shop_code,sku_ref,path_kind,warehouse_ref,"
                  + "warehouse_name,available_qty,collected_at,stock_source,source_reference,"
                  + "created_by_membership_id)"
                  + " values (?,?,?,'SHOP-US','SKU-1','MERCHANT_OWN','WH-LA','洛杉矶海外仓',"
                  + " 500,?::timestamptz,'TENANT_MAINTAINED','WH-LA',?)",
              idFor(tenant, 21),
              tenant,
              "AMAZON",
              at(-1),
              user);
          sql.execute(
              "insert into fulfillment_platform_stock_snapshot"
                  + "(id,tenant_id,platform_code,shop_code,sku_ref,path_kind,warehouse_ref,"
                  + "warehouse_name,available_qty,collected_at,stock_source,source_reference,"
                  + "created_by_membership_id)"
                  + " values (?,?,?,'SHOP-US','SKU-1','THIRD_PARTY','3PL-1','第三方中转仓',"
                  + " 300,?::timestamptz,'TENANT_MAINTAINED','3PL-1',?)",
              idFor(tenant, 22),
              tenant,
              "AMAZON",
              at(-1),
              user);
          return null;
        });
  }

  /** 固定偏移的 OffsetDateTime（+08:00），用于时间往返断言。 */
  static OffsetDateTime at(int daysFromNow) {
    return OffsetDateTime.now(ZoneOffset.ofHours(8))
        .plusDays(daysFromNow)
        .withNano(0)
        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
  }

  // ==========================================================================
  // 迁移落地与 RLS 强制
  // ==========================================================================
  @Test
  void 迁移落地且七张表全部强制rls() {
    for (var table :
        List.of(
            "fulfillment_mcf_authorization",
            "fulfillment_path_cost_profile",
            "fulfillment_platform_stock_snapshot",
            "fulfillment_routing_decision",
            "fulfillment_routing_candidate",
            "fulfillment_routing_switch",
            "fulfillment_routing_event")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity,relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V92 必须建出 " + table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as(table + " 未启用 RLS").isTrue();
      assertThat(row.get(1, Boolean.class)).as(table + " 只有 FORCE 才挡得住运行角色被误授 owner").isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as(table + "：trade_app 必须真的能读")
          .isTrue();
    }
    // 运行角色对举证凭据**没有** UPDATE/DELETE 权限：append-only 是真的，不是注释。
    for (var table : List.of("fulfillment_routing_switch", "fulfillment_routing_event")) {
      assertThat(
              sql.fetchOne("select has_table_privilege('trade_app',?,'UPDATE')", table)
                  .get(0, Boolean.class))
          .as(table + " 必须对 trade_app 只读")
          .isFalse();
    }
  }

  @Test
  void 能力声明如实报出上游状态() throws Exception {
    http.perform(
            get("/trade/fulfillment/routing/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.crossServiceReadAvailable").value(false))
        .andExpect(jsonPath("$.paths.length()").value(3))
        .andExpect(jsonPath("$.policies.length()").value(3))
        // 渠道库存与 MCF 资质可用（自维护），订单行与履约请求不可用（如实报出，不假装）。
        .andExpect(jsonPath("$.dimensions[?(@.code=='CHANNEL_STOCK')].available").value(true))
        .andExpect(jsonPath("$.dimensions[?(@.code=='ORDER_LINE')].available").value(false))
        .andExpect(
            jsonPath("$.dimensions[?(@.code=='FULFILLMENT_REQUEST')].available").value(false));
  }

  // ==========================================================================
  // 核心：路径判定
  // ==========================================================================
  @Test
  void 平台仓库存充足时按优先平台仓走平台仓() {
    var result = asA(() -> service.evaluate(evaluation("SO-1", 10, null)));
    assertThat(result.chosenPath()).isEqualTo(PathKind.PLATFORM_FBA);
    assertThat(result.mcfStatus()).isEqualTo(McfStatus.AUTHORIZED);
    assertThat(result.mcfUsable()).isTrue();
    assertThat(result.platformEligible()).isTrue();
    assertThat(result.reasonCode()).isEqualTo("PLATFORM_STOCK_SUFFICIENT");
    assertThat(result.platformAvailableQty()).isEqualByComparingTo("100.000000");
    // 三条候选都要给出，平台仓被选中
    assertThat(result.candidates()).hasSize(3);
    assertThat(result.candidates().stream().filter(RoutingCandidateRow::chosen).count())
        .isEqualTo(1);
    assertThat(
            result.candidates().stream()
                .filter(c -> c.chosen())
                .findFirst()
                .orElseThrow()
                .pathKind())
        .isEqualTo(PathKind.PLATFORM_FBA);
  }

  @Test
  void 缺货时切自发货且留下切换记录() {
    // 平台仓只有 2 件、需求 10 件 —— 走自发货，并且必须留痕。
    var evaluation = asA(() -> service.evaluate(evaluation("SO-2", 10, new BigDecimal("2"))));
    assertThat(evaluation.chosenPath()).isEqualTo(PathKind.MERCHANT_OWN);
    assertThat(evaluation.reasonCode()).isEqualTo("PLATFORM_STOCK_INSUFFICIENT");
    assertThat(evaluation.platformAvailableQty()).isEqualByComparingTo("2.000000");
    // 平台仓候选被排除，且排除原因必须能答出来
    var platform =
        evaluation.candidates().stream()
            .filter(c -> c.pathKind() == PathKind.PLATFORM_FBA)
            .findFirst()
            .orElseThrow();
    assertThat(platform.feasible()).isFalse();
    assertThat(platform.infeasibleReasonCode()).isEqualTo("INSUFFICIENT_STOCK");
    assertThat(platform.infeasibleReason()).contains("2.000000");

    setPlatformStock(a, new BigDecimal("2"));
    var saved =
        asA(() -> service.decide(decision("SO-2", 10, "PLATFORM_FIRST", false, null), user));
    assertThat(saved.chosenPath()).isEqualTo(PathKind.MERCHANT_OWN);
    assertThat(saved.switchId()).as("缺货切换必须留痕，这是蓝图输出的硬要求").isNotNull();
    assertThat(saved.switchTrigger()).isEqualTo(SwitchTriggerReason.PLATFORM_STOCK_SHORTAGE);
    assertThat(saved.candidateCount()).isEqualTo(3);

    // 详情里必须能查到切换记录（"为什么这单没走平台仓"）
    var detail = asA(() -> service.decision(saved.decisionId()));
    assertThat(detail.switches()).hasSize(1);
    var sw = detail.switches().get(0);
    assertThat(sw.fromPath()).isEqualTo(PathKind.PLATFORM_FBA);
    assertThat(sw.toPath()).isEqualTo(PathKind.MERCHANT_OWN);
    assertThat(sw.triggerReason()).isEqualTo(SwitchTriggerReason.PLATFORM_STOCK_SHORTAGE);
    assertThat(sw.platformAvailableQty()).isEqualByComparingTo("2.000000");
    assertThat(sw.requestedQty()).isEqualByComparingTo("10.000000");
    assertThat(detail.events()).isNotEmpty();
    assertThat(detail.candidates()).hasSize(3);
  }

  @Test
  void 缺货切换记录在数据库里可单独查询() {
    setPlatformStock(a, new BigDecimal("1"));
    var saved =
        asA(() -> service.decide(decision("SO-2B", 5, "PLATFORM_FIRST", false, null), user));
    var page = asA(() -> service.pageSwitches(new SwitchQuery(null, null, "AMAZON", null, 0, 50)));
    assertThat(page.total()).isGreaterThanOrEqualTo(1);
    assertThat(page.items().stream().map(RoutingSwitchRow::decisionId))
        .contains(saved.decisionId());
  }

  @Test
  void 成本优先策略在平台仓有货时也能选更便宜的自发货() {
    // 平台仓有 100 件（充足），但 MERCHANT_OWN 单件 4.25 < 平台仓 6.50。
    var result = asA(() -> service.evaluate(evaluation("SO-3", 10, null)));
    assertThat(result.platformEligible()).isTrue();

    var saved = asA(() -> service.decide(decision("SO-3", 10, "COST_FIRST", false, null), user));
    assertThat(saved.chosenPath()).isEqualTo(PathKind.MERCHANT_OWN);
    assertThat(saved.reasonCode()).isEqualTo("COST_ADVANTAGE");
    // 选了非平台仓就必须有切换记录，触发原因是成本优势
    assertThat(saved.switchTrigger()).isEqualTo(SwitchTriggerReason.COST_ADVANTAGE);
    var detail = asA(() -> service.decision(saved.decisionId()));
    assertThat(detail.switches()).hasSize(1);
    // 决策行上抄下了当时的成本快照（半截的成本对比无法复现决策）
    assertThat(detail.decision().platformUnitCost()).isEqualByComparingTo("6.5000");
    assertThat(detail.decision().merchantUnitCost()).isEqualByComparingTo("4.2500");
  }

  @Test
  void 两条自发货路径同价时按字典序定序不留歧义() {
    // 把自有仓成本调到与第三方仓相同（5.00），两次评估必须给出同一个结论。
    asA(
        () -> {
          sql.execute(
              "update fulfillment_path_cost_profile set unit_cost = 5.00"
                  + " where path_kind = 'MERCHANT_OWN'");
          return null;
        });
    var first = asA(() -> service.evaluate(evaluation("SO-4", 1, new BigDecimal("0"))));
    var second = asA(() -> service.evaluate(evaluation("SO-4", 1, new BigDecimal("0"))));
    assertThat(first.chosenPath()).isEqualTo(second.chosenPath());
    assertThat(first.chosenPath()).isEqualTo(PathKind.MERCHANT_OWN);
  }

  @Test
  void 没有可行路径时如实报错而不是悄悄回落到平台仓() {
    // 把第三方仓与自有仓库存都改成 0：平台仓够、自发货不够。
    // 平台仓仍可用 → 正常判平台仓；再把平台仓也标成不可用才该报错。
    asA(
        () -> {
          sql.execute(
              "update fulfillment_platform_stock_snapshot set available_qty = 0"
                  + " where path_kind in ('MERCHANT_OWN','THIRD_PARTY')");
          sql.execute(
              "update fulfillment_platform_stock_snapshot set available_qty = 0"
                  + " where path_kind = 'PLATFORM_FBA'");
          return null;
        });
    assertThatThrownBy(() -> service.evaluate(evaluation("SO-5", 10, null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("无可行履约路径");
  }

  @Test
  void 人工改判自发货在资质有效时可行并留痕() {
    var saved = asA(() -> service.decide(decision("SO-6", 10, "PLATFORM_FIRST", true, null), user));
    assertThat(saved.chosenPath()).isEqualTo(PathKind.MERCHANT_OWN);
    assertThat(saved.reasonCode()).isEqualTo("POLICY_OVERRIDE");
    assertThat(saved.switchTrigger()).isEqualTo(SwitchTriggerReason.MANUAL_OVERRIDE);
  }

  // ==========================================================================
  // MCF 资质硬闸（蓝图加粗句）
  // ==========================================================================
  @Test
  void 资质未核验时不允许切自发货() {
    asA(
        () -> {
          sql.execute("delete from fulfillment_mcf_authorization");
          sql.execute(
              "insert into fulfillment_mcf_authorization"
                  + "(id,tenant_id,platform_code,shop_code,status,authorization_source,"
                  + "note,created_by_membership_id)"
                  + " values (?,?,?,'SHOP-US','UNKNOWN','TENANT_MAINTAINED','尚未核验',?)",
              idFor(a, 99),
              a,
              "AMAZON",
              user);
          return null;
        });
    // 平台仓仍有货：这条用例要验的是**资质闸门**，若平台仓也缺货，
    // evaluate 会先在「无可行路径」处抛错，走不到资质那一格。
    var result = asA(() -> service.evaluate(evaluation("SO-7", 10, new BigDecimal("100"))));
    assertThat(result.mcfStatus()).isEqualTo(McfStatus.UNKNOWN);
    assertThat(result.mcfUsable()).isFalse();
    // 两条自发货候选都被资质挡住，且原因码必须答得出来
    for (var c : result.candidates()) {
      if (c.pathKind() != PathKind.PLATFORM_FBA) {
        assertThat(c.feasible()).isFalse();
        assertThat(c.infeasibleReasonCode()).isEqualTo("MCF_NOT_AUTHORIZED");
      }
    }
    // 人工强改也不行 —— 没有资质时切过去会被平台判违规发货
    assertThatThrownBy(
            () -> service.decide(decision("SO-7", 10, "PLATFORM_FIRST", true, null), user))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("多渠道配送资质");
  }

  @Test
  void 资质已过期同样不许切自发货() {
    asA(
        () -> {
          sql.execute(
              "update fulfillment_mcf_authorization set expires_at = ?::timestamptz", at(-1));
          return null;
        });
    var result = asA(() -> service.evaluate(evaluation("SO-8", 10, new BigDecimal("100"))));
    assertThat(result.mcfStatus()).isEqualTo(McfStatus.AUTHORIZED);
    assertThat(result.mcfUsable()).as("已授权但已过期 ≠ 可用").isFalse();
    assertThat(result.warnings()).anyMatch(w -> w.contains("过期"));
  }

  @Test
  void 绕过服务层直接写sql也切不了自发货() {
    // 先落一条合规的决策（资质有效时），再把资质撤销，模拟「资质在两个动作之间被收回」。
    setPlatformStock(a, new BigDecimal("0"));
    var saved =
        asA(() -> service.decide(decision("SO-9", 10, "PLATFORM_FIRST", false, null), user));
    asA(
        () -> {
          sql.execute("update fulfillment_mcf_authorization set status = 'DENIED'");
          return null;
        });
    // 直接 SQL 插一条切换记录：BEFORE 触发器 guard_routing_switch_mcf 必须挡住。
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into fulfillment_routing_switch"
                              + "(id,tenant_id,decision_id,from_path,to_path,trigger_reason,reason,"
                              + "platform_available_qty,requested_qty,switched_at,"
                              + "switched_by_membership_id)"
                              + " values (?,?,?,'PLATFORM_FBA','MERCHANT_OWN',"
                              + "'PLATFORM_STOCK_SHORTAGE','绕过服务层',0,1,?::timestamptz,?)",
                          UUID.randomUUID(),
                          a,
                          saved.decisionId(),
                          at(0),
                          user);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("多渠道配送资质");
  }

  // ==========================================================================
  // DB 级不变量（绕过服务层也要成立）
  // ==========================================================================
  @Test
  void 没有切换记录的非平台仓决策提交不了() {
    // 决策表对 trade_app 已 REVOKE UPDATE/DELETE，所以这条只能通过
    // 「先插头 → 不写切换 → 提交」来验证延迟约束触发器。
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into fulfillment_routing_decision"
                              + "(id,tenant_id,order_ref,platform_code,shop_code,sku_ref,requested_qty,"
                              + "selected_policy,policy_source,chosen_path,reason_code,reason,"
                              + "platform_available_qty,mcf_status,status,decided_at,"
                              + "created_by_membership_id)"
                              + " values (?,?,'SO-NO-SW','AMAZON','SHOP-US','SKU-1',10,"
                              + "'COST_FIRST','TENANT_POLICY','MERCHANT_OWN','COST_ADVANTAGE',"
                              + "'成本更优',2,'AUTHORIZED','DECIDED',?::timestamptz,?)",
                          UUID.randomUUID(),
                          a,
                          at(0),
                          user);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("切换记录");
  }

  @Test
  void 选了平台仓却记了切走的留痕会被拒() {
    var saved =
        asA(() -> service.decide(decision("SO-10", 10, "PLATFORM_FIRST", false, null), user));
    assertThat(saved.chosenPath()).isEqualTo(PathKind.PLATFORM_FBA);
    assertThat(saved.switchId()).as("走平台仓不该有切换记录").isNull();
    // 直接插一条「切到自发货」的留痕，与决策矛盾 → 延迟约束拒绝
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into fulfillment_routing_switch"
                              + "(id,tenant_id,decision_id,from_path,to_path,trigger_reason,reason,"
                              + "platform_available_qty,requested_qty,switched_at,"
                              + "switched_by_membership_id)"
                              + " values (?,?,?,'PLATFORM_FBA','MERCHANT_OWN',"
                              + "'PLATFORM_STOCK_SHORTAGE','与决策矛盾',0,10,?::timestamptz,?)",
                          UUID.randomUUID(),
                          a,
                          saved.decisionId(),
                          at(0),
                          user);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("互相矛盾");
  }

  @Test
  void 自发货决策但把资质记成未知会被拒() {
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "insert into fulfillment_routing_decision"
                              + "(id,tenant_id,order_ref,platform_code,shop_code,sku_ref,requested_qty,"
                              + "selected_policy,policy_source,chosen_path,reason_code,reason,"
                              + "platform_available_qty,mcf_status,status,decided_at,"
                              + "created_by_membership_id)"
                              + " values (?,?,'SO-BAD-MCF','AMAZON','SHOP-US','SKU-1',10,"
                              + "'COST_FIRST','TENANT_POLICY','MERCHANT_OWN','COST_ADVANTAGE',"
                              + "'没资质却切自发货',2,'UNKNOWN','DECIDED',?::timestamptz,?)",
                          UUID.randomUUID(),
                          a,
                          at(0),
                          user);
                      return null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void 候选不可用必须给原因且可用候选不得带原因() {
    var detail =
        asA(
            () -> {
              var saved = service.decide(decision("SO-11", 10, "COST_FIRST", false, null), user);
              return service.decision(saved.decisionId());
            });
    for (var c : detail.candidates()) {
      if (c.feasible()) {
        assertThat(c.infeasibleReasonCode()).isEmpty();
      } else {
        assertThat(c.infeasibleReasonCode()).isNotEmpty();
        assertThat(c.infeasibleReason()).isNotEmpty();
      }
    }
  }

  @Test
  void 成本未配置时该路径不可参与比较() {
    asA(
        () -> {
          sql.execute("delete from fulfillment_path_cost_profile where path_kind = 'THIRD_PARTY'");
          return null;
        });
    var result = asA(() -> service.evaluate(evaluation("SO-12", 10, null)));
    var third =
        result.candidates().stream()
            .filter(c -> c.pathKind() == PathKind.THIRD_PARTY)
            .findFirst()
            .orElseThrow();
    assertThat(third.feasible()).isFalse();
    assertThat(third.infeasibleReasonCode()).isEqualTo("NO_COST_PROFILE");
  }

  @Test
  void 未来时间的库存快照不参与判定() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8)).withNano(0);
    OffsetDateTime collected = now.plusDays(3);
    // 自发货路径给一条**现在**就有的快照：没有它整条 evaluate 会在「无可行路径」处
    // 提前抛错，测不到「未来采集时间的快照不能当成当前库存」这一条。
    asA(
        () -> {
          sql.execute(
              "insert into fulfillment_platform_stock_snapshot"
                  + "(id,tenant_id,platform_code,shop_code,sku_ref,path_kind,warehouse_ref,"
                  + "warehouse_name,available_qty,collected_at,stock_source,source_reference,"
                  + "created_by_membership_id)"
                  + " values (?,?,?,'SHOP-US','SKU-FUTURE','MERCHANT_OWN','WH-1','自有仓',"
                  + " 80,?::timestamptz,'TENANT_MAINTAINED','WH-1',?)",
              UUID.randomUUID(),
              a,
              "AMAZON",
              now,
              user);
          sql.execute(
              "insert into fulfillment_platform_stock_snapshot"
                  + "(id,tenant_id,platform_code,shop_code,sku_ref,path_kind,warehouse_ref,"
                  + "warehouse_name,available_qty,collected_at,stock_source,source_reference,"
                  + "created_by_membership_id)"
                  + " values (?,?,?,'SHOP-US','SKU-FUTURE','PLATFORM_FBA','FBA-1','FBA 仓',"
                  + " 999,?::timestamptz,'TENANT_MAINTAINED','FBA-1',?)",
              UUID.randomUUID(),
              a,
              "AMAZON",
              collected,
              user);
          return null;
        });
    var result =
        asA(
            () ->
                service.evaluate(
                    new RoutingEvaluationRequest(
                        "SO-13",
                        null,
                        "AMAZON",
                        "SHOP-US",
                        "SKU-FUTURE",
                        new BigDecimal("10"),
                        null,
                        now)));
    // SKU-FUTURE 只有一条「未来采集」的快照 → 可用量必须是 null（没测过），不是 100
    var platform =
        result.candidates().stream()
            .filter(c -> c.pathKind() == PathKind.PLATFORM_FBA)
            .findFirst()
            .orElseThrow();
    assertThat(platform.availableQty()).as("未来采集时间的快照不能当成当前库存").isNull();
    assertThat(platform.infeasibleReasonCode()).isEqualTo("NO_STOCK_RECORD");
    assertThat(collected).isAfter(now);
  }

  // ==========================================================================
  // 金额与时间精度
  // ==========================================================================
  @Test
  void 金额统一halFup到分且写回零漂移() {
    // 单件成本 4.255 → 结算口径 HALF_UP 到分 = 4.26，DB 侧 numeric(19,4) 存 4.2600
    asA(
        () -> {
          sql.execute(
              "update fulfillment_path_cost_profile set unit_cost = 4.255 where path_kind = 'MERCHANT_OWN'");
          return null;
        });
    var result = asA(() -> service.evaluate(evaluation("SO-14", 10, new BigDecimal("0"))));
    assertThat(result.selectedUnitCost()).isEqualByComparingTo("4.26");

    var saved =
        asA(
            () -> {
              var s = service.decide(decision("SO-14", 10, "COST_FIRST", false, null), user);
              return service.decision(s.decisionId());
            });
    // 读回来的值必须仍然是 4.26（DB 里是 4.2600，Java 侧 scale 2）
    assertThat(saved.decision().selectedUnitCost()).isEqualByComparingTo("4.26");
    var raw =
        asA(
            () ->
                sql.fetchOne(
                        "select selected_unit_cost from fulfillment_routing_decision where id = ?",
                        saved.decision().id())
                    .get(0, BigDecimal.class));
    assertThat(raw.setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("4.26");
    assertThat(raw.scale()).isEqualTo(4);
  }

  @Test
  void 时间字段往返零漂移() {
    var decidedAt = at(-1);
    var saved =
        asA(
            () ->
                service.decide(
                    new RoutingDecisionRequest(
                        "SO-15",
                        "L-1",
                        "AMAZON",
                        "SHOP-US",
                        "SKU-1",
                        new BigDecimal("10"),
                        "PLATFORM_FIRST",
                        null,
                        false,
                        null,
                        decidedAt),
                    user));
    var readBack =
        asA(
            () ->
                sql.fetchOne(
                        "select decided_at from fulfillment_routing_decision where id = ?",
                        saved.decisionId())
                    .get(0, OffsetDateTime.class));
    assertThat(readBack.toInstant()).isEqualTo(decidedAt.toInstant());
    assertThat(readBack.getOffset()).isEqualTo(ZoneOffset.ofHours(8));
  }

  // ==========================================================================
  // 状态机与 append-only
  // ==========================================================================
  @Test
  void 决策状态可推进到已确认且终态不可离开() throws Exception {
    var saved =
        asA(() -> service.decide(decision("SO-16", 10, "PLATFORM_FIRST", false, null), user));
    http.perform(
            post("/trade/fulfillment/routing/decisions/{id}/status", saved.decisionId())
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"CONFIRMED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("路径已确认"));

    var after = asA(() -> service.decision(saved.decisionId()));
    assertThat(after.decision().status()).isEqualTo(DecisionStatus.CONFIRMED);
    assertThat(after.decision().confirmedAt()).isNotNull();
    assertThat(after.events()).hasSize(2);

    // 终态不可再推进。终态判据在 DB（延迟到提交时核对），所以这里拿到的是约束冲突
    // 而不是服务层的 400 —— 断言真实的异常类型，别让后来的人去服务层找一个不存在的检查。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            saved.decisionId(), new DecisionStatusInput("CANCELLED", "反悔"), user)))
        .hasMessageContaining("终态");
  }

  @Test
  void 作废必须给原因() {
    var saved =
        asA(() -> service.decide(decision("SO-17", 10, "PLATFORM_FIRST", false, null), user));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            saved.decisionId(), new DecisionStatusInput("CANCELLED", "  "), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("原因");
  }

  @Test
  void 决策与切换不可改写或删除() {
    setPlatformStock(a, new BigDecimal("0"));
    var saved =
        asA(() -> service.decide(decision("SO-18", 10, "PLATFORM_FIRST", false, null), user));
    // 走本服务的运行角色根本没有 DELETE 权限，语句在到达触发器之前就被 DB 挡掉
    // （42501 会被 Spring 翻译成 bad SQL grammar）—— 断言真实的错误类型，
    // 别把它说成「触发器拦下来的」，那会让后来的人去改一个根本没被执行的触发器。
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "delete from fulfillment_routing_switch where id = ?", saved.switchId());
                      return null;
                    }))
        .hasMessageContaining("bad SQL grammar");
    // 迁移用户（表 owner，权限检查对他无效）绕开权限后，触发器本身仍然挡得住。
    // 用 **DELETE** 而不是 TRUNCATE：TRUNCATE 不触发行级 DELETE 触发器，
    // 拿它来证明 append-only 会得到一个「什么都没发生」的假绿灯。
    assertThatThrownBy(
            () -> {
              try (var c =
                      DriverManager.getConnection(
                          pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                s.execute(
                    "delete from fulfillment_routing_switch where id = '" + saved.switchId() + "'");
              }
            })
        .hasMessageContaining("不可改写");
  }

  // ==========================================================================
  // 输入校验（真校验，不是摆设）
  // ==========================================================================
  @Test
  void 非法输入被拒() {
    assertThatThrownBy(
            () ->
                service.saveCostProfile(
                    new CostProfileInput(
                        "AMAZON",
                        "PLATFORM_FBA",
                        "*",
                        new BigDecimal("-1"),
                        null,
                        null,
                        null,
                        null,
                        "")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能为负");
    assertThatThrownBy(
            () ->
                service.saveCostProfile(
                    new CostProfileInput(
                        "AMAZON",
                        "PLATFORM_FBA",
                        "USA",
                        new BigDecimal("1"),
                        null,
                        null,
                        null,
                        null,
                        "")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("两位国家码");
    assertThatThrownBy(() -> service.evaluate(evaluation("SO-19", 0, null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须大于 0");
    assertThatThrownBy(
            () ->
                service.pageDecisions(
                    new DecisionQuery(
                        null, null, null, null, "NOT_A_PATH", null, null, null, 0, 50)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("未知的履约路径");
  }

  @Test
  void 登记已授权资质必须给出来源出处() {
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.saveMcf(
                            new McfAuthorizationInput(
                                "SHOPIFY", "SHOP-EU", "AUTHORIZED", at(-1), null, "", ""),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("来源单号");
  }

  @Test
  void 分页是真分页而汇总不受分页影响() {
    for (int i = 0; i < 5; i++) {
      final String orderRef = "SO-PAGE-" + i;
      asA(
          () -> {
            service.decide(decision(orderRef, 10, "PLATFORM_FIRST", false, null), user);
            return null;
          });
    }
    var first =
        asA(
            () ->
                service.pageDecisions(
                    new DecisionQuery(null, null, null, null, null, null, null, null, 0, 2)));
    assertThat(first.items()).hasSize(2);
    assertThat(first.total()).isGreaterThanOrEqualTo(5);
    assertThat(first.platformPathCount()).as("汇总不随分页变化：翻页不能改变看板上的数字").isEqualTo(first.total());

    // 按 SKU 过滤真的生效，且过滤后 total 变小
    var filtered =
        asA(
            () ->
                service.pageDecisions(
                    new DecisionQuery(
                        null, null, null, "SKU-PAGE", null, null, null, null, 0, 50)));
    assertThat(filtered.total()).isZero();
  }

  @Test
  void 关键字搜索真的生效() {
    asA(() -> service.decide(decision("SO-KEY-UNIQUE", 10, "PLATFORM_FIRST", false, null), user));
    var hit =
        asA(
            () ->
                service.pageDecisions(
                    new DecisionQuery(
                        null, null, null, null, null, null, null, "KEY-UNIQUE", 0, 50)));
    assertThat(hit.total()).isEqualTo(1);
    var miss =
        asA(
            () ->
                service.pageDecisions(
                    new DecisionQuery(
                        null, null, null, null, null, null, null, "NOT-EXIST-XYZ", 0, 50)));
    assertThat(miss.total()).isZero();
  }

  // ==========================================================================
  // HTTP 边界
  // ==========================================================================
  @Test
  void 未认证与权限不足被拒() throws Exception {
    http.perform(get("/trade/fulfillment/routing/decisions")).andExpect(status().isUnauthorized());
    http.perform(
            get("/trade/fulfillment/routing/decisions").header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());
    // 只读角色不能落决策
    http.perform(
            post("/trade/fulfillment/routing/decisions")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"orderRef":"SO-X","platformCode":"AMAZON","shopCode":"SHOP-US",
                     "skuRef":"SKU-1","requestedQty":1,"selectedPolicy":"PLATFORM_FIRST"}"""))
        .andExpect(status().isForbidden());
  }

  @Test
  void 端点只从已认证成员关系取租户() throws Exception {
    // 伪造 X-Tenant-Id 头：只能看到会话租户（A）的数据，B 的决策一条都看不到。
    asB(() -> service.decide(decision("SO-TENANT-B", 10, "PLATFORM_FIRST", false, null), user));

    http.perform(
            get("/trade/fulfillment/routing/decisions")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.items.length()").value(0));

    http.perform(
            get("/trade/fulfillment/routing/decisions")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].orderRef").value("SO-TENANT-B"));
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================
  @Test
  void 断言1_两租户互不可见() {
    var savedA =
        asA(() -> service.decide(decision("SO-A-ONLY", 10, "PLATFORM_FIRST", false, null), user));
    setPlatformStock(b, new BigDecimal("0"));
    var savedB =
        asB(() -> service.decide(decision("SO-B-ONLY", 10, "PLATFORM_FIRST", false, null), user));

    var pageA =
        asA(
            () ->
                service.pageDecisions(
                    new DecisionQuery(null, null, null, null, null, null, null, null, 0, 50)));
    var pageB =
        asB(
            () ->
                service.pageDecisions(
                    new DecisionQuery(null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(pageA.items().stream().map(RoutingDecisionRow::orderRef))
        .contains("SO-A-ONLY")
        .doesNotContain("SO-B-ONLY");
    assertThat(pageB.items().stream().map(RoutingDecisionRow::orderRef))
        .contains("SO-B-ONLY")
        .doesNotContain("SO-A-ONLY");

    // 更强的一格：按 id 直查对方的决策也拿不到（不只是列表过滤）
    assertThatThrownBy(() -> asA(() -> service.decision(savedB.decisionId())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在");
    assertThatThrownBy(() -> asB(() -> service.decision(savedA.decisionId())))
        .isInstanceOf(ResponseStatusException.class);

    // 切换记录与候选同样隔离
    var switchesA = asA(() -> service.pageSwitches(new SwitchQuery(null, null, null, null, 0, 50)));
    var switchesB = asB(() -> service.pageSwitches(new SwitchQuery(null, null, null, null, 0, 50)));
    assertThat(switchesA.total()).isZero();
    assertThat(switchesB.total()).isEqualTo(1);

    // 配置表也隔离：A 改成本参数不影响 B
    asA(
        () -> {
          sql.execute(
              "update fulfillment_path_cost_profile set unit_cost = 99 where path_kind = 'MERCHANT_OWN'");
          return null;
        });
    var costB =
        asB(
            () ->
                service
                    .pageCostProfiles(new CostProfileQuery(null, "MERCHANT_OWN", null, null, 0, 10))
                    .items()
                    .get(0)
                    .unitCost());
    assertThat(costB).isEqualByComparingTo("4.25");
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    // (1) 服务在事务外没有「环境租户」：任何租户的行都看不见。
    assertThat(sql.fetch("select id from fulfillment_routing_decision").size())
        .as("未绑定租户时不应看到任何行")
        .isZero();

    // (2) HTTP 边界：带合法会话 + 伪造 X-Tenant-Id → 仍然只看得到会话租户的行。
    var savedA =
        asA(
            () ->
                service.decide(
                    decision("SO-FAKE-HEADER", 10, "PLATFORM_FIRST", false, null), user));
    http.perform(
            get("/trade/fulfillment/routing/decisions/{id}", savedA.decisionId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision.orderRef").value("SO-FAKE-HEADER"));

    // B 的会话 + 伪造指向 A 的头 → 依然 404
    http.perform(
            get("/trade/fulfillment/routing/decisions/{id}", savedA.decisionId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // 写入路径同样不认头：B 会话伪造 A 的头，落库仍属 B
    http.perform(
            post("/trade/fulfillment/routing/decisions")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"orderRef":"SO-FAKE-WRITE","platformCode":"AMAZON","shopCode":"SHOP-US",
                     "skuRef":"SKU-1","requestedQty":1,"selectedPolicy":"PLATFORM_FIRST"}"""))
        .andExpect(status().isOk());
    var inA =
        asA(
            () ->
                service.pageDecisions(
                    new DecisionQuery(
                        "SO-FAKE-WRITE", null, null, null, null, null, null, null, 0, 10)));
    var inB =
        asB(
            () ->
                service.pageDecisions(
                    new DecisionQuery(
                        "SO-FAKE-WRITE", null, null, null, null, null, null, null, 0, 10)));
    assertThat(inA.total()).as("伪造头不得把数据写进别的租户").isZero();
    assertThat(inB.total()).isEqualTo(1);

    // (3) 提交后租户上下文必须已清空。
    assertThat(sql.fetch("select id from fulfillment_routing_decision").size()).isZero();
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================
  @Test
  void 断言3_连接池归还后不串租户() {
    // 先在 A 下落一条决策，之后每轮都要看到「A 看得见、B 看不见」。
    asA(() -> service.decide(decision("SO-POOL", 10, "PLATFORM_FIRST", false, null), user));
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证第 6 次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      var pageA =
          asA(
              () ->
                  service.pageDecisions(
                      new DecisionQuery(
                          "SO-POOL", null, null, null, null, null, null, null, 0, 50)));
      var pageB =
          asB(
              () ->
                  service.pageDecisions(
                      new DecisionQuery(
                          "SO-POOL", null, null, null, null, null, null, null, 0, 50)));
      assertThat(pageA.items().stream().map(RoutingDecisionRow::orderRef))
          .as("第 %d 轮 A", i)
          .containsExactly("SO-POOL");
      assertThat(pageB.total()).as("第 %d 轮 B", i).isZero();
      assertThat(sql.fetch("select id from fulfillment_routing_decision").size())
          .as("第 %d 轮事务外", i)
          .isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(
            asB(() ->
                    service.pageDecisions(
                        new DecisionQuery(
                            "SO-POOL", null, null, null, null, null, null, null, 0, 50)))
                .total())
        .isZero();
    assertThat(
            asA(() ->
                    service.pageDecisions(
                        new DecisionQuery(
                            "SO-POOL", null, null, null, null, null, null, null, 0, 50)))
                .total())
        .isEqualTo(1);
  }

  // ==========================================================================
  // 夹具构造
  // ==========================================================================
  RoutingEvaluationRequest evaluation(String orderRef, int qty, BigDecimal platformAvailable) {
    return new RoutingEvaluationRequest(
        orderRef,
        "L-1",
        "AMAZON",
        "SHOP-US",
        "SKU-1",
        new BigDecimal(qty),
        platformAvailable,
        null);
  }

  /**
   * 构造一条落库请求。
   *
   * <p>{@code platformAvailable} 非空时**先把库存快照改成这个值**：落库路径读的是真实快照， 它不看「手工指定的可用量」—— 那个覆盖值只给只读预演用。让
   * decide() 假装能用一个 它并不读的参数，会让这条用例实际什么都没测到。
   */
  RoutingDecisionRequest decision(
      String orderRef,
      int qty,
      String policy,
      boolean forceMerchant,
      BigDecimal platformAvailable) {
    return new RoutingDecisionRequest(
        orderRef,
        "L-1",
        "AMAZON",
        "SHOP-US",
        "SKU-1",
        new BigDecimal(qty),
        policy,
        null,
        forceMerchant,
        null,
        at(0));
  }

  /**
   * 把平台仓快照的可用量改成给定值（造缺货场景用），在**指定租户**的事务里执行。
   *
   * <p>刻意做成独立方法、由用例显式调用，而不是塞进 {@link #decision} 的参数处理里： {@code decision(...)} 是在 {@code asA(...)}
   * 的实参位置上被调用的，那一刻**还没有租户事务**， 在那里写库会被 RLS 静默过滤成 0 行 —— 用例看着跑了，实际什么也没造出来。
   */
  void setPlatformStock(UUID tenant, BigDecimal available) {
    as(
        tenant,
        () -> {
          sql.execute(
              "update fulfillment_platform_stock_snapshot set available_qty = ?"
                  + " where path_kind = 'PLATFORM_FBA'",
              available);
          return null;
        });
  }
}
