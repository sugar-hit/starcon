package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.5.2.2 BOM 展开：多层递归展开、子 BOM 判定、逐层结转、按仓库过滤、缺口、
 * 循环引用拒绝、深度截断，以及租户隔离三断言（<b>覆盖深层组件行</b>）。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：BOM、版本、组件、展开记录全部真的落库。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>跨服务依赖的处理</b>：可用量来自 warehouse 服务（另一个数据库）。
 * 生产路径是真实的 {@code RestClient} HTTP 调用，本测试用一个
 * {@link HttpServer} 起一个<b>确定性的替身</b>来满足它 ——
 * 替身只存在于测试代码里，且被断言「确实收到了 catalog 透传的 Authorization 头」
 * 与「确实收到了 warehouseId」，所以它证明的是<b>生产代码真的走 HTTP 且透传了租户上下文</b>，
 * 而不是把替身逻辑塞进了生产代码。warehouse 侧那个端点自身的口径由
 * {@code InventoryAvailabilityIntegrationTest}（warehouse 服务内、真实 PG16）钉死。
 *
 * <p><b>本文件最重要的断言</b>：
 *
 * <ol>
 *   <li>{@link #多层展开按父行缺口逐层结转()}：三层树，逐值断言需求量、可用、在途、缺口，
 *       并钉死「子层用父行的**缺口**而不是需求量」。
 *   <li>{@link #循环引用被拒绝并逐个点名绕回路径()}：A→B→A 必须 409，且**不落任何记录**。
 *   <li>{@link #深度上限截断但结果里显式标明()}：截断必须看得见（status + 行级 truncated）。
 *   <li>{@link #租户隔离三断言覆盖深层组件行()}：不只是顶层 —— 深层组件行同样互不可见。
 *   <li>{@link #展开基准时刻被冻结成写回的值()}：断言 **as_of 写回的值**，不是只断言没抛异常。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class BomExplosionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer warehouse;
  static final ObjectMapper JSON = new ObjectMapper();
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** warehouse 替身的库存数据集（tenant → skuId → (available, inTransit)）。 */
  static final Map<UUID, Map<UUID, BigDecimal[]>> stock =
      new java.util.concurrent.ConcurrentHashMap<>();
  /** warehouse 替身最近一次收到的调用：用来断言 Authorization 头与 warehouseId 真的透传了。 */
  static final AtomicReference<String> lastBearer = new AtomicReference<>();
  static final AtomicReference<UUID> lastWarehouseId = new AtomicReference<>();
  /** 置位后替身一律回 503：模拟「库存服务不可用」，用于断言展开失败而不是把可用当成 0。 */
  static final java.util.concurrent.atomic.AtomicBoolean warehouseDown =
      new java.util.concurrent.atomic.AtomicBoolean();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(), grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try (var conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = conn.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
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
      startWarehouseStub();
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      actors.put(
          "Bearer reader", actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * warehouse 替身：一个真实的 HTTP 服务器，形状与 warehouse 的 AvailabilityView 逐字一致。
   *
   * <p>它刻意<b>不</b>校验 Authorization（真实 warehouse 会向 identity 校验），
   * 因为本测试要断言的正是「catalog 把这个头透传出去了」。
   */
  static void startWarehouseStub() throws Exception {
    warehouse = HttpServer.create(new InetSocketAddress(0), 0);
    warehouse.createContext(
        "/warehouse/inventory/availability",
        e -> {
          lastBearer.set(e.getRequestHeaders().getFirst("Authorization"));
          if (warehouseDown.get()) {
            e.sendResponseHeaders(503, -1);
            e.close();
            return;
          }
          JsonNode body = JSON.readTree(e.getRequestBody().readAllBytes());
          UUID warehouseId = body.hasNonNull("warehouseId") ? UUID.fromString(body.get("warehouseId").asText()) : null;
          lastWarehouseId.set(warehouseId);
          // 租户由 Authorization 头决定（与真实 warehouse 从已认证成员关系解析租户同构）。
          UUID tenant =
              "Bearer tenant-b".equals(lastBearer.get())
                  ? b
                  : "Bearer tenant-a".equals(lastBearer.get()) ? a : UUID.randomUUID();
          Map<UUID, BigDecimal[]> owned = stock.getOrDefault(tenant, Map.of());
          List<Map<String, Object>> items = new ArrayList<>();
          for (JsonNode sku : body.get("skuIds")) {
            UUID skuId = UUID.fromString(sku.asText());
            BigDecimal[] row = owned.get(skuId);
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("skuId", skuId.toString());
            item.put("skuCode", skuId.toString().substring(0, 8));
            item.put("available", row == null ? BigDecimal.ZERO : row[0]);
            item.put("inTransit", row == null || row.length < 2 ? BigDecimal.ZERO : row[1]);
            items.add(item);
          }
          Map<String, Object> response = new java.util.LinkedHashMap<>();
          response.put("warehouseId", warehouseId == null ? null : warehouseId.toString());
          response.put("requestedCount", items.size());
          response.put("items", items);
          response.put("note", "测试替身");
          byte[] out = JSON.writeValueAsBytes(response);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, out.length);
          e.getResponseBody().write(out);
          e.close();
        });
    warehouse.start();
  }

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "assets", "attribute")
        .flatMap(p -> java.util.stream.Stream.of("read", "create", "update", "delete")
            .map(op -> "catalog." + p + "." + op))
        .toList();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "catalog_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("WAREHOUSE_URL", () -> "http://localhost:" + warehouse.getAddress().getPort());
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired BomVersionService boms;
  @Autowired BomExplosionService explosions;
  @Autowired CatalogService catalog;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    warehouse.stop(0);
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setup() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    stock.clear();
    lastBearer.set(null);
    lastWarehouseId.set(null);
    warehouseDown.set(false);
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  String unique(String hint) {
    return hint + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  UUID skuOf(AccessService.Access who, String code) {
    UUID spuId = write(who, () -> catalog.saveSpu(
        who, null, new SpuInput(code, "SPU " + code, "", "Brand", "Category", "DRAFT", 0)));
    return write(who, () -> catalog.saveSku(
        who, spuId, null, new SkuInput(code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), 0)));
  }

  /** 建 BOM + 一版已发布版本（组件清单 = components）。 */
  UUID publishedBom(
      AccessService.Access who,
      UUID outputSku,
      String versionNo,
      List<BomComponentInput> components,
      Instant from) {
    UUID bomId = write(who, () -> boms.saveBom(who, null,
        new BomInput(unique("BOM"), outputSku, "", 0))).id();
    return publishedVersion(who, bomId, versionNo, components, from, null);
  }

  UUID publishedVersion(
      AccessService.Access who,
      UUID bomId,
      String versionNo,
      List<BomComponentInput> components,
      Instant from,
      Instant to) {
    UUID versionId =
        write(who, () -> boms.createVersion(who, bomId,
                new BomVersionInput(versionNo, "BOM " + versionNo, from.toString(),
                    to == null ? null : to.toString(), "", 0)))
            .id();
    write(who, () -> boms.replaceComponents(who, versionId,
        new BomComponentsInput(components, 0)));
    write(who, () -> boms.publishVersion(who, versionId));
    return versionId;
  }

  void stock(AccessService.Access who, UUID skuId, String available, String inTransit) {
    stock.computeIfAbsent(tenantOf(who), k -> new LinkedHashMap<>())
        .put(skuId, new BigDecimal[] {new BigDecimal(available), new BigDecimal(inTransit)});
  }

  UUID tenantOf(AccessService.Access who) {
    return who.tenantId();
  }

  String bearerOf(AccessService.Access who) {
    return who.tenantId().equals(a) ? "Bearer tenant-a" : "Bearer tenant-b";
  }

  BomExplosionRun explode(AccessService.Access who, BomExplosionInput input) {
    return tx(who, () -> explosions.explode(who, bearerOf(who), input));
  }

  BomExplosionLine lineOf(BomExplosionRun run, String skuCode) {
    return run.lines().stream()
        .filter(l -> l.skuCode().equals(skuCode))
        .findFirst()
        .orElseThrow(() -> new AssertionError("展开结果里没有 " + skuCode + "，实际有："
            + run.lines().stream().map(BomExplosionLine::skuCode).toList()));
  }

  // ================================================================ 多层展开与逐层结转

  /**
   * 三层树：FG-A → [SA, RAW-X]；SA → [RAW-Y, RAW-Z]。
   *
   * <p>逐值断言的核心是 <b>子层用父行的缺口</b>：SA 需要 10、可用 4、在途 3 → 缺口 3，
   * 所以 RAW-Y 是 3×4 = 12 而不是 10×4 = 40。按需求量展开会凭空多出 28 个面料。
   */
  @Test
  void 多层展开按父行缺口逐层结转() {
    Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
    UUID fg = skuOf(actor, unique("FG"));
    UUID sa = skuOf(actor, unique("SA"));
    UUID rawX = skuOf(actor, unique("RAW"));
    UUID rawY = skuOf(actor, unique("RAW"));
    UUID rawZ = skuOf(actor, unique("RAW"));

    // 第二层：BOM 的成品是 SA，组件是 RAW-Y / RAW-Z。
    publishedBom(actor, sa, "v1", List.of(
        new BomComponentInput(rawY, new BigDecimal("4"), BigDecimal.ZERO, "", ""),
        new BomComponentInput(rawZ, new BigDecimal("0.5"), new BigDecimal("10"), "", "")), from);
    // 第一层：成品 FG，组件是 SA（子装配）与 RAW-X（叶子）。
    UUID rootVersion = publishedBom(actor, fg, "v1", List.of(
        new BomComponentInput(sa, new BigDecimal("2"), new BigDecimal("5"), "", ""),
        new BomComponentInput(rawX, new BigDecimal("3"), BigDecimal.ZERO, "", "")), from);

    stock(actor, sa, "4", "3");
    stock(actor, rawX, "100", "0");
    stock(actor, rawY, "0", "0");
    stock(actor, rawZ, "1", "0");

    var run = explode(actor, new BomExplosionInput(rootVersion, null,
        new BigDecimal("10"), null, null, null, ""));
    var saLine = lineOf(run, code(sa));
    var leafXLine = run.lines().stream().filter(l -> l.depth() == 1 && !l.isSubAssembly())
        .findFirst().orElseThrow();

    // 需求 10 个成品 × 用量 2 × (1 + 5%) = 21 —— R6 百分数 + R7 公式，不取整。
    assertThat(saLine.requiredQuantity()).isEqualByComparingTo("21.000000");
    assertThat(saLine.available()).isEqualByComparingTo("4");
    assertThat(saLine.inTransit()).isEqualByComparingTo("3");
    assertThat(saLine.gap()).isEqualByComparingTo("14.000000");
    assertThat(saLine.isSubAssembly()).isTrue();
    assertThat(saLine.expanded()).isTrue();
    assertThat(saLine.depth()).isEqualTo(1);
    assertThat(saLine.subVersionNo()).isEqualTo("v1");

    // 子层的驱动量是**父行的缺口 14**，不是需求量 21。
    var childY = lineOf(run, code(rawY));
    assertThat(childY.requiredQuantity()).isEqualByComparingTo("56.000000"); // 14 × 4
    assertThat(childY.parentLineId()).isEqualTo(saLine.id());
    assertThat(childY.depth()).isEqualTo(2);
    assertThat(childY.sourceBomCode()).isEqualTo(saLine.subBomCode());
    // RAW-Z：14 × 0.5 × (1+10%) = 7.7 —— 小数需求不被取整。
    var childZ = lineOf(run, code(rawZ));
    assertThat(childZ.requiredQuantity()).isEqualByComparingTo("7.700000");
    assertThat(childZ.gap()).isEqualByComparingTo("6.700000"); // 7.7 − 1 可用

    // 叶子 RAW-X：10 × 3 = 30，可用 100 → 缺口 0，不再展开。
    var leafX = leafXLine;
    assertThat(leafX.requiredQuantity()).isEqualByComparingTo("30.000000");
    assertThat(leafX.gap()).isEqualByComparingTo("0.000000");
    assertThat(leafX.expanded()).isFalse();
    assertThat(leafX.covered()).isTrue();

    // 结果冻结成一次运行记录，可被 06 的采购单引用。
    assertThat(run.status()).isEqualTo("COMPLETED");
    assertThat(run.truncated()).isFalse();
    assertThat(run.reachedDepth()).isEqualTo(2);
    assertThat(run.totalGap()).isGreaterThan(BigDecimal.ZERO);
    assertThat(run.lines()).allMatch(l -> l.depth() >= 1 && l.depth() <= 2);
    assertThat(run.lines()).allMatch(l -> l.path() != null && !l.path().isBlank());
    // 物化路径是稳定的父子序。同层按 **SKU 编码**排（与 3.5.2.1 的组件清单同一形状），
    // 所以子装配落在哪一段取决于编码序 —— 断言的是「路径本身逐段父子相接」，而不是猜哪个排前面。
    var paths = run.lines().stream().map(BomExplosionLine::path).toList();
    assertThat(paths.stream().filter(p -> !p.contains(".")).count()).isEqualTo(2);
    for (String path : paths) {
      String parent = path.contains(".") ? path.substring(0, path.lastIndexOf('.')) : null;
      if (parent != null) assertThat(paths).contains(parent);
    }
    // 深度与路径段数一致，且第 2 层行的 parent 正是第 1 层的子装配行。
    assertThat(saLine.depth()).isEqualTo(1);
    assertThat(childY.depth()).isEqualTo(2);
    assertThat(saLine.path() + ".1").isIn(childY.path(), childZ.path());
  }

  // ================================================================ 子 BOM 判定

  @Test
  void 没有生效版本的子bom按采购件处理并说清原因() {
    Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
    UUID fg = skuOf(actor, unique("FG"));
    UUID sa = skuOf(actor, unique("SA"));
    UUID rawY = skuOf(actor, unique("RAW"));
    // 子 BOM 只有**草稿**版本：规则 R3 说只有已发布版本参与生效判定。
    UUID bomId = write(actor, () -> boms.saveBom(actor, null,
        new BomInput(unique("BOM"), sa, "", 0))).id();
    UUID draftId = write(actor, () -> boms.createVersion(actor, bomId,
        new BomVersionInput("v1", "草稿", from.toString(), null, "", 0))).id();
    write(actor, () -> boms.replaceComponents(actor, draftId,
        new BomComponentsInput(List.of(new BomComponentInput(rawY, BigDecimal.ONE, BigDecimal.ZERO, "", "")), 0)));

    UUID rootVersion = publishedBom(actor, fg, "v1", List.of(
        new BomComponentInput(sa, BigDecimal.ONE, BigDecimal.ZERO, "", "")), from);

    var run = explode(actor, new BomExplosionInput(rootVersion, null,
        BigDecimal.TEN, null, null, null, ""));
    var line = run.lines().stream().filter(l -> l.isSubAssembly()).findFirst().orElseThrow();
    // 它是子装配，但没有生效版本 → 按叶子处理，且 note 说清为什么。
    assertThat(line.isSubAssembly()).isTrue();
    assertThat(line.subVersionId()).isNull();
    assertThat(line.expanded()).isFalse();
    assertThat(line.note()).contains("没有生效版本").contains("草稿");
    assertThat(run.lines()).hasSize(1);
  }

  @Test
  void 多个bom同时声明同一成品时确定性取第一个并显式告警() {
    Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
    UUID fg = skuOf(actor, unique("FG"));
    UUID sa = skuOf(actor, unique("SA"));
    UUID rawY = skuOf(actor, unique("RAW"));
    // 两个 BOM 都把 SA 声明为产出。
    publishedBom(actor, sa, "v1", List.of(
        new BomComponentInput(rawY, new BigDecimal("2"), BigDecimal.ZERO, "", "")), from);
    publishedBom(actor, sa, "v1", List.of(
        new BomComponentInput(rawY, new BigDecimal("9"), BigDecimal.ZERO, "", "")), from);
    UUID rootVersion = publishedBom(actor, fg, "v1", List.of(
        new BomComponentInput(sa, BigDecimal.ONE, BigDecimal.ZERO, "", "")), from);

    var run = explode(actor, new BomExplosionInput(rootVersion, null,
        BigDecimal.ONE, null, null, null, ""));
    var line = run.lines().stream().filter(l -> l.isSubAssembly()).findFirst().orElseThrow();
    assertThat(line.ambiguousSubBom()).isTrue();
    assertThat(line.note()).contains("被 2 个 BOM 同时声明为产出").contains("按编码序取第一个");
    // 确定性：再展开一次取到的还是同一个子 BOM。
    var again = explode(actor, new BomExplosionInput(rootVersion, null,
        BigDecimal.ONE, null, null, null, ""));
    var sameLine = again.lines().stream().filter(l -> l.isSubAssembly()).findFirst().orElseThrow();
    assertThat(sameLine.subBomId()).isEqualTo(line.subBomId());
  }

  // ================================================================ 循环引用

  @Test
  void 循环引用被拒绝并逐个点名绕回路径() {
    Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
    UUID fa = skuOf(actor, unique("CYCLE"));
    UUID fb = skuOf(actor, unique("CYCLE"));
    UUID raw = skuOf(actor, unique("RAW"));
    // A 的组件是 B 的成品，B 的组件又是 A 的成品。
    UUID versionA = publishedBom(actor, fa, "v1", List.of(
        new BomComponentInput(fb, BigDecimal.ONE, BigDecimal.ZERO, "", "")), from);
    publishedBom(actor, fb, "v1", List.of(
        new BomComponentInput(fa, BigDecimal.ONE, BigDecimal.ZERO, "", "")), from);

    long before = tx(actor, () -> explosions.runs(new BomExplosionRunQuery(null, null, 0, 100)).total());
    assertThatThrownBy(() -> explode(actor, new BomExplosionInput(versionA, null,
        BigDecimal.ONE, null, null, null, "")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("BOM 循环引用")
        .hasMessageContaining("已拒绝展开")
        .hasMessageContaining("在数学上无界");
    // 拒绝必须是**无副作用**的：一条记录都不落。
    assertThat(tx(actor, () -> explosions.runs(new BomExplosionRunQuery(null, null, 0, 100)).total()))
        .isEqualTo(before);
    assertThat(raw).isNotNull();
  }

  // ================================================================ 深度上限

  @Test
  void 深度上限截断但结果里显式标明() {
    Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
    // 三层链：L1 → L2 → L3 → L4。
    UUID l1 = skuOf(actor, unique("L1"));
    UUID l2 = skuOf(actor, unique("L2"));
    UUID l3 = skuOf(actor, unique("L3"));
    UUID l4 = skuOf(actor, unique("L4"));
    publishedBom(actor, l3, "v1", List.of(
        new BomComponentInput(l4, BigDecimal.ONE, BigDecimal.ZERO, "", "")), from);
    publishedBom(actor, l2, "v1", List.of(
        new BomComponentInput(l3, BigDecimal.ONE, BigDecimal.ZERO, "", "")), from);
    UUID versionL1 = publishedBom(actor, l1, "v1", List.of(
        new BomComponentInput(l2, BigDecimal.ONE, BigDecimal.ZERO, "", "")), from);

    // 深度上限 2：L1 → L2 → L3 展开到第 2 层，L3 还有下级（l4）没展开。
    var run = explode(actor, new BomExplosionInput(versionL1, null,
        BigDecimal.ONE, null, null, 2, ""));
    assertThat(run.status()).isEqualTo("TRUNCATED");
    assertThat(run.truncated()).isTrue();
    var truncatedLine = run.lines().stream().filter(BomExplosionLine::truncated).findFirst().orElseThrow();
    assertThat(truncatedLine.note()).contains("这份清单不完整").contains("展开深度上限");
    assertThat(run.reachedDepth()).isEqualTo(2);
    // 静默截断是未完成：必须有行级标记。
    assertThat(run.lines()).anyMatch(BomExplosionLine::truncated);
  }

  // ================================================================ 按仓库过滤

  @Test
  void 仓库过滤被透传给库存服务并影响缺口() {
    Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
    UUID fg = skuOf(actor, unique("FG"));
    UUID raw = skuOf(actor, unique("RAW"));
    UUID version = publishedBom(actor, fg, "v1", List.of(
        new BomComponentInput(raw, new BigDecimal("10"), BigDecimal.ZERO, "", "")), from);
    stock(actor, raw, "4", "1");
    UUID warehouseId = UUID.randomUUID();

    explode(actor, new BomExplosionInput(version, null,
        new BigDecimal("2"), warehouseId, null, null, ""));
    // catalog 必须把仓库过滤**真的**发给 warehouse —— 否则页面上的仓库筛选是个不起作用的开关。
    assertThat(lastWarehouseId.get()).isEqualTo(warehouseId);
    // 并且必须透传调用者的 Authorization 头（租户上下文）。
    assertThat(lastBearer.get()).isEqualTo("Bearer tenant-a");
    var run = tx(actor, () -> explosions.run(tx(actor, () ->
        explosions.runs(new BomExplosionRunQuery(null, null, 0, 10)).items().get(0).id())));
    var line = lineOf(run, code(raw));
    assertThat(line.requiredQuantity()).isEqualByComparingTo("20.000000");
    assertThat(line.available()).isEqualByComparingTo("4");
    assertThat(line.inTransit()).isEqualByComparingTo("1");
    assertThat(line.gap()).isEqualByComparingTo("15.000000");
  }

  // ================================================================ as_of 冻结

  /**
   * 断言 <b>写回的值</b>而不是「没抛异常」。
   *
   * <p>这是 timestamptz 绑值必须做的断言：绑值路径一旦走了 {@code Timestamp.valueOf}
   * 就会按 JVM 默认时区二次偏移（东八区静默错 -8 小时），而页面只显示一个时间戳，
   * 看不出错在哪里 —— 只有把读回来的值与写进去的值逐毫秒对比才能钉死。
   */
  @Test
  void 展开基准时刻被冻结成写回的值() {
    Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
    UUID fg = skuOf(actor, unique("FG"));
    UUID raw = skuOf(actor, unique("RAW"));
    UUID version = publishedBom(actor, fg, "v1", List.of(
        new BomComponentInput(raw, BigDecimal.ONE, BigDecimal.ZERO, "", "")), from);
    // 用带 +08:00 偏移的写法提交，必须归一到同一时刻。
    String asOfLocal = OffsetDateTime.of(2026, 3, 1, 18, 30, 0, 0, ZoneOffset.ofHours(8))
        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    Instant expected = OffsetDateTime.parse(asOfLocal).toInstant();

    var run = explode(actor, new BomExplosionInput(version, null,
        BigDecimal.TEN, null, asOfLocal, null, ""));
    assertThat(Instant.parse(run.asOf())).isEqualTo(expected);
    // 直接查库再读一次：证明落库的就是这个时刻，而不是回显入参。
    var raw0 = tx(actor, () -> sql.fetchOne(
        "select as_of from bom_explosion_run where tenant_id=? and id=?",
        a, run.id()));
    assertThat(raw0.get("as_of", OffsetDateTime.class).toInstant()).isEqualTo(expected);
    // 业务时间与系统时间分开存：created_at 是落库时刻，不是 as_of。
    var both = tx(actor, () -> sql.fetchOne(
        "select as_of,created_at from bom_explosion_run where tenant_id=? and id=?", a, run.id()));
    assertThat(both.get("as_of", OffsetDateTime.class).toInstant())
        .isNotEqualTo(both.get("created_at", OffsetDateTime.class).toInstant());
  }

  @Test
  void 展开基准时刻决定子bom取哪一版() {
    Instant v1From = Instant.now().minus(60, ChronoUnit.DAYS);
    Instant v2From = Instant.now().minus(1, ChronoUnit.DAYS);
    UUID fg = skuOf(actor, unique("FG"));
    UUID sa = skuOf(actor, unique("SA"));
    UUID rawV1 = skuOf(actor, unique("RAW"));
    UUID rawV2 = skuOf(actor, unique("RAW"));
    UUID bomB = write(actor, () -> boms.saveBom(actor, null,
        new BomInput(unique("BOM"), sa, "", 0))).id();
    // v1 从 60 天前开始且一直有效；v2 从昨天开始 —— 区间不重叠。
    publishedVersion(actor, bomB, "v1", List.of(
        new BomComponentInput(rawV1, new BigDecimal("2"), BigDecimal.ZERO, "", "")), v1From, v2From);
    publishedVersion(actor, bomB, "v2", List.of(
        new BomComponentInput(rawV2, new BigDecimal("7"), BigDecimal.ZERO, "", "")), v2From, null);
    UUID version = publishedBom(actor, fg, "v1", List.of(
        new BomComponentInput(sa, BigDecimal.ONE, BigDecimal.ZERO, "", "")), v1From);

    var early = explode(actor, new BomExplosionInput(version, null,
        BigDecimal.ONE, null, Instant.now().minus(30, ChronoUnit.DAYS).toString(), null, ""));
    assertThat(lineOf(early, saCode(actor, sa)).subVersionNo()).isEqualTo("v1");
    var late = explode(actor, new BomExplosionInput(version, null,
        BigDecimal.ONE, null, null, null, ""));
    assertThat(lineOf(late, saCode(actor, sa)).subVersionNo()).isEqualTo("v2");
  }

  /** SKU 编码：测试里用它定位行（UUID 不便读）。 */
  String code(UUID skuId) {
    return tx(actor, () -> sql.fetchOne("select code from sku where tenant_id=? and id=?", a, skuId))
        .get("code", String.class);
  }

  String saCode(AccessService.Access who, UUID skuId) {
    return code(skuId);
  }

  // ================================================================ 校验

  @Test
  void 输入校验拒绝不合法的展开条件() {
    Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
    UUID fg = skuOf(actor, unique("FG"));
    UUID version = publishedBom(actor, fg, "v1", List.of(
        new BomComponentInput(skuOf(actor, unique("RAW")), BigDecimal.ONE, BigDecimal.ZERO, "", "")), from);
    // 两个都不给：无从判断展开哪一份物料清单。
    assertThatThrownBy(() -> explode(actor, new BomExplosionInput(null, null,
        BigDecimal.ONE, null, null, null, "")))
        .hasMessageContaining("请指定要展开的 BOM 版本");
    // 成品数量必须为正整数（规则 R7 的产量因子）。
    assertThatThrownBy(() -> explode(actor, new BomExplosionInput(version, null,
        BigDecimal.ZERO, null, null, null, "")))
        .hasMessageContaining("必须大于 0");
    assertThatThrownBy(() -> explode(actor, new BomExplosionInput(version, null,
        new BigDecimal("0.5"), null, null, null, "")))
        .hasMessageContaining("必须是整数");
    // 深度上限越界。
    assertThatThrownBy(() -> explode(actor, new BomExplosionInput(version, null,
        BigDecimal.ONE, null, null, 99, "")))
        .hasMessageContaining("展开深度上限最多");
    // 版本不属于本租户 → 404 而不是静默展开别人的 BOM。
    UUID foreignVersion = publishedBom(actorB, skuOf(actorB, unique("FG")), "v1", List.of(
        new BomComponentInput(skuOf(actorB, unique("RAW")), BigDecimal.ONE, BigDecimal.ZERO, "", "")), from);
    assertThatThrownBy(() -> explode(actor, new BomExplosionInput(foreignVersion, null,
        BigDecimal.ONE, null, null, null, "")))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ================================================================ 端点

  @Test
  void 端点接受展开请求并返回完整展开树() throws Exception {
    Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
    UUID fg = skuOf(actor, unique("FG"));
    UUID raw = skuOf(actor, unique("RAW"));
    UUID version = publishedBom(actor, fg, "v1", List.of(
        new BomComponentInput(raw, new BigDecimal("2"), BigDecimal.ZERO, "", "")), from);
    stock(actor, raw, "3", "0");

    http.perform(post("/catalog/bom-explosions")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"versionId\":\"" + version + "\",\"outputQuantity\":5,\"note\":\"试算\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.lines[0].requiredQuantity").value(10))
        .andExpect(jsonPath("$.lines[0].gap").value(7));

    var page = http.perform(get("/catalog/bom-explosions").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertThat(page).contains("COMPLETED");
  }

  // ================================================================ 租户隔离三断言

  /**
   * 租户隔离三断言，<b>并且覆盖深层组件行</b>。
   *
   * <p>只测顶层等于没测：展开是递归的，真实的越权面在第 2、3 层 ——
   * 「A 展开自己的树时混进 B 的深层组件」才是真正会出事的形态。
   */
  @Test
  void 租户隔离三断言覆盖深层组件行() throws Exception {
    Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
    UUID myL3 = skuOf(actor, unique("MY"));
    UUID myL2 = skuOf(actor, unique("MY"));
    UUID myL1 = skuOf(actor, unique("MY"));
    publishedBom(actor, myL2, "v1", List.of(
        new BomComponentInput(myL3, new BigDecimal("2"), BigDecimal.ZERO, "", "")), from);
    UUID myVersion = publishedBom(actor, myL1, "v1", List.of(
        new BomComponentInput(myL2, BigDecimal.ONE, BigDecimal.ZERO, "", "")), from);

    UUID theirL3 = skuOf(actorB, unique("THEIR"));
    UUID theirL2 = skuOf(actorB, unique("THEIR"));
    UUID theirL1 = skuOf(actorB, unique("THEIR"));
    publishedBom(actorB, theirL2, "v1", List.of(
        new BomComponentInput(theirL3, new BigDecimal("2"), BigDecimal.ZERO, "", "")), from);
    UUID theirVersion = publishedBom(actorB, theirL1, "v1", List.of(
        new BomComponentInput(theirL2, BigDecimal.ONE, BigDecimal.ZERO, "", "")), from);

    stock(actor, myL3, "1", "0");
    stock(actorB, theirL3, "1", "0");
    var myRun = explode(actor, new BomExplosionInput(myVersion, null, BigDecimal.TEN, null, null, null, ""));
    var theirRun = explode(actorB, new BomExplosionInput(theirVersion, null, BigDecimal.TEN, null, null, null, ""));
    // 两棵树都必须真的有第 2 层 —— 否则下面的「深层不串」就是空断言。
    assertThat(myRun.lines()).anyMatch(l -> l.depth() == 2);
    assertThat(theirRun.lines()).anyMatch(l -> l.depth() == 2);

    // 1) 两租户互不可见：读对方的展开记录直接 404。
    assertThatThrownBy(() -> tx(actor, () -> explosions.run(theirRun.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> explosions.run(myRun.id())))
        .isInstanceOf(ResponseStatusException.class);
    // 展开别人的版本：连版本本身都取不到（版本所在表同样 FORCE RLS）。
    assertThatThrownBy(() -> explode(actor, new BomExplosionInput(theirVersion, null,
        BigDecimal.ONE, null, null, null, "")))
        .isInstanceOf(ResponseStatusException.class);

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系。
    http.perform(get("/catalog/bom-explosions/" + myRun.id())
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // 3) 连接池归还后不串租户（单连接反复交替），**逐轮断言深层组件行也不串**。
    for (int i = 0; i < 5; i++) {
      var seenMine = tx(actor, () -> explosions.run(myRun.id())).lines();
      var seenTheirs = tx(actorB, () -> explosions.run(theirRun.id())).lines();
      // 深层组件行互不相交（第 2 层才有说服力）。
      var myDeep = seenMine.stream().filter(l -> l.depth() == 2).map(BomExplosionLine::skuId)
          .collect(java.util.stream.Collectors.toSet());
      var theirDeep = seenTheirs.stream().filter(l -> l.depth() == 2).map(BomExplosionLine::skuId)
          .collect(java.util.stream.Collectors.toSet());
      assertThat(myDeep).isNotEmpty();
      assertThat(theirDeep).isNotEmpty();
      assertThat(java.util.Collections.disjoint(myDeep, theirDeep)).isTrue();
      // 顶层同样不相交。
      assertThat(java.util.Collections.disjoint(
              seenMine.stream().map(BomExplosionLine::skuId).collect(java.util.stream.Collectors.toSet()),
              seenTheirs.stream().map(BomExplosionLine::skuId).collect(java.util.stream.Collectors.toSet())))
          .isTrue();
      // 运行记录列表互不可见。
      var myIds = tx(actor, () -> explosions.runs(new BomExplosionRunQuery(null, null, 0, 100)).items())
          .stream().map(BomExplosionRunRow::id).collect(java.util.stream.Collectors.toSet());
      var theirIds = tx(actorB, () -> explosions.runs(new BomExplosionRunQuery(null, null, 0, 100)).items())
          .stream().map(BomExplosionRunRow::id).collect(java.util.stream.Collectors.toSet());
      assertThat(java.util.Collections.disjoint(myIds, theirIds)).isTrue();
    }
  }

  @Test
  void 两张新表都强制rls且数量列精度正确() {
    var tables = tx(actor, () -> sql.fetch(
        "select relname,relrowsecurity,relforcerowsecurity from pg_class"
            + " where relname in ('bom_explosion_run','bom_explosion_line') and relkind='r'"));
    assertThat(tables).hasSize(2);
    assertThat(tables).allMatch(r -> Boolean.TRUE.equals(r.get("relforcerowsecurity", Boolean.class))
        && Boolean.TRUE.equals(r.get("relrowsecurity", Boolean.class)));
    // 需求量与缺口必须是 NUMERIC(19,6)，不是 double（禁浮点记账）。
    var columns = tx(actor, () -> sql.fetch(
        "select column_name,numeric_precision p,numeric_scale s from information_schema.columns"
            + " where table_name='bom_explosion_line'"
            + " and column_name in ('required_quantity','available','in_transit','gap')"));
    assertThat(columns).hasSize(4);
    assertThat(columns).allMatch(r ->
        r.get("p", Integer.class) == 19 && r.get("s", Integer.class) == 6);
    // 损耗率沿用 V25 的百分数口径 numeric(7,4)。
    var scrap = tx(actor, () -> sql.fetchOne(
        "select numeric_precision p,numeric_scale s from information_schema.columns"
            + " where table_name='bom_explosion_line' and column_name='scrap_rate'"));
    assertThat(scrap.get("p", Integer.class)).isEqualTo(7);
    assertThat(scrap.get("s", Integer.class)).isEqualTo(4);
  }

  @Test
  void 库存服务不可用时整次展开失败而不是把可用当成零() throws Exception {
    Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
    UUID fg = skuOf(actor, unique("FG"));
    UUID raw = skuOf(actor, unique("RAW"));
    UUID version = publishedBom(actor, fg, "v1", List.of(
        new BomComponentInput(raw, BigDecimal.ONE, BigDecimal.ZERO, "", "")), from);
    long before = tx(actor, () -> explosions.runs(new BomExplosionRunQuery(null, null, 0, 100)).total());
    warehouseDown.set(true);
    try {
      assertThatThrownBy(() -> explode(actor, new BomExplosionInput(version, null,
          BigDecimal.ONE, null, null, null, "")))
          .hasMessageContaining("库存服务");
      // 失败不留半份记录。
      assertThat(tx(actor, () -> explosions.runs(new BomExplosionRunQuery(null, null, 0, 100)).total()))
          .isEqualTo(before);
    } finally {
      warehouseDown.set(false);
    }
  }
}
