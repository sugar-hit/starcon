package com.starcon.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 13.5.1.1 库存分析的**多租户隔离测试**（真实 PostgreSQL 16 + FORCE RLS，无内存替身）。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条断言（缺一不可）：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>；
 *   <li><b>伪造租户上下文无效</b>（并断言行确实落在**会话租户**名下）；
 *   <li><b>连接池归还后不串租户</b>。
 * </ol>
 *
 * <p>⚠️ <b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryAnalysisTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> permissions =
      List.of(
          "reporting.inventory-analysis.read",
          "reporting.inventory-analysis.write",
          "reporting.projection.read",
          "reporting.projection.write");

  static final ObjectMapper JSON = JsonMapper.builder().addModule(new JavaTimeModule()).build();

  static final LocalDate DAY = LocalDate.of(2026, 10, 10);

  static {
    pg.start();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // 运行角色必须 NOBYPASSRLS，否则它会绕过策略，三条断言全部变成空断言。
      s.execute("create role starcon_app nologin");
      s.execute(
          "create role reporting_app login password 'test-only' nosuperuser nobypassrls");
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
      actors.put("Bearer tenant-a", actor(a));
      actors.put("Bearer tenant-b", actor(b));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static AccessService.Access actor(UUID tenant) {
    var grant = new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", permissions);
    return new AccessService.Access(
        user, tenant, "Test", UUID.randomUUID(), null, permissions, List.of(grant));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "reporting_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TenantTransactions tenants;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired ReportingInventoryAnalysisService inventoryAnalysis;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @BeforeEach
  void reset() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate reporting_inventory_analysis_position, reporting_inventory_analysis_sales_fact,"
              + " reporting_inventory_analysis_aging_fact, reporting_inventory_analysis_stocktake_fact,"
              + " reporting_inventory_analysis_rule cascade");
    }
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  /** 写一条库存头寸（落在**指定租户**名下）。 */
  private void position(UUID tenant, String skuCode, String warehouseCode, String onHand) {
    insertAs(tenant, tenant, skuCode, warehouseCode, onHand);
  }

  /**
   * 写一条库存头寸，但**行上的 tenant_id 可以与绑定的租户不同**。
   *
   * <p>⚠️ 用来证 RLS 的 {@code WITH CHECK}：绑定 A 时往 B 名下行写 ⇒ 必须被数据库挡住。
   */
  private void insertAs(
      UUID boundTenant, UUID rowTenant, String skuCode, String warehouseCode, String onHand) {
    tenants.run(
        boundTenant,
        () -> {
          var cost = new BigDecimal("10");
          var qty = new BigDecimal(onHand);
          sql.execute(
              """
              insert into reporting_inventory_analysis_position
                (id,tenant_id,as_of,sku_id,sku_code,sku_name,
                 warehouse_id,warehouse_code,warehouse_name,
                 legal_entity_id,legal_entity_code,category_code,shop_code,shop_name,
                 on_hand,reserved,available,max_on_hand,
                 unit_cost,cost_currency,cost_method,inventory_value,
                 zero_stock,overstock,threshold_configured,
                 source_service,source_reference,updated_at)
              values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
              """,
              UUID.randomUUID(),
              rowTenant,
              DAY,
              UUID.randomUUID(),
              skuCode,
              skuCode + " 名称",
              UUID.randomUUID(),
              warehouseCode,
              warehouseCode + " 仓",
              null,
              "",
              "家居收纳",
              "",
              "",
              qty,
              BigDecimal.ZERO,
              qty,
              null,
              cost,
              "USD",
              "MOVING_AVERAGE",
              qty.multiply(cost).setScale(4, java.math.RoundingMode.HALF_UP),
              false,
              false,
              false,
              "test",
              "test:" + rowTenant + ":" + skuCode + ":" + warehouseCode);
          return null;
        });
  }

  /** 某租户看到的库存金额结构（走完整的读模型）。 */
  private ReportingInventoryAnalysisModels.InventoryStructureResponse structure(UUID tenant) {
    return inventoryAnalysis.structure(tenant, "WAREHOUSE", null, null);
  }

  // ==========================================================================
  // 断言 1：两租户互不可见
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    position(a, "SKU-A", "WH-1", "100");
    position(b, "SKU-B", "WH-2", "50");

    var rowsA = structure(a).rows();
    var rowsB = structure(b).rows();
    assertThat(rowsA).as("租户 A 只能看到自己的库存头寸").hasSize(1);
    assertThat(rowsA.get(0).dimensionKey()).isEqualTo("WH-1");
    assertThat(rowsA.get(0).inventoryValue()).isEqualByComparingTo("1000.0000");
    assertThat(rowsB).hasSize(1);
    assertThat(rowsB.get(0).dimensionKey()).isEqualTo("WH-2");
    assertThat(rowsB.get(0).inventoryValue()).as("B 只看得到自己的").isEqualByComparingTo("500.0000");

    // 更强的一格：A 明确按 SKU 直查 B 的行也拿不到（RLS 挡住）。
    var cross =
        tenants.run(
            a,
            () ->
                sql.fetch(
                        "select sku_code from reporting_inventory_analysis_position "
                            + "where tenant_id = ? and sku_code = ?",
                        b,
                        "SKU-B")
                    .size());
    assertThat(cross).as("按主键直查也必须被 RLS 挡住").isZero();
  }

  // ==========================================================================
  // 断言 2：伪造租户上下文无效
  // ==========================================================================

  @Test
  void 断言2_伪造租户头无效() throws Exception {
    position(a, "SKU-A", "WH-1", "100");
    position(b, "SKU-B", "WH-2", "50");

    // (1) 事务外没有「环境租户」：不存在一个可以被沿用的默认租户。
    assertThat(rawTenantSetting()).isNullOrEmpty();
    assertThat(visiblePositionsWithoutTenant()).as("未绑定租户时不应看到任何行").isZero();

    // (2) 事务内绑定的值精确等于可信参数，提交后立刻失效。
    var bound =
        tenants.run(
            b,
            () -> {
              assertThat(effectiveTenant()).isEqualTo(b);
              return rawTenantSetting();
            });
    assertThat(bound).isEqualTo(b.toString());
    assertThat(effectiveTenant()).as("提交后租户上下文必须已清空").isNull();
    assertThat(visiblePositionsWithoutTenant()).isZero();

    // (3) HTTP 边界：带**租户 A 的合法会话** + **伪造的 X-Tenant-Id（租户 B）**
    //     ⇒ 返回的仍然是**会话租户 A** 的数据，B 的 50 一格都看不到。
    http.perform(
            get("/reporting/inventory-analysis/structure")
                .param("dimension", "WAREHOUSE")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].dimensionKey").value("WH-1"))
        .andExpect(jsonPath("$.rows[0].inventoryValue").value(org.hamcrest.Matchers.closeTo(1000.0, 0.001)));

    // 反向也成立：会话是 B、伪造头是 A ⇒ 看到的是 B 的 50，不是 A 的 1000。
    http.perform(
            get("/reporting/inventory-analysis/structure")
                .param("dimension", "WAREHOUSE")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].dimensionKey").value("WH-2"))
        .andExpect(jsonPath("$.rows[0].inventoryValue").value(org.hamcrest.Matchers.closeTo(500.0, 0.001)));

    // (4) 写侧也必须被 RLS 的 WITH CHECK 挡住：绑定 A 时插入 tenant_id = B 的行必须失败。
    assertThatThrownBy(() -> insertAs(a, b, "SKU-FORGED", "WH-9", "1"))
        .as("绑定 A 时不许往 B 名下写行")
        .isInstanceOf(RuntimeException.class);
    var landedInB =
        tenants.run(
            b,
            () ->
                sql.fetch(
                        "select sku_code from reporting_inventory_analysis_position "
                            + "where tenant_id = ? and sku_code = ?",
                        b,
                        "SKU-FORGED")
                    .size());
    assertThat(landedInB).as("被伪造的租户不得看见别人的库存头寸").isZero();
  }

  // ==========================================================================
  // 断言 3：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 断言3_连接池归还后不串租户() {
    position(a, "SKU-A", "WH-1", "100");
    for (int i = 0; i < 5; i++) {
      assertThat(valueOf(a)).as("第 %d 轮租户 A", i).isEqualByComparingTo("1000.0000");
      assertThat(valueOf(b)).as("第 %d 轮租户 B（无事实）", i).isNull();
      assertThat(visiblePositionsWithoutTenant()).as("第 %d 轮事务外", i).isZero();
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(valueOf(a)).isEqualByComparingTo("1000.0000");
    assertThat(valueOf(b)).isNull();
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  /** 某租户看到的库存金额合计（没有事实 ⇒ null，不是 0）。 */
  private BigDecimal valueOf(UUID tenant) {
    var rows = structure(tenant).rows();
    if (rows.isEmpty()) return null;
    return rows.get(0).inventoryValue();
  }

  private String rawTenantSetting() {
    return sql.fetch("select current_setting('app.current_tenant', true)").stream()
        .findFirst()
        .map(r -> r.get(0, String.class))
        .orElse(null);
  }

  private UUID effectiveTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  private int visiblePositionsWithoutTenant() {
    return sql.fetch("select id from reporting_inventory_analysis_position").size();
  }
}
