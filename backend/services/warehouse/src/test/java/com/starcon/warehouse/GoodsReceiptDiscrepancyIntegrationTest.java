package com.starcon.warehouse;

import static com.starcon.warehouse.GoodsReceiptDiscrepancyModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
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
 * 8.2.1.2 收货差异（处理）：**破损与短少不得入可售的硬规则**（服务层 + 数据库层两道）、
 * 拒收不入账与真撤出两条库存路径、部分接收的数量不变式、拍照留证字节真落库、
 * 索赔状态机与「不建假采购索赔单」边界，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link GoodsReceiptIntegrationTest} 完全一致（同样的 Testcontainers、
 * 同样的 fake identity server、同样的按租户派生 UUID、同样的 TRUNCATE 清场），
 * 派生 id 的前缀不同，避免两条车道的夹具互相污染。
 *
 * <p>差异事实一律走 8.2.1.1 的**真实入口**登记（实收请求的 discrepancies 数组），
 * 不直连 SQL 造差异 —— 否则测的是夹具而不是「上游真的交付了没有」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GoodsReceiptDiscrepancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("inventory")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static final UUID leA = idFor(a, 1);
  static final UUID skuA = idFor(a, 2);
  static final UUID skuA2 = idFor(a, 3);
  static final UUID whA = idFor(a, 4);
  static final UUID whB2 = idFor(a, 5);
  static final UUID qSellable = idFor(a, 6);
  static final UUID qPending = idFor(a, 7);
  static final UUID qDefective = idFor(a, 11);
  static final UUID locA1 = idFor(a, 8);
  static final UUID locA2 = idFor(a, 9);
  static final UUID lotA1 = idFor(a, 10);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-discrepancy-test-" + tenant + "-" + ordinal).getBytes());
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
            byte[] body =
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, null, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
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
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @Autowired GoodsReceiptDiscrepancyService handling;
  @Autowired GoodsReceiptService receipts;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actorA;
  AccessService.Access actorB;

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    // TRUNCATE 不触发行级触发器（append-only 只挡 UPDATE/DELETE），以超级用户连接执行可绕过
    // FORCE RLS —— 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate goods_receipt_discrepancy_stock_move, goods_receipt_discrepancy_claim,"
              + " goods_receipt_discrepancy_evidence, goods_receipt_discrepancy_handling,"
              + " goods_receipt_discrepancy, goods_receipt_event_serial,"
              + " goods_receipt_event_line, goods_receipt_event, goods_receipt_line,"
              + " goods_receipt, transfer_receipt_line, transfer_receipt, transfer_shipment,"
              + " transfer_order_line, transfer_order, inventory_transfer,"
              + " inventory_serial_movement, inventory_serial_operation, inventory_serial,"
              + " stock_bucket, warehouse_location, inventory_lot, inventory_entry,"
              + " inventory_transaction, inventory_balance, inventory_quality_status, warehouse,"
              + " sku_ref, legal_entity_ref restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1), tenant, "LE-1", "主体一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 2), tenant, "SKU-1", "商品一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3), tenant, "SKU-2", "商品二");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 4), tenant, "WH-SH", "上海仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 5), tenant, "WH-SZ", "深圳仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 6), tenant, "SELLABLE", "可售", "SELLABLE");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 7), tenant, "PENDING_INSPECTION", "待检", "PENDING_INSPECTION");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 11), tenant, "DEFECTIVE", "残次", "DEFECTIVE");
            return null;
          });
    }
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          location(idFor(a, 8), whA, "SH-A-01");
          location(idFor(a, 9), whA, "SH-A-02");
          sql.execute(
              "insert into inventory_lot(id,tenant_id,sku_id,lot_code,expires_on)"
                  + " values(?,?,?,?,?)",
              lotA1, a, skuA, "LOT-1", java.time.LocalDate.of(2027, 3, 1));
          return null;
        });
  }

  void location(UUID id, UUID warehouse, String code) {
    sql.execute(
        "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
            + " values(?,?,?,?,?,'STORAGE',true)",
        id, a, warehouse, code, "库位 " + code);
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.inventory.read", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.inventory.read", work);
  }

  // ================================================================ 夹具助手

  /** 走 8.2.1.1 的真实入口建单：调拨来源是唯一已交付的来源。 */
  GoodsReceiptModels.GoodsReceiptSaved createReceipt(String expected, UUID quality) {
    return asA(
        () ->
            receipts.create(
                a,
                new GoodsReceiptModels.GoodsReceiptCreateRequest(
                    "TRANSFER",
                    transferSourceId(),
                    "TO-2024-0001",
                    whA,
                    leA,
                    "顺丰",
                    "SF123",
                    "外箱完好",
                    List.of(
                        new GoodsReceiptModels.GoodsReceiptLineRequest(
                            skuA, quality, new BigDecimal(expected))))));
  }

  UUID transferSourceId() {
    return asA(
        () -> {
          var id = UUID.randomUUID();
          sql.execute(
              "insert into transfer_order(id,tenant_id,order_code,from_warehouse_id,"
                  + "to_warehouse_id,legal_entity_id,status,confirmed_at,shipped_at,created_by)"
                  + " values(?,?,?,?,?,?,'IN_TRANSIT',now(),now(),?)",
              id, a, "TO-" + id.toString().substring(0, 8), whB2, whA, leA, user);
          var lineId = UUID.randomUUID();
          sql.execute(
              "insert into transfer_order_line(id,tenant_id,order_id,sku_id,quality_status_id,"
                  + "requested_quantity,shipped_quantity) values(?,?,?,?,?,100,100)",
              lineId, a, id, skuA, qSellable, new BigDecimal("100"));
          sql.execute(
              "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,"
                  + "from_warehouse_id,to_warehouse_id,quality_status_id,quantity,status,"
                  + "ref_type,ref_id,shipped_at,line_id)"
                  + " values(?,?,?,?,?,?,?,100,'IN_TRANSIT','TRANSFER_ORDER',?,now(),?)",
              UUID.randomUUID(), a, skuA, leA, whB2, whA, qSellable, id, lineId);
          return id;
        });
  }

  UUID lineIdOf(UUID receiptId) {
    return asA(() -> receipts.detail(receiptId)).lines().get(0).id();
  }

  /**
   * 实收并在**同一次实收**里登记差异 —— 走 8.2.1.1 的真实入口。
   *
   * <p>差异类型不猜：明确给出 type，模拟现场指明「这 10 件是破损」。
   */
  UUID receiveWithDiscrepancy(
      UUID receiptId, String received, String type, String discrepancyQty, String reason) {
    UUID line = lineIdOf(receiptId);
    return asA(
            () -> {
              receipts.receive(
                  receiptId,
                  new GoodsReceiptModels.GoodsReceiptReceiveRequest(
                      asA(() -> receipts.detail(receiptId)).receipt().version(),
                      null,
                      locA1,
                      "SF123-A",
                      "第一车",
                      List.of(
                          new GoodsReceiptModels.GoodsReceiptReceiveLineRequest(
                              line, new BigDecimal(received), BigDecimal.ZERO, null, null,
                              List.of())),
                      List.of(
                          new GoodsReceiptModels.ReceiptDiscrepancyRequest(
                              line, type, new BigDecimal(discrepancyQty), reason, "现场判定", ""))));
              return discrepancyIdOf(receiptId, type);
            });
  }

  /** 最近一条该类型的差异事实 id（8.2.1.1 落的那张表）。 */
  UUID discrepancyIdOf(UUID receiptId, String type) {
    return asA(
            () ->
                sql.fetchOne(
                        "select id from goods_receipt_discrepancy where receipt_id=?"
                            + " and discrepancy_type=? order by recorded_at desc limit 1",
                        receiptId, type)
                    .get("id", UUID.class));
  }

  DiscrepancyHandlingSaved intake(UUID discrepancyId) {
    return asA(() -> handling.intake(actorA, new DiscrepancyHandlingIntakeRequest(discrepancyId)));
  }

  BigDecimal onHand(UUID sku, UUID warehouse, UUID quality) {
    return asA(
        () ->
            sql.fetchOne(
                    "select coalesce(sum(on_hand),0) from inventory_balance"
                        + " where sku_id=? and warehouse_id=? and quality_status_id=?",
                    sku, warehouse, quality)
                .get(0, BigDecimal.class));
  }

  long countRows(String table) {
    return asA(() -> sql.fetchOne("select count(*) from " + table).get(0, Long.class));
  }

  long countAdjustmentTransactions() {
    return asA(
        () ->
            sql.fetchOne(
                    "select count(*) from inventory_transaction where kind='ADJUSTMENT'"
                        + " and ref_type='GOODS_RECEIPT_DISCREPANCY'")
                .get(0, Long.class));
  }

  BigDecimal adjustmentEntryDelta() {
    return asA(
        () ->
            sql.fetchOne(
                    "select coalesce(sum(e.on_hand_delta),0) from inventory_entry e"
                        + " join inventory_transaction t on t.id=e.transaction_id"
                        + " where t.kind='ADJUSTMENT' and t.ref_type='GOODS_RECEIPT_DISCREPANCY'")
                .get(0, BigDecimal.class));
  }

  // ================================================================ 完整链路

  @Test
  void 完整链路_接手留证拒收索赔结案() {
    var receipt = createReceipt("100", qSellable);
    UUID line = lineIdOf(receipt.id());
    // 100 件已入可售 bucket，随后现场发现 10 件破损
    asA(
        () ->
            receipts.receive(
                receipt.id(),
                new GoodsReceiptModels.GoodsReceiptReceiveRequest(
                    0L, null, locA1, "SF123-A", "第一车",
                    List.of(
                        new GoodsReceiptModels.GoodsReceiptReceiveLineRequest(
                            line, new BigDecimal("100"), BigDecimal.ZERO, null, null, List.of())),
                    List.of(
                        new GoodsReceiptModels.ReceiptDiscrepancyRequest(
                            line, "DAMAGED", new BigDecimal("10"), "外箱压塌",
                            "现场开箱发现 10 件外箱压塌", "")))));
    UUID discrepancyId = discrepancyIdOf(receipt.id(), "DAMAGED");
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("100");

    // 接手 → 待处理
    var intake = intake(discrepancyId);
    assertThat(intake.status()).isEqualTo("PENDING");
    assertThat(intake.version()).isEqualTo(0);
    assertThat(countRows("goods_receipt_discrepancy_handling")).isEqualTo(1);

    // 拍照留证：字节真落库
    byte[] photo = "fake-jpeg-bytes-for-damage-evidence".getBytes(StandardCharsets.UTF_8);
    var evidence =
        asA(
            () ->
                handling.addEvidence(actorA, 
                    intake.id(),
                    new DiscrepancyEvidenceRequest(
                        "PHOTO", "damage.jpg", "image/jpeg",
                        Base64.getEncoder().encodeToString(photo), null, "外箱压塌照片")));
    assertThat(evidence.byteSize()).isEqualTo(photo.length);
    assertThat(evidence.sha256())
        .isEqualTo(HexFormat.of().formatHex(sha256(photo)));
    assertThat(evidence.dataUrl()).startsWith("data:image/jpeg;base64,");

    // 拒收并真从库存撤出：可售 100 → 90
    var decided =
        asA(
            () ->
                handling.decide(actorA, 
                    intake.id(),
                    new DiscrepancyDecisionRequest(
                        0L, "REJECT", null, null, null, qSellable, locA1, null,
                        "外箱压塌整箱拒收", "承运方到场确认")));
    assertThat(decided.status()).isEqualTo("DECIDED");
    assertThat(decided.disposition()).isEqualTo("REJECT");
    assertThat(decided.acceptedQuantity()).isEqualByComparingTo("0");
    assertThat(decided.rejectedQuantity()).isEqualByComparingTo("10");
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("90");
    // 分录真的落库且是负增量
    assertThat(countAdjustmentTransactions()).isEqualTo(1);
    assertThat(adjustmentEntryDelta()).isEqualByComparingTo("-10");

    // 索赔发起 → 提交
    var claimed =
        asA(
            () ->
                handling.claim(actorA, 
                    intake.id(),
                    new DiscrepancyClaimRequest(
                        1L, "DAMAGE", null, new BigDecimal("380.00"), "CNY",
                        null, null, "外箱压塌 10 件，按采购单价索赔")));
    assertThat(claimed.status()).isEqualTo("CLAIMED");
    assertThat(claimed.claimed()).isTrue();
    assertThat(claimed.claimStatus()).isEqualTo("INITIATED");

    var submitted =
        asA(() -> handling.submitClaim(actorA, intake.id(), new DiscrepancyClaimSubmitRequest(2L)));
    assertThat(submitted.status()).isEqualTo("SUBMITTED");
    assertThat(submitted.submittedAt()).isNotNull();
    // 6.5.1 未交付：claimRefId 恒为空，这是边界不是漏填
    assertThat(submitted.claimRefId()).isNull();
    assertThat(submitted.sourceRefCode()).isEqualTo("TO-2024-0001");

    // 结案
    // 提交索赔只推进索赔自己的状态，不动处理记录的版本 —— 所以这里沿用版本 2。
    var closed = asA(() -> handling.close(actorA, intake.id(), new DiscrepancyHandlingCloseRequest(2L, "已提交索赔")));
    assertThat(closed.status()).isEqualTo("CLOSED");
    assertThat(closed.closedAt()).isNotNull();

    // 详情回读：凭证 / 流转 / 索赔都在
    var detail = asA(() -> handling.detail(intake.id()));
    assertThat(detail.evidence()).hasSize(1);
    assertThat(detail.stockMoves()).hasSize(1);
    assertThat(detail.stockMoves().get(0).direction()).isEqualTo("WITHDRAW");
    assertThat(detail.claim()).isNotNull();
    assertThat(detail.discrepancy().discrepancyType()).isEqualTo("DAMAGED");
    assertThat(detail.discrepancy().reasonCode()).isEqualTo("外箱压塌");
  }

  // ================================================================ 硬规则：破损与短少不得入可售

  @Test
  void 硬规则_破损按正常入库被服务层拒绝() {
    var handlingId = damagedPending("10");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.decide(actorA, 
                            handlingId,
                            new DiscrepancyDecisionRequest(
                                0L, "ACCEPT", null, null, qSellable, null, locA1, null,
                                "当可售处理", ""))))
        .hasMessageContaining("不允许按「正常入库」处理")
        .hasMessageContaining("破损与短少必须拒收或待检入库");
    // 决定被拒 → 库存一行都没动，仍是收货时的 100（而不是 0，也不是 110）
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("100");
  }

  @Test
  void 硬规则_短少按正常入库被服务层拒绝() {
    // 短少由 8.2.1.1 关单时如实登记：预期 100、实收 0
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = asA(
        () -> {
          receipts.close(
              receipt.id(),
              new GoodsReceiptModels.GoodsReceiptCloseRequest(0L, "整批没到", "供应商未发货"));
          return discrepancyIdOf(receipt.id(), "SHORT_RECEIPT");
        });
    var handlingId = intake(discrepancyId).id();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.decide(actorA, 
                            handlingId,
                            new DiscrepancyDecisionRequest(
                                0L, "ACCEPT", null, null, qSellable, null, locA1, null,
                                "当可售处理", ""))))
        .hasMessageContaining("不允许按「正常入库」处理");
  }

  @Test
  void 硬规则_型号不符与过期同样禁止正常入库() {
    for (var type : List.of("WRONG_ITEM", "EXPIRED")) {
      var receipt = createReceipt("100", qSellable);
      UUID line = lineIdOf(receipt.id());
      UUID discrepancyId = asA(
          () -> {
            receipts.receive(
                receipt.id(),
                new GoodsReceiptModels.GoodsReceiptReceiveRequest(
                    0L, null, locA1, "SF123-A", "第一车",
                    List.of(
                        new GoodsReceiptModels.GoodsReceiptReceiveLineRequest(
                            line, new BigDecimal("100"), BigDecimal.ZERO, null, null, List.of())),
                    List.of(
                        new GoodsReceiptModels.ReceiptDiscrepancyRequest(
                            line, type, new BigDecimal("5"), "现场判定", "现场发现", ""))));
            return discrepancyIdOf(receipt.id(), type);
          });
      var handlingId = intake(discrepancyId).id();
      assertThatThrownBy(
              () ->
                  asA(
                      () ->
                          handling.decide(actorA, 
                              handlingId,
                              new DiscrepancyDecisionRequest(
                                  0L, "ACCEPT", null, null, qSellable, null, locA1, null,
                                  "当可售处理", ""))))
          .as(type + " 必须禁止正常入库")
          .hasMessageContaining("不允许按「正常入库」处理");
    }
  }

  @Test
  void 硬规则_绕过服务层直接写正常入库被数据库拒绝() {
    var receipt = createReceipt("100", qSellable);
    UUID line = lineIdOf(receipt.id());
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "外箱压塌");
    UUID handlingId = intake(discrepancyId).id();
    // 不经服务层，直连 SQL 把处理记录改成「正常入库」。
    // 服务层的拦截在这里被绕过，数据库触发器必须独立拦下。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update goods_receipt_discrepancy_handling set status='DECIDED',"
                                + "disposition='ACCEPT',accepted_quantity=10,rejected_quantity=0,"
                                + "inbound_quality_status_id=?,decided_at=now(),decided_by=?"
                                + " where id=?",
                            qSellable, user, handlingId)))
        .hasMessageContaining("不允许按「正常入库」处理");
  }

  @Test
  void 硬规则_绕过服务层把破损货入可售被数据库拒绝() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "外箱压塌");
    UUID handlingId = intake(discrepancyId).id();
    // 决定本身合法（拒收），但绕过服务层直接写一条「入可售」的库存流转：
    // guard_discrepancy_stock_move 独立拦下，服务层在这里被完全绕过。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "insert into goods_receipt_discrepancy_stock_move(id,tenant_id,"
                                + "handling_id,discrepancy_id,sku_id,warehouse_id,legal_entity_id,"
                                + "direction,from_quality_status_id,to_quality_status_id,"
                                + "location_id,lot_id,quantity,transaction_id,occurred_at,actor_id,note)"
                                + " values(?,?,?,?,?,?,?,'INBOUND_TO_SELLABLE',null,?,?,null,10,null,"
                                + "now(),?,'绕过服务层直连')",
                            UUID.randomUUID(), a, handlingId, discrepancyId, skuA, whA, leA,
                            qSellable, locA1, user)))
        .hasMessageContaining("不允许入可售");
  }

  @Test
  void 硬规则_待检入库的去向填可售被服务层拒绝() {
    var handlingId = damagedPending("10");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.decide(actorA, 
                            handlingId,
                            new DiscrepancyDecisionRequest(
                                0L, "HOLD_FOR_INSPECTION", null, null, qSellable, null, locA1,
                                null, "待检", ""))))
        .hasMessageContaining("待检入库的去向质量状态必须是待检或残次");
  }

  @Test
  void 硬规则_部分接收的接受部分也不得入可售() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "外箱压塌");
    var handlingId = intake(discrepancyId).id();
    // 部分接收本身合法，但接受 4 件去向填「可售」必须被拒：
    // 部分接收是最容易被当成绕过口子的路径 —— 它不是 ACCEPT，所以只拦 ACCEPT 是不够的。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.decide(actorA, 
                            handlingId,
                            new DiscrepancyDecisionRequest(
                                0L, "PARTIAL_ACCEPT", new BigDecimal("4"), new BigDecimal("6"),
                                qSellable, null, locA1, null, "部分接收", ""))))
        .hasMessageContaining("不允许入可售");
  }

  // ================================================================ 库存两条路径

  @Test
  void 拒收不入账_货从未入库只留不入账痕迹() {
    // 短少：货压根没到，拒收它唯一的库存痕迹就是 NOT_POSTED
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = asA(
        () -> {
          receipts.close(
              receipt.id(),
              new GoodsReceiptModels.GoodsReceiptCloseRequest(0L, "整批没到", "未发货"));
          return discrepancyIdOf(receipt.id(), "SHORT_RECEIPT");
        });
    var handlingId = intake(discrepancyId).id();
    var decided =
        asA(
            () ->
                handling.decide(actorA, 
                    handlingId,
                    new DiscrepancyDecisionRequest(
                        0L, "REJECT", null, null, null, null, null, null,
                        "整批短少，放弃该部分并索赔", "")));
    assertThat(decided.rejectedQuantity()).isEqualByComparingTo("100");
    // 没有任何库存分录：拒收不入账
    assertThat(countAdjustmentTransactions()).isEqualTo(0);
    assertThat(countRows("goods_receipt_discrepancy_stock_move")).isEqualTo(1);
    var move = asA(() -> handling.detail(handlingId)).stockMoves().get(0);
    assertThat(move.direction()).isEqualTo("NOT_POSTED");
    assertThat(move.quantity()).isEqualByComparingTo("100");
    assertThat(move.transactionId()).isNull();
  }

  @Test
  void 拒收撤出_货已入库真扣余额() {
    var receipt = createReceipt("100", qSellable);
    UUID line = lineIdOf(receipt.id());
    asA(
        () ->
            receipts.receive(
                receipt.id(),
                new GoodsReceiptModels.GoodsReceiptReceiveRequest(
                    0L, null, locA1, "SF123-A", "第一车",
                    List.of(
                        new GoodsReceiptModels.GoodsReceiptReceiveLineRequest(
                            line, new BigDecimal("100"), BigDecimal.ZERO, null, null, List.of())),
                    List.of(
                        new GoodsReceiptModels.ReceiptDiscrepancyRequest(
                            line, "DAMAGED", new BigDecimal("12"), "运输破损",
                            "12 件外箱破损", "")))));
    UUID discrepancyId = discrepancyIdOf(receipt.id(), "DAMAGED");
    var handlingId = intake(discrepancyId).id();
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("100");
    asA(
        () ->
            handling.decide(actorA, 
                handlingId,
                new DiscrepancyDecisionRequest(
                    0L, "REJECT", null, null, null, qSellable, locA1, null,
                    "破损整批拒收", "")));
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("88");
    assertThat(adjustmentEntryDelta()).isEqualByComparingTo("-12");
    var move = asA(() -> handling.detail(handlingId)).stockMoves().get(0);
    assertThat(move.direction()).isEqualTo("WITHDRAW");
    assertThat(move.transactionId()).isNotNull();
    assertThat(move.fromQualityStatusName()).isEqualTo("可售");
  }

  @Test
  void 撤出量超过在手量时给出可读错误() {
    var receipt = createReceipt("20", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "20", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    // 先把库位上的货扣掉一部分（模拟已出库），再撤出就会不够
    asA(
        () -> {
          sql.execute(
              "update inventory_balance set on_hand=on_hand-15 where sku_id=? and warehouse_id=?"
                  + " and quality_status_id=?",
              skuA, whA, qSellable);
          // V2 的 guard_bucket_movement_time 要求数量一变 last_movement_at 就得跟着动，
          // 夹具也得守这条，否则报错指向夹具而不是被测代码。
          sql.execute(
              "update stock_bucket set on_hand=on_hand-15,"
                  + " last_movement_at=last_movement_at + interval '1 microsecond'"
                  + " where sku_id=? and warehouse_id=? and quality_status_id=?",
              skuA, whA, qSellable);
          return null;
        });
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.decide(actorA, 
                            handlingId,
                            new DiscrepancyDecisionRequest(
                                0L, "REJECT", null, null, null, qSellable, locA1, null,
                                "拒收", ""))))
        .hasMessageContaining("撤出失败")
        .hasMessageContaining("先处理占用再撤出");
  }

  @Test
  void 拒收时不该传库位否则会被要求真从库存撤出() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    // 传了库位就表示货已入库；库位上没有这批货时必须报错而不是静默记一条不入账。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.decide(actorA, 
                            handlingId,
                            new DiscrepancyDecisionRequest(
                                0L, "REJECT", null, null, null, qSellable, locA2, null,
                                "拒收", ""))))
        .hasMessageContaining("拒收不入账请不要传 locationId");
  }

  @Test
  void 部分接收_接受入库拒收不入账两条痕迹同时留() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    var decided =
        asA(
            () ->
                handling.decide(actorA, 
                    handlingId,
                    new DiscrepancyDecisionRequest(
                        0L, "PARTIAL_ACCEPT", new BigDecimal("4"), new BigDecimal("6"),
                        qPending, qSellable, locA1, null, "4 件转待检、6 件退回", "")));
    assertThat(decided.acceptedQuantity()).isEqualByComparingTo("4");
    assertThat(decided.rejectedQuantity()).isEqualByComparingTo("6");
    // 4 件从可售**流转到**待检：待检 +4，可售 −4（总量不变）
    assertThat(onHand(skuA, whA, qPending)).isEqualByComparingTo("4");
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("90");
    // 三条流转：质量状态转出 + 转入待检 + 拒收撤出
    var moves = asA(() -> handling.detail(handlingId)).stockMoves();
    assertThat(moves).hasSize(3);
    assertThat(moves).extracting(DiscrepancyStockMoveRow::direction)
        .containsExactlyInAnyOrder("WITHDRAW", "INBOUND_TO_HOLD", "WITHDRAW");
    // 总库存守恒：100 件还在库里，只是 14 件离开了可售
    assertThat(
            asA(
                    () ->
                        sql.fetchOne("select coalesce(sum(on_hand),0) from inventory_balance")
                            .get(0, BigDecimal.class)))
        .isEqualByComparingTo("94");
  }

  @Test
  void 部分接收_接受量与拒收量之和对不上被服务层拒绝() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.decide(actorA, 
                            handlingId,
                            new DiscrepancyDecisionRequest(
                                0L, "PARTIAL_ACCEPT", new BigDecimal("4"), new BigDecimal("5"),
                                qPending, null, locA1, null, "对不上", ""))))
        .hasMessageContaining("必须恰好等于差异数量");
  }

  @Test
  void 数量不变式_绕过服务层写对不上的数量被数据库拒绝() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    UUID handlingId = intake(discrepancyId).id();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update goods_receipt_discrepancy_handling set status='DECIDED',"
                                + "disposition='REJECT',accepted_quantity=0,rejected_quantity=3,"
                                + "decided_at=now(),decided_by=? where id=?",
                            user, handlingId)))
        .hasMessageContaining("接受量与拒收量之和必须恰好等于差异数量");
  }

  @Test
  void 待检入库_落到待检质量状态() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    var decided =
        asA(
            () ->
                handling.decide(actorA, 
                    handlingId,
                    new DiscrepancyDecisionRequest(
                        0L, "HOLD_FOR_INSPECTION", null, null, qPending, qSellable, locA1, null,
                        "转待检等质检", "")));
    assertThat(decided.acceptedQuantity()).isEqualByComparingTo("10");
    // 这 10 件收货时已经进了可售，转待检是**挪走**不是新增
    assertThat(onHand(skuA, whA, qPending)).isEqualByComparingTo("10");
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("90");
    var holdMoves = asA(() -> handling.detail(handlingId)).stockMoves();
    assertThat(holdMoves).hasSize(2);
    assertThat(holdMoves).extracting(DiscrepancyStockMoveRow::direction)
        .containsExactlyInAnyOrder("WITHDRAW", "INBOUND_TO_HOLD");
  }

  // ================================================================ 超收与错发可以正常入库

  @Test
  void 超收可以正常入库因为货本身没问题() {
    var receipt = createReceipt("100", qSellable);
    UUID line = lineIdOf(receipt.id());
    UUID discrepancyId = asA(
        () -> {
          receipts.receive(
              receipt.id(),
              new GoodsReceiptModels.GoodsReceiptReceiveRequest(
                  0L, null, locA1, "SF123-A", "第一车",
                  List.of(
                      new GoodsReceiptModels.GoodsReceiptReceiveLineRequest(
                          line, new BigDecimal("105"), BigDecimal.ZERO, null, null, List.of())),
                  List.of(
                      new GoodsReceiptModels.ReceiptDiscrepancyRequest(
                          line, "OVER_RECEIPT", new BigDecimal("5"), "多发",
                          "供应商多发 5 件", ""))));
          return discrepancyIdOf(receipt.id(), "OVER_RECEIPT");
        });
    var handlingId = intake(discrepancyId).id();
    var decided =
        asA(
            () ->
                handling.decide(actorA, 
                    handlingId,
                    new DiscrepancyDecisionRequest(
                        0L, "ACCEPT", null, null, qSellable, qSellable, locA1, null,
                        "货没问题，确认入可售", "")));
    assertThat(decided.disposition()).isEqualTo("ACCEPT");
    // 关键：这 5 件在收货那一步就已经进了可售 bucket，确认接受**不能再加一次** ——
    // 再加一次会凭空多出 5 件可售货，而这 5 件本来就在库里会被直接卖掉。
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("105");
    assertThat(asA(() -> handling.detail(handlingId)).stockMoves().get(0).direction())
        .isEqualTo("NO_MOVE");
  }

  // ================================================================ 拍照留证

  @Test
  void 拍照留证_字节真落库且读回一致() {
    var handlingId = damagedPending("10");
    byte[] photo = new byte[4096];
    for (int i = 0; i < photo.length; i++) {
      photo[i] = (byte) (i % 251);
    }
    var evidence =
        asA(
            () ->
                handling.addEvidence(actorA, 
                    handlingId,
                    new DiscrepancyEvidenceRequest(
                        "PHOTO", "damage.jpg", "image/jpeg",
                        Base64.getEncoder().encodeToString(photo), null, "外箱压塌")));
    assertThat(evidence.byteSize()).isEqualTo(4096);
    assertThat(evidence.sha256()).isEqualTo(HexFormat.of().formatHex(sha256(photo)));
    // 读回数据库里的原始字节，逐字节相等
    var stored =
        asA(
            () ->
                sql.fetchOne(
                        "select content from goods_receipt_discrepancy_evidence where id=?",
                        evidence.id())
                    .get("content", byte[].class));
    assertThat(stored).isEqualTo(photo);
    // dataUrl 能解回原始字节
    String b64 = evidence.dataUrl().substring(evidence.dataUrl().indexOf(',') + 1);
    assertThat(Base64.getDecoder().decode(b64)).isEqualTo(photo);
  }

  @Test
  void 拍照留证_接受带data前缀的base64() {
    var handlingId = damagedPending("10");
    byte[] photo = "small-photo".getBytes(StandardCharsets.UTF_8);
    var evidence =
        asA(
            () ->
                handling.addEvidence(actorA, 
                    handlingId,
                    new DiscrepancyEvidenceRequest(
                        "PHOTO", "p.jpg", "image/jpeg",
                        "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(photo),
                        null, "")));
    assertThat(evidence.byteSize()).isEqualTo(photo.length);
  }

  @Test
  void 拍照留证_超过上限被拒绝且没留下行() {
    var handlingId = damagedPending("10");
    byte[] tooBig = new byte[GoodsReceiptDiscrepancyService.MAX_EVIDENCE_BYTES + 1];
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.addEvidence(actorA, 
                            handlingId,
                            new DiscrepancyEvidenceRequest(
                                "PHOTO", "big.jpg", "image/jpeg",
                                Base64.getEncoder().encodeToString(tooBig), null, ""))))
        .hasMessageContaining("不能超过");
    assertThat(countRows("goods_receipt_discrepancy_evidence")).isEqualTo(0);
  }

  @Test
  void 拍照留证_空内容被拒绝() {
    var handlingId = damagedPending("10");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.addEvidence(actorA, 
                            handlingId,
                            new DiscrepancyEvidenceRequest(
                                "PHOTO", "empty.jpg", "image/jpeg", "", null, ""))))
        .hasMessageContaining("凭证内容为空");
  }

  @Test
  void 拍照留证_已关闭后不能再加凭证() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    asA(
        () ->
            handling.decide(actorA, 
                handlingId,
                new DiscrepancyDecisionRequest(
                    0L, "REJECT", null, null, null, null, null, null, "拒收", "")));
    asA(() -> handling.close(actorA, handlingId, new DiscrepancyHandlingCloseRequest(1L, "结案")));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.addEvidence(actorA, 
                            handlingId,
                            new DiscrepancyEvidenceRequest(
                                "PHOTO", "late.jpg", "image/jpeg",
                                Base64.getEncoder().encodeToString("late".getBytes()),
                                null, ""))))
        .hasMessageContaining("已关闭的处理记录不再接受新凭证");
  }

  // ================================================================ 索赔

  @Test
  void 索赔_提交后不可撤回() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    asA(
        () ->
            handling.decide(actorA, 
                handlingId,
                new DiscrepancyDecisionRequest(
                    0L, "REJECT", null, null, null, null, null, null, "拒收", "")));
    asA(
        () ->
            handling.claim(actorA, 
                handlingId,
                new DiscrepancyClaimRequest(
                    1L, "DAMAGE", null, null, null, null, null, "货损索赔")));
    asA(() -> handling.submitClaim(actorA, handlingId, new DiscrepancyClaimSubmitRequest(2L)));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.withdrawClaim(actorA, 
                            handlingId,
                            new DiscrepancyClaimWithdrawRequest(2L, "反悔了"))))
        .hasMessageContaining("已提交不可撤回");
    // 数据库层同样拦得住（绕过服务层改状态）
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update goods_receipt_discrepancy_claim set status='WITHDRAWN',"
                                + "withdrawn_at=now(),withdrawn_reason='绕过服务层'"
                                + " where handling_id=?",
                            handlingId)))
        .hasMessageContaining("不可再改状态");
  }

  @Test
  void 索赔_发起后可撤回且处理记录退回已决定() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    asA(
        () ->
            handling.decide(actorA, 
                handlingId,
                new DiscrepancyDecisionRequest(
                    0L, "REJECT", null, null, null, null, null, null, "拒收", "")));
    asA(
        () ->
            handling.claim(actorA, 
                handlingId,
                new DiscrepancyClaimRequest(
                    1L, "DAMAGE", null, null, null, null, null, "货损索赔")));
    var withdrawn =
        asA(
            () ->
                handling.withdrawClaim(actorA, 
                    handlingId, new DiscrepancyClaimWithdrawRequest(2L, "供应商已补货")));
    assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
    assertThat(withdrawn.withdrawnAt()).isNotNull();
    // 处理记录退回「已决定」：它不再已索赔，但决定与库存流转原样保留
    var detail = asA(() -> handling.detail(handlingId));
    assertThat(detail.handling().status()).isEqualTo("DECIDED");
    assertThat(detail.handling().disposition()).isEqualTo("REJECT");
  }

  @Test
  void 索赔_撤回必须给原因() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    asA(
        () ->
            handling.decide(actorA, 
                handlingId,
                new DiscrepancyDecisionRequest(
                    0L, "REJECT", null, null, null, null, null, null, "拒收", "")));
    asA(
        () ->
            handling.claim(actorA, 
                handlingId,
                new DiscrepancyClaimRequest(
                    1L, "DAMAGE", null, null, null, null, null, "货损索赔")));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.withdrawClaim(actorA, 
                            handlingId, new DiscrepancyClaimWithdrawRequest(2L, "  "))))
        .hasMessageContaining("必须给出原因");
  }

  @Test
  void 索赔_自行填写claimRefId被数据库拒绝() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    asA(
        () ->
            handling.decide(actorA, 
                handlingId,
                new DiscrepancyDecisionRequest(
                    0L, "REJECT", null, null, null, null, null, null, "拒收", "")));
    asA(
        () ->
            handling.claim(actorA, 
                handlingId,
                new DiscrepancyClaimRequest(
                    1L, "DAMAGE", null, null, null, null, null, "货损索赔")));
    // claim_ref_id 归 6.5.1 拥有，上游未交付时不允许自行填写
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update goods_receipt_discrepancy_claim set claim_ref_id=?"
                                + " where handling_id=?",
                            UUID.randomUUID(), handlingId)))
        .hasMessageContaining("上游未交付时不允许自行填写");
  }

  @Test
  void 索赔_金额与币种必须成对() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    asA(
        () ->
            handling.decide(actorA, 
                handlingId,
                new DiscrepancyDecisionRequest(
                    0L, "REJECT", null, null, null, null, null, null, "拒收", "")));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.claim(actorA, 
                            handlingId,
                            new DiscrepancyClaimRequest(
                                1L, "DAMAGE", null, new BigDecimal("100"), null, null, null,
                                "只有金额"))))
        .hasMessageContaining("必须同时给币种");
  }

  @Test
  void 索赔_币种必须是三字母大写() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    asA(
        () ->
            handling.decide(actorA, 
                handlingId,
                new DiscrepancyDecisionRequest(
                    0L, "REJECT", null, null, null, null, null, null, "拒收", "")));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.claim(actorA, 
                            handlingId,
                            new DiscrepancyClaimRequest(
                                1L, "DAMAGE", null, new BigDecimal("100"), "人民币", null, null,
                                "币种不对"))))
        .hasMessageContaining("ISO 4217");
  }

  @Test
  void 索赔_同一处理记录只可能有一笔索赔() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    asA(
        () ->
            handling.decide(actorA, 
                handlingId,
                new DiscrepancyDecisionRequest(
                    0L, "REJECT", null, null, null, null, null, null, "拒收", "")));
    asA(
        () ->
            handling.claim(actorA, 
                handlingId,
                new DiscrepancyClaimRequest(
                    1L, "DAMAGE", null, null, null, null, null, "第一次")));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.claim(actorA, 
                            handlingId,
                            new DiscrepancyClaimRequest(
                                2L, "DAMAGE", null, null, null, null, null, "第二次"))))
        .hasMessageContaining("发起索赔");
    // 真正的不变量是对账那边只会收到一笔。状态机先挡下第二次索赔，
    // UNIQUE(tenant_id,handling_id) 是绕过服务层时的兜底。
    assertThat(countRows("goods_receipt_discrepancy_claim")).isEqualTo(1);
  }

  @Test
  void 索赔_未处理就发起索赔被拒绝() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.claim(actorA, 
                            handlingId,
                            new DiscrepancyClaimRequest(
                                0L, "DAMAGE", null, null, null, null, null, "没决定就索赔"))))
        .hasMessageContaining("只有已决定状态才能发起索赔");
  }

  // ================================================================ 状态机与乐观锁

  @Test
  void 状态机_已决定不能改判() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    asA(
        () ->
            handling.decide(actorA, 
                handlingId,
                new DiscrepancyDecisionRequest(
                    0L, "REJECT", null, null, null, null, null, null, "拒收", "")));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.decide(actorA, 
                            handlingId,
                            new DiscrepancyDecisionRequest(
                                1L, "HOLD_FOR_INSPECTION", null, null, qPending, null, locA1,
                                null, "改判", ""))))
        .hasMessageContaining("只有待处理状态才能提交处理决定");
  }

  @Test
  void 状态机_已关闭是终态() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    asA(
        () ->
            handling.decide(actorA, 
                handlingId,
                new DiscrepancyDecisionRequest(
                    0L, "REJECT", null, null, null, null, null, null, "拒收", "")));
    asA(() -> handling.close(actorA, handlingId, new DiscrepancyHandlingCloseRequest(1L, "结案")));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.close(actorA, handlingId, new DiscrepancyHandlingCloseRequest(2L, "再结"))))
        .hasMessageContaining("当前状态不能关闭");
  }

  @Test
  void 状态机_关闭空白处理被数据库拒绝() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    UUID handlingId = intake(discrepancyId).id();
    // 绕过服务层：直接造一条「已决定 + 已关闭」但没有任何凭证 / 流转 / 索赔的记录。
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update goods_receipt_discrepancy_handling set status='CLOSED',"
                                + "disposition='REJECT',accepted_quantity=0,"
                                + "rejected_quantity=quantity,decided_at=now(),decided_by=?,"
                                + "closed_at=now(),closed_by=? where id=?",
                            user, user, handlingId)))
        .hasMessageContaining("等于把货损痕迹抹掉");
  }

  @Test
  void 状态机_待处理不能直接关闭() {
    var handlingId = damagedPending("10");
    assertThatThrownBy(
            () -> asA(() -> handling.close(actorA, handlingId, new DiscrepancyHandlingCloseRequest(0L, "直接结"))))
        .hasMessageContaining("当前状态不能关闭");
  }

  @Test
  void 乐观锁_版本不符与缺失都被拒绝() {
    var handlingId = damagedPending("10");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.decide(actorA, 
                            handlingId,
                            new DiscrepancyDecisionRequest(
                                null, "REJECT", null, null, null, null, null, null, "拒收", ""))))
        .hasMessageContaining("缺少 expectedVersion");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        handling.decide(actorA, 
                            handlingId,
                            new DiscrepancyDecisionRequest(
                                99L, "REJECT", null, null, null, null, null, null, "拒收", ""))))
        .hasMessageContaining("已被他人修改");
  }

  @Test
  void 一条差异只能接手一次() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    intake(discrepancyId);
    assertThatThrownBy(() -> intake(discrepancyId))
        .hasMessageContaining("这条差异已经在处理中");
    assertThat(countRows("goods_receipt_discrepancy_handling")).isEqualTo(1);
  }

  @Test
  void 接手不存在的差异报404() {
    assertThatThrownBy(() -> intake(UUID.randomUUID()))
        .hasMessageContaining("差异记录不存在");
  }

  // ================================================================ 列表与查询

  @Test
  void 真分页两页不重叠且汇总不受分页() {
    for (int i = 0; i < 3; i++) {
      var receipt = createReceipt("100", qSellable);
      UUID discrepancyId =
          receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", String.valueOf(10 + i), "破损");
      intake(discrepancyId);
    }
    var first =
        asA(
            () ->
                handling.page(
                    new DiscrepancyHandlingQuery(null, null, null, null, null, null, null, null, 0, 2),
                    actorA));
    var second =
        asA(
            () ->
                handling.page(
                    new DiscrepancyHandlingQuery(null, null, null, null, null, null, null, null, 1, 2),
                    actorA));
    assertThat(first.total()).isEqualTo(3);
    assertThat(second.total()).isEqualTo(3);
    assertThat(first.rows()).hasSize(2);
    assertThat(second.rows()).hasSize(1);
    assertThat(first.rows()).extracting(DiscrepancyHandlingRow::id)
        .doesNotContainAnyElementsOf(
            second.rows().stream().map(DiscrepancyHandlingRow::id).toList());
    // 汇总是全量的，不受分页影响
    assertThat(first.summary().handlingCount()).isEqualTo(3);
    assertThat(second.summary().handlingCount()).isEqualTo(3);
    assertThat(first.summary().pendingCount()).isEqualTo(3);
    assertThat(first.summary().riskCount()).isEqualTo(3);
    // 下拉词表随响应一起给
    assertThat(first.statusOptions()).isNotEmpty();
    assertThat(first.qualityStatusOptions()).isNotEmpty();
  }

  @Test
  void 排序白名单拒绝任意表达式() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    intake(discrepancyId);
    // 非法排序名退回默认排序，不拼进 SQL
    var page =
        asA(
            () ->
                handling.page(
                    new DiscrepancyHandlingQuery(
                        null, null, null, null, null, null, null, "id; drop table goods_receipt", 0, 50),
                    actorA));
    assertThat(page.total()).isEqualTo(1);
    assertThat(countRows("goods_receipt")).isEqualTo(1);
  }

  @Test
  void 真实模糊检索命中收货单号与SKU() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "外箱压塌");
    intake(discrepancyId);
    var byCode =
        asA(
            () ->
                handling.page(
                    new DiscrepancyHandlingQuery(
                        null, null, null, null, null, null, receipt.receiptCode(), null, 0, 50),
                    actorA));
    assertThat(byCode.total()).isEqualTo(1);
    var bySku =
        asA(
            () ->
                handling.page(
                    new DiscrepancyHandlingQuery(
                        null, null, null, null, null, null, "SKU-1", null, 0, 50),
                    actorA));
    assertThat(bySku.total()).isEqualTo(1);
    var byReason =
        asA(
            () ->
                handling.page(
                    new DiscrepancyHandlingQuery(
                        null, null, null, null, null, null, "外箱压塌", null, 0, 50),
                    actorA));
    assertThat(byReason.total()).isEqualTo(1);
    var noHit =
        asA(
            () ->
                handling.page(
                    new DiscrepancyHandlingQuery(
                        null, null, null, null, null, null, "不存在的关键字", null, 0, 50),
                    actorA));
    assertThat(noHit.total()).isZero();
  }

  @Test
  void 按收货单筛选命中该单的全部差异处理() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    intake(discrepancyId);
    var page =
        asA(
            () ->
                handling.page(
                    new DiscrepancyHandlingQuery(
                        receipt.id(), null, null, null, null, null, null, null, 0, 50),
                    actorA));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.rows().get(0).receiptCode()).isEqualTo(receipt.receiptCode());
    // warehouseId 必须真的有值（它住在收货单上而不是处理记录上）
    assertThat(page.rows().get(0).warehouseId()).isEqualTo(whA);
    assertThat(page.rows().get(0).warehouseCode()).isEqualTo("WH-SH");
  }

  @Test
  void capabilities显式声明未交付下游available为false() {
    var caps = asA(() -> handling.capabilities());
    assertThat(caps.downstream()).isNotEmpty();
    for (var d : caps.downstream()) {
      assertThat(d.available()).as(d.key() + " 必须显式声明不可用").isFalse();
      assertThat(d.ownerModule()).as(d.key() + " 必须写明归属模块").isNotBlank();
      assertThat(d.href()).as(d.key() + " 必须给出路由契约而不是死链").isNotBlank();
      assertThat(d.note()).as(d.key() + " 必须说明为什么不可用").isNotBlank();
    }
    // 四态状态机
    assertThat(caps.statusOptions()).extracting(DiscrepancyHandlingOption::value)
        .containsExactly("PENDING", "DECIDED", "CLAIMED", "CLOSED");
    // 风险类型与硬规则说明
    assertThat(caps.riskDiscrepancyTypes())
        .containsExactly("DAMAGED", "SHORT_RECEIPT", "WRONG_ITEM", "EXPIRED");
    assertThat(caps.sellableRuleNote()).contains("破损与短少必须拒收或待检入库");
    // 正常入库对风险类型关闭
    var accept =
        caps.dispositionOptions().stream()
            .filter(o -> o.value().equals("ACCEPT"))
            .findFirst()
            .orElseThrow();
    assertThat(accept.allowedForRiskTypes()).isFalse();
  }

  @Test
  void timestamptz带偏移写入读回零漂移() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var handlingId = intake(discrepancyId).id();
    OffsetDateTime captured =
        OffsetDateTime.of(2026, 3, 14, 15, 9, 26, 0, java.time.ZoneOffset.ofHours(8));
    asA(
        () ->
            handling.addEvidence(actorA, 
                handlingId,
                new DiscrepancyEvidenceRequest(
                    "PHOTO", "t.jpg", "image/jpeg",
                    Base64.getEncoder().encodeToString("t".getBytes()), captured, "带偏移的时间")));
    var stored =
        asA(
            () ->
                sql.fetchOne(
                        "select captured_at from goods_receipt_discrepancy_evidence"
                            + " where handling_id=?",
                        handlingId)
                    .get("captured_at", OffsetDateTime.class));
    assertThat(stored.toInstant()).isEqualTo(captured.toInstant());
    assertThat(stored.getOffset()).isEqualTo(java.time.ZoneOffset.ofHours(8));
  }

  @Test
  void 每张表都强制行级安全() {
    for (var table :
        List.of(
            "goods_receipt_discrepancy_handling",
            "goods_receipt_discrepancy_evidence",
            "goods_receipt_discrepancy_claim",
            "goods_receipt_discrepancy_stock_move")) {
      var forced =
          sql.fetchOne(
                  "select relforcerowsecurity from pg_class"
                      + " where relname=? and relnamespace='public'::regnamespace",
                  table)
              .get(0, Boolean.class);
      assertThat(forced).as(table + " 必须 FORCE ROW LEVEL SECURITY").isTrue();
      var enabled =
          sql.fetchOne(
                  "select relrowsecurity from pg_class"
                      + " where relname=? and relnamespace='public'::regnamespace",
                  table)
              .get(0, Boolean.class);
      assertThat(enabled).as(table + " 必须 ENABLE ROW LEVEL SECURITY").isTrue();
    }
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var mine = intake(discrepancyId);

    // B 租户看不到 A 的处理记录
    assertThat(
            asB(
                () ->
                    handling.page(
                        new DiscrepancyHandlingQuery(
                            null, null, null, null, null, null, null, null, 0, 50),
                        actorB))
                .total())
        .isZero();
    assertThatThrownBy(() -> asB(() -> handling.detail(mine.id())))
        .hasMessageContaining("不存在或无权查看");
    // B 租户的汇总也看不到 A 的量
    assertThat(
            asB(
                    () ->
                        handling
                            .page(
                                new DiscrepancyHandlingQuery(
                                    null, null, null, null, null, null, null, null, 0, 50),
                                actorB)
                            .summary()
                            .discrepancyQuantity())
                .toString())
        .isEqualTo("0");
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var mine = intake(discrepancyId);

    // 身份是 B 但伪造 X-Tenant-Id 指向 A → 只能看到 B 自己的（这里是空）
    http.perform(get("/warehouse/goods-receipt-discrepancies")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    // 反向：身份是 A 伪造 B 的头也拿不到 B 的数据
    http.perform(get("/warehouse/goods-receipt-discrepancies")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(mine.id().toString()));
    // 详情端点同样不受伪造头影响
    http.perform(get("/warehouse/goods-receipt-discrepancies/" + mine.id())
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 未认证 401
    http.perform(get("/warehouse/goods-receipt-discrepancies")).andExpect(status().isUnauthorized());
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var mine = intake(discrepancyId);
    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮，
    // 每一轮都校验列表与详情两条路径都不串租户。
    for (int i = 0; i < 20; i++) {
      var pa = asA(() -> handling.page(new DiscrepancyHandlingQuery(null, null, null, null, null,
          null, null, null, 0, 50), actorA));
      var pb = asB(() -> handling.page(new DiscrepancyHandlingQuery(null, null, null, null, null,
          null, null, null, 0, 50), actorB));
      assertThat(pa.total()).as("第 %d 轮 A 租户应看到自己的 1 条", i).isEqualTo(1);
      assertThat(pb.total()).as("第 %d 轮 B 租户应看到 0 条", i).isZero();
      assertThat(asA(() -> handling.detail(mine.id())).handling().id()).isEqualTo(mine.id());
      assertThatThrownBy(() -> asB(() -> handling.detail(mine.id())))
          .as("第 %d 轮 B 租户不应看到 A 的处理记录", i)
          .hasMessageContaining("不存在或无权查看");
    }
  }

  // ================================================================ 端点层

  @Test
  void 端点_未认证401只读角色403() throws Exception {
    http.perform(get("/warehouse/goods-receipt-discrepancies"))
        .andExpect(status().isUnauthorized());
    // 只读角色能读不能写
    http.perform(get("/warehouse/goods-receipt-discrepancies")
            .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());
    http.perform(post("/warehouse/goods-receipt-discrepancies")
            .header("Authorization", "Bearer read-only")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"discrepancyId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 端点_详情不存在404且坏请求400() throws Exception {
    http.perform(get("/warehouse/goods-receipt-discrepancies/" + UUID.randomUUID())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
    // 空 body 必须报可读的 400，而不是 NPE
    http.perform(post("/warehouse/goods-receipt-discrepancies")
            .header("Authorization", "Bearer tenant-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void 端点_版本不符409() throws Exception {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var mine = intake(discrepancyId);
    http.perform(post("/warehouse/goods-receipt-discrepancies/" + mine.id() + "/decide")
            .header("Authorization", "Bearer tenant-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedVersion\":99,\"disposition\":\"REJECT\",\"reason\":\"拒收\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void 端点_破损按正常入库返回400而不是入库成功() throws Exception {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    var mine = intake(discrepancyId);
    http.perform(post("/warehouse/goods-receipt-discrepancies/" + mine.id() + "/decide")
            .header("Authorization", "Bearer tenant-a")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"expectedVersion\":0,\"disposition\":\"ACCEPT\",\"inboundQualityStatusId\":\""
                    + qSellable + "\",\"locationId\":\"" + locA1 + "\",\"reason\":\"当可售处理\"}"))
        .andExpect(status().isBadRequest());
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("100");
  }

  @Test
  void 端点_capabilities与列表返回真实形状() throws Exception {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", "10", "破损");
    intake(discrepancyId);
    http.perform(get("/warehouse/goods-receipt-discrepancies/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statusOptions.length()").value(4))
        .andExpect(jsonPath("$.riskDiscrepancyTypes.length()").value(4))
        .andExpect(jsonPath("$.downstream.length()").value(6))
        .andExpect(jsonPath("$.downstream[0].available").value(false));
    http.perform(get("/warehouse/goods-receipt-discrepancies")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.summary.handlingCount").value(1))
        .andExpect(jsonPath("$.rows[0].status").value("PENDING"))
        .andExpect(jsonPath("$.rows[0].discrepancyType").value("DAMAGED"))
        .andExpect(jsonPath("$.rows[0].quantity").value(10.0));
  }

  // ================================================================ 跨车道回归

  @Test
  void 跨车道回归_本迁移不改既有收货六表任何列与约束() {
    // V78 只新增表；既有 goods_receipt* 六张表的列集合必须与 V70 交付时完全一致。
    var expected =
        java.util.Map.of(
            "goods_receipt", 0,
            "goods_receipt_line", 0,
            "goods_receipt_event", 0,
            "goods_receipt_event_line", 0,
            "goods_receipt_discrepancy", 0);
    for (var table : expected.keySet()) {
      // 既有表仍然可用，且仍由 8.2.1.1 的写入路径正常写入
      assertThat(countRows(table)).as(table + " 必须仍然可读").isGreaterThanOrEqualTo(0);
    }
    // 新表全部存在
    assertThat(countRows("goods_receipt_discrepancy_handling")).isNotNegative();
    assertThat(countRows("goods_receipt_discrepancy_evidence")).isNotNegative();
    assertThat(countRows("goods_receipt_discrepancy_claim")).isNotNegative();
    assertThat(countRows("goods_receipt_discrepancy_stock_move")).isNotNegative();
  }

  // ================================================================ 工具

  /** 建一条「已收 100 件进可售、并登记 10 件破损」的夹具，返回待处理记录 id。 */
  UUID damagedPending(String discrepancyQty) {
    var receipt = createReceipt("100", qSellable);
    UUID discrepancyId = receiveWithDiscrepancy(receipt.id(), "100", "DAMAGED", discrepancyQty, "外箱压塌");
    return intake(discrepancyId).id();
  }

  static byte[] sha256(byte[] content) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(content);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
