package com.starcon.warehouse;

import static com.starcon.warehouse.GoodsReceiptModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
 * 8.2.1.1 收货单：完整状态机、**预期与实收分开记录**、入库分录**真的改变余额**、 带符号增量不抹掉并发交易、拒收不入库、超收必须留痕、序列号逐号建档、批次与效期逐次实收，
 * 以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link InventoryTransferIntegrationTest} 完全一致（同样的 Testcontainers、 同样的 fake identity
 * server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）， 派生 id 的前缀不同，避免两条车道的夹具互相污染。
 */
@SpringBootTest
@AutoConfigureMockMvc
class GoodsReceiptIntegrationTest {
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
  static final UUID locA1 = idFor(a, 8);
  static final UUID lotA1 = idFor(a, 10);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-goodsreceipt-test-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired GoodsReceiptService service;
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

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    // TRUNCATE 不触发行级触发器（append-only 只挡 UPDATE/DELETE），以超级用户连接执行可绕过
    // FORCE RLS —— 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate goods_receipt_discrepancy, goods_receipt_event_serial,"
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
                idFor(tenant, 1),
                tenant,
                "LE-1",
                "主体一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 2),
                tenant,
                "SKU-1",
                "商品一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3),
                tenant,
                "SKU-2",
                "商品二");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "WH-SH",
                "上海仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "WH-SZ",
                "深圳仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role)"
                    + " values(?,?,?,?,?)",
                idFor(tenant, 7),
                tenant,
                "PENDING_INSPECTION",
                "待检",
                "PENDING_INSPECTION");
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
              lotA1,
              a,
              skuA,
              "LOT-1",
              LocalDate.of(2027, 3, 1));
          return null;
        });
  }

  void location(UUID id, UUID warehouse, String code) {
    sql.execute(
        "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
            + " values(?,?,?,?,?,'STORAGE',true)",
        id,
        a,
        warehouse,
        code,
        "库位 " + code);
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  // ================================================================ 夹具与查询助手

  /**
   * 建一条余额行并在指定库位拆一个 bucket，**同一事务**写入。
   *
   * <p>必须同一事务：V2 的守恒是延迟约束触发器，提交前才对平。
   */
  void seedBalance(UUID sku, UUID warehouse, UUID location, UUID quality, String onHand) {
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          var balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              balanceId,
              a,
              sku,
              warehouse,
              leA,
              quality,
              new BigDecimal(onHand));
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at) values(?,?,?,?,?,?,null,?,?,?,0,now())",
              UUID.randomUUID(),
              a,
              leA,
              sku,
              warehouse,
              location,
              quality,
              balanceId,
              new BigDecimal(onHand));
          return null;
        });
  }

  /**
   * 直接改余额，**不经服务层** —— 用来模拟「收货期间并行发生的真实交易」。
   *
   * <p>这是带符号增量那条断言的关键：如果收货用覆盖式写回，这笔并行扣减会被抹掉。
   */
  void rawDeduct(UUID sku, UUID warehouse, UUID quality, BigDecimal qty) {
    asA(
        () -> {
          sql.execute(
              "update inventory_balance set on_hand=on_hand-?,version=version+1,updated_at=now()"
                  + " where sku_id=? and warehouse_id=? and quality_status_id=?",
              qty,
              sku,
              warehouse,
              quality);
          sql.execute(
              "update stock_bucket set on_hand=on_hand-?,"
                  + " last_movement_at=last_movement_at + interval '1 microsecond'"
                  + " where sku_id=? and warehouse_id=? and quality_status_id=?",
              qty,
              sku,
              warehouse,
              quality);
          return null;
        });
  }

  BigDecimal onHand(UUID sku, UUID warehouse, UUID quality) {
    return asA(
        () ->
            sql.fetchOne(
                    "select coalesce(sum(on_hand),0) from inventory_balance"
                        + " where sku_id=? and warehouse_id=? and quality_status_id=?",
                    sku,
                    warehouse,
                    quality)
                .get(0, BigDecimal.class));
  }

  long countTransactions(String kind) {
    return asA(
        () ->
            sql.fetchOne("select count(*) from inventory_transaction where kind=?", kind)
                .get(0, Long.class));
  }

  BigDecimal entryDelta(String kind) {
    return asA(
        () ->
            sql.fetchOne(
                    "select coalesce(sum(e.on_hand_delta),0) from inventory_entry e"
                        + " join inventory_transaction t on t.id=e.transaction_id"
                        + " where t.kind=?",
                    kind)
                .get(0, BigDecimal.class));
  }

  long serialCount() {
    return asA(() -> sql.fetchOne("select count(*) from inventory_serial").get(0, Long.class));
  }

  /** 走本车道约定的建单路径：调拨来源是唯一已交付的来源。单行单 SKU。 */
  GoodsReceiptSaved createReceipt(String expected, UUID quality) {
    return asA(
        () ->
            service.create(
                a,
                new GoodsReceiptCreateRequest(
                    "TRANSFER",
                    transferSourceId(),
                    "TO-2024-0001",
                    whA,
                    leA,
                    "顺丰",
                    "SF123",
                    "外箱完好",
                    List.of(
                        new GoodsReceiptLineRequest(skuA, quality, new BigDecimal(expected))))));
  }

  /** 建一张已发出的调拨单当来源。7.5.1.1 已交付，所以这个来源是真通的。 */
  UUID transferSourceId() {
    return asA(
        () -> {
          var id = UUID.randomUUID();
          sql.execute(
              "insert into transfer_order(id,tenant_id,order_code,from_warehouse_id,"
                  + "to_warehouse_id,legal_entity_id,status,confirmed_at,shipped_at,created_by)"
                  + " values(?,?,?,?,?,?,'IN_TRANSIT',now(),now(),?)",
              id,
              a,
              "TO-" + id.toString().substring(0, 8),
              whB2,
              whA,
              leA,
              user);
          var lineId = UUID.randomUUID();
          sql.execute(
              "insert into transfer_order_line(id,tenant_id,order_id,sku_id,quality_status_id,"
                  + "requested_quantity,shipped_quantity) values(?,?,?,?,?,100,100)",
              lineId,
              a,
              id,
              skuA,
              qSellable,
              new BigDecimal("100"));
          // V61 的守恒触发器要求「已发出 = 已实收 + 已确认差异 + 仍在途」，
          // 所以一条 IN_TRANSIT 在途腿必须同时存在，否则夹具自己就对不上账。
          sql.execute(
              "insert into inventory_transfer(id,tenant_id,sku_id,legal_entity_id,"
                  + "from_warehouse_id,to_warehouse_id,quality_status_id,quantity,status,"
                  + "ref_type,ref_id,shipped_at,line_id)"
                  + " values(?,?,?,?,?,?,?,100,'IN_TRANSIT','TRANSFER_ORDER',?,now(),?)",
              UUID.randomUUID(),
              a,
              skuA,
              leA,
              whB2,
              whA,
              qSellable,
              id,
              lineId);
          return id;
        });
  }

  UUID lineIdOf(UUID receiptId) {
    return asA(() -> service.detail(receiptId)).lines().get(0).id();
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.inventory.read", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.inventory.read", work);
  }

  // ================================================================ 完整链路

  @Test
  void 完整链路_草稿到已关闭且预期与实收全程分开记录() {
    var draft = createReceipt("100", qSellable);
    assertThat(draft.status()).isEqualTo("DRAFT");
    assertThat(draft.version()).isEqualTo(0);
    // 草稿不动库存：只是意图
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("0");

    UUID line = lineIdOf(draft.id());

    // 第一车到 60：部分收
    var partial =
        asA(
            () ->
                service.receive(
                    draft.id(),
                    new GoodsReceiptReceiveRequest(
                        0L,
                        null,
                        locA1,
                        "SF123-A",
                        "第一车",
                        List.of(
                            new GoodsReceiptReceiveLineRequest(
                                line,
                                new BigDecimal("60"),
                                BigDecimal.ZERO,
                                null,
                                null,
                                List.of())),
                        List.of())));
    assertThat(partial.status()).isEqualTo("PARTIALLY_RECEIVED");
    // **预期与实收分开记录**：预期仍是 100，实收 60，还差 40
    assertThat(partial.expectedQuantity()).isEqualByComparingTo("100");
    assertThat(partial.receivedQuantity()).isEqualByComparingTo("60");
    assertThat(partial.outstandingQuantity()).isEqualByComparingTo("40");

    // 第二车到 40：已收
    var full =
        asA(
            () ->
                service.receive(
                    draft.id(),
                    new GoodsReceiptReceiveRequest(
                        1L,
                        null,
                        locA1,
                        "SF123-B",
                        "第二车",
                        List.of(
                            new GoodsReceiptReceiveLineRequest(
                                line,
                                new BigDecimal("40"),
                                BigDecimal.ZERO,
                                null,
                                null,
                                List.of())),
                        List.of())));
    assertThat(full.status()).isEqualTo("RECEIVED");
    assertThat(full.receivedQuantity()).isEqualByComparingTo("100");
    assertThat(full.outstandingQuantity()).isEqualByComparingTo("0");

    // 关单：已关闭是终态
    var closed = asA(() -> service.close(draft.id(), new GoodsReceiptCloseRequest(2L, "全部到齐", "")));
    assertThat(closed.status()).isEqualTo("CLOSED");
    assertThat(closed.closedAt()).isNotNull();

    // 终态：再收货必须被拒绝
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.receive(
                            draft.id(),
                            new GoodsReceiptReceiveRequest(
                                3L,
                                null,
                                locA1,
                                null,
                                "",
                                List.of(
                                    new GoodsReceiptReceiveLineRequest(
                                        line, BigDecimal.ONE, null, null, null, List.of())),
                                List.of()))))
        .isInstanceOf(ResponseStatusException.class);
    // 关单后数量纹丝不动
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("100");
  }

  // ================================================================ 入库分录

  @Test
  void 入库分录真的写进既有库存表并让余额真的变化() {
    seedBalance(skuA, whA, locA1, qSellable, "100");
    var draft = createReceipt("50", qSellable);
    UUID line = lineIdOf(draft.id());

    assertThat(countTransactions("RECEIPT")).isZero();
    assertThat(entryDelta("RECEIPT")).isEqualByComparingTo("0");

    asA(
        () ->
            service.receive(
                draft.id(),
                new GoodsReceiptReceiveRequest(
                    0L,
                    null,
                    locA1,
                    null,
                    "",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            line, new BigDecimal("50"), BigDecimal.ZERO, null, null, List.of())),
                    List.of())));

    // 余额真的变了（不是覆盖成 50，而是 100 + 50 = 150）
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("150");
    // 分录真的写进了 V1 的 inventory_transaction / inventory_entry
    assertThat(countTransactions("RECEIPT")).isEqualTo(1);
    assertThat(entryDelta("RECEIPT")).isEqualByComparingTo("50");
    // 库位明细同步增加（V2 守恒：改余额必须同时改库位）
    var bucket =
        asA(
            () ->
                sql.fetchOne(
                        "select on_hand from stock_bucket where sku_id=? and warehouse_id=?"
                            + " and location_id=? and quality_status_id=?",
                        skuA,
                        whA,
                        locA1,
                        qSellable)
                    .get(0, BigDecimal.class));
    assertThat(bucket).isEqualByComparingTo("150");
  }

  @Test
  void 带符号增量_不抹掉收货期间并行发生的真实交易() {
    seedBalance(skuA, whA, locA1, qSellable, "100");
    var draft = createReceipt("80", qSellable);
    UUID line = lineIdOf(draft.id());

    // 先收 50 → 150
    asA(
        () ->
            service.receive(
                draft.id(),
                new GoodsReceiptReceiveRequest(
                    0L,
                    null,
                    locA1,
                    null,
                    "",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            line, new BigDecimal("50"), null, null, null, List.of())),
                    List.of())));
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("150");

    // 收货期间并行发生一笔出库（不经本模块）：150 → 120
    rawDeduct(skuA, whA, qSellable, new BigDecimal("30"));
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("120");

    // 再收 30 → 必须是 150
    asA(
        () ->
            service.receive(
                draft.id(),
                new GoodsReceiptReceiveRequest(
                    1L,
                    null,
                    locA1,
                    null,
                    "",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            line, new BigDecimal("30"), null, null, null, List.of())),
                    List.of())));
    // 覆盖式写回会给 80；带符号增量才给 150
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("150");
    assertThat(entryDelta("RECEIPT")).isEqualByComparingTo("80");
  }

  @Test
  void 拒收不入库_不写分录且余额不变() {
    var draft = createReceipt("100", qSellable);
    UUID line = lineIdOf(draft.id());

    // 收 60、拒收 10：只有 60 入库
    asA(
        () ->
            service.receive(
                draft.id(),
                new GoodsReceiptReceiveRequest(
                    0L,
                    null,
                    locA1,
                    null,
                    "",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            line,
                            new BigDecimal("60"),
                            new BigDecimal("10"),
                            null,
                            null,
                            List.of())),
                    List.of())));

    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("60");
    assertThat(entryDelta("RECEIPT")).isEqualByComparingTo("60");
    var detail = asA(() -> service.detail(draft.id()));
    var l = detail.lines().get(0);
    assertThat(l.receivedQuantity()).isEqualByComparingTo("60");
    assertThat(l.rejectedQuantity()).isEqualByComparingTo("10");
    // 拒收必须留下差异记录（否则「为什么这批没进库」永远答不出来）
    assertThat(detail.discrepancies())
        .anyMatch(
            d ->
                d.discrepancyType().equals("REJECTED")
                    && d.quantity().compareTo(new BigDecimal("10")) == 0);
  }

  @Test
  void 全单拒收_不开空事务() {
    var draft = createReceipt("50", qSellable);
    UUID line = lineIdOf(draft.id());
    asA(
        () ->
            service.receive(
                draft.id(),
                new GoodsReceiptReceiveRequest(
                    0L,
                    null,
                    locA1,
                    null,
                    "货到了但一件不要",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            line, BigDecimal.ZERO, new BigDecimal("50"), null, null, List.of())),
                    List.of())));
    // 一条**没有分录**的 inventory_transaction 会让台账出现一笔查无下文的空交易，
    // 比不记更糟：它让「这批完全没入库」看起来像是记了账。
    assertThat(countTransactions("RECEIPT")).isZero();
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("0");
    var detail = asA(() -> service.detail(draft.id()));
    assertThat(detail.lines().get(0).rejectedQuantity()).isEqualByComparingTo("50");
    // 状态仍不是「已收」：收到的东西是 0
    assertThat(detail.receipt().status()).isNotEqualTo("RECEIVED");
  }

  @Test
  void 超收必须留痕_数据库级兜底() {
    var draft = createReceipt("50", qSellable);
    UUID line = lineIdOf(draft.id());

    // 收 60 超出预期 50：服务层显式登记一条 OVER_RECEIPT
    asA(
        () ->
            service.receive(
                draft.id(),
                new GoodsReceiptReceiveRequest(
                    0L,
                    null,
                    locA1,
                    null,
                    "供应商多送了",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            line, new BigDecimal("60"), null, null, null, List.of())),
                    List.of())));
    var detail = asA(() -> service.detail(draft.id()));
    assertThat(detail.discrepancies())
        .anyMatch(
            d ->
                d.discrepancyType().equals("OVER_RECEIPT")
                    && d.quantity().compareTo(new BigDecimal("10")) == 0);
    // 超收的部分真的进了库存（不是被悄悄截断）
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("60");
  }

  @Test
  void 超收不留痕被数据库拒绝() {
    var draft = createReceipt("50", qSellable);
    UUID line = lineIdOf(draft.id());
    // 绕过服务层直接改数量：不登记差异 → guard_receipt_line_overage 必须挡下
    assertThatThrownBy(
            () ->
                asA(
                    () -> {
                      sql.execute(
                          "update goods_receipt_line set received_quantity=?,version=version+1"
                              + " where id=?",
                          new BigDecimal("80"),
                          line);
                      return null;
                    }))
        .hasMessageContaining("超收未留痕");
  }

  @Test
  void 关单把剩余未到齐如实记为少收() {
    var draft = createReceipt("100", qSellable);
    UUID line = lineIdOf(draft.id());
    asA(
        () ->
            service.receive(
                draft.id(),
                new GoodsReceiptReceiveRequest(
                    0L,
                    null,
                    locA1,
                    null,
                    "",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            line, new BigDecimal("60"), null, null, null, List.of())),
                    List.of())));
    var closed =
        asA(() -> service.close(draft.id(), new GoodsReceiptCloseRequest(1L, "供应商少发", "")));
    assertThat(closed.status()).isEqualTo("CLOSED");
    assertThat(closed.receivedQuantity()).isEqualByComparingTo("60");

    var detail = asA(() -> service.detail(draft.id()));
    // 短少 40 如实登记，**不**自动抹平
    assertThat(detail.discrepancies())
        .anyMatch(
            d ->
                d.discrepancyType().equals("SHORT_RECEIPT")
                    && d.quantity().compareTo(new BigDecimal("40")) == 0);
    // 没到的 40 没有进库存
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("60");
  }

  // ================================================================ 批次、效期、序列号

  @Test
  void 批次与效期是逐次实收的属性() {
    var draft = createReceipt("60", qSellable);
    UUID line = lineIdOf(draft.id());
    asA(
        () ->
            service.receive(
                draft.id(),
                new GoodsReceiptReceiveRequest(
                    0L,
                    null,
                    locA1,
                    "SF-A",
                    "第一批",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            line,
                            new BigDecimal("25"),
                            null,
                            lotA1,
                            LocalDate.of(2027, 3, 1),
                            List.of())),
                    List.of())));
    asA(
        () ->
            service.receive(
                draft.id(),
                new GoodsReceiptReceiveRequest(
                    1L,
                    null,
                    locA1,
                    "SF-B",
                    "第二批无批次",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            line, new BigDecimal("35"), null, null, null, List.of())),
                    List.of())));

    var detail = asA(() -> service.detail(draft.id()));
    assertThat(detail.events()).hasSize(2);
    var first = detail.events().get(0).lines().get(0);
    assertThat(first.lotId()).isEqualTo(lotA1);
    assertThat(first.lotCode()).isEqualTo("LOT-1");
    // 效期是日历日：用 date 存，读回来不会被时区换算漂成 02-28
    assertThat(first.expiryDate()).isEqualTo(LocalDate.of(2027, 3, 1));
    var second = detail.events().get(1).lines().get(0);
    assertThat(second.lotId()).isNull();
    // 两个批次落到两个 bucket：合计 60，但各自独立
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("60");
  }

  @Test
  void 序列号逐号建档进库存序列号档案() {
    var draft = createReceipt("3", qSellable);
    UUID line = lineIdOf(draft.id());
    asA(
        () ->
            service.receive(
                draft.id(),
                new GoodsReceiptReceiveRequest(
                    0L,
                    null,
                    locA1,
                    null,
                    "扫码收货",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            line,
                            new BigDecimal("3"),
                            null,
                            null,
                            null,
                            List.of("SN-A", "SN-B", "SN-C"))),
                    List.of())));

    // 序列号**真的**进了 7.3.1.x 的库存档案，不在本模块另起一张号表
    assertThat(serialCount()).isEqualTo(3);
    var stored =
        asA(
            () ->
                sql.fetch(
                    "select serial_number from inventory_serial where sku_id=? order by serial_number",
                    skuA));
    assertThat(stored)
        .extracting(r -> r.get(0, String.class))
        .containsExactly("SN-A", "SN-B", "SN-C");

    var detail = asA(() -> service.detail(draft.id()));
    assertThat(detail.events().get(0).lines().get(0).serialNumbers())
        .containsExactly("SN-A", "SN-B", "SN-C");
  }

  @Test
  void 序列号条数与数量不符被拒绝_不允许大概齐() {
    var draft = createReceipt("3", qSellable);
    UUID line = lineIdOf(draft.id());
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.receive(
                            draft.id(),
                            new GoodsReceiptReceiveRequest(
                                0L,
                                null,
                                locA1,
                                null,
                                "",
                                List.of(
                                    new GoodsReceiptReceiveLineRequest(
                                        line,
                                        new BigDecimal("3"),
                                        null,
                                        null,
                                        null,
                                        List.of("SN-A", "SN-B"))),
                                List.of()))))
        .hasMessageContaining("不允许「大概齐」");
    // 失败请求不留任何库存痕迹
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("0");
    assertThat(serialCount()).isZero();
  }

  // ================================================================ 落库质量状态

  @Test
  void 实收转入指定的质量状态_待检或可售() {
    var draft = createReceipt("40", qPending);
    UUID line = lineIdOf(draft.id());
    asA(
        () ->
            service.receive(
                draft.id(),
                new GoodsReceiptReceiveRequest(
                    0L,
                    null,
                    locA1,
                    null,
                    "",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            line, new BigDecimal("40"), null, null, null, List.of())),
                    List.of())));
    // 落在待检，不落可售：破损与短少因此「待检入库」，不会被直接当成可售卖掉
    assertThat(onHand(skuA, whA, qPending)).isEqualByComparingTo("40");
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("0");
  }

  // ================================================================ 来源维度

  @Test
  void 能力端点显式声明未交付来源不可用() {
    var caps = asA(() -> service.capabilities());
    // 已交付的只有调拨单
    assertThat(caps.sources())
        .filteredOn(s -> s.available())
        .extracting(s -> s.value())
        .containsExactly("TRANSFER");
    for (var type : List.of("PURCHASE", "RETURN_AUTH", "PLATFORM_WAREHOUSE")) {
      var cap =
          caps.sources().stream().filter(s -> s.value().equals(type)).findFirst().orElseThrow();
      assertThat(cap.available()).as(type).isFalse();
      // 必须给出原因，不能是空集冒充成功
      assertThat(cap.note()).as(type + " 必须说明为什么不可用").isNotBlank();
      assertThat(cap.ownerModule()).isNotBlank();
      assertThat(cap.href()).isNotBlank();
    }
    // 差异处理流程显式归 8.2.1.2 且未交付
    assertThat(caps.discrepancyWorkflowAvailable()).isFalse();
    assertThat(caps.discrepancyOwnerModule()).isEqualTo("8.2.1.2");
    assertThat(caps.discrepancyNote()).contains("8.2.1.2");
    // 不回写来源单据这件事必须说清楚
    assertThat(caps.writebackNote()).contains("不回写");
  }

  @Test
  void 未交付来源被拒绝且不建占位单据() {
    for (var type : List.of("PURCHASE", "RETURN_AUTH", "PLATFORM_WAREHOUSE")) {
      assertThatThrownBy(
              () ->
                  asA(
                      () ->
                          service.create(
                              a,
                              new GoodsReceiptCreateRequest(
                                  type,
                                  null,
                                  "SRC-1",
                                  whA,
                                  leA,
                                  "",
                                  "",
                                  "",
                                  List.of(
                                      new GoodsReceiptLineRequest(
                                          skuA, qSellable, new BigDecimal("10")))))))
          .hasMessageContaining("尚未交付");
    }
    // 一张都没建
    assertThat(
            asA(() -> service.page(new GoodsReceiptQuery(null, null, null, null, null, 0, 50)))
                .rows())
        .isEmpty();
  }

  @Test
  void 调拨来源必须真存在且已发出() {
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.create(
                            a,
                            new GoodsReceiptCreateRequest(
                                "TRANSFER",
                                UUID.randomUUID(),
                                "NOPE",
                                whA,
                                leA,
                                "",
                                "",
                                "",
                                List.of(
                                    new GoodsReceiptLineRequest(
                                        skuA, qSellable, new BigDecimal("10")))))))
        .hasMessageContaining("来源调拨单不存在或无权访问");

    // 草稿状态的调拨单不构成「到货」
    var draftTransfer =
        asA(
            () -> {
              var id = UUID.randomUUID();
              sql.execute(
                  "insert into transfer_order(id,tenant_id,order_code,from_warehouse_id,"
                      + "to_warehouse_id,legal_entity_id,status,created_by)"
                      + " values(?,?,?,?,?,?,'DRAFT',?)",
                  id,
                  a,
                  "TO-DRAFT",
                  whB2,
                  whA,
                  leA,
                  user);
              return id;
            });
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.create(
                            a,
                            new GoodsReceiptCreateRequest(
                                "TRANSFER",
                                draftTransfer,
                                "TO-DRAFT",
                                whA,
                                leA,
                                "",
                                "",
                                "",
                                List.of(
                                    new GoodsReceiptLineRequest(
                                        skuA, qSellable, new BigDecimal("10")))))))
        .hasMessageContaining("只有已发出");
  }

  // ================================================================ 状态机

  @Test
  void 状态机拒绝非法跳转() {
    var draft = createReceipt("100", qSellable);
    // 已关闭后不能再动
    asA(() -> service.close(draft.id(), new GoodsReceiptCloseRequest(0L, "整单拒收", "")));
    var closed = asA(() -> service.detail(draft.id()));
    assertThat(closed.receipt().status()).isEqualTo("CLOSED");
    // 详情只给合法下一步：已关闭没有下一步
    assertThat(closed.transitions()).isEmpty();

    // 状态转换表本身：CLOSED 到任何状态都不允许
    var caps = asA(() -> service.capabilities());
    assertThat(caps.transitions())
        .filteredOn(t -> t.from().equals("CLOSED"))
        .allMatch(t -> !t.allowed());
    // DRAFT 可以直接关单（货一件没到、整单拒收时必须有合法动作把它结掉）
    assertThat(ReceiptStatus.allowedNext(ReceiptStatus.DRAFT)).contains(ReceiptStatus.CLOSED);
    assertThat(ReceiptStatus.allowedNext(ReceiptStatus.RECEIVED))
        .containsExactly(ReceiptStatus.CLOSED);
  }

  @Test
  void 草稿关单时剩余全部记为少收() {
    var draft = createReceipt("80", qSellable);
    asA(() -> service.close(draft.id(), new GoodsReceiptCloseRequest(0L, "供应商取消发货", "")));
    var detail = asA(() -> service.detail(draft.id()));
    assertThat(detail.discrepancies())
        .anyMatch(
            d ->
                d.discrepancyType().equals("SHORT_RECEIPT")
                    && d.quantity().compareTo(new BigDecimal("80")) == 0);
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("0");
  }

  @Test
  void 实收后不允许改单() {
    var draft = createReceipt("100", qSellable);
    UUID line = lineIdOf(draft.id());
    asA(
        () ->
            service.receive(
                draft.id(),
                new GoodsReceiptReceiveRequest(
                    0L,
                    null,
                    locA1,
                    null,
                    "",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            line, new BigDecimal("10"), null, null, null, List.of())),
                    List.of())));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.update(
                            draft.id(),
                            new GoodsReceiptUpdateRequest(
                                1L,
                                "",
                                "",
                                "",
                                "",
                                List.of(
                                    new GoodsReceiptLineRequest(
                                        skuA, qSellable, new BigDecimal("999")))))))
        .hasMessageContaining("只有「草稿」状态");
  }

  // ================================================================ 乐观锁

  @Test
  void 乐观锁_版本不符与缺失都拒绝且不留库存痕迹() {
    var draft = createReceipt("100", qSellable);
    UUID line = lineIdOf(draft.id());

    // 版本不符
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.receive(
                            draft.id(),
                            new GoodsReceiptReceiveRequest(
                                99L,
                                null,
                                locA1,
                                null,
                                "",
                                List.of(
                                    new GoodsReceiptReceiveLineRequest(
                                        line, new BigDecimal("10"), null, null, null, List.of())),
                                List.of()))))
        .isInstanceOf(ResponseStatusException.class);
    // 缺失
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.receive(
                            draft.id(),
                            new GoodsReceiptReceiveRequest(
                                null,
                                null,
                                locA1,
                                null,
                                "",
                                List.of(
                                    new GoodsReceiptReceiveLineRequest(
                                        line, new BigDecimal("10"), null, null, null, List.of())),
                                List.of()))))
        .hasMessageContaining("缺少 expectedVersion");
    // 失败请求一行库存痕迹都不留
    assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("0");
    assertThat(countTransactions("RECEIPT")).isZero();
  }

  // ================================================================ 跨租户库位

  @Test
  void 落库库位必须属于收货仓() {
    var other = UUID.randomUUID();
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,pickable)"
                  + " values(?,?,?,?,?,'STORAGE',true)",
              other,
              a,
              whB2,
              "SZ-X-01",
              "别的仓的库位");
          return null;
        });
    var draft = createReceipt("50", qSellable);
    UUID line = lineIdOf(draft.id());
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.receive(
                            draft.id(),
                            new GoodsReceiptReceiveRequest(
                                0L,
                                null,
                                other,
                                null,
                                "",
                                List.of(
                                    new GoodsReceiptReceiveLineRequest(
                                        line, new BigDecimal("50"), null, null, null, List.of())),
                                List.of()))))
        .hasMessageContaining("不属于收货仓");
  }

  // ================================================================ 显式差异

  @Test
  void 显式差异登记_类型不猜且必须给原因() {
    var draft = createReceipt("100", qSellable);
    UUID line = lineIdOf(draft.id());
    asA(
        () ->
            service.receive(
                draft.id(),
                new GoodsReceiptReceiveRequest(
                    0L,
                    null,
                    locA1,
                    null,
                    "",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            line, new BigDecimal("60"), null, null, null, List.of())),
                    List.of(
                        new ReceiptDiscrepancyRequest(
                            line, "DAMAGED", new BigDecimal("3"), "外箱压塌", "3 件外包装破损", "")))));

    var detail = asA(() -> service.detail(draft.id()));
    assertThat(detail.discrepancies())
        .anyMatch(
            d ->
                d.discrepancyType().equals("DAMAGED")
                    && d.quantity().compareTo(new BigDecimal("3")) == 0
                    && d.reasonCode().equals("外箱压塌"));

    // 类型缺失且按数量判不出超收时必须报错，不猜
    var draft2 = createReceipt("100", qSellable);
    UUID line2 = lineIdOf(draft2.id());
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        service.receive(
                            draft2.id(),
                            new GoodsReceiptReceiveRequest(
                                0L,
                                null,
                                locA1,
                                null,
                                "",
                                List.of(
                                    new GoodsReceiptReceiveLineRequest(
                                        line2, new BigDecimal("10"), null, null, null, List.of())),
                                List.of(
                                    new ReceiptDiscrepancyRequest(
                                        line2, null, new BigDecimal("2"), "包装脏污", "", ""))))))
        .hasMessageContaining("必须明确差异类型");
  }

  // ================================================================ 时间戳

  @Test
  void timestamptz带偏移写入后读回零漂移() {
    var draft = createReceipt("50", qSellable);
    UUID line = lineIdOf(draft.id());
    var at = OffsetDateTime.of(2026, 3, 15, 9, 30, 0, 0, ZoneOffset.ofHours(8));
    asA(
        () ->
            service.receive(
                draft.id(),
                new GoodsReceiptReceiveRequest(
                    0L,
                    at,
                    locA1,
                    null,
                    "",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            line, new BigDecimal("50"), null, null, null, List.of())),
                    List.of())));

    var stored =
        asA(
            () ->
                sql.fetchOne(
                        "select received_at from goods_receipt_event where receipt_id=?",
                        draft.id())
                    .get("received_at", OffsetDateTime.class));
    // 用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s；这里断言写回的值与偏移
    assertThat(stored.toInstant()).isEqualTo(at.toInstant());
    assertThat(stored.getOffset()).isEqualTo(ZoneOffset.ofHours(8));

    // 业务时间也进了库存业务时间字段
    var moved =
        asA(
            () ->
                sql.fetchOne(
                        "select last_movement_at from stock_bucket where sku_id=? and warehouse_id=?",
                        skuA,
                        whA)
                    .get("last_movement_at", OffsetDateTime.class));
    assertThat(moved.toInstant()).isEqualTo(at.toInstant());
  }

  // ================================================================ 列表与分页

  @Test
  void 列表是真分页且汇总不受分页影响() {
    var ids = new java.util.ArrayList<UUID>();
    for (int i = 0; i < 3; i++) {
      ids.add(createReceipt("10", qSellable).id());
    }
    var page1 = asA(() -> service.page(new GoodsReceiptQuery(null, null, null, null, null, 0, 2)));
    assertThat(page1.rows()).hasSize(2);
    assertThat(page1.total()).isEqualTo(3);
    // 汇总是全量的，不受分页影响
    assertThat(page1.summary().receiptCount()).isEqualTo(3);
    assertThat(page1.summary().expectedQuantity()).isEqualByComparingTo("30");
    var page2 = asA(() -> service.page(new GoodsReceiptQuery(null, null, null, null, null, 1, 2)));
    assertThat(page2.rows()).hasSize(1);
    // 真分页 = 两页不重叠且合起来覆盖全部，而不是同一批行被重复吐出来
    var page1Ids = page1.rows().stream().map(GoodsReceiptRow::id).toList();
    var page2Ids = page2.rows().stream().map(GoodsReceiptRow::id).toList();
    assertThat(page2Ids).doesNotContainAnyElementsOf(page1Ids);
    assertThat(java.util.stream.Stream.concat(page1Ids.stream(), page2Ids.stream()).toList())
        .containsExactlyInAnyOrderElementsOf(ids);

    // 状态筛选真实生效
    asA(
        () ->
            service.receive(
                ids.get(0),
                new GoodsReceiptReceiveRequest(
                    0L,
                    null,
                    locA1,
                    null,
                    "",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            lineIdOf(ids.get(0)),
                            new BigDecimal("10"),
                            null,
                            null,
                            null,
                            List.of())),
                    List.of())));
    var filtered =
        asA(
            () ->
                service.page(
                    new GoodsReceiptQuery(null, List.of("RECEIVED"), null, null, null, 0, 50)));
    assertThat(filtered.rows()).hasSize(1);
    assertThat(filtered.rows().get(0).id()).isEqualTo(ids.get(0));

    // 关键字是真实模糊检索
    var target = asA(() -> service.detail(ids.get(1))).receipt();
    var byCode =
        asA(
            () ->
                service.page(
                    new GoodsReceiptQuery(null, null, null, target.receiptCode(), null, 0, 50)));
    assertThat(byCode.rows()).hasSize(1);
    var byWaybill =
        asA(() -> service.page(new GoodsReceiptQuery(null, null, null, "SF123", null, 0, 50)));
    assertThat(byWaybill.rows()).hasSize(3);
    var miss =
        asA(() -> service.page(new GoodsReceiptQuery(null, null, null, "不存在的单号", null, 0, 50)));
    assertThat(miss.rows()).isEmpty();
    assertThat(miss.total()).isZero();
  }

  @Test
  void 排序白名单拒绝任意表达式() {
    createReceipt("10", qSellable);
    // 传 SQL 片段只能落回默认排序，不报错也不被执行
    var page =
        asA(
            () ->
                service.page(
                    new GoodsReceiptQuery(
                        null, null, null, null, "receipt_code; drop table x", 0, 50)));
    assertThat(page.rows()).hasSize(1);
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    var receiptA = createReceipt("100", qSellable);
    UUID lineA = lineIdOf(receiptA.id());
    asA(
        () ->
            service.receive(
                receiptA.id(),
                new GoodsReceiptReceiveRequest(
                    0L,
                    null,
                    locA1,
                    null,
                    "",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            lineA, new BigDecimal("60"), null, null, null, List.of())),
                    List.of())));

    // B 租户看不到 A 的单
    assertThatThrownBy(() -> asB(() -> service.detail(receiptA.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                asB(
                    () ->
                        service.receive(
                            receiptA.id(),
                            new GoodsReceiptReceiveRequest(
                                1L,
                                null,
                                locA1,
                                null,
                                "",
                                List.of(
                                    new GoodsReceiptReceiveLineRequest(
                                        lineA, new BigDecimal("1"), null, null, null, List.of())),
                                List.of()))))
        .isInstanceOf(ResponseStatusException.class);

    // B 自己建一张，各看各的
    var receiptB =
        asB(
            () ->
                service.create(
                    b,
                    new GoodsReceiptCreateRequest(
                        "TRANSFER",
                        bTransfer(),
                        "TO-B",
                        idFor(b, 4),
                        idFor(b, 1),
                        "",
                        "",
                        "",
                        List.of(
                            new GoodsReceiptLineRequest(
                                idFor(b, 2), idFor(b, 6), new BigDecimal("7"))))));
    assertThat(
            asA(() -> service.page(new GoodsReceiptQuery(null, null, null, null, null, 0, 50)))
                .rows())
        .hasSize(1);
    assertThat(
            asB(() -> service.page(new GoodsReceiptQuery(null, null, null, null, null, 0, 50)))
                .rows())
        .hasSize(1);
    assertThat(asB(() -> service.detail(receiptB.id())).receipt().receiptCode())
        .isEqualTo(receiptB.receiptCode());
  }

  UUID bTransfer() {
    return access.run(
        actorB,
        "warehouse.inventory.read",
        () -> {
          var id = UUID.randomUUID();
          sql.execute(
              "insert into transfer_order(id,tenant_id,order_code,from_warehouse_id,"
                  + "to_warehouse_id,legal_entity_id,status,confirmed_at,shipped_at,created_by)"
                  + " values(?,?,?,?,?,?,'IN_TRANSIT',now(),now(),?)",
              id,
              b,
              "TO-B",
              idFor(b, 5),
              idFor(b, 4),
              idFor(b, 1),
              user);
          return id;
        });
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    var receiptA = createReceipt("100", qSellable);
    // 身份是 B 但伪造 X-Tenant-Id 指向 A → 只能看到 B 自己的（这里是空）
    http.perform(
            get("/warehouse/goods-receipts")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    // 反向：身份是 A 伪造 B 的头也拿不到 B 的数据
    http.perform(
            get("/warehouse/goods-receipts")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].receiptCode").value(receiptA.receiptCode()));
    // 详情端点同样不受伪造头影响
    http.perform(
            get("/warehouse/goods-receipts/" + receiptA.id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 未认证 401
    http.perform(get("/warehouse/goods-receipts")).andExpect(status().isUnauthorized());
    // 写端点要 update 权限：只有 read 的角色不能建单
    http.perform(
            post("/warehouse/goods-receipts")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{\"sourceType\":\"TRANSFER\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    var receiptA = createReceipt("100", qSellable);
    UUID lineA = lineIdOf(receiptA.id());
    asA(
        () ->
            service.receive(
                receiptA.id(),
                new GoodsReceiptReceiveRequest(
                    0L,
                    null,
                    locA1,
                    null,
                    "",
                    List.of(
                        new GoodsReceiptReceiveLineRequest(
                            lineA, new BigDecimal("60"), null, null, null, List.of())),
                    List.of())));
    access.run(
        actorB,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into goods_receipt(id,tenant_id,receipt_code,source_type,source_ref_code,"
                  + "warehouse_id,legal_entity_id,status,created_by)"
                  + " values(?,?,'GR-B-1','TRANSFER','TO-B',?,?,'DRAFT',?)",
              UUID.randomUUID(),
              b,
              idFor(b, 4),
              idFor(b, 1),
              user);
          return null;
        });

    // 池大小为 1，同一条连接被反复借还：交替访问两个租户 20 轮，
    // 每一轮都校验列表、详情与库存余额三条路径都不串租户。
    for (int i = 0; i < 20; i++) {
      var pa = asA(() -> service.page(new GoodsReceiptQuery(null, null, null, null, null, 0, 50)));
      var pb = asB(() -> service.page(new GoodsReceiptQuery(null, null, null, null, null, 0, 50)));
      assertThat(pa.rows())
          .extracting(GoodsReceiptRow::receiptCode)
          .containsExactly(receiptA.receiptCode());
      assertThat(pb.rows()).extracting(GoodsReceiptRow::receiptCode).containsExactly("GR-B-1");
      assertThat(pa.summary().receivedQuantity()).isEqualByComparingTo("60");
      assertThat(pb.summary().receivedQuantity()).isEqualByComparingTo("0");
      assertThat(onHand(skuA, whA, qSellable)).isEqualByComparingTo("60");
      assertThatThrownBy(() -> asB(() -> service.detail(receiptA.id())))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(asA(() -> service.detail(receiptA.id())).receipt().receiptCode())
          .isEqualTo(receiptA.receiptCode());
      // B 仓的余额不因 A 的收货而变化
      assertThat(
              asB(
                  () ->
                      sql.fetchOne("select coalesce(sum(on_hand),0) from inventory_balance")
                          .get(0, BigDecimal.class)))
          .isEqualByComparingTo("0");
    }
  }

  @Test
  void 每张表都强制行级安全() {
    for (var table :
        List.of(
            "goods_receipt",
            "goods_receipt_line",
            "goods_receipt_event",
            "goods_receipt_event_line",
            "goods_receipt_event_serial",
            "goods_receipt_discrepancy")) {
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

  // ================================================================ 端点层

  @Test
  void 端点_完整流转与响应形状() throws Exception {
    var receiptA = createReceipt("100", qSellable);
    UUID line = lineIdOf(receiptA.id());

    http.perform(
            get("/warehouse/goods-receipts/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sources[?(@.value=='TRANSFER')].available").value(true))
        .andExpect(jsonPath("$.discrepancyWorkflowAvailable").value(false))
        .andExpect(jsonPath("$.discrepancyOwnerModule").value("8.2.1.2"));

    http.perform(get("/warehouse/goods-receipts").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.summary.expectedQuantity").value(100))
        .andExpect(jsonPath("$.rows[0].status").value("DRAFT"));

    // 实收
    http.perform(
            post("/warehouse/goods-receipts/" + receiptA.id() + "/receive")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"expectedVersion\":0,\"locationId\":\""
                        + locA1
                        + "\",\"waybillNo\":\"SF-E2E\","
                        + "\"lines\":[{\"lineId\":\""
                        + line
                        + "\",\"receivedQuantity\":60}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PARTIALLY_RECEIVED"))
        .andExpect(jsonPath("$.receivedQuantity").value(60))
        .andExpect(jsonPath("$.outstandingQuantity").value(40));

    // 详情：逐笔实收可见
    http.perform(
            get("/warehouse/goods-receipts/" + receiptA.id())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events.length()").value(1))
        .andExpect(jsonPath("$.events[0].waybillNo").value("SF-E2E"))
        .andExpect(jsonPath("$.lines[0].receivedQuantity").value(60))
        .andExpect(jsonPath("$.transitions[?(@.to=='RECEIVED')].allowed").exists());

    // 分录复用 7.1.2.1 台账
    http.perform(
            get("/warehouse/goods-receipts/" + receiptA.id() + "/movements")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attribution").value("ORDER_GRANULAR"))
        .andExpect(jsonPath("$.attributionNote").exists())
        .andExpect(jsonPath("$.movements.rows.length()").value(1));

    // 关单
    http.perform(
            post("/warehouse/goods-receipts/" + receiptA.id() + "/close")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"expectedVersion\":1,\"reason\":\"供应商少发\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLOSED"))
        .andExpect(jsonPath("$.closedAt").exists());

    // 非法请求：预期量必须大于 0
    http.perform(
            post("/warehouse/goods-receipts")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"sourceType\":\"TRANSFER\",\"sourceRefId\":\""
                        + transferSourceId()
                        + "\","
                        + "\"warehouseId\":\""
                        + whA
                        + "\",\"legalEntityId\":\""
                        + leA
                        + "\","
                        + "\"lines\":[{\"skuId\":\""
                        + skuA
                        + "\",\"qualityStatusId\":\""
                        + qSellable
                        + "\",\"expectedQuantity\":0}]}"))
        .andExpect(status().isBadRequest());

    // 乐观锁冲突 409
    http.perform(
            post("/warehouse/goods-receipts/" + receiptA.id() + "/close")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"expectedVersion\":0,\"reason\":\"x\"}"))
        .andExpect(status().isConflict());
  }
}
