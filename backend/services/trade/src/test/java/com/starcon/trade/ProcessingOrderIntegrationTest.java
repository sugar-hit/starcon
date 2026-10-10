package com.starcon.trade;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
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
 * 6.4.1.1 加工单（trade 侧）。
 *
 * <p><b>本测试是 V97 迁移那些 DB 级不变量的独立复验</b>：不采信任何外部结论，
 * 逐条在真实 PostgreSQL 16 上跑一遍，并且走 HTTP 端点而不是直连数据库，
 * 以便同时验证「服务层 + DB 层」两层都没有把闸门漏掉。
 *
 * <p><b>catalog 是真起一个本地 stub 服务</b>（而不是在测试里 new 一个假对象）：
 * 组件领用量必须来自 catalog 3.5.2.1 的规则 R7，而 R7 的语义写在 catalog 那边，
 * 本车道**不重新实现**。stub 只负责按 catalog 的契约形状返回那一版 BOM 的
 * 「单位用量 / 损耗率 / 需求量」三元组 —— 数字本身是按 R7 公式真算出来的，
 * 不是写死的数组。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProcessingOrderIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer catalog;
  static final ObjectMapper json = new ObjectMapper().registerModule(
      new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("processing")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  /** catalog stub 的 BOM 版本登记表：版本 ID → 该版本的组件与状态。 */
  static final Map<String, Object> catalogBoms = new java.util.concurrent.ConcurrentHashMap<>();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-trade-processing-test-" + tenant + "-" + ordinal).getBytes());
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

  static void write(HttpServer server, com.sun.net.httpserver.HttpExchange e, int code, Object body)
      throws java.io.IOException {
    byte[] bytes = json.writeValueAsBytes(body);
    e.getResponseHeaders().add("Content-Type", "application/json");
    e.sendResponseHeaders(code, bytes.length);
    e.getResponseBody().write(bytes);
    e.close();
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
          actor(user, a, null, List.of(grant("ALL", List.of("trade.processing.read")))));
      identity.start();

      // ---- catalog stub：按 catalog 3.5.2.1 的契约形状返回 BOM 版本与 R7 需求量。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/bom-versions/",
          e -> {
            String path = e.getRequestURI().getPath();
            String rest = path.substring("/catalog/bom-versions/".length());
            boolean requirement = rest.endsWith("/requirement");
            String versionId = requirement ? rest.substring(0, rest.length() - "/requirement".length()) : rest;
            Object registered = catalogBoms.get(versionId);
            if (registered == null) {
              write(catalog, e, 404, Map.of("message", "BOM 版本不存在"));
              return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) registered;
            if (!requirement) {
              write(
                  catalog,
                  e,
                  200,
                  Map.of(
                      "id", versionId,
                      "bomId", meta.get("bomId"),
                      "bomCode", meta.get("bomCode"),
                      "versionNo", meta.get("versionNo"),
                      "outputSkuId", meta.get("outputSkuId"),
                      "outputSkuCode", meta.get("outputSkuCode"),
                      "status", meta.get("status")));
              return;
            }
            // R7：需求量 = 单位用量 × 产量 × (1 + 损耗率/100)，6 位小数 HALF_UP。
            // 这里的算法是**真算**的，不是写死的数组：改产量会得到不同的需求量。
            String query = e.getRequestURI().getQuery();
            BigDecimal outputQuantity = new BigDecimal(query.replace("outputQuantity=", ""));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> components = (List<Map<String, Object>>) meta.get("components");
            List<Map<String, Object>> lines = new java.util.ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            for (Map<String, Object> component : components) {
              BigDecimal unit = new BigDecimal(String.valueOf(component.get("unitQuantity")));
              BigDecimal scrap = new BigDecimal(String.valueOf(component.get("scrapRate")));
              BigDecimal required =
                  unit.multiply(outputQuantity)
                      .multiply(BigDecimal.ONE.add(scrap.divide(new BigDecimal("100"), 8,
                          java.math.RoundingMode.HALF_UP)))
                      .setScale(6, java.math.RoundingMode.HALF_UP);
              total = total.add(required);
              Map<String, Object> line = new LinkedHashMap<>();
              line.put("skuId", component.get("skuId"));
              line.put("skuCode", component.get("skuCode"));
              line.put("skuName", component.get("skuCode"));
              line.put("unitQuantity", unit);
              line.put("scrapRate", scrap);
              line.put("requiredQuantity", required);
              lines.add(line);
            }
            write(
                catalog,
                e,
                200,
                Map.of(
                    "bomId", meta.get("bomId"),
                    "versionId", versionId,
                    "versionNo", meta.get("versionNo"),
                    "outputSkuId", meta.get("outputSkuId"),
                    "outputSkuCode", meta.get("outputSkuCode"),
                    "outputQuantity", outputQuantity,
                    "lines", lines,
                    "totalRequiredQuantity", total.setScale(6, java.math.RoundingMode.HALF_UP),
                    "singleLevel", true,
                    "note", "catalog stub：按规则 R7 计算"));
          });
      catalog.start();
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
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    catalog.stop(0);
    pg.stop();
  }

  @Autowired ProcessingOrderService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actorA;
  AccessService.Access actorB;

  // BOM 与组件的逻辑 ID（catalog 侧主键，本服务只存逻辑 ID，跨服务不建外键）
  static final UUID bomId = UUID.randomUUID(), bomVersionPublished = UUID.randomUUID(),
      bomVersionDraft = UUID.randomUUID(), skuFabric = UUID.randomUUID(),
      skuZipper = UUID.randomUUID(), skuOut = UUID.randomUUID();

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    catalogBoms.clear();
    // 一版已发布 BOM：0.5 米面料（损耗 5%）+ 2 颗拉链（无损耗）
    registerBom(
        bomVersionPublished,
        "PUBLISHED",
        List.of(
            Map.of("skuId", skuFabric, "skuCode", "FABRIC", "unitQuantity", "0.5", "scrapRate", "5"),
            Map.of("skuId", skuZipper, "skuCode", "ZIPPER", "unitQuantity", "2", "scrapRate", "0")));
    // 一版草稿 BOM：组件可改，不能用于加工单
    registerBom(
        bomVersionDraft,
        "DRAFT",
        List.of(Map.of("skuId", skuFabric, "skuCode", "FABRIC", "unitQuantity", "0.5", "scrapRate", "5")));

    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate processing_order_event, processing_order_component, processing_inbound_request,"
              + " processing_order, processing_factory restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      as(
          tenant,
          () -> {
            sql.execute(
                "insert into processing_factory(id,tenant_id,code,name,factory_kind,status,note)"
                    + " values(?,?,?,?,?,'ACTIVE',?)",
                idFor(tenant, 1), tenant, "F-01", "上海某加工厂", "OUTSOURCED", "常年合作");
            sql.execute(
                "insert into processing_factory(id,tenant_id,code,name,factory_kind,status,note)"
                    + " values(?,?,?,?,?,'ACTIVE',?)",
                idFor(tenant, 2), tenant, "F-02", "自有工厂", "IN_HOUSE", "");
            return null;
          });
    }
  }

  void registerBom(UUID versionId, String status, List<Map<String, Object>> components) {
    catalogBoms.put(
        versionId.toString(),
        Map.of(
            "bomId", bomId,
            "bomCode", "BOM-001",
            "versionNo", "v" + (status.equals("PUBLISHED") ? "1" : "9"),
            "outputSkuId", skuOut,
            "outputSkuCode", "OUT-1",
            "status", status,
            "components", components));
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.processing.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.processing.update", work);
  }

  <T> T as(UUID tenant, java.util.function.Supplier<T> work) {
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  ProcessingModels.ProcessingOrderSaved createOrder(UUID tenant, String orderNo, UUID bomVersion) {
    return as(
        tenant,
        () ->
            service.createOrder(
                new ProcessingModels.ProcessingOrderInput(
                    orderNo,
                    idFor(tenant, 1),
                    bomVersion,
                    new BigDecimal("10"),
                    LocalDate.now().plusDays(30),
                    "春季外套加工"),
                user,
                "Bearer tenant-a"));
  }

  // ================================================================ BOM 口径（R6/R7 不重新定义）

  @Test
  @DisplayName("组件领用量按 catalog 规则 R7 冻结：0.5 米 × 10 件 × (1+5%) = 5.25")
  void componentQuantityFollowsCatalogR7() {
    var order = createOrder(a, "PO-R7-001", bomVersionPublished);
    var detail = asA(() -> service.order(order.id()));

    assertThat(detail.components()).hasSize(2);
    var fabric =
        detail.components().stream()
            .filter(c -> c.skuCode().equals("FABRIC"))
            .findFirst()
            .orElseThrow();
    // 单位用量与损耗率原样冻结（不重新定义 R6）
    assertThat(fabric.unitQuantity()).isEqualByComparingTo("0.5");
    assertThat(fabric.scrapRate()).isEqualByComparingTo("5");
    // 需求量 = 0.5 × 10 × (1 + 5/100) = 5.25
    assertThat(fabric.requiredQuantity()).isEqualByComparingTo("5.250000");
    // 已领为 0，欠的就是整个需求量 —— 进度跟踪的真身
    assertThat(fabric.issuedQuantity()).isEqualByComparingTo("0");
    assertThat(fabric.outstandingQuantity()).isEqualByComparingTo("5.250000");

    var zipper =
        detail.components().stream()
            .filter(c -> c.skuCode().equals("ZIPPER"))
            .findFirst()
            .orElseThrow();
    // 无损耗：2 × 10 = 20
    assertThat(zipper.requiredQuantity()).isEqualByComparingTo("20.000000");
    assertThat(detail.order().totalRequiredQuantity()).isEqualByComparingTo("25.250000");
  }

  @Test
  @DisplayName("草稿 BOM 版本不能用于加工单（已发布版本的组件才是稳定的）")
  void draftBomVersionIsRejected() {
    assertThatThrownBy(() -> createOrder(a, "PO-DRAFT-001", bomVersionDraft))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能用于加工单");
  }

  @Test
  @DisplayName("catalog 不可达时显式失败，不返回空组件冒充「这个 BOM 没有组件」")
  void unknownBomVersionFailsLoudly() {
    // catalog 查不到这一版 → 显式 404，**不**落任何加工单与组件行。
    // 若这里返回空组件列表冒充「这个 BOM 没有组件」，就会凭空生成一张零组件的单子。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.createOrder(
                            new ProcessingModels.ProcessingOrderInput(
                                "PO-404-001",
                                idFor(a, 1),
                                UUID.randomUUID(),
                                new BigDecimal("1"),
                                LocalDate.now().plusDays(10),
                                null),
                            user,
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("BOM 版本不存在");
    Long orders =
        asA(
            () ->
                sql.fetchOne("select count(*) from processing_order where order_no = 'PO-404-001'")
                    .get(0, Long.class));
    assertThat(orders).isZero();
  }

  // ================================================================ 状态流转与进度跟踪

  @Test
  @DisplayName("生命周期：草稿 → 已下达（生成入库需求通知）→ 加工中 → 已完工 → 已入库")
  void fullLifecycle() {
    var order = createOrder(a, "PO-LIFE-001", bomVersionPublished);
    // 草稿态还没有入库需求通知（随下达生成）
    assertThat(asA(() -> service.order(order.id())).inboundRequest()).isNull();

    var released =
        asA(
            () ->
                service.advanceStatus(
                    order.id(),
                    new ProcessingModels.ProcessingStatusInput("RELEASED", null, null, null,
                        order.version()),
                    user));
    assertThat(released.status()).isEqualTo("RELEASED");
    assertThat(released.releasedAt()).isNotNull();

    // 入库需求通知（蓝图输出句的硬输出）真的落库了，且数量 = 约定完工数量
    var detail = asA(() -> service.order(order.id()));
    assertThat(detail.inboundRequest()).isNotNull();
    assertThat(detail.inboundRequest().status()).isEqualTo("PENDING");
    assertThat(detail.inboundRequest().requestedQuantity()).isEqualByComparingTo("10.000000");
    assertThat(detail.inboundRequest().receivedQuantity()).isEqualByComparingTo("0");
    assertThat(detail.events()).hasSize(2);   // CREATED + RELEASED

    var inProcess =
        asA(
            () ->
                service.advanceStatus(
                    order.id(),
                    new ProcessingModels.ProcessingStatusInput("IN_PROCESS", null, null, null,
                        released.version()),
                    user));
    assertThat(inProcess.status()).isEqualTo("IN_PROCESS");

    var completed =
        asA(
            () ->
                service.advanceStatus(
                    order.id(),
                    new ProcessingModels.ProcessingStatusInput("COMPLETED",
                        new BigDecimal("8"), null, "厂里报了 8 件成品", inProcess.version()),
                    user));
    assertThat(completed.status()).isEqualTo("COMPLETED");
    assertThat(completed.completedQuantity()).isEqualByComparingTo("8.000000");

    // 先收 8 件（待入库 10）：**未满**，加工单停在已完工，已入库是终态不能先进来 ——
    // 否则剩下的 2 件将永远没有地方登记。
    var partial =
        asA(
            () ->
                service.advanceStatus(
                    order.id(),
                    new ProcessingModels.ProcessingStatusInput("RECEIVED",
                        new BigDecimal("8"), "TXN-0001", "先到 8 件", completed.version()),
                    user));
    assertThat(partial.status()).isEqualTo("COMPLETED");
    assertThat(asA(() -> service.order(order.id())).inboundRequest().status()).isEqualTo("PARTIAL");

    // 再收 2 件凑满：这时才转已入库（终态）
    var received =
        asA(
            () ->
                service.advanceStatus(
                    order.id(),
                    new ProcessingModels.ProcessingStatusInput("RECEIVED",
                        new BigDecimal("2"), "TXN-0002", "剩下 2 件到齐", partial.version()),
                    user));
    assertThat(received.status()).isEqualTo("RECEIVED");
    assertThat(received.receivedAt()).isNotNull();

    var finalDetail = asA(() -> service.order(order.id()));
    assertThat(finalDetail.inboundRequest().status()).isEqualTo("RECEIVED");
    assertThat(finalDetail.inboundRequest().receivedQuantity()).isEqualByComparingTo("10.000000");
    assertThat(finalDetail.inboundRequest().inventoryTransactionRef()).isEqualTo("TXN-0002");
    // 完工数量与实收数量分开记：报 8、实收 10，但它们是两列两处（报完工 ≠ 已进仓）
    assertThat(finalDetail.order().completedQuantity()).isEqualByComparingTo("8.000000");
  }

  @Test
  @DisplayName("部分入库：实收小于待入库量 → PARTIAL，且不能超过待入库量")
  void partialInbound() {
    var order = createOrder(a, "PO-PART-001", bomVersionPublished);
    var released = release(order);
    var inProcess = toInProcess(order, released);
    var completed =
        asA(
            () ->
                service.advanceStatus(
                    order.id(),
                    new ProcessingModels.ProcessingStatusInput("COMPLETED",
                        new BigDecimal("10"), null, null, inProcess.version()),
                    user));
    var received =
        asA(
            () ->
                service.advanceStatus(
                    order.id(),
                    new ProcessingModels.ProcessingStatusInput("RECEIVED",
                        new BigDecimal("6"), "TXN-0002", "先到 6 件", completed.version()),
                    user));
    var detail = asA(() -> service.order(received.id()));
    assertThat(detail.inboundRequest().status()).isEqualTo("PARTIAL");
    assertThat(detail.inboundRequest().outstandingQuantity()).isEqualByComparingTo("4.000000");
    // 加工单**不**转已入库（终态）：剩下的 4 件还要能继续登记
    assertThat(detail.order().status()).isEqualTo("COMPLETED");
    assertThat(received.status()).isEqualTo("COMPLETED");
  }

  @Test
  @DisplayName("超收（实收超过待入库量）被服务层拒绝")
  void overReceiptIsRejected() {
    var order = createOrder(a, "PO-OVER-001", bomVersionPublished);
    var released = release(order);
    var inProcess = toInProcess(order, released);
    var completed =
        asA(
            () ->
                service.advanceStatus(
                    order.id(),
                    new ProcessingModels.ProcessingStatusInput("COMPLETED",
                        new BigDecimal("10"), null, null, inProcess.version()),
                    user));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            order.id(),
                            new ProcessingModels.ProcessingStatusInput("RECEIVED",
                                new BigDecimal("11"), "TXN-0003", null, completed.version()),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("超过了待入库量");
  }

  @Test
  @DisplayName("完工报 0 个被拒（报 0 个的「完工」不是完工）")
  void completionWithZeroQuantityIsRejected() {
    var order = createOrder(a, "PO-ZERO-001", bomVersionPublished);
    var released = release(order);
    var inProcess = toInProcess(order, released);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            order.id(),
                            new ProcessingModels.ProcessingStatusInput("COMPLETED",
                                null, null, null, inProcess.version()),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("完工必须报数量");
  }

  @Test
  @DisplayName("完工超过约定数量被拒（超产会让良率失去意义）")
  void completionAbovePlannedIsRejected() {
    var order = createOrder(a, "PO-OVERPLAN-001", bomVersionPublished);
    var released = release(order);
    var inProcess = toInProcess(order, released);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            order.id(),
                            new ProcessingModels.ProcessingStatusInput("COMPLETED",
                                new BigDecimal("11"), null, null, inProcess.version()),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("超过了约定的");
  }

  @Test
  @DisplayName("不能跳步：草稿直接到已完工被拒")
  void cannotSkipFromDraftToCompleted() {
    var order = createOrder(a, "PO-SKIP-001", bomVersionPublished);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            order.id(),
                            new ProcessingModels.ProcessingStatusInput("COMPLETED",
                                new BigDecimal("1"), null, null, order.version()),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能推进到");
  }

  @Test
  @DisplayName("终态不可离开：已入库不能再改状态")
  void receivedIsTerminal() {
    var order = createOrder(a, "PO-TERM-001", bomVersionPublished);
    var released = release(order);
    var inProcess = toInProcess(order, released);
    var completed =
        asA(
            () ->
                service.advanceStatus(
                    order.id(),
                    new ProcessingModels.ProcessingStatusInput("COMPLETED",
                        new BigDecimal("1"), null, null, inProcess.version()),
                    user));
    // 收满待入库量（10 件）才转终态
    var received =
        asA(
            () ->
                service.advanceStatus(
                    order.id(),
                    new ProcessingModels.ProcessingStatusInput("RECEIVED",
                        new BigDecimal("10"), "TXN", null, completed.version()),
                    user));
    assertThat(received.status()).isEqualTo("RECEIVED");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            order.id(),
                            new ProcessingModels.ProcessingStatusInput("IN_PROCESS", null, null,
                                null, received.version()),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能推进到");
  }

  @Test
  @DisplayName("作废必须给原因；作废后入库需求通知同步作废")
  void cancelRequiresReasonAndCancelsInboundRequest() {
    var order = createOrder(a, "PO-CANCEL-001", bomVersionPublished);
    var released = release(order);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.advanceStatus(
                            order.id(),
                            new ProcessingModels.ProcessingStatusInput("CANCELLED", null, null,
                                null, released.version()),
                            user)))
        .isInstanceOf(ResponseStatusException.class);
    var cancelled =
        asA(
            () ->
                service.advanceStatus(
                    order.id(),
                    new ProcessingModels.ProcessingStatusInput("CANCELLED", null, "客户取消订单",
                        null, released.version()),
                    user));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(cancelled.cancelReason()).isEqualTo("客户取消订单");
    assertThat(asA(() -> service.order(order.id())).inboundRequest().status()).isEqualTo("CANCELLED");
  }

  // ================================================================ 组件领用进度

  @Test
  @DisplayName("领用登记推进进度：已领累计与欠料都随之变化")
  void issueProgressTracksOutstanding() {
    var order = createOrder(a, "PO-ISSUE-001", bomVersionPublished);
    release(order);
    var detail0 = asA(() -> service.order(order.id()));
    var fabric =
        detail0.components().stream()
            .filter(c -> c.skuCode().equals("FABRIC"))
            .findFirst()
            .orElseThrow();

    var after =
        asA(
            () ->
                service.issueComponent(
                    order.id(),
                    new ProcessingModels.IssueInput(fabric.id(), new BigDecimal("2"), "先发两米",
                        detail0.order().version()),
                    user));
    assertThat(after.componentCount()).isEqualTo(2);
    assertThat(after.issuedComponentCount()).isEqualTo(0);   // 5.25 未领满
    assertThat(after.totalIssuedQuantity()).isEqualByComparingTo("2.000000");
    assertThat(after.totalRequiredQuantity()).isEqualByComparingTo("25.250000");

    // 领满后计数变 1（真正领完的组件数）
    var fabric2 =
        asA(() -> service.order(order.id())).components().stream()
            .filter(c -> c.skuCode().equals("FABRIC"))
            .findFirst()
            .orElseThrow();
    var full =
        asA(
            () ->
                service.issueComponent(
                    order.id(),
                    new ProcessingModels.IssueInput(fabric2.id(), new BigDecimal("3.25"), "补齐",
                        after.version()),
                    user));
    assertThat(full.issuedComponentCount()).isEqualTo(1);
    assertThat(full.totalIssuedQuantity()).isEqualByComparingTo("5.250000");
  }

  @Test
  @DisplayName("超领被服务层与 DB 双重拒绝")
  void overIssueIsRejected() {
    var order = createOrder(a, "PO-OVERISSUE-001", bomVersionPublished);
    release(order);
    var detail = asA(() -> service.order(order.id()));
    var fabric =
        detail.components().stream()
            .filter(c -> c.skuCode().equals("FABRIC"))
            .findFirst()
            .orElseThrow();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.issueComponent(
                            order.id(),
                            new ProcessingModels.IssueInput(fabric.id(), new BigDecimal("99"),
                                null, detail.order().version()),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("超过需求量");
  }

  @Test
  @DisplayName("绕过服务层直连 SQL 超领被 DB CHECK 挡下")
  void overIssueBlockedByDatabase() {
    var order = createOrder(a, "PO-DBSHOVER-001", bomVersionPublished);
    release(order);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update processing_order_component set issued_quantity=99"
                                + " where order_id = ?",
                            order.id())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("绕过服务层直连 SQL 冻结列（单位用量）被 DB 触发器挡下")
  void frozenColumnsBlockedByDatabase() {
    var order = createOrder(a, "PO-FROZEN-001", bomVersionPublished);
    release(order);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update processing_order_component set required_quantity = required_quantity * 2"
                                + " where order_id = ?",
                            order.id())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("绕过服务层直连 SQL 把已下达单据改回草稿被 DB 触发器挡下")
  void cannotRevertToDraftViaSql() {
    var order = createOrder(a, "PO-NODRAFT-001", bomVersionPublished);
    release(order);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update processing_order set status='DRAFT' where id = ?", order.id())))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("绕过服务层直连 SQL 改事件流被 REVOKE + 触发器挡下")
  void eventStreamIsAppendOnly() {
    var order = createOrder(a, "PO-EVENT-001", bomVersionPublished);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update processing_order_event set note = '改写留痕'"
                                + " where order_id = ?",
                            order.id())))
        .isInstanceOf(Throwable.class);
  }

  // ================================================================ 加工厂

  @Test
  @DisplayName("加工厂：登记、分页、停用后不能再下达新单")
  void factoryLifecycle() {
    ProcessingModels.FactorySaved factory =
        asA(
            () ->
                service.saveFactory(
                    new ProcessingModels.FactoryInput("F-SZ", "苏州加工厂", "OUTSOURCED", null, "新签", 0L)));
    assertThat(factory.code()).isEqualTo("F-SZ");
    assertThat(factory.factoryKind()).isEqualTo("OUTSOURCED");

    // 夹具两家 + 本用例新建一家 = 3 家
    var page = asA(() -> service.pageFactories(new ProcessingModels.FactoryQuery(null, null, null, 0,
        50)));
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.activeCount()).isEqualTo(3);
    assertThat(page.inHouseCount()).isEqualTo(1);

    // 真实模糊检索命中名称
    var found =
        asA(() -> service.pageFactories(new ProcessingModels.FactoryQuery(null, null, "上海", 0, 50)));
    assertThat(found.total()).isEqualTo(1);   // 只有夹具那家叫「上海某加工厂」

    // 停用后不能再下达新单 —— 用**被停用的那家**建单（不是仍在启用的 F-01）
    asA(
        () ->
            service.updateFactory(
                factory.id(),
                new ProcessingModels.FactoryInput("F-SZ", "苏州加工厂", "OUTSOURCED", "SUSPENDED",
                    "停用", factory.version())));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.createOrder(
                            new ProcessingModels.ProcessingOrderInput(
                                "PO-SUSPENDED-001",
                                factory.id(),
                                bomVersionPublished,
                                new BigDecimal("1"),
                                LocalDate.now().plusDays(10),
                                null),
                            user,
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已停用");
  }

  // ================================================================ 列表与能力声明

  @Test
  @DisplayName("列表是真分页 + 全量汇总（汇总不受分页影响）+ 真实模糊检索 + 逾期筛选")
  void listQuery() {
    createOrder(a, "PO-LIST-001", bomVersionPublished);
    createOrder(a, "PO-LIST-002", bomVersionPublished);
    createOrder(b, "PO-LIST-003", bomVersionPublished);

    var pageA = asA(() -> service.pageOrders(new ProcessingModels.ProcessingOrderQuery(null, null,
        null, null, null, 0, 1)));
    assertThat(pageA.total()).isEqualTo(2);
    assertThat(pageA.items()).hasSize(1);
    assertThat(pageA.draftCount()).isEqualTo(2);

    // B 租户看不到 A 的单
    var pageB = asB(() -> service.pageOrders(new ProcessingModels.ProcessingOrderQuery(null, null,
        null, null, null, 0, 50)));
    assertThat(pageB.total()).isEqualTo(1);

    // 模糊检索命中单号
    var hit = asA(() -> service.pageOrders(new ProcessingModels.ProcessingOrderQuery(null, null, null,
        "LIST-002", null, 0, 50)));
    assertThat(hit.total()).isEqualTo(1);

    // 按状态筛选
    var released = release(createOrder(a, "PO-LIST-004", bomVersionPublished));
    var byStatus = asA(() -> service.pageOrders(new ProcessingModels.ProcessingOrderQuery(null, null,
        "RELEASED", null, null, 0, 50)));
    assertThat(byStatus.releasedCount()).isEqualTo(1);
    assertThat(released.status()).isEqualTo("RELEASED");
  }

  @Test
  @DisplayName("逾期筛选：交期已过且未入库/未作废")
  void overdueFilter() {
    // 交期必须在今天之后才能建单，所以逾期单直接改 DB 把交期推到过去
    var order = createOrder(a, "PO-OVERDUE-001", bomVersionPublished);
    asA(() -> sql.execute(
        "update processing_order set due_on = current_date - 1 where id = ?", order.id()));
    var overdue = asA(() -> service.pageOrders(new ProcessingModels.ProcessingOrderQuery(null, null,
        null, null, true, 0, 50)));
    assertThat(overdue.total()).isEqualTo(1);
    assertThat(overdue.overdueCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("能力声明如实报出库存动作归属与未交付下游")
  void capabilities() {
    var caps = asA(() -> service.capabilities());
    assertThat(caps.bomVersionBindingAvailable()).isTrue();
    assertThat(caps.warehouseStockMovementOwnedByWarehouse()).isTrue();
    assertThat(caps.upstreamNote()).contains("R7").contains("百分数");
    assertThat(caps.downstreamGaps()).anySatisfy(gap -> {
      assertThat(gap.moduleId()).isEqualTo("8.4.1.1");
      assertThat(gap.available()).isFalse();
      assertThat(gap.ownerModule()).contains("8");
    });
    // 7 条合法迁移 + 1 条终态说明
    assertThat(caps.statusTransitions()).hasSize(8);
    assertThat(caps.statusTransitions().get(7)).contains("终态");
  }

  // ================================================================ 多租户三断言（AGENTS.md 硬要求）

  @Test
  @DisplayName("多租户断言 1：A 看不到 B 的加工单（RLS 下查不到 → 404）")
  void tenantIsolationList() {
    var orderA = createOrder(a, "PO-ISO-A", bomVersionPublished);
    var orderB = createOrder(b, "PO-ISO-B", bomVersionPublished);

    var detailA = asA(() -> service.order(orderA.id()));
    assertThat(detailA.order().orderNo()).isEqualTo("PO-ISO-A");

    assertThatThrownBy(() -> asA(() -> service.order(orderB.id())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权查看");
  }

  @Test
  @DisplayName("多租户断言 2：伪造 X-Tenant-Id 头无效（走 HTTP 端点，租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    var orderA = createOrder(a, "PO-FORGE-A", bomVersionPublished);
    createOrder(b, "PO-FORGE-B", bomVersionPublished);

    http.perform(
            get("/trade/processing/orders/{id}", orderA.id().toString())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.orderNo").value("PO-FORGE-A"));

    // 拿 A 的身份 + B 的头去读 B 的单：读不到（租户由 Bearer 决定）
    var orderB = asB(() -> service.pageOrders(new ProcessingModels.ProcessingOrderQuery(null, null,
        null, null, null, 0, 50))).items().get(0);
    http.perform(
            get("/trade/processing/orders/{id}", orderB.id().toString())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("多租户断言 3：连接池归还后不串租户（池压到 1，20 轮往返）")
  void connectionPoolDoesNotLeakTenant() {
    var orderA = createOrder(a, "PO-POOL-A", bomVersionPublished);
    var orderB = createOrder(b, "PO-POOL-B", bomVersionPublished);

    for (int i = 0; i < 20; i++) {
      assertThat(asA(() -> service.order(orderA.id()).order().orderNo())).isEqualTo("PO-POOL-A");
      assertThat(asB(() -> service.order(orderB.id()).order().orderNo())).isEqualTo("PO-POOL-B");
    }
  }

  @Test
  @DisplayName("每张新表都 relforcerowsecurity")
  void rlsIsForcedOnEveryTable() {
    for (String table : List.of("processing_factory", "processing_order",
        "processing_order_component", "processing_order_event", "processing_inbound_request")) {
      Boolean forced =
          asA(
              () ->
                  sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", table)
                      .get(0, Boolean.class));
      assertThat(forced).as(table).isTrue();
    }
  }

  // ================================================================ HTTP 契约

  @Test
  @DisplayName("HTTP 契约：401 未认证 / 403 只读 / 404 跨租户 / 409 乐观锁")
  void httpContract() throws Exception {
    http.perform(get("/trade/processing/orders")).andExpect(status().isUnauthorized());

    http.perform(get("/trade/processing/capabilities").header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());

    // 只读身份不能建单
    http.perform(
            post("/trade/processing/orders")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new ProcessingModels.ProcessingOrderInput(
                            "PO-HTTP-1", idFor(a, 1), bomVersionPublished, new BigDecimal("1"),
                            LocalDate.now().plusDays(5), null))))
        .andExpect(status().isForbidden());

    // 乐观锁版本不符 → 409
    var order = createOrder(a, "PO-LOCK-001", bomVersionPublished);
    http.perform(
            post("/trade/processing/orders/{id}/status", order.id().toString())
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new ProcessingModels.ProcessingStatusInput("RELEASED", null, null, null,
                            999L))))
        .andExpect(status().isConflict());

    // 数量给 0 → 400
    http.perform(
            post("/trade/processing/orders")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new ProcessingModels.ProcessingOrderInput(
                            "PO-HTTP-2", idFor(a, 1), bomVersionPublished, BigDecimal.ZERO,
                            LocalDate.now().plusDays(5), null))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("timestamptz 写入后偏移零漂移（不是「没抛异常」而已）")
  void timestampRoundTripsWithoutDrift() {
    var order = createOrder(a, "PO-TS-001", bomVersionPublished);
    OffsetDateTime written =
        asA(() -> sql.fetchOne("select created_at from processing_order where id = ?", order.id())
            .get(0, OffsetDateTime.class));
    assertThat(written.toInstant()).isEqualTo(OffsetDateTime.parse(order.createdAt()).toInstant());
    assertThat(written.getOffset()).isEqualTo(OffsetDateTime.parse(order.createdAt()).getOffset());
  }

  // ================================================================ 助手

  ProcessingModels.ProcessingOrderSaved release(ProcessingModels.ProcessingOrderSaved order) {
    return asA(
        () ->
            service.advanceStatus(
                order.id(),
                new ProcessingModels.ProcessingStatusInput("RELEASED", null, null, null,
                    order.version()),
                user));
  }

  ProcessingModels.ProcessingOrderSaved toInProcess(
      ProcessingModels.ProcessingOrderSaved order, ProcessingModels.ProcessingOrderSaved released) {
    return asA(
        () ->
            service.advanceStatus(
                order.id(),
                new ProcessingModels.ProcessingStatusInput("IN_PROCESS", null, null, null,
                    released.version()),
                user));
  }
}
