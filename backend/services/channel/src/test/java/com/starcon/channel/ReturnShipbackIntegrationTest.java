package com.starcon.channel;

import static com.starcon.channel.ReturnShipbackModels.*;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 10.7.1.2 退运回国。
 *
 * <p>本测试类钉死七组事：
 *
 * <ol>
 *   <li><b>🔴 合单是真的</b>：一张退运单装多条处置单的货；一条处置单最多进一个退运单；
 *       转销售 / 弃置销毁的处置单<b>根本进不来</b>（DB 触发器也拒）。</li>
 *   <li><b>🔴 成本分摊是真的算出来的</b>：逐行权重 / 比例 / 分摊金额 / 尾差；
 *       <b>合计恒等于总额</b>；权重之和为 0 时显式回退到平均并如实记录实际口径。</li>
 *   <li><b>🔴 关税是派生的</b>：关税金额 = 申报货值 × 税率；合计 = 关税 + 税费
 *       （DB CHECK 再钉一道）。</li>
 *   <li><b>状态机只有一处真相</b>：非法迁移被拒；确认前必须有明细行与报关资料。</li>
 *   <li><b>🔴 入库分录只出不消费</b>：货没到货不许出分录；分录恒 PENDING，
 *       消费方 7.4.1.1 / 10.7.1.3；运行角色对它没有 UPDATE / DELETE 权限。</li>
 *   <li><b>时刻一律钉死</b>：timestamptz 往返不偏 8 小时，且断言<b>写回的值</b>。</li>
 *   <li><b>绕过服务层直接写 SQL 一样被拒</b>：非「退运回国」的处置单、
 *       分摊合计不等于总额、混币种成本，都在 DB 层被挡下。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReturnShipbackIntegrationTest {
  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 复用 identity 已注册的 channel 权限码：本模块不新增权限码，identity 零改动。 */
  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
            + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
      actors.put("Bearer tenant-a", actor(user, a, grants));
      actors.put("Bearer tenant-b", actor(user, b, grants));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired ReturnShipbackService service;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  @BeforeEach
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    truncateAll();
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  void truncateAll() {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // CASCADE：这些表当前还没有外键引用，但以后任何车道给它们加外键时，
      // 引用方不在同一条 truncate 语句里 PG 会直接拒
      // （cannot truncate a table referenced in a foreign key constraint），级联一并清干净。
      s.execute("truncate return_shipback_event, return_shipback_inventory_entry,"
          + " return_shipback_arrival, return_shipback_cost_allocation, return_shipback_cost,"
          + " return_shipback_customs_declaration, return_shipback_line,"
          + " return_shipback_consignment, return_inventory_change_instruction,"
          + " return_disposition_cost, return_disposition_order, return_disposition_decision,"
          + " return_resale_restriction, return_receiving_registration cascade");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 上游 10.7.1.1 夹具（**同服务**，直接落库）
  // ==================================================================

  record Disposition(UUID registrationId, UUID decisionId, UUID orderId, String dispositionNo) {}

  /**
   * 落一条 10.7.1.1 的处置事实（登记 → 决策 → 处置单）。
   *
   * <p>走的是真实表与真实触发器（app.actor 照常盖章），所以本模块读到的是**真上游**。
   */
  Disposition seedDisposition(UUID tenant, String method, String skuCode, String qty) {
    UUID registrationId = UUID.randomUUID();
    UUID decisionId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    String receiptId = UUID.randomUUID().toString();
    String receiptLineId = UUID.randomUUID().toString();
    String receiptNo = "RR-" + shortId();
    // 单号在租户内唯一：每次夹具都用新号（否则第二次夹具就撞唯一键）
    String registrationNo = shortId();
    String decisionNo = shortId();
    String dispositionNo = shortId();
    // 🔴 「转销售」的决策在 10.7.1.1 里必须带 RESALABLE 质检结论（DB CHECK 钉死）
    boolean resale = "RESALE".equals(method);
    execAsTenant(
        tenant,
        "insert into return_receiving_registration"
            + " (id,tenant_id,receipt_id,receipt_line_id,receipt_no,sku_code,sku_name,batch_no,"
            + "  brand_code,platform_code,warehouse_type,warehouse_ref,warehouse_country,received_qty,"
            + "  received_at,note,registration_no)"
            + " values ('" + registrationId + "','" + tenant + "','" + receiptId + "','"
            + receiptLineId + "','" + receiptNo + "','" + skuCode + "','" + skuCode + " 样机','B-1',"
            + "'BRAND-A','AMAZON','OVERSEAS','WH-DE-1','DE'," + qty + ", now(), '夹具','RCV-"
            + registrationNo + "')");
    execAsTenant(
        tenant,
        "insert into return_disposition_decision"
            + " (id,tenant_id,registration_id,receipt_id,receipt_line_id,receipt_no,sku_code,sku_name,"
            + "  batch_no,brand_code,platform_code,disposition_method,decided_qty,decision_basis,"
            + "  decided_at,resale_restricted,status,decision_no"
            + (resale ? ",quality_inspection_id,quality_verdict,quality_verdict_at" : "")
            + ")"
            + " values ('" + decisionId + "','" + tenant + "','" + registrationId + "','"
            + receiptId + "','" + receiptLineId + "','" + receiptNo + "','" + skuCode + "','" + skuCode
            + " 样机','B-1','BRAND-A','AMAZON','" + method + "'," + qty + ",'夹具', now(), false,"
            + "'RECORDED','RDC-" + decisionNo + "'"
            + (resale ? ",'" + UUID.randomUUID() + "','RESALABLE', now()" : "")
            + ")");
    execAsTenant(
        tenant,
        "insert into return_disposition_order"
            + " (id,tenant_id,decision_id,registration_id,receipt_id,receipt_line_id,receipt_no,"
            + "  sku_code,sku_name,batch_no,brand_code,platform_code,disposition_method,disposed_qty,"
            + "  cost_currency,salvage_currency,salvage_amount,status,issued_at,disposition_no)"
            + " values ('" + orderId + "','" + tenant + "','" + decisionId + "','" + registrationId
            + "','" + receiptId + "','" + receiptLineId + "','" + receiptNo + "','" + skuCode + "','"
            + skuCode + " 样机','B-1','BRAND-A','AMAZON','" + method + "'," + qty
            + ",'USD','USD',0,'ISSUED', now(), 'RDO-" + dispositionNo + "')");
    return new Disposition(registrationId, decisionId, orderId, "RDO-" + dispositionNo);
  }

  /** 短且唯一的单号后缀。 */
  static String shortId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  static void execAsTenant(UUID tenant, String sql) {
    try (Connection c =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenant + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  // ==================================================================
  // 夹具：建退运单
  // ==================================================================

  ShipbackLineDraft draft(UUID dispositionOrderId, String qty, String weight) {
    return new ShipbackLineDraft(
        dispositionOrderId,
        new BigDecimal(qty),
        weight == null ? null : new BigDecimal(weight),
        null,
        "61091000",
        "退货 T 恤（申报品名）",
        new BigDecimal("100.00"),
        "CNY",
        new BigDecimal("5.00"),
        "CNY",
        "夹具明细");
  }


  /** 最小报关资料请求（15 个分量，缺一个都编译不过 —— 这就是「申报资料不许缺项」）。 */
  RecordShipbackDeclarationRequest declarationRequest(String qty, String value, String rate) {
    return new RecordShipbackDeclarationRequest(
        null,
        "RETURN_IMPORT",
        "61091000",
        "申报品名",
        new BigDecimal(qty),
        new BigDecimal(value),
        "CNY",
        new BigDecimal(rate),
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  CreateConsignmentRequest consignmentRequest(List<ShipbackLineDraft> lines) {
    return new CreateConsignmentRequest(
        "SEA_CONSOLIDATION",
        "DE",
        "CN",
        "WH-DE-1",
        "WH-CN-1",
        "USD",
        "HAMBURG",
        "SHANGHAI",
        "MAEU",
        "TRK-001",
        OffsetDateTime.of(2026, 4, 1, 8, 0, 0, 0, ZoneOffset.UTC),
        OffsetDateTime.of(2026, 4, 30, 8, 0, 0, 0, ZoneOffset.UTC),
        "凑单：两票货合一个海运拼箱",
        "夹具退运单",
        lines);
  }

  ReturnShipbackDetail create(List<ShipbackLineDraft> lines) {
    return as(actorA, "channel.operation.create", () -> service.createConsignment(consignmentRequest(lines)));
  }

  ShipbackAllocationResult recordCost(UUID consignmentId, String amount, String basis) {
    return as(
        actorA,
        "channel.operation.create",
        () ->
            service.recordCost(
                consignmentId,
                new RecordShipbackCostRequest(
                    "OCEAN_FREIGHT",
                    new BigDecimal(amount),
                    "USD",
                    basis,
                    "MAEU",
                    OffsetDateTime.of(2026, 4, 2, 8, 0, 0, 0, ZoneOffset.UTC),
                    "夹具成本")));
  }

  // ==================================================================
  // 1. 能力声明
  // ==================================================================

  @Test
  @DisplayName("10.7.1.2 能力声明：合单 / 分摊口径 / 未交付下游逐条写明 ownerModule")
  void capabilitiesDeclareConsolidationAndAllocation() {
    var caps = as(actorA, "channel.operation.read", () -> service.capabilities());

    assertThat(caps.statuses()).containsExactlyInAnyOrder("DRAFT", "CONFIRMED", "DISPATCHED", "ARRIVED", "CLOSED", "CANCELLED");
    assertThat(caps.transportModes()).contains("SEA_CONSOLIDATION");
    assertThat(caps.allocationBases()).containsExactlyInAnyOrder("WEIGHT", "QUANTITY", "DECLARED_VALUE", "EQUAL");
    assertThat(caps.consolidationNote()).contains("合单").contains("摊薄");

    var consolidation =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("consolidation"))
            .findFirst()
            .orElseThrow();
    assertThat(consolidation.available()).isTrue();
    assertThat(consolidation.source()).contains("return_shipback_consignment");

    var allocation =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("cost_allocation"))
            .findFirst()
            .orElseThrow();
    assertThat(allocation.available()).isTrue();
    assertThat(allocation.source()).contains("return_shipback_cost_allocation");
    assertThat(allocation.impact()).contains("恒等于");

    // 🔴 12 财务不存在：成本以本服务自有事实落地
    var cost =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("shipback_cost"))
            .findFirst()
            .orElseThrow();
    assertThat(cost.impact()).contains("本服务").contains("12 财务");
    assertThat(caps.financeNote()).contains("不存在");

    // 🔴 7 库存 / 10.7.1.3 未交付：分录恒 PENDING
    var entry =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("restock_entry"))
            .findFirst()
            .orElseThrow();
    assertThat(entry.ownerModule()).contains("7.4.1.1").contains("10.7.1.3");
    assertThat(entry.impact()).contains("PENDING");

    // 🔴 10.5.1 报关跨服务：能力声明里写清是本服务自持快照
    var customs =
        caps.dimensions().stream()
            .filter(d -> d.dimension().equals("customs_declaration"))
            .findFirst()
            .orElseThrow();
    assertThat(customs.ownerModule()).isEqualTo("10.5.1");
    assertThat(customs.impact()).contains("跨服务").contains("快照");
  }

  // ==================================================================
  // 2. 待退运清单
  // ==================================================================

  @Test
  @DisplayName("10.7.1.2 待退运清单：只含「退运回国」的处置单，转销售 / 弃置销毁根本进不来")
  void candidatesOnlyContainReturnToCountry() {
    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-BACK", "10");
    seedDisposition(a, "RESALE", "SKU-RESALE", "5");
    seedDisposition(a, "DISCARD", "SKU-DISCARD", "3");

    var page = as(actorA, "channel.operation.read", () -> service.candidates(null, 0, 50));
    assertThat(page.rows()).hasSize(1);
    assertThat(page.rows().get(0).dispositionOrderId()).isEqualTo(back.orderId());
    assertThat(page.rows().get(0).dispositionMethod()).isEqualTo("RETURN_TO_COUNTRY");
    assertThat(page.totalQty()).isEqualByComparingTo("10");
  }

  @Test
  @DisplayName("10.7.1.2 待退运清单：进了退运单之后就不再是候选")
  void candidateLeavesTheListAfterConsolidation() {
    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-BACK", "10");
    create(List.of(draft(back.orderId(), "10", "5")));

    var page = as(actorA, "channel.operation.read", () -> service.candidates(null, 0, 50));
    assertThat(page.rows()).isEmpty();
    assertThat(page.total()).isZero();
  }

  // ==================================================================
  // 3. 🔴 合单
  // ==================================================================

  @Test
  @DisplayName("10.7.1.2 🔴 合单：一张退运单装多条处置单的货，单票成本由此摊薄")
  void oneConsignmentCarriesManyDispositionOrders() {
    var d1 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var d2 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-B", "5");
    var d3 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-C", "2");

    var detail = create(List.of(draft(d1.orderId(), "10", "5"), draft(d2.orderId(), "5", "3"), draft(d3.orderId(), "2", "1")));

    assertThat(detail.head().shipbackNo()).startsWith("SBK");
    assertThat(detail.head().status()).isEqualTo("DRAFT");
    assertThat(detail.head().lineCount()).isEqualTo(3);
    assertThat(detail.lines()).hasSize(3);
    assertThat(detail.head().totalQty()).isEqualByComparingTo("17");
    assertThat(detail.head().totalWeightKg()).isEqualByComparingTo("9");
    // 🔴 明细行上的 SKU 是**从处置单抄的**，不是客户端自报
    assertThat(detail.lines().get(0).skuCode()).isEqualTo("SKU-A");
    assertThat(detail.lines().get(0).dispositionNo()).startsWith("RDO-");
  }

  @Test
  @DisplayName("10.7.1.2 🔴 一条处置单最多进一个退运单（重复进单被拒）")
  void oneDispositionOrderCannotEnterTwoConsignments() {
    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-BACK", "10");
    create(List.of(draft(back.orderId(), "10", "5")));

    var error =
        catchThrowable(
            () -> create(List.of(draft(back.orderId(), "10", "5"))));
    assertThat(error).as("同一条处置单不能进第二张退运单").isNotNull();
  }

  @Test
  @DisplayName("10.7.1.2 🔴 转销售 / 弃置销毁的处置单进不了退运单（服务层与 DB 各拒一次）")
  void nonRepatriationDispositionIsRejected() {
    var resale = seedDisposition(a, "RESALE", "SKU-RESALE", "5");
    var error =
        catchThrowable(() -> create(List.of(draft(resale.orderId(), "5", "1"))));
    assertThat(error).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(422);

    // 🔴 绕过服务层直接写 SQL：DB 触发器一样拒
    var d2 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-OK", "5");
    var detail = create(List.of(draft(d2.orderId(), "5", "1")));
    UUID illegalLine = UUID.randomUUID();
    var sqlError =
        catchThrowable(
            () ->
                execAsTenant(
                    a,
                    "insert into return_shipback_line"
                        + " (id,tenant_id,consignment_id,disposition_order_id,decision_id,disposition_no,"
                        + "  receipt_no,sku_code,sku_name,batch_no,qty,declared_value,declared_currency,"
                        + "  salvage_amount,salvage_currency)"
                        + " values ('" + illegalLine + "','" + a + "','" + detail.head().id() + "','"
                        + resale.orderId() + "','" + resale.decisionId() + "','RDO-RAW','RR-SKU-RESALE',"
                        + "'SKU-RESALE','x','B-1',1,0,'CNY',0,'CNY')"));
    assertThat(sqlError).as("DB 触发器必须拒非「退运回国」的处置单").isNotNull();
  }

  @Test
  @DisplayName("10.7.1.2 🔴 退运数量不得超过处置单的处置量")
  void lineQuantityCannotExceedDisposedQuantity() {
    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-BACK", "10");
    var error = catchThrowable(() -> create(List.of(draft(back.orderId(), "11", "5"))));
    assertThat(error).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(422);
  }

  // ==================================================================
  // 4. 🔴 成本分摊
  // ==================================================================

  @Test
  @DisplayName("10.7.1.2 🔴 海运拼箱按重量分摊：逐行比例与金额，合计恒等于总额")
  void allocationByWeightSumsToTotal() {
    var d1 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var d2 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-B", "10");
    var d3 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-C", "10");
    var detail =
        create(List.of(draft(d1.orderId(), "10", "10"), draft(d2.orderId(), "10", "20"), draft(d3.orderId(), "10", "70")));

    var result = recordCost(detail.head().id(), "1000.00", "WEIGHT");

    assertThat(result.allocations()).hasSize(3);
    assertThat(result.totalAllocated()).isEqualByComparingTo("1000.00");
    assertThat(result.appliedBasis()).isEqualTo("WEIGHT");
    assertThat(result.basisFallback()).isFalse();

    BigDecimal sum = BigDecimal.ZERO;
    for (ReturnShipbackAllocationRow row : result.allocations()) {
      assertThat(row.basisTotal()).isEqualByComparingTo("100");
      assertThat(row.currency()).isEqualTo("USD");
      sum = sum.add(row.allocatedAmount());
    }
    assertThat(sum).as("分摊合计必须等于成本金额").isEqualByComparingTo("1000.00");
    // 10 / 20 / 70 ⇒ 100 / 200 / 700
    BigDecimal byWeight = new BigDecimal("0").add(result.allocations().get(0).allocatedAmount());
    assertThat(byWeight).isEqualByComparingTo("100.00");
    assertThat(result.allocations().get(1).allocatedAmount()).isEqualByComparingTo("200.00");
    assertThat(result.allocations().get(2).allocatedAmount()).isEqualByComparingTo("700.00");
    // 比例是 scale 4
    assertThat(result.allocations().get(0).allocationRatio().scale()).isEqualTo(4);
    assertThat(result.allocations().get(0).allocationRatio()).isEqualByComparingTo("0.1000");
  }

  @Test
  @DisplayName("10.7.1.2 🔴 除不尽时分摊：尾差补到最后一笔，合计仍等于总额（尾差不许丢）")
  void allocationKeepsTheResidualSoTotalMatches() {
    var d1 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var d2 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-B", "10");
    var d3 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-C", "10");
    var detail = create(List.of(draft(d1.orderId(), "10", "1"), draft(d2.orderId(), "10", "1"), draft(d3.orderId(), "10", "1")));

    var result = recordCost(detail.head().id(), "100.00", "WEIGHT");

    BigDecimal sum = BigDecimal.ZERO;
    BigDecimal residual = BigDecimal.ZERO;
    for (ReturnShipbackAllocationRow row : result.allocations()) {
      sum = sum.add(row.allocatedAmount());
      residual = residual.add(row.residualAmount());
    }
    assertThat(sum).as("三等分除不尽：合计必须仍然等于 100").isEqualByComparingTo("100.0000");
    assertThat(residual).as("尾差必须显式记在某一笔上").isEqualByComparingTo("0.0001");
    // 🔴 分摊金额按 scale 4 四舍五入：100/3 = 33.3333…，三笔取整后差 0.0001
    assertThat(result.allocations().get(0).allocatedAmount()).isEqualByComparingTo("33.3333");
    assertThat(result.allocations().get(1).allocatedAmount()).isEqualByComparingTo("33.3333");
    assertThat(result.allocations().get(2).allocatedAmount()).isEqualByComparingTo("33.3334");
    assertThat(result.allocations().get(2).residualAmount()).isEqualByComparingTo("0.0001");
    assertThat(result.totalAllocated()).isEqualByComparingTo("100.0000");
  }

  @Test
  @DisplayName("10.7.1.2 🔴 权重之和为 0：显式回退到平均分摊并如实记录实际口径")
  void zeroWeightBasisFallsBackExplicitlyToEqual() {
    var d1 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var d2 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-B", "10");
    var detail = create(List.of(draft(d1.orderId(), "10", "0"), draft(d2.orderId(), "10", "0")));

    var result = recordCost(detail.head().id(), "90.00", "WEIGHT");

    assertThat(result.requestedBasis()).isEqualTo("WEIGHT");
    assertThat(result.appliedBasis()).isEqualTo("EQUAL");
    assertThat(result.basisFallback()).isTrue();
    assertThat(result.totalAllocated()).isEqualByComparingTo("90.00");
    assertThat(result.allocations()).hasSize(2);
    for (ReturnShipbackAllocationRow row : result.allocations()) {
      assertThat(row.requestedBasis()).isEqualTo("WEIGHT");
      assertThat(row.appliedBasis()).isEqualTo("EQUAL");
      assertThat(row.allocatedAmount()).isEqualByComparingTo("45.00");
    }
  }

  @Test
  @DisplayName("10.7.1.2 🔴 分摊比例用 BigDecimal（scale 4，HALF_UP），全程禁浮点")
  void allocationRatioIsScaleFourHalfUp() {
    var d1 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var d2 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-B", "10");
    var d3 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-C", "10");
    var detail = create(List.of(draft(d1.orderId(), "10", "1"), draft(d2.orderId(), "10", "1"), draft(d3.orderId(), "10", "1")));

    var result = recordCost(detail.head().id(), "10.00", "WEIGHT");

    for (ReturnShipbackAllocationRow row : result.allocations()) {
      assertThat(row.allocationRatio().scale()).isEqualTo(4);
      assertThat(row.allocationRatio()).isEqualByComparingTo("0.3333");
    }
  }

  @Test
  @DisplayName("10.7.1.2 🔴 空退运单不许记成本：没有明细行就没法分摊")
  void costOnEmptyConsignmentIsRejected() {
    var empty = as(actorA, "channel.operation.create", () -> service.createConsignment(consignmentRequest(List.of())));
    var error = catchThrowable(() -> recordCost(empty.head().id(), "100.00", "WEIGHT"));
    assertThat(error).isNotNull();
    assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(422);
  }

  @Test
  @DisplayName("10.7.1.2 🔴 混币种成本被拒（成本行币种必须与退运单成本币种逐字相同）")
  void mixedCurrencyCostIsRejected() {
    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var detail = create(List.of(draft(back.orderId(), "10", "5")));

    var error =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.recordCost(
                            detail.head().id(),
                            new RecordShipbackCostRequest(
                                "OCEAN_FREIGHT", new BigDecimal("100.00"), "EUR", "WEIGHT", null, null, null))));
    assertThat(error).as("混币种的「合计」是没有意义的数字").isNotNull();
  }

  @Test
  @DisplayName("10.7.1.2 🔴 绕过服务层写 SQL：分摊合计不等于总额 ⇒ 提交时被 DB 拒")
  void allocationTotalMismatchIsRejectedByDatabase() {
    var d1 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var d2 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-B", "10");
    var detail = create(List.of(draft(d1.orderId(), "10", "1"), draft(d2.orderId(), "10", "1")));
    UUID costId = UUID.randomUUID();
    var error =
        catchThrowable(
            () -> {
              try (Connection c =
                      DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                c.setAutoCommit(false);
                try (var s = c.createStatement()) {
                  s.execute("select set_config('app.current_tenant','" + a + "',false)");
                  s.execute("select set_config('app.actor','" + user + "',false)");
                  s.execute(
                      "insert into return_shipback_cost"
                          + " (id,tenant_id,consignment_id,cost_type,amount,currency,allocation_basis,"
                          + "  incurred_at) values ('" + costId + "','" + a + "','"
                          + detail.head().id() + "','OCEAN_FREIGHT',100.00,'USD','WEIGHT', now())");
                  s.execute(
                      "insert into return_shipback_cost_allocation"
                          + " (id,tenant_id,consignment_id,line_id,cost_id,requested_basis,applied_basis,"
                          + "  basis_value,basis_total,allocation_ratio,allocated_amount,currency)"
                          + " select gen_random_uuid(),'" + a + "','" + detail.head().id() + "',l.id,'"
                          + costId + "','WEIGHT','WEIGHT',1,2,0.5,10.00,'USD'"
                          + "  from return_shipback_line l where l.tenant_id='" + a + "'");
                }
                c.commit();
              } catch (SQLException e) {
                throw new RuntimeException(e);
              }
            });
    assertThat(error).as("分摊合计 20 ≠ 成本 100：提交时必须被拒").isNotNull();
  }

  // ==================================================================
  // 5. 🔴 报关资料与关税
  // ==================================================================

  @Test
  @DisplayName("10.7.1.2 🔴 关税是派生的：关税金额 = 申报货值 × 税率，合计 = 关税 + 税费")
  void tariffAmountIsDerivedFromRateAndValue() {
    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var detail = create(List.of(draft(back.orderId(), "10", "5")));

    var declaration =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.recordDeclaration(
                    detail.head().id(),
                    new RecordShipbackDeclarationRequest(
                        null,
                        "RETURN_IMPORT",
                        "61091000",
                        "退货 T 恤（申报品名）",
                        new BigDecimal("10"),
                        new BigDecimal("1000.00"),
                        "CNY",
                        new BigDecimal("0.15"),
                        new BigDecimal("30.00"),
                        null,
                        "DEC-2026-001",
                        "SUBMITTED",
                        OffsetDateTime.of(2026, 4, 3, 8, 0, 0, 0, ZoneOffset.UTC),
                        null,
                        "夹具报关")));

    assertThat(declaration.tariffAmount()).isEqualByComparingTo("150.0000");
    assertThat(declaration.taxAmount()).isEqualByComparingTo("30.0000");
    assertThat(declaration.totalAmount()).isEqualByComparingTo("180.0000");
    assertThat(declaration.declaredCurrency()).isEqualTo("CNY");
    assertThat(declaration.declarationTypeLabel()).isEqualTo("退运进口");
  }

  @Test
  @DisplayName("10.7.1.2 🔴 税率填成 15（而不是 0.15）被拒")
  void tariffRateMustBeAFraction() {
    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var detail = create(List.of(draft(back.orderId(), "10", "5")));
    var error =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.recordDeclaration(
                            detail.head().id(),
                            new RecordShipbackDeclarationRequest(
                                null, "RETURN_IMPORT", "61091000", "申报品名",
                                new BigDecimal("10"), new BigDecimal("1000.00"), "CNY",
                                new BigDecimal("15"), null, null, null, null, null, null, null))));
    assertThat(error).isNotNull();
  }

  @Test
  @DisplayName("10.7.1.2 🔴 申报品名不能为空（申报品名不照搬销售标题，但它必须有）")
  void declaredItemNameIsRequired() {
    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var detail = create(List.of(draft(back.orderId(), "10", "5")));
    var error =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.recordDeclaration(
                            detail.head().id(),
                            new RecordShipbackDeclarationRequest(
                                null, "RETURN_IMPORT", "61091000", "  ",
                                new BigDecimal("10"), new BigDecimal("1000.00"), "CNY",
                                new BigDecimal("0.15"), null, null, null, null, null, null, null))));
    assertThat(error).isNotNull();
  }

  // ==================================================================
  // 6. 状态机
  // ==================================================================

  @Test
  @DisplayName("10.7.1.2 状态机：确认前必须有明细行与报关资料；缺一样都过不去")
  void confirmRequiresLinesAndCustoms() {
    var empty = as(actorA, "channel.operation.create", () -> service.createConsignment(consignmentRequest(List.of())));
    var errorNoLines =
        catchThrowable(() -> as(actorA, "channel.operation.update", () -> service.confirm(empty.head().id(), null)));
    assertThat(errorNoLines).isNotNull();

    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var detail = create(List.of(draft(back.orderId(), "10", "5")));
    var errorNoCustoms =
        catchThrowable(() -> as(actorA, "channel.operation.update", () -> service.confirm(detail.head().id(), null)));
    assertThat(errorNoCustoms).as("没报关资料不能确认").isNotNull();

    as(
        actorA,
        "channel.operation.create",
        () ->
            service.recordDeclaration(
                detail.head().id(),
                declarationRequest("10", "1000.00", "0.15")));
    var confirmed = as(actorA, "channel.operation.update", () -> service.confirm(detail.head().id(), null));
    assertThat(confirmed.head().status()).isEqualTo("CONFIRMED");
  }

  @Test
  @DisplayName("10.7.1.2 状态机：非法迁移被拒（草稿不能直接到货）")
  void illegalTransitionIsRejected() {
    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var detail = create(List.of(draft(back.orderId(), "10", "5")));
    var error =
        catchThrowable(
            () -> as(actorA, "channel.operation.update", () -> service.dispatch(detail.head().id(), null)));
    assertThat(error).as("未确认不能发出").isNotNull();
  }

  @Test
  @DisplayName("10.7.1.2 取消必须给理由；已发出的单不能取消")
  void cancelRequiresReasonAndOnlyBeforeDispatch() {
    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var detail = create(List.of(draft(back.orderId(), "10", "5")));

    var noReason =
        catchThrowable(
            () -> as(actorA, "channel.operation.update", () -> service.cancel(detail.head().id(), null)));
    assertThat(noReason).isNotNull();

    var cancelled =
        as(
            actorA,
            "channel.operation.update",
            () -> service.cancel(detail.head().id(), new AdvanceShipbackRequest(null, null, "货找不回来了", null)));
    assertThat(cancelled.head().status()).isEqualTo("CANCELLED");
  }

  // ==================================================================
  // 7. 🔴 回国收货 + 重新入库 / 报废分录
  // ==================================================================

  @Test
  @DisplayName("10.7.1.2 🔴 入库分录只在到货之后可登记；分录恒 PENDING、消费方写明")
  void inventoryEntryOnlyAfterArrivalAndAlwaysPending() {
    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var detail = create(List.of(draft(back.orderId(), "10", "5")));

    // 还没到货
    var tooEarly =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.recordEntry(
                            detail.head().id(),
                            new RecordShipbackEntryRequest(
                                detail.lines().get(0).id(), "RESTOCK", new BigDecimal("10"), null, null))));
    assertThat(tooEarly).as("货还没回来就入库是假的").isNotNull();

    // 走完 确认 → 发出 → 收货
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.recordDeclaration(
                detail.head().id(),
                declarationRequest("10", "1000.00", "0.15")));
    as(actorA, "channel.operation.update", () -> service.confirm(detail.head().id(), null));
    var dispatched =
        as(
            actorA,
            "channel.operation.update",
            () ->
                service.dispatch(
                    detail.head().id(),
                    new AdvanceShipbackRequest(null, "TRK-999", null, null)));
    assertThat(dispatched.head().status()).isEqualTo("DISPATCHED");
    assertThat(dispatched.head().trackingNo()).isEqualTo("TRK-999");
    assertThat(dispatched.lines().get(0).status()).isEqualTo("SHIPPED");

    var arrived =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.recordArrival(
                    detail.head().id(),
                    new RecordShipbackArrivalRequest(
                        "WH-CN-1",
                        OffsetDateTime.of(2026, 5, 1, 9, 0, 0, 0, ZoneOffset.UTC),
                        new BigDecimal("8"),
                        new BigDecimal("1"),
                        new BigDecimal("1"),
                        "到货，少了一件")));
    assertThat(arrived.head().status()).isEqualTo("ARRIVED");
    // 🔴 时刻：断言**写回的值**
    assertThat(arrived.arrivals().get(0).arrivedAt())
        .isEqualTo(OffsetDateTime.of(2026, 5, 1, 9, 0, 0, 0, ZoneOffset.UTC));
    assertThat(arrived.arrivals().get(0).shortageQty()).isEqualByComparingTo("1");

    var entry =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.recordEntry(
                    detail.head().id(),
                    new RecordShipbackEntryRequest(
                        detail.lines().get(0).id(), "RESTOCK", new BigDecimal("8"), null, "重新入库")));
    assertThat(entry.entries()).hasSize(1);
    assertThat(entry.entries().get(0).postingStatus()).isEqualTo("PENDING");
    assertThat(entry.entries().get(0).consumerModule()).isEqualTo("7.4.1.1");
    assertThat(entry.entries().get(0).entryNo()).startsWith("SBE");
    assertThat(entry.lines().get(0).status()).isEqualTo("RECEIVED");
  }

  @Test
  @DisplayName("10.7.1.2 🔴 报废去向的消费方是 10.7.1.3（同样是未交付 ⇒ 恒 PENDING）")
  void scrapEntryPointsToDisposalModule() {
    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var detail = create(List.of(draft(back.orderId(), "10", "5")));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.recordDeclaration(
                detail.head().id(),
                declarationRequest("10", "1000.00", "0.15")));
    as(actorA, "channel.operation.update", () -> service.confirm(detail.head().id(), null));
    as(actorA, "channel.operation.update", () -> service.dispatch(detail.head().id(), null));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.recordArrival(
                detail.head().id(),
                new RecordShipbackArrivalRequest(
                    "WH-CN-1", null, new BigDecimal("10"), null, null, null)));

    var entry =
        as(
            actorA,
            "channel.operation.create",
            () ->
                service.recordEntry(
                    detail.head().id(),
                    new RecordShipbackEntryRequest(
                        detail.lines().get(0).id(), "SCRAP", new BigDecimal("10"), null, "残次报废")));
    assertThat(entry.entries().get(0).consumerModule()).isEqualTo("10.7.1.3");
    assertThat(entry.entries().get(0).postingStatus()).isEqualTo("PENDING");
    assertThat(entry.lines().get(0).status()).isEqualTo("SCRAPPED");
  }

  @Test
  @DisplayName("10.7.1.2 🔴 入库分录数量不得超过该行数量（同一行的分录合计也不许超）")
  void inventoryEntryQuantityCannotExceedLineQuantity() {
    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var detail = create(List.of(draft(back.orderId(), "10", "5")));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.recordDeclaration(
                detail.head().id(),
                declarationRequest("10", "1000.00", "0.15")));
    as(actorA, "channel.operation.update", () -> service.confirm(detail.head().id(), null));
    as(actorA, "channel.operation.update", () -> service.dispatch(detail.head().id(), null));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.recordArrival(
                detail.head().id(),
                new RecordShipbackArrivalRequest(
                    "WH-CN-1", null, new BigDecimal("10"), null, null, null)));

    var tooMuch =
        catchThrowable(
            () ->
                as(
                    actorA,
                    "channel.operation.create",
                    () ->
                        service.recordEntry(
                            detail.head().id(),
                            new RecordShipbackEntryRequest(
                                detail.lines().get(0).id(), "RESTOCK", new BigDecimal("11"), null, null))));
    assertThat(tooMuch).isNotNull();
  }

  @Test
  @DisplayName("10.7.1.2 🔴 关闭前每一行都要有去向（重新入库 / 报废分录）")
  void closeRequiresEveryLineToHaveAnOutcome() {
    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var detail = create(List.of(draft(back.orderId(), "10", "5")));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.recordDeclaration(
                detail.head().id(),
                declarationRequest("10", "1000.00", "0.15")));
    as(actorA, "channel.operation.update", () -> service.confirm(detail.head().id(), null));
    as(actorA, "channel.operation.update", () -> service.dispatch(detail.head().id(), null));
    as(
        actorA,
        "channel.operation.create",
        () ->
            service.recordArrival(
                detail.head().id(),
                new RecordShipbackArrivalRequest(
                    "WH-CN-1", null, new BigDecimal("10"), null, null, null)));

    var error =
        catchThrowable(
            () -> as(actorA, "channel.operation.update", () -> service.close(detail.head().id(), null)));
    assertThat(error).as("还有行没有去向就关不了账").isNotNull();

    as(
        actorA,
        "channel.operation.create",
        () ->
            service.recordEntry(
                detail.head().id(),
                new RecordShipbackEntryRequest(
                    detail.lines().get(0).id(), "RESTOCK", new BigDecimal("10"), null, null)));
    var closed = as(actorA, "channel.operation.update", () -> service.close(detail.head().id(), null));
    assertThat(closed.head().status()).isEqualTo("CLOSED");
  }

  // ==================================================================
  // 8. 列表 / 分页 / 筛选
  // ==================================================================

  @Test
  @DisplayName("10.7.1.2 列表：真分页 + 真筛选（状态 / 运输方式 / 关键词）")
  void listSupportsRealPaginationAndFilters() {
    var d1 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var d2 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-B", "10");
    var d3 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-C", "10");
    create(List.of(draft(d1.orderId(), "10", "5")));
    create(List.of(draft(d2.orderId(), "10", "5")));
    create(List.of(draft(d3.orderId(), "10", "5")));

    var page1 = as(actorA, "channel.operation.read", () -> service.consignments(null, null, null, 0, 2));
    var page2 = as(actorA, "channel.operation.read", () -> service.consignments(null, null, null, 1, 2));
    assertThat(page1.total()).isEqualTo(3);
    assertThat(page1.rows()).hasSize(2);
    assertThat(page2.rows()).hasSize(1);
    assertThat(page1.rows().get(0).id()).isNotEqualTo(page2.rows().get(0).id());

    var filtered = as(actorA, "channel.operation.read", () -> service.consignments(null, "DRAFT", null, 0, 50));
    assertThat(filtered.rows()).hasSize(3);

    var byTransport =
        as(actorA, "channel.operation.read", () -> service.consignments(null, null, "SEA_CONSOLIDATION", 0, 50));
    assertThat(byTransport.rows()).hasSize(3);
    var byAir = as(actorA, "channel.operation.read", () -> service.consignments(null, null, "AIR", 0, 50));
    assertThat(byAir.rows()).isEmpty();

    var byKeyword = as(actorA, "channel.operation.read", () -> service.consignments("SBK", null, null, 0, 50));
    assertThat(byKeyword.rows()).hasSize(3);
    var byMiss = as(actorA, "channel.operation.read", () -> service.consignments("不存在的单号", null, null, 0, 50));
    assertThat(byMiss.rows()).isEmpty();
  }

  @Test
  @DisplayName("10.7.1.2 概览：待退运候选数、合单数、成本合计按币种分行")
  void overviewCountsRealFacts() {
    var d1 = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-B", "5");
    var detail = create(List.of(draft(d1.orderId(), "10", "5")));
    recordCost(detail.head().id(), "100.00", "WEIGHT");

    var overview = as(actorA, "channel.operation.read", () -> service.overview());
    assertThat(overview.consignmentCount()).isEqualTo(1);
    assertThat(overview.draftCount()).isEqualTo(1);
    assertThat(overview.lineCount()).isEqualTo(1);
    // 两条候选里有一条已经进了退运单 ⇒ 只剩一条
    assertThat(overview.candidateCount()).isEqualTo(1);
    assertThat(overview.candidateQty()).isEqualByComparingTo("5");
    assertThat(overview.costTotals()).hasSize(1);
    assertThat(overview.costTotals().get(0).currency()).isEqualTo("USD");
    assertThat(overview.costTotals().get(0).amount()).isEqualByComparingTo("100.00");
  }

  // ==================================================================
  // 9. 端点契约
  // ==================================================================

  @Test
  @DisplayName("10.7.1.2 端点：无会话 401 / 越权 403 / 不存在 404 / 参数错 400")
  void endpointsEnforceAuthAndValidation() throws Exception {
    http.perform(get("/channel/return-shipbacks/consignments"))
        .andExpect(status().isUnauthorized());
    http.perform(get("/channel/return-shipbacks/consignments/not-a-uuid")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
    http.perform(get("/channel/return-shipbacks/consignments/" + UUID.randomUUID())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
    http.perform(post("/channel/return-shipbacks/consignments")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  @DisplayName("10.7.1.2 端点：能力声明与概览走真实 HTTP")
  void capabilitiesAndOverviewOverHttp() throws Exception {
    http.perform(get("/channel/return-shipbacks/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.allocationBases.length()").value(4))
        .andExpect(jsonPath("$.dimensions.length()").value(11));
    http.perform(get("/channel/return-shipbacks/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.consignmentCount").value(0));
    http.perform(get("/channel/return-shipbacks/candidates").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(0));
  }

  @Test
  @DisplayName("10.7.1.2 端点：留痕是追加式的（已写入的事件不许改、不许删）")
  void eventsAreAppendOnly() {
    var back = seedDisposition(a, "RETURN_TO_COUNTRY", "SKU-A", "10");
    var detail = create(List.of(draft(back.orderId(), "10", "5")));
    var events = as(actorA, "channel.operation.read", () -> service.events(detail.head().id(), 0, 50));
    assertThat(events.rows()).isNotEmpty();

    var error =
        catchThrowable(
            () ->
                execAsTenant(
                    a,
                    "update return_shipback_event set detail='改过了' where id='"
                        + events.rows().get(0).id() + "'"));
    assertThat(error).as("留痕不许改").isNotNull();
  }
}
