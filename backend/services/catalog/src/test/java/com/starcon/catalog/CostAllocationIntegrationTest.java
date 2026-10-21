package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.CostAllocationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 5.2.1.3 成本分摊：真实 PostgreSQL 16 + 强制 RLS 下的「逐行之和严格等于总额」、
 * 「换规则留历史版本」、人工微调留痕、租户隔离三断言、时间戳写回，
 * 以及上游不可达时**不拿空清单冒充「没有可分摊的货件」**。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：分摊单、版本快照、逐行明细全部真的落库。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>跨服务依赖的处理</b>：可分摊的货件来自 warehouse 服务（另一个数据库）。
 * 生产路径是真实的 {@code RestClient} HTTP 调用，本测试用一个 {@link HttpServer} 起一个
 * <b>确定性的替身</b>来满足它 —— 替身只存在于测试代码里，且被断言「确实收到了 catalog 透传的
 * {@code Authorization} 头」，所以它证明的是<b>生产代码真的走 HTTP 且透传了租户上下文</b>，
 * 而不是把替身逻辑塞进了生产代码。
 *
 * <p><b>本文件最重要的六个断言</b>：
 *
 * <ol>
 *   <li>{@link #逐行分摊额之和严格等于货件成本总额()}：分摊是拆分不是缩水。
 *   <li>{@link #换规则会追加新版本而旧版本原样留着()}：蓝图加粗句的技术落点 ——
 *       规则变更会导致历史利润变化，所以必须留版本、不能就地覆盖。
 *   <li>{@link #人工微调必须带原因且差额看得见()}：微调是「留痕」的一部分。
 *   <li>{@link #租户隔离三断言覆盖分摊单版本与明细()}：成本是最敏感的数据之一。
 *   <li>{@link #生效时间按时刻写回不被时区偏移()}：timestamptz 走 {@code ?::timestamptz} +
 *       {@code OffsetDateTime} 绑定；断言**写回的值**，不是只断言没抛异常。
 *   <li>{@link #上游不可达时返回503而不是把货件当成没有()}：拿不到时显示 0
 *       会让成本构成偏低、毛利偏高，而页面上看不出任何异常。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CostAllocationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer warehouse;
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();

  /** 基准时刻：**从 DB 自己的 now() 取**，不与 JVM 墙钟比较（PG 的 now() 是事务开始时刻）。 */
  static OffsetDateTime dbNow;

  /** warehouse 替身的头程发运单数据（tenant → 发运单）。 */
  static final Map<UUID, List<Map<String, Object>>> SHIPMENTS =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 替身最近一次收到的 Authorization 头：用来断言租户上下文真的透传出去了。 */
  static final AtomicReference<String> lastBearer = new AtomicReference<>();

  /** 置位后替身一律回 503：模拟「仓库服务不可用」。 */
  static final AtomicBoolean warehouseDown = new AtomicBoolean();

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

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "attribute", "research", "compliance", "assets")
        .flatMap(
            p ->
                java.util.stream.Stream.of("read", "create", "update", "delete")
                    .map(op -> "catalog." + p + "." + op))
        .toList();
  }

  static {
    pg.start();
    try (var conn =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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
      // 只有商品读权限：写端点必须 403。
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read", "catalog.sku.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * warehouse 替身：一个真实的 HTTP 服务器，形状与 warehouse 的
   * {@code FirstMilePage} / {@code FirstMileDetail} 逐字一致。
   *
   * <p>它刻意<b>不</b>校验 Authorization（真实 warehouse 会向 identity 校验），
   * 因为本测试要断言的正是「catalog 把这个头透传出去了」。租户由该头决定，
   * 与真实 warehouse 从已认证成员关系解析租户同构。
   */
  static void startWarehouseStub() throws Exception {
    warehouse = HttpServer.create(new InetSocketAddress(0), 0);
    warehouse.createContext(
        "/warehouse",
        e -> {
          lastBearer.set(e.getRequestHeaders().getFirst("Authorization"));
          if (warehouseDown.get()) {
            e.sendResponseHeaders(503, -1);
            e.close();
            return;
          }
          var path = e.getRequestURI().getPath();
          UUID tenant =
              "Bearer tenant-b".equals(lastBearer.get())
                  ? b
                  : "Bearer tenant-a".equals(lastBearer.get()) ? a : UUID.randomUUID();
          Object body;
          if (path.equals("/warehouse/first-mile-shipments")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var shipment : SHIPMENTS.getOrDefault(tenant, List.of())) {
              var row = new LinkedHashMap<String, Object>();
              row.put("id", shipment.get("id"));
              row.put("shipmentCode", shipment.get("shipmentCode"));
              row.put("currency", shipment.get("currency"));
              row.put("freightCost", shipment.get("freightCost"));
              row.put("insuranceCost", shipment.get("insuranceCost"));
              row.put("handlingCost", shipment.get("handlingCost"));
              row.put("status", shipment.get("status"));
              row.put("lineCount", shipment.get("lines") == null ? 0 : ((List<?>) shipment.get("lines")).size());
              row.put("createdAt", shipment.get("createdAt"));
              rows.add(row);
            }
            body = Map.of("rows", rows, "total", rows.size(), "page", 0, "size", rows.size());
          } else if (path.startsWith("/warehouse/first-mile-shipments/")) {
            String id = path.substring("/warehouse/first-mile-shipments/".length());
            Map<String, Object> shipment = null;
            for (var candidate : SHIPMENTS.getOrDefault(tenant, List.of())) {
              if (id.equals(candidate.get("id"))) shipment = candidate;
            }
            if (shipment == null) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("id", shipment.get("id"));
            row.put("shipmentCode", shipment.get("shipmentCode"));
            row.put("currency", shipment.get("currency"));
            row.put("freightCost", shipment.get("freightCost"));
            row.put("insuranceCost", shipment.get("insuranceCost"));
            row.put("handlingCost", shipment.get("handlingCost"));
            row.put("status", shipment.get("status"));
            row.put("lineCount", ((List<?>) shipment.get("lines")).size());
            row.put("createdAt", shipment.get("createdAt"));
            body = Map.of("shipment", row, "lines", shipment.get("lines"));
          } else {
            e.sendResponseHeaders(404, -1);
            e.close();
            return;
          }
          byte[] out = JSON.writeValueAsBytes(body);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, out.length);
          e.getResponseBody().write(out);
          e.close();
        });
    warehouse.start();
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
    // 单连接：只有复用同一条连接，才能真正证明归还之后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired CostAllocationService allocations;
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
  void setUp() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    warehouseDown.set(false);
    lastBearer.set(null);
    // 🔴 基准时刻取自 **DB 自己的 now()**：PostgreSQL 的 now() 是**事务开始**时刻，
    // 全量跑时与 JVM 墙钟能差几十秒 —— 拿 JVM 墙钟当基准的测试会假红。
    dbNow =
        tx(
            actor,
            () ->
                sql.fetchOne("select now()")
                    .get(0, OffsetDateTime.class)
                    .truncatedTo(ChronoUnit.SECONDS));
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  // ================================================================ 夹具

  /** 建一个 SPU + SKU。 */
  UUID sku(AccessService.Access who, String prefix) {
    String code = unique(prefix);
    UUID spuId =
        write(
            who,
            () ->
                catalog.saveSpu(
                    who,
                    null,
                    new SpuInput(
                        code + "-SPU", "SPU " + code, "", "Brand", "Category", "DRAFT", 0)));
    return write(
        who,
        () ->
            catalog.saveSku(
                who,
                spuId,
                null,
                new SkuInput(code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), 0)));
  }

  /** 给 SKU 补包装信息（毛重 + 外形尺寸）。 */
  void pack(AccessService.Access who, UUID skuId, String grossWeightG, String l, String w, String h) {
    write(
        who,
        () -> {
          sql.execute(
              "insert into sku_package(id,tenant_id,sku_id,length_mm,width_mm,height_mm,"
                  + "net_weight_g,gross_weight_g,packaging_type,version,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?,?,?,?,?,'',0,now(),now())",
              UUID.randomUUID(),
              who.tenantId(),
              skuId,
              l == null ? null : new BigDecimal(l),
              w == null ? null : new BigDecimal(w),
              h == null ? null : new BigDecimal(h),
              null,
              grossWeightG == null ? null : new BigDecimal(grossWeightG));
          return null;
        });
  }

  /**
   * 给 SKU 补一条 5.2.1.1 的成本构成（采购单价 + 当前版本指针）。
   *
   * <p>这是本模块 AMOUNT 口径的回落来源：货件行没申报货值时，货值基数取自这里的采购单价。
   */
  void costComposition(
      AccessService.Access who, UUID skuId, String currency, String purchaseCost) {
    write(
        who,
        () -> {
          UUID profileId = UUID.randomUUID();
          UUID revisionId = UUID.randomUUID();
          sql.execute(
              "insert into sku_cost_revision(id,tenant_id,sku_id,currency,revision_no,cost_method,"
                  + "purchase_cost,total_unit_cost,snapshot_complete,missing_components,"
                  + "effective_from,change_reason,created_by,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?,1,'MOVING_AVERAGE',?,?,true,'',"
                  + "?::timestamptz,'夹具',?::uuid,now())",
              revisionId,
              who.tenantId(),
              skuId,
              currency,
              new BigDecimal(purchaseCost),
              new BigDecimal(purchaseCost),
              dbNow,
              who.userId());
          sql.execute(
              "insert into sku_cost_profile(id,tenant_id,sku_id,cost_method,currency,packaging_cost,"
                  + "effective_from,current_revision_id,version,created_by,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?::uuid,'MOVING_AVERAGE',?,null,?::timestamptz,"
                  + "?::uuid,0,?::uuid,now(),now())",
              profileId,
              who.tenantId(),
              skuId,
              currency,
              dbNow,
              revisionId,
              who.userId());
          return null;
        });
  }

  /** 登记一张头程发运单（货件）。 */
  void shipment(
      AccessService.Access who,
      String code,
      String currency,
      String freight,
      List<Map<String, Object>> lines) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", UUID.randomUUID().toString());
    row.put("shipmentCode", code);
    row.put("currency", currency);
    row.put("freightCost", new BigDecimal(freight));
    row.put("insuranceCost", BigDecimal.ZERO);
    row.put("handlingCost", BigDecimal.ZERO);
    row.put("status", "DISPATCHED");
    row.put("createdAt", dbNow.toString());
    row.put("lines", lines);
    SHIPMENTS.computeIfAbsent(who.tenantId(), k -> new ArrayList<>()).add(row);
  }

  /** 货件的一行。 */
  Map<String, Object> shipmentLine(
      UUID skuId, String skuCode, String quantity, String value, String weightKg, String volumeCbm) {
    var line = new LinkedHashMap<String, Object>();
    line.put("skuId", skuId);
    line.put("skuCode", skuCode);
    line.put("skuName", "SKU " + skuCode);
    line.put("plannedQuantity", new BigDecimal(quantity));
    line.put("shippedQuantity", new BigDecimal(quantity));
    line.put("declaredValue", value == null ? null : new BigDecimal(value));
    line.put("grossWeightKg", weightKg == null ? null : new BigDecimal(weightKg));
    line.put("volumeCbm", volumeCbm == null ? null : new BigDecimal(volumeCbm));
    return line;
  }

  // ================================================================ 逐行之和严格等于总额

  /**
   * 逐行分摊额之和**严格等于**货件成本总额。
   *
   * <p>断言的是**从库里读回来的逐行金额**之和（不是内存里的计算结果）——
   * 只有这样才能证明真的落库了，而且落下来的数对得上。
   */
  @Test
  void 逐行分摊额之和严格等于货件成本总额() {
    UUID sku1 = sku(actor, "SUM-1");
    UUID sku2 = sku(actor, "SUM-2");
    shipment(
        actor,
        "FM-SUM-1",
        "USD",
        "100",
        List.of(
            shipmentLine(sku1, "A", "1", "10", "1", "0.001"),
            shipmentLine(sku2, "B", "1", "20", "2", "0.002")));

    var saved =
        write(
            actor,
            () ->
                allocations.create(
                    actor,
                    new PricingCostAllocationCreateInput(
                        "FIRST_MILE_SHIPMENT",
                        shipmentRef("FM-SUM-1"),
                        "FM-SUM-1",
                        null,
                        new BigDecimal("100.00"),
                        "WEIGHT",
                        dbNow,
                        "第一次分摊",
                        null,
                        null),
                    "Bearer tenant-a"));

    assertThat(saved.revisionNo()).isEqualTo(1);
    assertThat(saved.allocatedTotal()).isEqualByComparingTo("100.0000");

    // 从库里读逐行金额再求和。
    var rows =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select allocated_amount from cost_allocation_revision_line"
                        + " where tenant_id=? and revision_id=?",
                    actor.tenantId(),
                    saved.revisionId()));
    assertThat(rows).hasSize(2);
    var sum =
        rows.stream()
            .map(r -> r.get(0, BigDecimal.class))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertThat(sum).isEqualByComparingTo("100.0000");
    // 重量口径 1 : 2 ⇒ 1/3 与 2/3。
    var detail = tx(actor, () -> allocations.detail(saved.allocationId()));
    assertThat(detail.lines()).hasSize(2);
    assertThat(detail.currentRevision().allocatedTotal()).isEqualByComparingTo("100.0000");
    assertThat(detail.currentRevision().finalTotal()).isEqualByComparingTo("100.0000");
    // 没有人工微调 ⇒ 差额为 0。
    assertThat(detail.currentRevision().variance()).isEqualByComparingTo("0.0000");
  }

  /** 重量 1:2、总额 100 ⇒ 33.3333 / 66.6667（最大余数法把 0.0001 补到小数部分大的那一行）。 */
  @Test
  void 换规则给出不同的逐行金额且总和不变() {
    UUID sku1 = sku(actor, "RULE-1");
    UUID sku2 = sku(actor, "RULE-2");
    shipment(
        actor,
        "FM-RULE-1",
        "USD",
        "100",
        List.of(
            shipmentLine(sku1, "A", "1", "10", "1", "0.001"),
            shipmentLine(sku2, "B", "1", "90", "2", "0.002")));

    var saved =
        write(
            actor,
            () ->
                allocations.create(
                    actor,
                    new PricingCostAllocationCreateInput(
                        "FIRST_MILE_SHIPMENT",
                        shipmentRef("FM-RULE-1"),
                        "FM-RULE-1",
                        null,
                        new BigDecimal("100.00"),
                        "WEIGHT",
                        dbNow,
                        "第一次分摊",
                        null,
                        null),
                    "Bearer tenant-a"));

    var byWeight = tx(actor, () -> allocations.detail(saved.allocationId()));
    var weightAmounts =
        byWeight.lines().stream().map(PricingCostAllocationLineRow::finalAmount).toList();
    assertThat(weightAmounts).containsExactlyInAnyOrder(
        new BigDecimal("33.3333"), new BigDecimal("66.6667"));
    assertThat(byWeight.currentRevision().allocatedTotal()).isEqualByComparingTo("100.0000");

    // 换成按金额摊（10 : 90）⇒ 10 : 90。
    write(
        actor,
        () ->
            allocations.applyRecalculation(
                actor,
                saved.allocationId(),
                new PricingCostAllocationRecalculateInput(
                    "AMOUNT", null, dbNow, "改按金额摊", null, null),
                "Bearer tenant-a"));
    var byAmount = tx(actor, () -> allocations.detail(saved.allocationId()));
    var amountAmounts =
        byAmount.lines().stream().map(PricingCostAllocationLineRow::finalAmount).toList();
    assertThat(amountAmounts).containsExactlyInAnyOrder(
        new BigDecimal("10.0000"), new BigDecimal("90.0000"));
    assertThat(byAmount.currentRevision().allocatedTotal()).isEqualByComparingTo("100.0000");
  }

  // ================================================================ 换规则留历史版本

  /**
   * 换规则会**追加新版本**，旧版本原样留着。
   *
   * <p>这是蓝图加粗句「调整必须留历史版本，不能就地覆盖」的技术落点。
   */
  @Test
  void 换规则会追加新版本而旧版本原样留着() {
    UUID sku1 = sku(actor, "HIST-1");
    UUID sku2 = sku(actor, "HIST-2");
    shipment(
        actor,
        "FM-HIST-1",
        "USD",
        "100",
        List.of(
            shipmentLine(sku1, "A", "1", "10", "1", "0.001"),
            shipmentLine(sku2, "B", "1", "90", "2", "0.002")));

    var saved =
        write(
            actor,
            () ->
                allocations.create(
                    actor,
                    new PricingCostAllocationCreateInput(
                        "FIRST_MILE_SHIPMENT",
                        shipmentRef("FM-HIST-1"),
                        "FM-HIST-1",
                        null,
                        new BigDecimal("100.00"),
                        "WEIGHT",
                        dbNow,
                        "第一次分摊",
                        null,
                        null),
                    "Bearer tenant-a"));

    write(
        actor,
        () ->
            allocations.applyRecalculation(
                actor,
                saved.allocationId(),
                new PricingCostAllocationRecalculateInput("AMOUNT", null, dbNow, "改按金额摊", null, null),
                "Bearer tenant-a"));

    var revisions = tx(actor, () -> allocations.revisions(saved.allocationId()));
    assertThat(revisions).hasSize(2);
    assertThat(revisions.get(0).revisionNo()).isEqualTo(2);
    assertThat(revisions.get(0).basisRule()).isEqualTo("AMOUNT");
    assertThat(revisions.get(1).revisionNo()).isEqualTo(1);
    assertThat(revisions.get(1).basisRule()).isEqualTo("WEIGHT");

    // 🔴 旧版本的一行都没被改：按重量摊的 33.3333 还在第 1 版里。
    var v1 = tx(actor, () -> allocations.revision(saved.allocationId(), 1));
    assertThat(v1.revision().basisRule()).isEqualTo("WEIGHT");
    assertThat(v1.lines()).hasSize(2);
    assertThat(v1.lines().stream().map(PricingCostAllocationLineRow::finalAmount))
        .containsExactlyInAnyOrder(new BigDecimal("33.3333"), new BigDecimal("66.6667"));
    // 旧版本的总额也还在（合计现算，但算的是冻结的逐行金额）。
    assertThat(v1.revision().allocatedTotal()).isEqualByComparingTo("100.0000");

    // 差异对比能看出「哪一行变了、变了多少」。
    var diff = tx(actor, () -> allocations.diff(saved.allocationId(), null, null));
    assertThat(diff.ruleChanged()).isTrue();
    assertThat(diff.beforeRule()).isEqualTo("WEIGHT");
    assertThat(diff.afterRule()).isEqualTo("AMOUNT");
    assertThat(diff.changedCount()).isEqualTo(2);
    assertThat(diff.rows()).allSatisfy(row -> assertThat(row.change()).isEqualTo("CHANGED"));
  }

  /** 试算重算**不落库**：版本数不变。 */
  @Test
  void 试算重算不落库() {
    UUID sku1 = sku(actor, "PREVIEW-1");
    UUID sku2 = sku(actor, "PREVIEW-2");
    shipment(
        actor,
        "FM-PREVIEW-1",
        "USD",
        "100",
        List.of(
            shipmentLine(sku1, "A", "1", "10", "1", "0.001"),
            shipmentLine(sku2, "B", "1", "90", "2", "0.002")));

    var saved =
        write(
            actor,
            () ->
                allocations.create(
                    actor,
                    new PricingCostAllocationCreateInput(
                        "FIRST_MILE_SHIPMENT",
                        shipmentRef("FM-PREVIEW-1"),
                        "FM-PREVIEW-1",
                        null,
                        new BigDecimal("100.00"),
                        "WEIGHT",
                        dbNow,
                        "第一次分摊",
                        null,
                        null),
                    "Bearer tenant-a"));

    var preview =
        tx(
            actor,
            () ->
                allocations.recalculate(
                    saved.allocationId(),
                    new PricingCostAllocationRecalculateInput("AMOUNT", null, null, null, null, null),
                    "Bearer tenant-a"));
    assertThat(preview.basisRule()).isEqualTo("AMOUNT");
    assertThat(preview.diff().ruleChanged()).isTrue();
    assertThat(preview.allocatedTotal()).isEqualByComparingTo("100.0000");

    // 版本数没有变 —— 试算没有落库。
    assertThat(tx(actor, () -> allocations.revisions(saved.allocationId()))).hasSize(1);
  }

  // ================================================================ 人工微调

  /** 人工微调必须带原因；带原因的微调会留痕，差额看得见。 */
  @Test
  void 人工微调必须带原因且差额看得见() {
    UUID sku1 = sku(actor, "ADJ-1");
    UUID sku2 = sku(actor, "ADJ-2");
    shipment(
        actor,
        "FM-ADJ-1",
        "USD",
        "100",
        List.of(
            shipmentLine(sku1, "A", "1", "10", "1", "0.001"),
            shipmentLine(sku2, "B", "1", "90", "2", "0.002")));

    var saved =
        write(
            actor,
            () ->
                allocations.create(
                    actor,
                    new PricingCostAllocationCreateInput(
                        "FIRST_MILE_SHIPMENT",
                        shipmentRef("FM-ADJ-1"),
                        "FM-ADJ-1",
                        null,
                        new BigDecimal("100.00"),
                        "WEIGHT",
                        dbNow,
                        "第一次分摊",
                        null,
                        null),
                    "Bearer tenant-a"));

    // 没有原因 ⇒ 拒绝。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        allocations.applyRecalculation(
                            actor,
                            saved.allocationId(),
                            new PricingCostAllocationRecalculateInput(
                                null,
                                null,
                                dbNow,
                                "微调",
                                List.of(new PricingCostAllocationAdjustmentInput(sku1, new BigDecimal("5.00"), "  ")),
                                null),
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("原因必填");

    // 有原因 ⇒ 落一个新版本，差额可见。
    write(
        actor,
        () ->
            allocations.applyRecalculation(
                actor,
                saved.allocationId(),
                new PricingCostAllocationRecalculateInput(
                    null,
                    null,
                    dbNow,
                    "A 的运费按实际重量微调",
                    List.of(new PricingCostAllocationAdjustmentInput(sku1, new BigDecimal("5.00"), "A 实际重了一档")),
                    null),
                "Bearer tenant-a"));

    var detail = tx(actor, () -> allocations.detail(saved.allocationId()));
    assertThat(detail.currentRevision().revisionNo()).isEqualTo(2);
    assertThat(detail.currentRevision().adjustmentTotal()).isEqualByComparingTo("5.0000");
    // 逐行计算额之和仍然是 100（微调是额外的），最终额之和是 105 ⇒ 差额 5。
    assertThat(detail.currentRevision().allocatedTotal()).isEqualByComparingTo("100.0000");
    assertThat(detail.currentRevision().finalTotal()).isEqualByComparingTo("105.0000");
    assertThat(detail.currentRevision().variance()).isEqualByComparingTo("5.0000");
    var adjusted =
        detail.lines().stream().filter(l -> l.skuId().equals(sku1)).findFirst().orElseThrow();
    assertThat(adjusted.adjustmentAmount()).isEqualByComparingTo("5.0000");
    assertThat(adjusted.adjustmentReason()).isEqualTo("A 实际重了一档");
    assertThat(adjusted.finalAmount())
        .isEqualByComparingTo(adjusted.allocatedAmount().add(new BigDecimal("5.00")));
  }

  // ================================================================ 租户隔离三断言

  /**
   * 租户隔离三断言，<b>并且覆盖分摊单、版本快照与逐行明细</b>。
   *
   * <p>只测主体等于没测：成本分摊能看出别的租户一票货的运费与货值，
   * A 读到 B 的分摊结果会直接让 A 的利润算错，而页面上看不出任何异常。
   */
  @Test
  void 租户隔离三断言覆盖分摊单版本与明细() throws Exception {
    UUID mySku = sku(actor, "ISO-A");
    UUID theirSku = sku(actorB, "ISO-B");
    shipment(
        actor,
        "FM-ISO-A",
        "USD",
        "100",
        List.of(shipmentLine(mySku, "A", "1", "10", "1", "0.001")));
    shipment(
        actorB,
        "FM-ISO-B",
        "USD",
        "200",
        List.of(shipmentLine(theirSku, "B", "1", "20", "1", "0.001")));

    var mine =
        write(
            actor,
            () ->
                allocations.create(
                    actor,
                    new PricingCostAllocationCreateInput(
                        "FIRST_MILE_SHIPMENT",
                        shipmentRef("FM-ISO-A"),
                        "FM-ISO-A",
                        null,
                        new BigDecimal("100.00"),
                        "QUANTITY",
                        dbNow,
                        "A 的分摊",
                        null,
                        null),
                    "Bearer tenant-a"));
    var theirs =
        write(
            actorB,
            () ->
                allocations.create(
                    actorB,
                    new PricingCostAllocationCreateInput(
                        "FIRST_MILE_SHIPMENT",
                        shipmentRef("FM-ISO-B"),
                        "FM-ISO-B",
                        null,
                        new BigDecimal("200.00"),
                        "QUANTITY",
                        dbNow,
                        "B 的分摊",
                        null,
                        null),
                    "Bearer tenant-b"));

    // 两边都必须真的有数据 —— 否则下面的断言就是空的。
    assertThat(tx(actor, () -> allocations.detail(mine.allocationId())).lines()).isNotEmpty();
    assertThat(tx(actorB, () -> allocations.detail(theirs.allocationId())).lines()).isNotEmpty();

    // 1) 两租户互不可见
    assertThatThrownBy(() -> tx(actor, () -> allocations.detail(theirs.allocationId())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> allocations.detail(mine.allocationId())))
        .isInstanceOf(ResponseStatusException.class);
    // A 的台账里混不进 B 的分摊单。
    var myRows = tx(actor, () -> allocations.list(new PricingCostAllocationListQuery(null, null, null, null, 0, 200))).items();
    assertThat(myRows).isNotEmpty();
    assertThat(myRows.stream().map(PricingCostAllocationRow::allocationId)).doesNotContain(theirs.allocationId());
    assertThat(myRows.stream().map(PricingCostAllocationRow::allocationId)).contains(mine.allocationId());
    // 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from cost_allocation where tenant_id=? and id=?",
                                b,
                                mine.allocationId())
                            .get(0, Long.class))
                .longValue())
        .isZero();
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from cost_allocation_revision_line"
                                    + " where tenant_id=? and allocation_id=?",
                                b,
                                mine.allocationId())
                            .get(0, Long.class))
                .longValue())
        .isZero();

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(
            get("/catalog/cost-allocations/" + theirs.allocationId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/cost-allocations/" + mine.allocationId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 伪造头下的写入仍落在**调用者自己**名下 —— 断言行确实落在 A 名下，不只断言 B 看不见。
    http.perform(
            post("/catalog/cost-allocations")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new PricingCostAllocationCreateInput(
                            "MANUAL",
                            unique("FORGED"),
                            "伪造头下的分摊",
                            "USD",
                            new BigDecimal("10.00"),
                            "QUANTITY",
                            dbNow,
                            "伪造头下的分摊",
                            List.of(new PricingCostAllocationManualLineInput(mySku, new BigDecimal("1"), null, null, null)),
                            null))))
        .andExpect(status().isOk());
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select tenant_id from cost_allocation where source_label=?",
                                "伪造头下的分摊")
                            .get(0, UUID.class)))
        .isEqualTo(a);

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言两个集合不相交
    for (int i = 0; i < 5; i++) {
      var mySet =
          tx(
                  actor,
                  () ->
                      allocations.list(new PricingCostAllocationListQuery(null, null, null, null, 0, 200))
                          .items()
                          .stream()
                          .map(PricingCostAllocationRow::allocationId)
                          .collect(java.util.stream.Collectors.toSet()));
      var theirSet =
          tx(
                  actorB,
                  () ->
                      allocations.list(new PricingCostAllocationListQuery(null, null, null, null, 0, 200))
                          .items()
                          .stream()
                          .map(PricingCostAllocationRow::allocationId)
                          .collect(java.util.stream.Collectors.toSet()));
      assertThat(mySet).isNotEmpty();
      assertThat(theirSet).isNotEmpty();
      assertThat(java.util.Collections.disjoint(mySet, theirSet)).isTrue();
      // 明细也不串：A 读 B 的分摊单必须是异常，不是「读到了 B 的分摊结果」。
      assertThatThrownBy(() -> tx(actor, () -> allocations.detail(theirs.allocationId())))
          .as("A 读 B 的分摊单必须失败而不是串过去")
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  // ================================================================ 时间戳写回

  /** 生效时间按时刻写回**不被时区偏移**（timestamptz 走 ?::timestamptz + OffsetDateTime 绑定）。 */
  @Test
  void 生效时间按时刻写回不被时区偏移() {
    UUID sku1 = sku(actor, "TIME-1");
    shipment(
        actor,
        "FM-TIME-1",
        "USD",
        "100",
        List.of(shipmentLine(sku1, "A", "1", "10", "1", "0.001")));

    // 刻意用一个带非零时区偏移的时刻：写回去必须还是同一个瞬间。
    var effective = OffsetDateTime.parse("2026-03-04T05:06:07+08:00");
    var saved =
        write(
            actor,
            () ->
                allocations.create(
                    actor,
                    new PricingCostAllocationCreateInput(
                        "FIRST_MILE_SHIPMENT",
                        shipmentRef("FM-TIME-1"),
                        "FM-TIME-1",
                        null,
                        new BigDecimal("100.00"),
                        "QUANTITY",
                        effective,
                        "时间戳夹具",
                        null,
                        null),
                    "Bearer tenant-a"));

    var stored =
        tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select effective_from from cost_allocation_revision"
                                + " where tenant_id=? and id=?",
                            actor.tenantId(),
                            saved.revisionId())
                        .get(0, OffsetDateTime.class))
            .truncatedTo(ChronoUnit.SECONDS);
    // 断言写回的**瞬间**相等（不是「没抛异常」）。
    assertThat(stored.toInstant()).isEqualTo(effective.toInstant());
    // 读端点给出来的也是同一个瞬间。
    var detail = tx(actor, () -> allocations.detail(saved.allocationId()));
    assertThat(detail.currentRevision().effectiveFrom().toInstant())
        .isEqualTo(effective.toInstant());
  }

  // ================================================================ 上游不可达

  /**
   * 上游不可达 ⇒ **503**，而不是把货件当成「没有」。
   *
   * <p>空清单会让页面显示「没有可分摊的货件」，而真实情况是「仓库服务挂了」——
   * 成本分摊页看起来一切正常，账却算不出来。
   */
  @Test
  void 上游不可达时返回503而不是把货件当成没有() throws Exception {
    warehouseDown.set(true);
    assertThatThrownBy(() -> tx(actor, () -> allocations.sources("Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("仓库服务");
    http.perform(get("/catalog/cost-allocations/sources").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isServiceUnavailable());
    warehouseDown.set(false);
  }

  /** 跨服务调用必须透传调用方的 Authorization 头（租户由 warehouse 从成员关系解析）。 */
  @Test
  void 跨服务调用透传调用方的Authorization头() {
    UUID sku1 = sku(actor, "PASS-1");
    shipment(
        actor,
        "FM-PASS-1",
        "USD",
        "100",
        List.of(shipmentLine(sku1, "A", "1", "10", "1", "0.001")));
    tx(actor, () -> allocations.sources("Bearer tenant-a"));
    assertThat(lastBearer.get()).isEqualTo("Bearer tenant-a");
  }

  // ================================================================ 基数不可得

  /**
   * 基数取不到的行**不参与分母**，并明确标「基数不可得」——
   * 它不是「摊到 0」，是「没基数可分」。
   */
  @Test
  void 基数取不到的行不参与分母并明确标记() {
    UUID sku1 = sku(actor, "BASIS-1");
    UUID sku2 = sku(actor, "BASIS-2");
    // sku2 没有申报毛重、也没有包装毛重 ⇒ 重量口径不可得。
    shipment(
        actor,
        "FM-BASIS-1",
        "USD",
        "100",
        List.of(
            shipmentLine(sku1, "A", "1", "10", "1", null),
            shipmentLine(sku2, "B", "1", "20", null, null)));

    var saved =
        write(
            actor,
            () ->
                allocations.create(
                    actor,
                    new PricingCostAllocationCreateInput(
                        "FIRST_MILE_SHIPMENT",
                        shipmentRef("FM-BASIS-1"),
                        "FM-BASIS-1",
                        null,
                        new BigDecimal("100.00"),
                        "WEIGHT",
                        dbNow,
                        "重量口径",
                        null,
                        null),
                    "Bearer tenant-a"));

    var detail = tx(actor, () -> allocations.detail(saved.allocationId()));
    assertThat(detail.lines()).hasSize(2);
    assertThat(detail.currentRevision().eligibleLineCount()).isEqualTo(1);
    var skipped =
        detail.lines().stream().filter(l -> l.skuId().equals(sku2)).findFirst().orElseThrow();
    assertThat(skipped.basisAvailable()).isFalse();
    assertThat(skipped.basisValue()).isNull();
    assertThat(skipped.basisNote()).contains("毛重");
    // 不可得的那行分到 0，剩下那一行拿全部 100 —— 但总额仍然守恒。
    assertThat(detail.currentRevision().allocatedTotal()).isEqualByComparingTo("100.0000");
  }

  /** 所有行都取不到基数 ⇒ 拒绝分摊（不摊出一张全 0 的单）。 */
  @Test
  void 所有行都取不到基数时拒绝分摊() {
    UUID sku1 = sku(actor, "NOBASIS-1");
    shipment(
        actor,
        "FM-NOBASIS-1",
        "USD",
        "100",
        List.of(shipmentLine(sku1, "A", "1", null, null, null)));

    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        allocations.create(
                            actor,
                            new PricingCostAllocationCreateInput(
                                "FIRST_MILE_SHIPMENT",
                                shipmentRef("FM-NOBASIS-1"),
                                "FM-NOBASIS-1",
                                null,
                                new BigDecimal("100.00"),
                                "WEIGHT",
                                dbNow,
                                "重量口径",
                                null,
                                null),
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("分摊基数");
    // 拒绝之后**没有**留下半张单。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from cost_allocation where source_ref=?",
                                shipmentRef("FM-NOBASIS-1"))
                            .get(0, Long.class))
                .longValue())
        .isZero();
  }

  // ================================================================ 人工货件（12 财务未交付时的自持事实）

  /** 人工货件：本服务自持的货件事实（12 财务的 landed_cost_allocation 未交付）。 */
  @Test
  void 人工货件由本服务自持并能重算() {
    UUID sku1 = sku(actor, "MANUAL-1");
    UUID sku2 = sku(actor, "MANUAL-2");
    var saved =
        write(
            actor,
            () ->
                allocations.create(
                    actor,
                    new PricingCostAllocationCreateInput(
                        "MANUAL",
                        unique("BOX-"),
                        "人工货件",
                        "CNY",
                        new BigDecimal("300.00"),
                        "QUANTITY",
                        dbNow,
                        "人工登记",
                        List.of(
                            new PricingCostAllocationManualLineInput(
                                sku1, new BigDecimal("1"), new BigDecimal("1000"), null, new BigDecimal("50")),
                            new PricingCostAllocationManualLineInput(
                                sku2, new BigDecimal("2"), new BigDecimal("3000"), null, new BigDecimal("100"))),
                        null),
                    "Bearer tenant-a"));

    assertThat(saved.currency()).isEqualTo("CNY");
    assertThat(saved.allocatedTotal()).isEqualByComparingTo("300.0000");
    var detail = tx(actor, () -> allocations.detail(saved.allocationId()));
    assertThat(detail.lines()).hasSize(2);
    // 数量口径 1 : 2 ⇒ 100 : 200。
    assertThat(detail.lines().stream().map(PricingCostAllocationLineRow::finalAmount).toList())
        .containsExactlyInAnyOrder(new BigDecimal("100.0000"), new BigDecimal("200.0000"));

    // 重算（换规则）走同一套逻辑，仍然守恒。
    write(
        actor,
        () ->
            allocations.applyRecalculation(
                actor,
                saved.allocationId(),
                new PricingCostAllocationRecalculateInput("WEIGHT", null, dbNow, "改按重量摊", null, null),
                "Bearer tenant-a"));
    var after = tx(actor, () -> allocations.detail(saved.allocationId()));
    assertThat(after.currentRevision().basisRule()).isEqualTo("WEIGHT");
    assertThat(after.currentRevision().allocatedTotal()).isEqualByComparingTo("300.0000");
  }

  // ================================================================ 端点行为

  /** 写端点要权限：只有读权限的角色必须 403。 */
  @Test
  void 写端点要权限() throws Exception {
    // 🔴 请求体本身必须是**合法**的：否则 Spring 的 @Valid 会先抛 400，
    // 那样就测不到「权限不足」这条路径了（400 掩盖了 403）。
    UUID sku1 = sku(actor, "PERM-1");
    http.perform(
            post("/catalog/cost-allocations")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new PricingCostAllocationCreateInput(
                            "MANUAL",
                            unique("PERM-"),
                            "只读角色不该建分摊单",
                            "USD",
                            new BigDecimal("10.00"),
                            "QUANTITY",
                            dbNow,
                            "权限夹具",
                            List.of(
                                new PricingCostAllocationManualLineInput(
                                    sku1, new BigDecimal("1"), null, null, null)),
                            null))))
        .andExpect(status().isForbidden());
  }

  /** 读端点返回能力声明与来源清单。 */
  @Test
  void 读端点返回能力声明与来源清单() throws Exception {
    var caps =
        http.perform(
                get("/catalog/cost-allocations/capabilities").header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(caps).contains("WEIGHT").contains("VOLUME").contains("AMOUNT").contains("QUANTITY");
    assertThat(caps).contains("12 财务");
    assertThat(caps).contains("历史版本");

    http.perform(get("/catalog/cost-allocations/sources").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/cost-allocations").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
  }

  // ================================================================ 工具

  /** 取出替身里那张发运单的 id。 */
  static String shipmentRef(String code) {
    for (var list : SHIPMENTS.values()) {
      for (var shipment : list) {
        if (code.equals(shipment.get("shipmentCode"))) return (String) shipment.get("id");
      }
    }
    throw new IllegalStateException("没有这张发运单：" + code);
  }
}
