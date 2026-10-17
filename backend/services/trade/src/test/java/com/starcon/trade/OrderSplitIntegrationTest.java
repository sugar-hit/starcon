package com.starcon.trade;

import static com.starcon.trade.OrderSplitModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * 9.3.1.1 拆单。
 *
 * <p>本测试是 <b>V151 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，逐条在真实
 * PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不只是直连数据库，以便同时验证
 * 「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本测试要钉住的九条</b>：
 *
 * <ol>
 *   <li><b>🔴 金额守恒</b>：各履约单元分摊额 + 尾差**恰好等于**订单总金额，
 *       且这条不变量在 DB 侧被延迟约束触发器独立复核（人为造一条不守恒的记录，
 *       提交时必须被拒）。
 *   <li><b>🔴 拆单不产生新的销售订单</b>：落拆单前后 sales_order 行数不变，
 *       且 DB 层试图把 creates_sales_order 置真会被 CHECK 拒绝。
 *   <li><b>🔴 尾差有归属</b>：至多一行承载 residual，且它把守恒补齐到分毫不差。
 *   <li><b>四个拆分维度都是租户配置</b>：按仓库 / 按承运商 / 按时效 / 按平台限制
 *       各自改变履约单元的切法与数量。
 *   <li><b>候选依据落库</b>：落选候选逐条带原因码（平台受限 / 时效不达标 / 库存不足）。
 *   <li><b>确定性</b>：同样输入两次拆，单元序号、仓、承运商、金额逐项一致。
 *   <li><b>🔴 不预占上游库存</b>：upstream_reserved 恒 false，DB 层置真被拒。
 *   <li><b>时间钉死并断言写回值</b>：用 +08:00 非 UTC 时刻落库并断言读回值，
 *       防止 {@code Timestamp.valueOf(LocalDateTime)} 那 −28800s 静默偏移。
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 /
 *       连接池归还后不串租户，外加<b>跨租户不可拆单</b>。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderSplitIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper json = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

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

  @Autowired OrderSplitService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

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

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    // ⚠️ truncate 必须走**超级用户**连接，不能用应用连接：
    // trade_app 对 order_split_event 刻意 REVOKE 了 UPDATE,DELETE（append-only 留痕表），
    // 而 TRUNCATE 需要该权限 —— 用应用连接清理会直接撞上这条 REVOKE。
    // 这条 REVOKE 本身是对的：它保证运行期没有人能改写拆单事件。
    try (var c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate order_split_event, order_split_writeback_task,"
              + " order_split_reservation_command, order_split_candidate,"
              + " order_split_unit_line, order_split_unit, order_split,"
              + " order_split_stock_snapshot, order_split_warehouse, order_split_rule,"
              + " order_charge, order_address_snapshot, sales_order_party_snapshot,"
              + " sales_order_line, sales_order cascade");
    }
  }

  // ------------------------------------------------------------ 夹具常量（时间全部钉死）

  /** 钉死的时刻。**故意用 +08:00 非 UTC**：能直接暴露那 −28800s 静默偏移。 */
  static final OffsetDateTime PLANNED = OffsetDateTime.parse("2026-03-01T10:45:00+08:00");

  static final OffsetDateTime T_COLLECTED = OffsetDateTime.parse("2026-03-01T06:00:00Z");
  static final OffsetDateTime T_OCCURRED = OffsetDateTime.parse("2026-03-02T15:20:00+08:00");

  static final String SKU = "SKU-1";
  static final String REF = "ORD-9311-1";

  // ================================================================ 夹具

  /** 登记一个仓。 */
  void warehouse(String ref, String carrier, Integer hours, String platforms, boolean active) {
    asA(
        () -> {
          service.saveWarehouse(
              new SplitWarehouseInput(ref, "仓 " + ref, "LE-" + ref, carrier, hours, platforms, active,
                  ""),
              actorA.membershipId());
          return null;
        });
  }

  /** 登记一份可用库存分布。 */
  void stock(String ref, String sku, String available, OffsetDateTime collectedAt) {
    asA(
        () -> {
          service.saveStockSnapshot(
              new SplitStockSnapshotInput(ref, sku, new BigDecimal(available), collectedAt,
                  "TENANT_MAINTAINED", "", ""),
              actorA.membershipId());
          return null;
        });
  }

  /** 落一份拆单规则（默认：按仓库 + 承运商 + 平台限制，主维度 WAREHOUSE，允许部分先发）。 */
  void rule(
      SplitStrategy primary, int maxUnits, boolean allowPartial, Integer maxHours,
      Integer freshnessHours) {
    asA(
        () -> {
          service.saveRule(
              new SplitRuleInput(
                  true,
                  true,
                  true,
                  primary == SplitStrategy.TIMELINESS,
                  true,
                  primary,
                  maxUnits,
                  allowPartial,
                  maxHours,
                  freshnessHours),
              actorA.membershipId());
          return null;
        });
  }

  /** 常用三仓夹具：DHL 仓 24 小时、SF 仓 6 小时、只做 AMAZON 的 UPS 仓 48 小时。 */
  void threeWarehouses() {
    warehouse("WH-HK", "SF", 6, "", true);
    warehouse("WH-SH", "DHL", 24, "", true);
    warehouse("WH-US", "UPS", 48, "AMAZON", true);
  }

  /** 一张只含快照路径的拆单请求（orderSource=REQUEST_SNAPSHOT）。 */
  SplitInput snapshotInput(String orderRef, String qty, String lineAmount, String totalAmount) {
    return new SplitInput(
        orderRef,
        "SO-" + orderRef,
        "REQUEST_SNAPSHOT",
        "USD",
        "Ada Lovelace",
        "+1-213-555-0100",
        "ada@example.com",
        "US",
        "100 Main St",
        "AMAZON",
        "SHOP-1",
        "EXT-" + orderRef,
        new BigDecimal(totalAmount),
        List.of(new OrderLineInput("L1-" + orderRef, SKU, "SKU-1", "测试商品",
            new BigDecimal(qty), new BigDecimal(lineAmount))),
        PLANNED,
        "9.3.1.1 拆单测试");
  }

  long splitCount(UUID tenant) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select count(*) c from order_split where tenant_id = ?", tenant)
                .get("c", Long.class));
  }

  long salesOrderCount(UUID tenant) {
    return as(
        tenant,
        () ->
            sql.fetchOne(
                    "select count(*) c from sales_order where tenant_id = ?", tenant)
                .get("c", Long.class));
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明如实报出审单未接通与不预占上游库存() {
    var caps = asA(() -> service.capabilities());
    assertThat(caps.reservesUpstreamStock())
        .as("7 预占在 warehouse 独立库，本模块不预占上游库存 —— 必须如实报出，"
            + "否则下游落地时两边都以为占了同一批货")
        .isFalse();
    assertThat(caps.orderReviewConnected())
        .as("9.2 审单整条线未交付，capabilities 必须显式报出而不是假装接通")
        .isFalse();
    assertThat(caps.ruleConfigured()).isFalse();

    var review =
        caps.dimensions().stream()
            .filter(d -> "ORDER_REVIEW".equals(d.code()))
            .findFirst()
            .orElseThrow();
    assertThat(review.available()).isFalse();
    assertThat(review.unavailableReason())
        .as("审单维度的缺口说明必须点名 9.2 与「不校验审单结论」这件事")
        .contains("9.2")
        .contains("审单")
        .contains("不校验");

    var reserve =
        caps.dimensions().stream()
            .filter(d -> "STOCK_RESERVATION".equals(d.code()))
            .findFirst()
            .orElseThrow();
    assertThat(reserve.available()).isFalse();
    assertThat(reserve.unavailableReason()).contains("warehouse");

    // 四个拆分维度与四组状态词表都要能被界面读到
    assertThat(caps.strategies()).hasSize(4);
    assertThat(caps.rejectReasons()).isNotEmpty();
  }

  // ================================================================ 规则

  @Test
  void 没配规则不许落拆单() {
    threeWarehouses();
    stock("WH-HK", SKU, "10.000000", T_COLLECTED);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.split(
                            snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                            actorA.membershipId())))
        .as("「没配规则 = 默认全拆」会把一次运营疏忽变成一批错发的货，本模块不给隐含默认值")
        .hasMessageContaining("尚未登记拆单规则");
    assertThat(splitCount(a)).isZero();
  }

  @Test
  void 规则主维度必须真的开着() {
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      // 主维度是 TIMELINESS，但时效开关是关的
                      service.saveRule(
                          new SplitRuleInput(true, true, true, false, true,
                              SplitStrategy.TIMELINESS, 8, true, null, null),
                          actorA.membershipId());
                      return null;
                    }))
        .as("规则说「主维度是它」而它没开，分组时无据可依")
        .hasMessageContaining("主维度");
  }

  @Test
  void 四个维度全关被拒() {
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      service.saveRule(
                          new SplitRuleInput(true, false, false, false, false,
                              SplitStrategy.WAREHOUSE, 8, true, null, null),
                          actorA.membershipId());
                      return null;
                    }))
        .as("全关就是「不许拆」，而「暂停拆单」请用 enabled=false，两者是不同的意思")
        .hasMessageContaining("至少要开一个");
  }

  // ================================================================ 🔴 金额守恒

  @Test
  void 拆单不产生新的销售订单且金额守恒() {
    threeWarehouses();
    // HK 只能吃 6 件，剩下 4 件走 SH —— 拆成两个单元。
    stock("WH-HK", SKU, "6.000000", T_COLLECTED);
    stock("WH-SH", SKU, "4.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);

    long ordersBefore = salesOrderCount(a);
    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));

    assertThat(salesOrderCount(a))
        .as("🔴 蓝图加粗句「拆单不产生新的销售订单」：sales_order 行数不许变")
        .isEqualTo(ordersBefore);

    // 读回 DB 实算值（不是服务层的自说自话）
    var detail = asA(() -> service.split(saved.id()));
    assertThat(detail.createsSalesOrder())
        .as("creates_sales_order 由 DB CHECK 恒钉为 false")
        .isFalse();
    assertThat(detail.amountConserved()).isTrue();
    assertThat(detail.split().orderTotalAmount()).isEqualByComparingTo("100.0000");
    assertThat(detail.split().unitCount()).isEqualTo(2);
    assertThat(detail.split().allocatedAmountTotal().add(detail.split().residualAmountTotal()))
        .as("🔴 各单元分摊合计 + 尾差 必须恰好等于订单总金额")
        .isEqualByComparingTo("100.0000");
    assertThat(detail.units()).hasSize(2);
    assertThat(detail.units())
        .extracting(SplitUnit::warehouseRef)
        .containsExactly("WH-HK", "WH-SH");
    assertThat(detail.units())
        .extracting(SplitUnit::unitSeq)
        .as("单元序号按 warehouse_ref 字典序发：HK < SH")
        .containsExactly(1, 2);
  }

  @Test
  void 金额守恒由DB延迟约束触发器独立复核() throws Exception {
    threeWarehouses();
    stock("WH-HK", SKU, "10.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);
    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));

    // 绕过服务层，直接用超级用户连接插一条「分摊额不等于订单总额」的明细。
    // 守恒检查是 DEFERRABLE INITIALLY DEFERRED 的，所以它在**提交时**才拒 ——
    // 这正是本测试要验的点：不是服务层的 if 在拦，是数据库在拦。
    UUID splitId = saved.id();
    assertThatThrownBy(
            () -> {
              try (Connection c =
                  DriverManager.getConnection(
                      pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                c.setAutoCommit(false);
                try (var s = c.createStatement()) {
                  s.execute(
                      "select set_config('app.current_tenant','" + a + "',false)");
                  s.execute(
                      "insert into order_split_unit(id,tenant_id,split_id,unit_seq,unit_status,"
                          + "unit_strategy,warehouse_ref,carrier_code,ship_by_hours,platform_code,"
                          + "legal_entity_code,note,created_at,updated_at)"
                          + " values(gen_random_uuid(),'" + a + "','" + splitId + "',99,'PLANNED',"
                          + "'WAREHOUSE','WH-XX','XX',24,'AMAZON','LE-XX','',now(),now())");
                  s.execute(
                      "insert into order_split_unit_line(id,tenant_id,split_id,unit_id,"
                          + "order_line_ref,sku_ref,sku_code,sku_name,allocated_qty,"
                          + "allocated_amount,residual_amount,created_at)"
                          + " select gen_random_uuid(),tenant_id,split_id,id,'L9','SKU-X','X','X',"
                          + "1.000000,7.0000,0,now() from order_split_unit"
                          + " where split_id='" + splitId + "' and unit_seq=99");
                }
                // 分摊 7 元 ≠ 订单总额 100 元 → 提交必须被守恒触发器拒。
                c.commit();
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("金额不守恒");
  }

  @Test
  void 尾差必须有确定归属且至多一行承载() {
    threeWarehouses();
    // 3 仓各能吃一点，且分摊权重除不尽 → 必须出现尾差。
    stock("WH-HK", SKU, "1.000000", T_COLLECTED);
    stock("WH-SH", SKU, "1.000000", T_COLLECTED);
    stock("WH-US", SKU, "1.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);

    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "3.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    var detail = asA(() -> service.split(saved.id()));

    assertThat(detail.amountConserved()).isTrue();
    long residualRows =
        asA(
            () ->
                sql.fetchOne(
                        "select count(*) c from order_split_unit_line"
                            + " where split_id = ? and residual_amount <> 0", saved.id())
                    .get("c", Long.class));
    assertThat(residualRows)
        .as("🔴 尾差至多一行承载：两行都带 residual 时「这笔尾差归谁」就没有答案了")
        .isLessThanOrEqualTo(1);
    assertThat(detail.split().allocatedAmountTotal().add(detail.split().residualAmountTotal()))
        .as("尾差把守恒补齐到分毫不差 —— 差额有承载，不静默吞掉")
        .isEqualByComparingTo("100.0000");

    // 每个单元行的金额之和 = 各单元分摊合计（行级是真值）
    BigDecimal lines =
        detail.unitLines().stream()
            .map(UnitLine::allocatedAmount)
            .reduce(new BigDecimal("0.0000"), BigDecimal::add);
    assertThat(lines)
        .as("行级分摊之和必须与头行的分摊合计一致（同一个 DB 实算源）")
        .isEqualByComparingTo(detail.split().allocatedAmountTotal());
  }

  @Test
  void db层拒绝把拆单标成产生了新销售订单() {
    threeWarehouses();
    stock("WH-HK", SKU, "10.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);
    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update order_split set creates_sales_order = true where id = ?",
                          saved.id());
                      return null;
                    }))
        .as("🔴 蓝图加粗句「拆单不产生新的销售订单」由 DB CHECK 写死，任何服务层都改不动它")
        .hasMessageContaining("order_split_never_creates_sales_order");
  }

  @Test
  void db层拒绝把预占指令标成已预占上游库存() {
    threeWarehouses();
    stock("WH-HK", SKU, "10.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);
    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update order_split_reservation_command set upstream_reserved = true"
                              + " where split_id = ?", saved.id());
                      return null;
                    }))
        .as("🔴 本模块不预占上游库存（7 预占在 warehouse 独立库），"
            + "这条 CHECK 从数据层杜绝「两边都以为占了同一批货」")
        .hasMessageContaining("order_split_reservation_never_reserves_upstream");
  }

  // ================================================================ 四个拆分维度

  @Test
  void 按仓库拆() {
    warehouse("WH-B", "DHL", 24, "", true);
    warehouse("WH-A", "SF", 24, "", true);
    stock("WH-A", SKU, "5.000000", T_COLLECTED);
    stock("WH-B", SKU, "5.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);

    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    var detail = asA(() -> service.split(saved.id()));
    assertThat(detail.split().strategy()).isEqualTo(SplitStrategy.WAREHOUSE);
    assertThat(detail.units())
        .as("按仓拆：同承运商也各切一刀（WH-A 与 WH-B 是两个仓）")
        .extracting(SplitUnit::warehouseRef)
        .containsExactly("WH-A", "WH-B");
  }

  @Test
  void 按承运商拆() {
    warehouse("WH-A", "DHL", 24, "", true);
    warehouse("WH-B", "SF", 24, "", true);
    stock("WH-A", SKU, "5.000000", T_COLLECTED);
    stock("WH-B", SKU, "5.000000", T_COLLECTED);
    rule(SplitStrategy.CARRIER, 8, true, null, null);

    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    var detail = asA(() -> service.split(saved.id()));
    assertThat(detail.split().strategy()).isEqualTo(SplitStrategy.CARRIER);
    assertThat(detail.units())
        .as("按承运商拆：DHL 一件、SF 一件")
        .extracting(SplitUnit::carrierCode)
        .containsExactly("DHL", "SF");
    assertThat(detail.split().distinctCarrierCount()).isEqualTo(2);
  }

  @Test
  void 按发货时效拆_现货仓先发() {
    warehouse("WH-FAST", "SF", 6, "", true);
    warehouse("WH-SLOW", "DHL", 48, "", true);
    stock("WH-FAST", SKU, "5.000000", T_COLLECTED);
    stock("WH-SLOW", SKU, "5.000000", T_COLLECTED);
    rule(SplitStrategy.TIMELINESS, 8, true, null, null);

    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    var detail = asA(() -> service.split(saved.id()));
    assertThat(detail.split().strategy()).isEqualTo(SplitStrategy.TIMELINESS);
    assertThat(detail.units()).hasSize(2);
    assertThat(detail.units()).allSatisfy(u -> {
      assertThat(u.shipByAt()).as("🔴 按时效拆的单元必须带 ship_by 时刻（DB 有 CHECK）").isNotNull();
      assertThat(u.shipByHours()).isNotNull();
    });
    // 时效档小的先排：WH-FAST(6h) 在 WH-SLOW(48h) 之前
    assertThat(detail.units()).extracting(SplitUnit::shipByHours).containsExactly(6, 48);

    // 🔴 时间断言：ship_by_at 必须等于 plannedAt + 该仓承诺时效，且**不偏 8 小时**
    var fast = detail.units().get(0);
    assertThat(fast.shipByAt())
        .as("ship_by = 拆分时刻 + 承诺时效。偏移 −28800s 会让「今天先发」变成昨天")
        .isEqualTo(PLANNED.plusHours(6));
  }

  @Test
  void 时效未知的仓排不进时效单元() {
    warehouse("WH-FAST", "SF", 6, "", true);
    warehouse("WH-UNKNOWN", "DHL", null, "", true);
    stock("WH-FAST", SKU, "5.000000", T_COLLECTED);
    stock("WH-UNKNOWN", SKU, "5.000000", T_COLLECTED);
    rule(SplitStrategy.TIMELINESS, 8, true, null, null);

    var preview =
        asA(
            () ->
                service.preview(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000")));
    assertThat(preview.units())
        .as("时效未知的仓成不了时效单元（否则 DB 的 ship_by CHECK 会拒，而错误信息不可读）")
        .extracting(PreviewUnit::warehouseRef)
        .containsExactly("WH-FAST");
    assertThat(preview.candidates())
        .anySatisfy(
            c -> {
              assertThat(c.warehouseRef()).isEqualTo("WH-UNKNOWN");
              assertThat(c.selected()).isFalse();
              assertThat(c.rejectReason()).isEqualTo("SHIP_BY_TOO_SLOW");
            });
  }

  @Test
  void 按平台限制拆() {
    warehouse("WH-AMZ", "DHL", 24, "AMAZON", true);
    warehouse("WH-ALL", "SF", 24, "", true);
    stock("WH-AMZ", SKU, "5.000000", T_COLLECTED);
    stock("WH-ALL", SKU, "5.000000", T_COLLECTED);
    rule(SplitStrategy.PLATFORM, 8, true, null, null);

    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    var detail = asA(() -> service.split(saved.id()));
    assertThat(detail.split().strategy()).isEqualTo(SplitStrategy.PLATFORM);
    assertThat(detail.units())
        .as("按平台限制拆：每个单元都带平台归属，发货回传才能逐单元回传")
        .allSatisfy(u -> assertThat(u.platformCode()).isEqualTo("AMAZON"));

    // 只做 AMAZON 的仓与非平台无关的仓都可承接 AMAZON 单 → 两个单元
    assertThat(detail.units()).hasSize(2);
  }

  @Test
  void 平台不匹配的仓带原因落选且候选落库() {
    threeWarehouses();
    stock("WH-HK", SKU, "10.000000", T_COLLECTED);
    // WH-US 只做 AMAZON，本单是 AMAZON → 可用；再登记一个只做 SHOPIFY 的仓。
    warehouse("WH-SHOPIFY", "YUNDA", 24, "SHOPIFY", true);
    stock("WH-SHOPIFY", SKU, "10.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);

    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    var detail = asA(() -> service.split(saved.id()));

    assertThat(detail.candidates())
        .as("落选候选与被选候选同等重要：「为什么这批货没从某仓发」必须答得出来")
        .anySatisfy(
            c -> {
              assertThat(c.warehouseRef()).isEqualTo("WH-SHOPIFY");
              assertThat(c.selected()).isFalse();
              assertThat(c.rejectReason()).isEqualTo("PLATFORM_RESTRICTED");
              assertThat(c.rejectReasonLabel()).isNotBlank();
            });
    // 候选依据真的落库了（不是查询时联算）
    long persisted =
        asA(
            () ->
                sql.fetchOne(
                        "select count(*) c from order_split_candidate where split_id = ?",
                        saved.id())
                    .get("c", Long.class));
    assertThat(persisted).isGreaterThan(0);
  }

  @Test
  void 停用的仓不参与候选() {
    warehouse("WH-A", "SF", 24, "", true);
    warehouse("WH-OFF", "DHL", 24, "", false);
    stock("WH-A", SKU, "10.000000", T_COLLECTED);
    stock("WH-OFF", SKU, "10.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);
    var preview =
        asA(
            () ->
                service.preview(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000")));
    assertThat(preview.units()).extracting(PreviewUnit::warehouseRef).containsExactly("WH-A");
  }

  // ================================================================ 库存不足

  @Test
  void 库存不足时显式阻断而不静默塞给某个仓() {
    warehouse("WH-A", "SF", 24, "", true);
    stock("WH-A", SKU, "4.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.split(
                            snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                            actorA.membershipId())))
        .as("🔴 排不下的货绝不静默塞给某一个仓 —— 货权错记比晚发严重得多")
        .hasMessageContaining("排不下");
    assertThat(splitCount(a)).as("被阻断的拆单一条都不许留下").isZero();
  }

  @Test
  void 超过单元数上限被拒() {
    warehouse("WH-A", "SF", 24, "", true);
    warehouse("WH-B", "SF", 24, "", true);
    warehouse("WH-C", "SF", 24, "", true);
    stock("WH-A", SKU, "4.000000", T_COLLECTED);
    stock("WH-B", SKU, "3.000000", T_COLLECTED);
    stock("WH-C", SKU, "3.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 2, true, null, null);

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.split(
                            snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                            actorA.membershipId())))
        .hasMessageContaining("超过本租户规则的上限");
  }

  @Test
  void 规则不允许部分先发时装不满的仓整行不动它() {
    warehouse("WH-A", "SF", 24, "", true);
    stock("WH-A", SKU, "4.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, false, null, null);

    var preview =
        asA(
            () ->
                service.preview(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000")));
    assertThat(preview.blockedReason())
        .as("一个仓都承接不了时，阻断理由必须说清「候选仓全部落选、去哪看逐条原因」")
        .contains("没有任何仓能承接")
        .contains("candidates");
    assertThat(preview.candidates())
        .anySatisfy(
            c -> assertThat(c.rejectReason()).isEqualTo("PARTIAL_FULFILMENT_DISABLED"));
  }

  // ================================================================ 确定性

  @Test
  void 同样输入两次拆结果完全一致() {
    threeWarehouses();
    stock("WH-HK", SKU, "6.000000", T_COLLECTED);
    stock("WH-SH", SKU, "4.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);

    var first =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    var second =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));

    var d1 = asA(() -> service.split(first.id()));
    var d2 = asA(() -> service.split(second.id()));

    assertThat(d2.units())
        .as("🔴 确定性：同样输入必得同样方案（tiebreak 落到字典序）")
        .usingRecursiveComparison()
        .ignoringFields("id", "note")
        .isEqualTo(d1.units());
    assertThat(d2.unitLines())
        .usingRecursiveComparison()
        .ignoringFields("id", "unitId")
        .isEqualTo(d1.unitLines());
    assertThat(d2.split().allocatedAmountTotal())
        .isEqualByComparingTo(d1.split().allocatedAmountTotal());
    assertThat(d2.split().residualAmountTotal())
        .isEqualByComparingTo(d1.split().residualAmountTotal());
    assertThat(d2.split().unitCount()).isEqualTo(d1.split().unitCount());
  }

  // ================================================================ 预览无副作用

  @Test
  void 预览不写库且与落库结果一致() {
    threeWarehouses();
    stock("WH-HK", SKU, "6.000000", T_COLLECTED);
    stock("WH-SH", SKU, "4.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);

    var preview =
        asA(
            () ->
                service.preview(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000")));
    assertThat(splitCount(a)).as("🔴 预览必须无副作用").isZero();

    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    var detail = asA(() -> service.split(saved.id()));

    assertThat(detail.units()).hasSize(preview.units().size());
    assertThat(detail.units())
        .extracting(SplitUnit::warehouseRef)
        .containsExactlyElementsOf(
            preview.units().stream().map(PreviewUnit::warehouseRef).toList());
    assertThat(detail.split().allocatedAmountTotal())
        .isEqualByComparingTo(preview.allocatedTotal());
    assertThat(detail.split().residualAmountTotal())
        .isEqualByComparingTo(preview.residualTotal());
    assertThat(preview.amountConserved()).isTrue();
    assertThat(preview.createsSalesOrder()).isFalse();
  }

  // ================================================================ 预占指令

  @Test
  void 拆单自动登记预占指令且不预占上游库存() {
    threeWarehouses();
    stock("WH-HK", SKU, "6.000000", T_COLLECTED);
    stock("WH-SH", SKU, "4.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);
    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));

    var detail = asA(() -> service.split(saved.id()));
    assertThat(detail.reservations())
        .as("蓝图输出②：拆单要落库存预占指令")
        .hasSize(2);
    assertThat(detail.reservations())
        .allSatisfy(
            r -> {
              assertThat(r.targetService()).isEqualTo("WAREHOUSE");
              assertThat(r.commandStatus()).isEqualTo(SplitReservationStatus.REQUESTED);
              assertThat(r.commandSource()).isEqualTo("SPLIT_PLAN");
              assertThat(r.upstreamReserved())
                  .as("🔴 本模块不预占上游库存 —— 只是指令登记")
                  .isFalse();
            });

    var unitId = detail.units().get(0).id();
    var ack =
        asA(
            () ->
                service.recordReservation(
                    saved.id(),
                    unitId,
                    new SplitReservationInput(
                        SplitReservationStatus.ACKNOWLEDGED, "RSV-9001", "", T_OCCURRED),
                    actorA.membershipId()));
    assertThat(ack.commandStatus()).isEqualTo(SplitReservationStatus.ACKNOWLEDGED);
    assertThat(ack.externalReservationRef()).isEqualTo("RSV-9001");
    assertThat(ack.acknowledgedAt())
        .as("🔴 时间断言：读回值必须与写入值一致（+08:00 时刻不能偏 8 小时）")
        .isEqualTo(T_OCCURRED);
    assertThat(ack.upstreamReserved()).isFalse();

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.recordReservation(
                            saved.id(),
                            unitId,
                            new SplitReservationInput(
                                SplitReservationStatus.ACKNOWLEDGED, "", "", T_OCCURRED),
                            actorA.membershipId())))
        .as("已预占必须带 warehouse 侧预占号，否则「已预占」是无内容的一句谎")
        .hasMessageContaining("预占号");
  }

  @Test
  void 预占指令列表是真分页且带汇总额() {
    threeWarehouses();
    stock("WH-HK", SKU, "6.000000", T_COLLECTED);
    stock("WH-SH", SKU, "4.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);
    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    var page =
        asA(
            () ->
                service.pageReservations(
                    new SplitReservationQuery(saved.id(), null, null, null, 0, 1)));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.items()).hasSize(1);
    assertThat(page.requestedCount())
        .as("汇总不受分页影响")
        .isEqualTo(2);
  }

  // ================================================================ 发货回传

  @Test
  void 发货回传按拆分执行_一单元一条() {
    threeWarehouses();
    stock("WH-HK", SKU, "6.000000", T_COLLECTED);
    stock("WH-SH", SKU, "4.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);
    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    var detail = asA(() -> service.split(saved.id()));

    assertThat(detail.writebackTasks())
        .as("蓝图输出③：回传按拆分执行 —— 拆成 2 个单元就有 2 条回传任务")
        .hasSize(2);
    assertThat(detail.writebackTasks())
        .extracting(SplitWritebackTask::unitId)
        .containsExactlyInAnyOrderElementsOf(
            detail.units().stream().map(SplitUnit::id).toList());

    var unitId = detail.units().get(0).id();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.recordWriteback(
                            saved.id(),
                            unitId,
                            new SplitWritebackInput(
                                SplitWritebackStatus.CONFIRMED, "", "", "", T_OCCURRED),
                            actorA.membershipId())))
        .hasMessageContaining("物流号");

    var done =
        asA(
            () ->
                service.recordWriteback(
                    saved.id(),
                    unitId,
                    new SplitWritebackInput(
                        SplitWritebackStatus.CONFIRMED, "SF123456789", "", "", T_OCCURRED),
                    actorA.membershipId()));
    assertThat(done.status()).isEqualTo(SplitWritebackStatus.CONFIRMED);
    assertThat(done.trackingNumber()).isEqualTo("SF123456789");
    assertThat(done.settledAt()).isEqualTo(T_OCCURRED);
    assertThat(done.attemptCount()).isEqualTo(1);

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.recordWriteback(
                            saved.id(),
                            unitId,
                            new SplitWritebackInput(
                                SplitWritebackStatus.CONFIRMED, "X", "", "", T_OCCURRED),
                            actorA.membershipId())))
        .as("终态不可改写")
        .hasMessageContaining("终态");
  }

  @Test
  void 非平台单不建回传任务() {
    warehouse("WH-A", "SF", 24, "", true);
    stock("WH-A", SKU, "10.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);

    var input =
        new SplitInput(
            REF, "SO-MANUAL", "REQUEST_SNAPSHOT", "USD", "Ada", "+1-213-555-0100",
            "ada@example.com", "US", "100 Main St", "", "", "", new BigDecimal("100.0000"),
            List.of(new OrderLineInput("L1", SKU, "SKU-1", "商品", new BigDecimal("10.000000"),
                new BigDecimal("100.0000"))),
            PLANNED, "");
    var saved = asA(() -> service.split(input, actorA.membershipId()));
    var detail = asA(() -> service.split(saved.id()));

    assertThat(detail.writebackTasks())
        .as("手工单没有平台可回传，给它造一条任务是给「回传零失败」这个指标掺水")
        .isEmpty();
    assertThat(detail.amountConserved()).isTrue();
  }

  // ================================================================ 状态机

  @Test
  void 状态机单向推进且作废连带撤销预占指令() {
    threeWarehouses();
    stock("WH-HK", SKU, "6.000000", T_COLLECTED);
    stock("WH-SH", SKU, "4.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);
    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));

    var confirmed =
        asA(
            () ->
                service.advanceStatus(
                    saved.id(),
                    new SplitStatusInput(SplitStatus.CONFIRMED, "", T_OCCURRED),
                    actorA.membershipId()));
    assertThat(confirmed.status()).isEqualTo(SplitStatus.CONFIRMED);
    assertThat(confirmed.amountConserved()).isTrue();

    var dispatched =
        asA(
            () ->
                service.advanceStatus(
                    saved.id(),
                    new SplitStatusInput(SplitStatus.DISPATCHED, "", T_OCCURRED),
                    actorA.membershipId()));
    assertThat(dispatched.status()).isEqualTo(SplitStatus.DISPATCHED);

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            saved.id(),
                            new SplitStatusInput(SplitStatus.CANCELLED, "撤", T_OCCURRED),
                            actorA.membershipId())))
        .as("DISPATCHED 之后不可作废：货已经进履约了，此时作废等于让仓库凭空多一个包裹")
        .hasMessageContaining("非法状态流转");

    // 另起一条测作废：未落定的预占指令必须连带撤销
    var second =
        asA(
            () ->
                service.split(
                    snapshotInput("ORD-9311-2", "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    asA(
        () ->
            service.advanceStatus(
                second.id(),
                new SplitStatusInput(SplitStatus.CANCELLED, "买家改地址了", T_OCCURRED),
                actorA.membershipId()));
    long stillRequested =
        asA(
            () ->
                sql.fetchOne(
                        "select count(*) c from order_split_reservation_command"
                            + " where split_id = ? and command_status = 'REQUESTED'", second.id())
                    .get("c", Long.class));
    assertThat(stillRequested)
        .as("🔴 方案没了还让 warehouse 去锁货，就是在锁一堆没人会取的货")
        .isZero();
  }

  @Test
  void 作废必须给原因() {
    warehouse("WH-A", "SF", 24, "", true);
    stock("WH-A", SKU, "10.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);
    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            saved.id(),
                            new SplitStatusInput(SplitStatus.CANCELLED, "  ", T_OCCURRED),
                            actorA.membershipId())))
        .hasMessageContaining("作废必须给原因");
  }

  // ================================================================ 订单真值只读路径

  @Test
  void 按sales_order真值拆单_读不到时明确报404() {
    // ⚠️ SALES_ORDER 路径的 sku_ref 取的是 sales_order_line.sku_id 的 uuid 文本，
    // 所以库存分布必须登记在**同一个** uuid 上（解析顺序的字段名不同不是同一个键）。
    String skuRef = java.util.UUID.nameUUIDFromBytes(SKU.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    warehouse("WH-HK", "SF", 24, "", true);
    warehouse("WH-SH", "DHL", 24, "", true);
    stock("WH-HK", skuRef, "6.000000", T_COLLECTED);
    stock("WH-SH", skuRef, "4.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);

    UUID orderId = salesOrder("SO-REAL-1", "10.000000", "100.0000");
    var input =
        new SplitInput(
            orderId.toString(), "SO-REAL-1", "SALES_ORDER", "USD", "Ada", "+1-213-555-0100",
            "ada@example.com", "US", "100 Main St", "AMAZON", "", "", new BigDecimal("100.0000"),
            List.of(), PLANNED, "");
    var saved = asA(() -> service.split(input, actorA.membershipId()));
    var detail = asA(() -> service.split(saved.id()));
    assertThat(detail.split().orderSource()).isEqualTo("SALES_ORDER");
    assertThat(detail.amountConserved()).isTrue();
    assertThat(detail.units()).hasSize(2);
    assertThat(detail.unitLines())
        .allSatisfy(
            l -> {
              assertThat(l.skuRef()).isEqualTo(skuRef);
              assertThat(l.allocatedQty().signum()).isPositive();
            });

    // 显式要真值却读不到 → 404，并且**不回落**到快照（否则界面以为读到了真值）
    var bogus =
        new SplitInput(
            UUID.randomUUID().toString(), "SO-NOPE", "SALES_ORDER", "USD", "Ada", "", "", "US",
            "", "AMAZON", "", "", new BigDecimal("100.0000"), List.of(), PLANNED, "");
    assertThatThrownBy(() -> asA(() -> service.split(bogus, actorA.membershipId())))
        .hasMessageContaining("读不到");
  }

  /** 一张真实 sales_order（含一行），供 SALES_ORDER 只读路径使用。 */
  UUID salesOrder(String orderNo, String qty, String lineAmount) {
    UUID orderId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    BigDecimal q = new BigDecimal(qty);
    BigDecimal lineAmt = new BigDecimal(lineAmount);
    BigDecimal unitPrice = lineAmt.divide(q, 4, java.math.RoundingMode.HALF_UP);
    asA(
        () -> {
          sql.execute(
              "insert into sales_order(id,tenant_id,order_no,order_source,manual_kind,"
                  + "external_order_no,currency,payment_method,payment_terms_days,status,"
                  + "submitted_at,subtotal_amount,discount_amount,tax_amount,shipping_amount,"
                  + "other_amount,grand_total_amount,note,version,created_at,updated_at)"
                  + " values(?,?,?,'MANUAL','OFFLINE_STORE',?,'USD','PREPAID',0,'SUBMITTED',"
                  + "?::timestamptz,?::numeric,0,0,0,0,?::numeric,'',0,"
                  + "?::timestamptz,?::timestamptz)",
              orderId, a, orderNo, "EXT-" + orderNo, PLANNED, lineAmt, lineAmt,
              PLANNED.toInstant(), PLANNED.toInstant());
          sql.execute(
              "insert into sales_order_line(id,tenant_id,order_id,line_no,sku_id,sku_code,"
                  + "sku_name,quantity,unit_price,discount_amount,tax_amount,line_amount,note,"
                  + "created_at) values(?,?,?,1,?,?,'测试商品',?,?::numeric,0,0,?::numeric,'',"
                  + "?::timestamptz)",
              lineId, a, orderId, java.util.UUID.nameUUIDFromBytes(SKU.getBytes()),
              "SKU-1", q, unitPrice, lineAmt, PLANNED.toInstant());
          sql.execute(
              "insert into sales_order_party_snapshot(id,tenant_id,order_id,customer_party_id,"
                  + "customer_code,customer_name,customer_type,customer_default_currency,created_at)"
                  + " values(?,?,?,?,?,'Ada','INDIVIDUAL','USD',?::timestamptz)",
              UUID.randomUUID(), a, orderId, UUID.randomUUID(), "C-1", PLANNED.toInstant());
          // ⚠️ SUBMITTED 订单必须带收货地址快照：V99 的 guard_sales_order_submitted
          // 会拒绝提交时点缺地址的订单（「没有它就无法发货」）。这不是本模块的要求，
          // 补上是为了让这张夹具订单能真的提交成功，从而走通 SALES_ORDER 只读路径。
          sql.execute(
              "insert into order_address_snapshot(id,tenant_id,order_id,address_role,"
                  + "recipient_name,recipient_phone,recipient_email,company_name,country_code,"
                  + "province,city,district,postcode,address_line1,address_line2,tax_id,note,"
                  + "created_at)"
                  + " values(?,?,?,'SHIPPING','Ada','+1-213-555-0100','ada@example.com','',"
                  + "'US','CA','LOS ANGELES','','90001','100 Main St','','','',?::timestamptz)",
              UUID.randomUUID(), a, orderId, PLANNED.toInstant());
          return null;
        });
    return orderId;
  }

  // ================================================================ 多租户三断言

  // 断言 1：两租户互不可见
  @Test
  void 断言1_两租户互不可见() {
    threeWarehouses();
    stock("WH-HK", SKU, "10.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);
    var savedA =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));

    // B 租户另起一套夹具（同样的仓码与 SKU，但属另一个租户）
    asB(
        () -> {
          service.saveRule(
              new SplitRuleInput(true, true, true, false, true, SplitStrategy.WAREHOUSE, 8, true,
                  null, null),
              actorB.membershipId());
          service.saveWarehouse(
              new SplitWarehouseInput("WH-HK", "B 的仓", "LE-B", "SF", 24, "", true, ""),
              actorB.membershipId());
          service.saveStockSnapshot(
              new SplitStockSnapshotInput("WH-HK", SKU, new BigDecimal("10.000000"), T_COLLECTED,
                  "TENANT_MAINTAINED", "", ""),
              actorB.membershipId());
          service.split(
              new SplitInput(
                  REF, "SO-B", "REQUEST_SNAPSHOT", "USD", "Bob", "", "", "US", "",
                  "AMAZON", "", "", new BigDecimal("55.0000"),
                  List.of(new OrderLineInput("L1-B", SKU, "SKU-1", "商品",
                      new BigDecimal("10.000000"), new BigDecimal("55.0000"))),
                  PLANNED, ""),
              actorB.membershipId());
          return null;
        });

    var listA = asA(() -> service.pageSplits(new SplitQuery(null, null, null, null, null, 0, 50)));
    var listB = asB(() -> service.pageSplits(new SplitQuery(null, null, null, null, null, 0, 50)));
    assertThat(listA.total()).isEqualTo(1);
    assertThat(listA.items()).allSatisfy(s -> assertThat(s.id()).isEqualTo(savedA.id()));
    assertThat(listB.total()).isEqualTo(1);
    assertThat(listB.items()).allSatisfy(s -> assertThat(s.id()).isNotEqualTo(savedA.id()));

    assertThatThrownBy(() -> asB(() -> service.split(savedA.id())))
        .as("🔴 跨租户按 id 取拆单 → 404。拆单查询形状是「本租户全部可用仓」的宽条件，"
            + "一旦漏掉租户谓词就是跨租户拆单 —— 两家公司的货会切到对方的仓去")
        .hasMessageContaining("不存在");

    assertThat(asB(() -> service.warehouses(true)))
        .as("B 看不到 A 的仓档案")
        .allSatisfy(w -> assertThat(w.warehouseName()).isEqualTo("B 的仓"));
  }

  // 断言 2：伪造租户上下文无效
  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    threeWarehouses();
    stock("WH-HK", SKU, "10.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);
    var savedA =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    asB(
        () -> {
          service.saveRule(
              new SplitRuleInput(true, true, true, false, true, SplitStrategy.WAREHOUSE, 8, true,
                  null, null),
              actorB.membershipId());
          service.saveWarehouse(
              new SplitWarehouseInput("WH-HK", "B 的仓", "LE-B", "SF", 24, "", true, ""),
              actorB.membershipId());
          service.saveStockSnapshot(
              new SplitStockSnapshotInput("WH-HK", SKU, new BigDecimal("10.000000"), T_COLLECTED,
                  "TENANT_MAINTAINED", "", ""),
              actorB.membershipId());
          service.split(
              new SplitInput(
                  "ORD-B-1", "SO-B", "REQUEST_SNAPSHOT", "USD", "Bob", "", "", "US", "",
                  "AMAZON", "", "", new BigDecimal("55.0000"),
                  List.of(new OrderLineInput("L1-B", SKU, "SKU-1", "商品",
                      new BigDecimal("10.000000"), new BigDecimal("55.0000"))),
                  PLANNED, ""),
              actorB.membershipId());
          return null;
        });

    // (1) DB 层：没有 app.current_tenant 上下文时 FORCE RLS 什么都看不到
    assertThat(sql.fetchOne("select count(*) c from order_split").get("c", Integer.class))
        .as("RLS 之外无租户上下文 = 一行都读不到")
        .isZero();

    // (2) HTTP 边界：A 会话 + 伪造 X-Tenant-Id=B → 仍然只看得到 A 的行
    http.perform(get("/trade/fulfillment/split/splits")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].id").value(savedA.id().toString()));

    // (3) B 会话 + 伪造指向 A 的头 → 依然只看得到 B 自己的拆单，A 的详情 404
    String bOwnId =
        asB(
                () ->
                    service.pageSplits(new SplitQuery(null, null, null, null, null, 0, 50))
                        .items().get(0).id().toString());
    assertThat(bOwnId).isNotEqualTo(savedA.id().toString());
    http.perform(get("/trade/fulfillment/split/splits/" + savedA.id())
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // (4) 写入路径同样不认头：B 会话伪造 A 的头，落库仍属 B
    http.perform(post("/trade/fulfillment/split/warehouses")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    Map.of("warehouseRef", "WH-FAKE", "carrierCode", "XX", "shipByHours", 24))))
        .andExpect(status().isOk());
    assertThat(asA(() -> service.warehouse("WH-FAKE")))
        .as("🔴 伪造头不得把数据写进别的租户")
        .isNull();
    assertThat(asB(() -> service.warehouse("WH-FAKE"))).isNotNull();
  }

  // 断言 3：连接池归还后不串租户
  @Test
  void 断言3_连接池归还后不串租户() throws Exception {
    threeWarehouses();
    stock("WH-HK", SKU, "10.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);
    asA(
        () ->
            service.split(
                snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                actorA.membershipId()));
    asB(
        () -> {
          service.saveRule(
              new SplitRuleInput(true, true, true, false, true, SplitStrategy.WAREHOUSE, 8, true,
                  null, null),
              actorB.membershipId());
          service.saveWarehouse(
              new SplitWarehouseInput("WH-HK", "B 的仓", "LE-B", "SF", 24, "", true, ""),
              actorB.membershipId());
          service.saveStockSnapshot(
              new SplitStockSnapshotInput("WH-HK", SKU, new BigDecimal("10.000000"), T_COLLECTED,
                  "TENANT_MAINTAINED", "", ""),
              actorB.membershipId());
          service.split(
              new SplitInput(
                  "ORD-B-1", "SO-B", "REQUEST_SNAPSHOT", "USD", "Bob", "", "", "US", "",
                  "AMAZON", "", "", new BigDecimal("55.0000"),
                  List.of(new OrderLineInput("L1-B", SKU, "SKU-1", "商品",
                      new BigDecimal("10.000000"), new BigDecimal("55.0000"))),
                  PLANNED, ""),
              actorB.membershipId());
          return null;
        });

    // 交替 8 轮：连接池只有 1 条连接，每一轮都复用同一条 —— 归还后若残留 A 的租户设置，
    // 第二轮 B 的查询就会读出 A 的行。
    for (int i = 0; i < 8; i++) {
      var aList =
          asA(() -> service.pageSplits(new SplitQuery(null, null, null, null, null, 0, 50)));
      assertThat(aList.total()).as("第 " + i + " 轮 A 只看得到自己那一条").isEqualTo(1);
      assertThat(aList.items()).allSatisfy(s -> assertThat(s.recipientName()).isEqualTo("Ada Lovelace"));

      var bList =
          asB(() -> service.pageSplits(new SplitQuery(null, null, null, null, null, 0, 50)));
      assertThat(bList.total()).as("第 " + i + " 轮 B 只看得到自己那一条").isEqualTo(1);
      assertThat(bList.items()).allSatisfy(s -> assertThat(s.recipientName()).isEqualTo("Bob"));
    }

    // HTTP 层同样交替一轮，确认不是只有服务层方法隔离
    http.perform(get("/trade/fulfillment/split/splits")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(jsonPath("$.total").value(1));
    http.perform(get("/trade/fulfillment/split/splits")
            .header("Authorization", "Bearer tenant-b"))
        .andExpect(jsonPath("$.total").value(1));
    http.perform(get("/trade/fulfillment/split/splits")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(jsonPath("$.items[0].recipientName").value("Ada Lovelace"));
  }

  // ================================================================ HTTP 端点

  @Test
  void 端点真分页与筛选() throws Exception {
    threeWarehouses();
    stock("WH-HK", SKU, "6.000000", T_COLLECTED);
    stock("WH-SH", SKU, "4.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);
    asA(
        () ->
            service.split(
                snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                actorA.membershipId()));

    http.perform(get("/trade/fulfillment/split/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reservesUpstreamStock").value(false))
        .andExpect(jsonPath("$.orderReviewConnected").value(false))
        .andExpect(jsonPath("$.strategies.length()").value(4));

    http.perform(get("/trade/fulfillment/split/splits")
            .header("Authorization", "Bearer tenant-a")
            .queryParam("strategy", "WAREHOUSE")
            .queryParam("warehouseRef", "WH-HK")
            .queryParam("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].unitCount").value(2));

    // 关键字筛不到时返回真 0，不是假数据
    http.perform(get("/trade/fulfillment/split/splits")
            .header("Authorization", "Bearer tenant-a")
            .queryParam("keyword", "不存在的单号-ZZZ"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void 只读权限不能落拆单() throws Exception {
    warehouse("WH-A", "SF", 24, "", true);
    stock("WH-A", SKU, "10.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);
    http.perform(post("/trade/fulfillment/split/splits")
            .header("Authorization", "Bearer read-only")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                json.writeValueAsString(
                    Map.of(
                        "orderRef", REF,
                        "currencyCode", "USD",
                        "orderTotalAmount", "100.0000",
                        "plannedAt", "2026-03-01T10:45:00+08:00",
                        "lines", List.of(
                            Map.of(
                                "orderLineRef", "L1", "skuRef", SKU, "qty", "10.000000",
                                "amount", "100.0000"))))))
        .andExpect(status().isForbidden());
    assertThat(splitCount(a)).isZero();
  }

  @Test
  void 库存快照来源为7_1_1_3时必须给出处单号() {
    warehouse("WH-A", "SF", 24, "", true);
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      service.saveStockSnapshot(
                          new SplitStockSnapshotInput("WH-A", SKU, new BigDecimal("5.000000"),
                              T_COLLECTED, "MODULE_7_1_1_3", "", ""),
                          actorA.membershipId());
                      return null;
                    }))
        .as("没有出处的库存快照等于凭空捏一个数去决定从哪个仓发货")
        .hasMessageContaining("出处单号");
  }

  @Test
  void 库存分布挂在未登记的仓上被拒() {
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      service.saveStockSnapshot(
                          new SplitStockSnapshotInput("WH-NOT-REGISTERED", SKU,
                              new BigDecimal("5.000000"), T_COLLECTED, "TENANT_MAINTAINED", "",
                              ""),
                          actorA.membershipId());
                      return null;
                    }))
        .as("仓未登记时，拆单无法知道这个仓走哪个承运商、什么时效")
        .hasMessageContaining("未登记");
  }

  @Test
  void 事件流不可改写() {
    warehouse("WH-A", "SF", 24, "", true);
    stock("WH-A", SKU, "10.000000", T_COLLECTED);
    rule(SplitStrategy.WAREHOUSE, 8, true, null, null);
    var saved =
        asA(
            () ->
                service.split(
                    snapshotInput(REF, "10.000000", "100.0000", "100.0000"),
                    actorA.membershipId()));
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update order_split_event set detail = '篡改' where split_id = ?",
                          saved.id());
                      return null;
                    }))
        .as("拆单事件是回传与对账的证据，不许改写。"
            + "本迁移对 order_split_event 同时 REVOKE 了 UPDATE,DELETE 并挂了 BEFORE UPDATE OR DELETE 触发器；"
            + "运行时**权限**先挡下来（所以报的是权限而不是触发器那句话），两道闸都在")
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    // 两道闸各自独立生效：撤掉权限后触发器仍会拒 —— 用超级用户连接验证。
    assertThatThrownBy(
            () -> {
              try (var c =
                      DriverManager.getConnection(
                          pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                s.execute("select set_config('app.current_tenant','" + a + "',false)");
                s.execute(
                    "update order_split_event set detail = '篡改' where split_id = '"
                        + saved.id() + "'");
              }
            })
        .as("即使绕过权限（超级用户），append-only 触发器仍然拒绝改写")
        .hasMessageContaining("不可改写的留痕表");
  }
}
