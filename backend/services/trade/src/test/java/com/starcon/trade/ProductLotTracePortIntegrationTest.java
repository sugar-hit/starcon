package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;

import com.starcon.trade.ProductIssueAttributionModels.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 11.1.2.2 的<b>跨服务边界</b>集成测试 —— 7.3.1.1 批次追溯。
 *
 * <p>本测试存在的理由：蓝图输入句写「<b>批次信息</b>」，而批次真值在
 * <b>warehouse 独立库</b>（7.3.1.1，V57 inventory_lot）。AGENTS.md 模块边界纪律禁止
 * 跨服务 SQL / 外键 / 共享视图 / 直连对方库，所以本模块<b>只经 warehouse 的授权
 * HTTP 端点读</b>。这条边界「代码看起来对」是不够的，必须实跑证明三件事：
 *
 * <ol>
 *   <li><b>转发的是调用方自己的 Authorization bearer</b>，warehouse 侧仍按已认证成员关系
 *       解析租户 —— 隔离是<b>两道</b>（trade 侧 RLS + warehouse 侧 RLS），
 *       而不是「本服务替对方决定租户」。</li>
 *   <li><b>一次都不传 X-Tenant-Id</b>。本测试直接把桩收到的全部请求头记下来断言，
 *       让「有没有偷偷传租户头」变成一条可跑的断言而不是一句口头保证。</li>
 *   <li><b>不可达 / 越权如实报出，不降级</b>：降级成「先归因、批次稍后补」会造出一批
 *       永远补不上的批次标记，而批次标记正是下游 6.4.2.2 索赔要引用的证据。</li>
 * </ol>
 *
 * <p>⚠️ 桩用 JDK 内置 {@link HttpServer} 起在端口 0（操作系统现分配），
 * 与同服务其它测试同一手法。
 */
@SpringBootTest
class ProductLotTracePortIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID tid = UUID.randomUUID();
  static final UUID uid = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of("trade.aftersales.read", "trade.aftersales.write");

  /** warehouse 桩看到的全部请求头（用来断言「没有传 X-Tenant-Id」）。 */
  static final List<String> seenHeaders = new CopyOnWriteArrayList<>();
  static final List<String> seenPaths = new CopyOnWriteArrayList<>();

  static HttpServer warehouse;
  static volatile boolean warehouseDown;
  static final UUID knownLot = UUID.randomUUID();
  static final Map<UUID, Map<String, Object>> lots = new ConcurrentHashMap<>();

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
      // ⚠️ 桩必须在**静态初始化块**里起：@DynamicPropertySource 早于 @BeforeAll 执行，
      //   要把它的端口注入 WAREHOUSE_URL 就只能在这里启动。
      startWarehouse();
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
    r.add("IDENTITY_URL", () -> "http://localhost:8081");
    r.add("INTERNAL_KEY", () -> "test-key");
    // 指向本类静态块里起的 warehouse 桩 —— 端口由操作系统现分配（0）。
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
  }

  static void startWarehouse() throws Exception {
    // ---- 7.3.1.1 GET /warehouse/inventory/lots/{lotId}：批次档案详情 ----
    warehouse = HttpServer.create(new InetSocketAddress(0), 0);
    warehouse.createContext(
        "/warehouse/inventory/lots",
        e -> {
          record(e);
          if (warehouseDown) {
            e.sendResponseHeaders(503, -1);
            e.close();
            return;
          }
          String auth = e.getRequestHeaders().getFirst("Authorization");
          if (auth == null || !auth.startsWith("Bearer ")) {
            respond(e, 401, "{\"error\":\"missing bearer\"}");
            return;
          }
          String path = e.getRequestURI().getPath();
          // 列表
          if (path.equals("/warehouse/inventory/lots")) {
            var rows = new ArrayList<Map<String, Object>>();
            for (var row : lots.values()) rows.add(row);
            // ⚠️ 这里**不能**再套一层 json()：那会把已经拼好的 JSON 序列化成
            //   一个带引号的字符串字面量，Jackson 收到的是 VALUE_STRING 而不是对象，
            //   报成「no String-argument constructor」—— 症状看着像形状不对，
            //   病因其实是多编码了一层。
            respond(e, 200,
                "{\"rows\":" + arr(rows) + ",\"total\":" + rows.size()
                    + ",\"page\":0,\"size\":50}");
            return;
          }
          // 详情：/warehouse/inventory/lots/{lotId}
          String id = path.substring(path.lastIndexOf('/') + 1);
          Map<String, Object> row;
          try {
            row = lots.get(UUID.fromString(id));
          } catch (IllegalArgumentException ex) {
            respond(e, 400, "{\"error\":\"bad uuid\"}");
            return;
          }
          if (row == null) {
            // warehouse 侧 RLS 之下查不到 → 404（本端口必须原样如实报出）
            respond(e, 404, "{\"error\":\"lot not found\"}");
            return;
          }
          respond(e, 200, json(row));
        });
    warehouse.start();

    lots.put(knownLot, sampleLot());
  }

  @AfterAll
  static void stopWarehouse() {
    if (warehouse != null) warehouse.stop(0);
  }

  /** 7.3.1.1 {@code LotArchiveRow} 的应答形状（LinkedHashMap 保序，便于人读）。 */
  static Map<String, Object> sampleLot() {
    var m = new java.util.LinkedHashMap<String, Object>();
    m.put("lotId", knownLot.toString());
    m.put("skuId", UUID.randomUUID().toString());
    m.put("skuCode", "SKU-LOT-1");
    m.put("skuName", "批次测试商品");
    m.put("lotCode", "LOT-2026-03-A");
    m.put("producedOn", LocalDate.of(2026, 2, 1).toString());
    m.put("origin", "RECEIPT");
    m.put("supplierRefId", UUID.randomUUID().toString());
    m.put("supplierName", "供应商甲");
    m.put("status", "ACTIVE");
    m.put("onHand", new BigDecimal("100.000000").toPlainString());
    m.put("available", new BigDecimal("100.000000").toPlainString());
    m.put("bucketCount", 4);
    return m;
  }

  static void record(HttpExchange e) {
    seenPaths.add(e.getRequestURI().toString());
    e.getRequestHeaders().forEach((k, v) -> seenHeaders.add(k.toLowerCase() + "=" + v.getFirst()));
  }

  static String json(Object o) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String arr(List<Map<String, Object>> rows) {
    return rows.stream().map(ProductLotTracePortIntegrationTest::json)
        .collect(java.util.stream.Collectors.joining(",", "[", "]"));
  }

  static void respond(HttpExchange e, int code, String body) {
    try {
      byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      e.getResponseHeaders().add("Content-Type", "application/json");
      e.sendResponseHeaders(code, bytes.length);
      e.getResponseBody().write(bytes);
      e.close();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  @MockitoSpyBean AccessService access;
  @Autowired ProductLotTracePort port;
  @Autowired DSLContext sql;

  @AfterAll
  static void stopPg() {
    pg.stop();
  }

  @BeforeEach
  void reset() {
    seenHeaders.clear();
    seenPaths.clear();
    warehouseDown = false;
    lots.clear();
    lots.put(knownLot, sampleLot());
  }

  // ==========================================================================
  // 断言
  // ==========================================================================

  @Test
  void 批次档案经授权端点读到并落成本地快照() {
    var snapshot = port.snapshot(knownLot, "Bearer caller-token");

    assertThat(snapshot.lotId()).isEqualTo(knownLot);
    assertThat(snapshot.lotCode()).isEqualTo("LOT-2026-03-A");
    assertThat(snapshot.producedOn()).isEqualTo(LocalDate.of(2026, 2, 1));
    assertThat(snapshot.supplierName()).isEqualTo("供应商甲");

    // ⚠️ **一次都不传 X-Tenant-Id**：这条断言让边界纪律变成可跑的检查。
    assertThat(seenHeaders)
        .as("跨服务调用绝不传递或信任 X-Tenant-Id 头")
        .noneMatch(h -> h.startsWith("x-tenant-id"));
    // 转发的是**调用方自己的** bearer。
    assertThat(seenHeaders).contains("authorization=Bearer caller-token");
    // 走的是 7.3.1.1 的授权读端点。
    assertThat(seenPaths).anyMatch(p -> p.equals("/warehouse/inventory/lots/" + knownLot));
  }

  @Test
  void 批次查不到如实报404不降级() {
    // 「批次不存在」与「仓库挂了」必须能被区分开：
    // 返回 0 / 空快照会让运营以为这个 SKU 没问题，而实际上查询根本没成功。
    assertThatThrownBy(() -> port.snapshot(UUID.randomUUID(), "Bearer caller-token"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404))
        .hasMessageContaining("批次不存在或不属于当前租户");

    // 仓库挂了 → 503，且**保留原始异常**（排查时要能看到真实的传输层错误）。
    warehouseDown = true;
    assertThatThrownBy(() -> port.snapshot(knownLot, "Bearer caller-token"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> {
              var ex = (ResponseStatusException) e;
              assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(ex.getReason()).contains("批次追溯的合法入口只有 warehouse 的授权读接口");
              assertThat(ex.getCause()).as("不可达要保留 cause，排查不能靠猜").isNotNull();
            });
  }

  @Test
  void 批次下拉真分页不返回假空列表() {
    var page = port.candidates(null, "LOT", 0, 20, "Bearer caller-token");
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.rows()).hasSize(1);
    assertThat(page.rows().get(0).lotCode()).isEqualTo("LOT-2026-03-A");

    // 关键字真的传过去了（路径里能看到查询参数）。
    assertThat(seenPaths).anyMatch(p -> p.contains("keyword=LOT"));
    assertThat(seenPaths).anyMatch(p -> p.contains("size=20"));
    assertThat(seenHeaders).noneMatch(h -> h.startsWith("x-tenant-id"));
  }

  @Test
  void 本服务库内不存在warehouse的批次表() {
    // ⚠️ 跨库 FK / 视图被 AGENTS.md 禁止：本库**根本没有** inventory_lot 这张表。
    // 断言这一点比读注释可靠 —— 真有人建了跨库视图时，这里会立刻红。
    Integer count =
        sql.fetchOne(
                "select count(*) from information_schema.tables"
                    + " where table_schema = current_schema() and table_name = 'inventory_lot'")
            .get(0, Integer.class);
    assertThat(count).as("批次真值在 warehouse 独立库，本库不得有它的表").isZero();

    // 本模块的归因表只存 lot_id（逻辑 id）+ 快照列，没有任何指向对方的外键。
    Integer fks =
        sql.fetchOne(
                "select count(*) from pg_constraint"
                    + " where conrelid = 'product_issue_attribution'::regclass and contype = 'f'")
            .get(0, Integer.class);
    assertThat(fks).as("归因单不得建任何外键（跨车道 DDL 依赖 + 跨库外键都被禁止）").isZero();
  }
}
