package com.starcon.warehouse;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
 * 8.2.2.1 上架任务集成测试。
 *
 * <p><b>本测试最要紧的两节</b>：
 *
 * <ol>
 *   <li><b>蓝图加粗句</b>：「上架只改位置不改数量，数量在收货时已确定。」它落成三条 可被数据库裁决的不变量 P1/P2/P3，所以这里不只测「走服务层时成立」，还用
 *       <b>绕过服务层直连 SQL</b> 的写法去证明同样被 V109 的守卫触发器挡下 —— 否则那三条只是应用层的一行 if，绕过服务就能造出「货凭空增减」的记录。<br>
 *   <li><b>幂等</b>：PDA 网络抖动重发是现场常态。同一个 scanReference 重复提交会撞 部分唯一索引 —— 少了这道，同一批货会被搬两遍（源位扣两次、目标位加两次）
 *       <b>而 P1 的净额仍然是 0</b>。这是「只改位置」这条不变量唯一抓不到的 bug 形态。
 * </ol>
 *
 * <p>还有一节是<b>时间</b>：{@code scanned_at} 是业务时间（作业员扫的那一下）， 不是行写入库的时刻。所以有一例直接断言<b>写回来的值</b> —— 用 {@code
 * Timestamp.valueOf(LocalDateTime)} 绑 timestamptz 会静默偏 −28800s， 而偏 8 小时的时间戳在任何断言里都「看起来是个时间」，极难发现。
 *
 * <p>真实 PostgreSQL 16 + FORCE RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PutawayTaskIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  // 注册 JSR310：请求体里有 OffsetDateTime（scannedAt），裸 ObjectMapper 不认 Java 8 时间类型。
  static final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("putaway")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-putaway-test-" + tenant + "-" + ordinal).getBytes());
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
          actor(user, a, null, List.of(grant("ALL", List.of("warehouse.putaway.read")))));
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

  @Autowired PutawayTaskService putaway;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

  /**
   * warehouse 服务<b>没有</b> {@code TenantTransactions} Bean（它的租户绑定点只有 {@link AccessService#run}
   * 一处）。这里按同一个类、同一个绑定语义就地构造一个， 供「连接池归还后不串租户」断言使用 —— 不给生产代码新增第二条绑定路径。
   */
  TenantTransactions tenants() {
    return new TenantTransactions(
        sql, new org.springframework.transaction.support.TransactionTemplate(transactionManager));
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  final UUID leA = idFor(a, 1),
      skuA = idFor(a, 2),
      skuB = idFor(a, 3),
      whA = idFor(a, 5),
      qSellA = idFor(a, 6),
      qDefA = idFor(a, 7),
      locReceivingA = idFor(a, 10),
      locStorageA = idFor(a, 11),
      locStorageA2 = idFor(a, 12),
      locDefA = idFor(a, 13),
      receiptA = idFor(a, 20),
      lineA = idFor(a, 21),
      eventA = idFor(a, 22),
      eventLineA = idFor(a, 23),
      lotA = idFor(a, 24);
  final UUID leB = idFor(b, 1),
      skuB2 = idFor(b, 2),
      whB = idFor(b, 5),
      qSellB = idFor(b, 6),
      locReceivingB = idFor(b, 10),
      locStorageB = idFor(b, 11),
      receiptB = idFor(b, 20),
      lineB = idFor(b, 21),
      eventB = idFor(b, 22),
      eventLineB = idFor(b, 23);

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS ——
    // 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate putaway_task_scan, putaway_task_line, putaway_task, location_allocation,"
              + " goods_receipt_event_serial, goods_receipt_event_line, goods_receipt_event,"
              + " goods_receipt_line, goods_receipt_discrepancy, goods_receipt,"
              + " stock_bucket, warehouse_location, inventory_lot, inventory_entry,"
              + " inventory_transaction, inventory_balance, inventory_quality_status, warehouse,"
              + " sku_ref, legal_entity_ref restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.putaway.read",
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
                "SKU-A",
                "商品一");
            sql.execute(
                "insert into sku_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 3),
                tenant,
                "SKU-B",
                "商品二");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "WH-SH",
                "上海仓",
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
                "DEFECTIVE",
                "残次",
                "DEFECTIVE");
            // 收货暂存位（货现在压着的地方）
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,"
                    + "status,pickable) values(?,?,?,?,?,'RECEIVING','ACTIVE',false)",
                idFor(tenant, 10),
                tenant,
                idFor(tenant, 5),
                "RCV-01",
                "收货暂存位");
            // 正式存储库位 ×2（容量 / 承重留给 8.1.2.1 的量测，这里先不给）
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,"
                    + "status,pickable) values(?,?,?,?,?,'STORAGE','ACTIVE',true)",
                idFor(tenant, 11),
                tenant,
                idFor(tenant, 5),
                "A-01",
                "存储一");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,"
                    + "status,pickable) values(?,?,?,?,?,'STORAGE','ACTIVE',true)",
                idFor(tenant, 12),
                tenant,
                idFor(tenant, 5),
                "A-02",
                "存储二");
            // 残次专用库位（用来验证硬规则）
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,"
                    + "status,pickable) values(?,?,?,?,?,'DEFECTIVE','ACTIVE',false)",
                idFor(tenant, 13),
                tenant,
                idFor(tenant, 5),
                "D-01",
                "残次库位");
            // 批次（用来验证无批次 / 有批次两种定位）
            sql.execute(
                "insert into inventory_lot(id,tenant_id,sku_id,lot_code) values(?,?,?,?)",
                idFor(tenant, 24),
                tenant,
                idFor(tenant, 2),
                "LOT-1");
            return null;
          });
    }

    // ---- A 租户：一张已实收的收货单，货压在收货暂存位上。
    access.run(
        actorA,
        "warehouse.putaway.update",
        () -> {
          sql.execute(
              "insert into goods_receipt(id,tenant_id,receipt_code,source_type,source_ref_code,"
                  + "warehouse_id,legal_entity_id,status,created_by)"
                  + " values(?,?,?,'PURCHASE','PO-1',?,?,'RECEIVED',?)",
              receiptA,
              a,
              "GR-A-1",
              whA,
              leA,
              user);
          sql.execute(
              "insert into goods_receipt_line(id,tenant_id,receipt_id,sku_id,quality_status_id,"
                  + "expected_quantity,received_quantity) values(?,?,?,?,?,?,?)",
              lineA,
              a,
              receiptA,
              skuA,
              qSellA,
              new BigDecimal("100"),
              new BigDecimal("100"));
          sql.execute(
              "insert into goods_receipt_event(id,tenant_id,receipt_id,received_at,location_id,"
                  + "actor_id) values(?,?,?,?::timestamptz,?,?)",
              eventA,
              a,
              receiptA,
              OffsetDateTime.now(),
              locReceivingA,
              user);
          sql.execute(
              "insert into goods_receipt_event_line(id,tenant_id,event_id,line_id,"
                  + "received_quantity,lot_id) values(?,?,?,?,?,?)",
              eventLineA,
              a,
              eventA,
              lineA,
              new BigDecimal("100"),
              lotA);
          return null;
        });
    stockAt(a, skuA, whA, leA, qSellA, locReceivingA, lotA, "100");

    // ---- B 租户：同样一张收货单，同样压在收货暂存位（用于两租户互不可见）
    access.run(
        actorB,
        "warehouse.putaway.update",
        () -> {
          sql.execute(
              "insert into goods_receipt(id,tenant_id,receipt_code,source_type,source_ref_code,"
                  + "warehouse_id,legal_entity_id,status,created_by)"
                  + " values(?,?,?,'PURCHASE','PO-B',?,?,'RECEIVED',?)",
              receiptB,
              b,
              "GR-B-1",
              whB,
              leB,
              user);
          sql.execute(
              "insert into goods_receipt_line(id,tenant_id,receipt_id,sku_id,quality_status_id,"
                  + "expected_quantity,received_quantity) values(?,?,?,?,?,?,?)",
              lineB,
              b,
              receiptB,
              skuB2,
              qSellB,
              new BigDecimal("50"),
              new BigDecimal("50"));
          sql.execute(
              "insert into goods_receipt_event(id,tenant_id,receipt_id,received_at,location_id,"
                  + "actor_id) values(?,?,?,?::timestamptz,?,?)",
              eventB,
              b,
              receiptB,
              OffsetDateTime.now(),
              locReceivingB,
              user);
          sql.execute(
              "insert into goods_receipt_event_line(id,tenant_id,event_id,line_id,"
                  + "received_quantity,lot_id) values(?,?,?,?,?,null)",
              eventLineB,
              b,
              eventB,
              lineB,
              new BigDecimal("50"));
          return null;
        });
    stockAt(b, skuB2, whB, leB, qSellB, locReceivingB, null, "50");
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.putaway.read", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.putaway.read", work);
  }

  <T> T asAU(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.putaway.update", work);
  }

  <T> T asBU(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.putaway.update", work);
  }

  /** 造一条库存：bucket 与 balance 同时写，守恒触发器会核对两者。 */
  void stockAt(
      UUID tenant,
      UUID skuId,
      UUID warehouseId,
      UUID legalEntityId,
      UUID qualityId,
      UUID locationId,
      UUID lotId,
      String onHand) {
    access.run(
        actorFor(tenant),
        "warehouse.putaway.update",
        () -> {
          UUID balance = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
              balance,
              tenant,
              skuId,
              warehouseId,
              legalEntityId,
              qualityId,
              new BigDecimal(onHand));
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                  + "location_id,lot_id,quality_status_id,balance_id,on_hand,reserved,"
                  + "last_movement_at) values(?,?,?,?,?,?,?,?,?,?,0,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              legalEntityId,
              skuId,
              warehouseId,
              locationId,
              lotId,
              qualityId,
              balance,
              new BigDecimal(onHand),
              OffsetDateTime.now());
          return null;
        });
  }

  /**
   * 给 A 的收货单补一条**第二条**明细行（另一个 SKU、无批次）。
   *
   * <p>需要它的原因：一条收货明细行同时只能有一个未取消的上架任务 （V109 的 putaway_task_line_open 部分唯一索引），所以「两张任务单」这个夹具
   * 只能靠第二条收货明细行来造，不能靠把同一行指两次。
   */
  void addSecondReceiptLine(String received) {
    asAU(
        () -> {
          sql.execute(
              "insert into goods_receipt_line(id,tenant_id,receipt_id,sku_id,quality_status_id,"
                  + "expected_quantity,received_quantity) values(?,?,?,?,?,?,?)",
              idFor(a, 25),
              a,
              receiptA,
              skuB,
              qSellA,
              new BigDecimal(received),
              new BigDecimal(received));
          sql.execute(
              "insert into goods_receipt_event_line(id,tenant_id,event_id,line_id,"
                  + "received_quantity,lot_id) values(?,?,?,?,?,null)",
              idFor(a, 26),
              a,
              eventA,
              idFor(a, 25),
              new BigDecimal(received));
          return null;
        });
  }

  /** 给 A 的收货单补一条**残次**质量状态的收货明细行（验证质量状态→库位类型硬规则用）。 */
  UUID addDefectiveReceiptLine(String received) {
    UUID lineId = idFor(a, 27);
    asAU(
        () -> {
          sql.execute(
              "insert into goods_receipt_line(id,tenant_id,receipt_id,sku_id,quality_status_id,"
                  + "expected_quantity,received_quantity) values(?,?,?,?,?,?,?)",
              lineId,
              a,
              receiptA,
              skuA,
              qDefA,
              new BigDecimal(received),
              new BigDecimal(received));
          sql.execute(
              "insert into goods_receipt_event_line(id,tenant_id,event_id,line_id,"
                  + "received_quantity,lot_id) values(?,?,?,?,?,?)",
              idFor(a, 28),
              a,
              eventA,
              lineId,
              new BigDecimal(received),
              lotA);
          return null;
        });
    return lineId;
  }

  /** 标准建单：一行，把 A 的那条收货明细行搬到存储一位。 */
  PutawayTaskModels.PutawayTaskCreateInput createInput(UUID targetLocationId) {
    return new PutawayTaskModels.PutawayTaskCreateInput(
        receiptA,
        null,
        List.of(
            new PutawayTaskModels.PutawayTaskLineInput(
                lineA, targetLocationId, lotA, null, null, "标准行")),
        "上架任务测试");
  }

  PutawayTaskModels.PutawayTaskCreated newTask(UUID targetLocationId) {
    return asAU(() -> putaway.create(createInput(targetLocationId), user));
  }

  PutawayTaskModels.PutawayTaskCreated newTask() {
    return newTask(locStorageA);
  }

  PutawayTaskModels.PutawayTaskScanInput scanInput(UUID lineId, String quantity, String reference) {
    return new PutawayTaskModels.PutawayTaskScanInput(
        lineId, new BigDecimal(quantity), reference, "PDA", OffsetDateTime.now(), "扫码");
  }

  BigDecimal onHand(UUID tenant, UUID skuId, UUID locationId) {
    return access.run(
        actorFor(tenant),
        "warehouse.putaway.read",
        () -> {
          Record row =
              sql.fetchOne(
                  "select on_hand from stock_bucket where sku_id=? and location_id=?",
                  skuId,
                  locationId);
          return row == null ? null : row.get("on_hand", BigDecimal.class);
        });
  }

  // ================================================================ 能力与迁移

  @Test
  @DisplayName("能力声明把「只改位置不改数量」、硬规则与未交付下游如实报出来")
  void capabilities() {
    var caps = asA(() -> putaway.capabilities());
    assertThat(caps.refType()).isEqualTo("PUTAWAY_TASK");
    assertThat(caps.atomicLocationMoves()).isTrue();
    assertThat(caps.quantityIsNotAnInput()).isTrue();
    assertThat(caps.quantityBoundary()).contains("没有数量字段").contains("净额恰好为 0");
    assertThat(caps.upstreamBoundary()).contains("复合外键").contains("goods_receipt_line");
    assertThat(caps.allocationBoundary())
        .contains("不重写推荐算法")
        .contains("location_allocation_required_type");
    assertThat(caps.invariant()).contains("P1").contains("P2").contains("P3");
    assertThat(caps.upstreamModules())
        .anySatisfy(
            m -> {
              assertThat(m.moduleId()).isEqualTo("8.2.1.1");
              assertThat(m.available()).isTrue();
            });
    assertThat(caps.downstreamModules())
        .filteredOn(m -> m.moduleId().equals("8.3.2.1"))
        .singleElement()
        .satisfies(m -> assertThat(m.available()).isFalse());
  }

  @Test
  @DisplayName("三张业务表都 tenant_id NOT NULL 且 relforcerowsecurity")
  void rlsIsForcedOnEveryTable() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var p = c.prepareStatement("select relforcerowsecurity from pg_class where relname = ?")) {
      for (var table : List.of("putaway_task", "putaway_task_line", "putaway_task_scan")) {
        p.setString(1, table);
        boolean forced;
        try (var rs = p.executeQuery()) {
          assertThat(rs.next()).as(table).isTrue();
          forced = rs.getBoolean(1);
        }
        assertThat(forced).as(table).isTrue();
      }
    }
    for (var table : List.of("putaway_task", "putaway_task_line", "putaway_task_scan")) {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var p =
              c.prepareStatement(
                  "select is_nullable from information_schema.columns"
                      + " where table_name = ? and column_name = 'tenant_id'")) {
        p.setString(1, table);
        try (var rs = p.executeQuery()) {
          assertThat(rs.next()).as(table).isTrue();
          assertThat(rs.getString(1)).as(table).isEqualTo("NO");
        }
      }
    }
  }

  // ================================================================ 建单：数量不是输入

  @Test
  @DisplayName("建单：应上架量取自收货明细行的实收量并在建单时冻结，货还压在收货暂存位")
  void createFreezesReceivedQuantityAndMovesNoStock() {
    var task = newTask();
    assertThat(task.status()).isEqualTo("PENDING");
    assertThat(task.detailLineCount()).isEqualTo(1);
    // 收货行实收 100 → 应上架量 100。**调用方没有传数量的机会**。
    assertThat(task.plannedQuantity()).isEqualByComparingTo("100");

    // 货没动：还在收货暂存位，存储位是空的
    assertThat(onHand(a, skuA, locReceivingA)).isEqualByComparingTo("100");
    assertThat(onHand(a, skuA, locStorageA)).isNull();

    var detail = asA(() -> putaway.detail(task.taskId()));
    assertThat(detail.task().status()).isEqualTo("PENDING");
    assertThat(detail.lines()).hasSize(1);
    var line = detail.lines().get(0);
    assertThat(line.sourceLocationId()).isEqualTo(locReceivingA);
    assertThat(line.targetLocationId()).isEqualTo(locStorageA);
    assertThat(line.plannedQuantity()).isEqualByComparingTo("100");
    assertThat(line.putawayQuantity()).isEqualByComparingTo("0");
    assertThat(line.remainingQuantity()).isEqualByComparingTo("100");
    assertThat(line.completed()).isFalse();
    // 源 bucket / 余额行由数据库触发器定位，不是调用方填的
    assertThat(line.sourceBucketId()).isNotNull();
    assertThat(line.balanceId()).isNotNull();
    // 没扫码就没有分录，净额 0
    assertThat(detail.entries()).isEmpty();
    assertThat(detail.netOnHandDelta()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("建单：收货方事后改实收量，不影响已建的上架任务（快照冻结）")
  void receiptChangeDoesNotMutateAnExistingTask() {
    var task = newTask();
    asAU(
        () -> {
          // V70 有「超收未留痕」守卫：实收量大于预期量时必须同时登记 OVER_RECEIPT 差异记录，
          // 那是 8.2.1.2 的职责。这里先把预期量抬上去，再改实收量 —— 本用例只关心
          // 「收货方事后改数不会影响已建的上架任务」，不该去碰别的模块的差异流程。
          sql.execute(
              "update goods_receipt_line set expected_quantity=? where id=?",
              new BigDecimal("1000"),
              lineA);
          sql.execute(
              "update goods_receipt_line set received_quantity=? where id=?",
              new BigDecimal("999"),
              lineA);
          return null;
        });
    // 已建任务仍是 100：收货时定下的量被冻结进了作业单
    assertThat(asA(() -> putaway.detail(task.taskId())).lines().get(0).plannedQuantity())
        .isEqualByComparingTo("100");
  }

  @Test
  @DisplayName("建单：草稿收货单（尚未实收）长不出上架任务")
  void draftReceiptCannotBePutAway() {
    asAU(
        () -> {
          sql.execute("update goods_receipt set status='DRAFT' where id=?", receiptA);
          return null;
        });
    assertThatThrownBy(() -> newTask())
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("DRAFT");
  }

  @Test
  @DisplayName("建单：硬规则 —— 残次品只能进残次库位，不许混放（DB 裁决）")
  void defectiveGoodsCannotGoToStorageLocation() {
    // 自备一条**残次**收货明细行与它的库存（不能复用第一条：那条的库存是按
    // SELLABLE 质量状态落的，而质量状态是 5.4 定位源位 bucket 的键之一）。
    UUID defectLine = addDefectiveReceiptLine("40");
    stockAt(a, skuA, whA, leA, qDefA, locReceivingA, lotA, "40");

    var defectInput =
        new PutawayTaskModels.PutawayTaskCreateInput(
            receiptA,
            null,
            List.of(
                new PutawayTaskModels.PutawayTaskLineInput(
                    defectLine, locStorageA, lotA, null, null, null)),
            null);
    // 残次品进存储位：被 5.2 拒（复用 V106 的 location_allocation_required_type）
    assertThatThrownBy(() -> asAU(() -> putaway.create(defectInput, user)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("残次");
    // 换成残次专用库位就成立
    var ok =
        asAU(
            () ->
                putaway.create(
                    new PutawayTaskModels.PutawayTaskCreateInput(
                        receiptA,
                        null,
                        List.of(
                            new PutawayTaskModels.PutawayTaskLineInput(
                                defectLine, locDefA, lotA, null, null, null)),
                        null),
                    user));
    assertThat(ok.detailLineCount()).isEqualTo(1);
    assertThat(asA(() -> putaway.detail(ok.taskId())).lines().get(0).qualityRole())
        .isEqualTo("DEFECTIVE");
  }

  @Test
  @DisplayName("建单：源位上根本没有这批货时，DB 直接拒绝（不是「凭空改位置」）")
  void sourceLocationMustActuallyHoldTheGoods() {
    // 把货从收货暂存位挪走（造一个「源位没货」的场景）
    asAU(
        () -> {
          sql.execute(
              "update goods_receipt_event set location_id=? where id=?", locStorageA2, eventA);
          return null;
        });
    // 源位（locStorageA2）没有这批货 → 触发器 5.4 拒
    assertThatThrownBy(() -> newTask())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("上架是把**已有的货**挪个位置");
  }

  @Test
  @DisplayName("建单：同一收货明细行不能有两个上架任务（部分唯一索引，取消后可重新指派）")
  void oneReceiptLineOneOpenPutaway() {
    var first = newTask();
    assertThatThrownBy(() -> newTask())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("putaway_task_line_open");
    // 取消之后释放占用，可以重新指派（货没搬过，取消不动库存）
    asAU(
        () ->
            putaway.cancel(
                first.taskId(), new PutawayTaskModels.PutawayTaskCancelInput("重新指派"), user));
    var second = newTask();
    assertThat(second.taskId()).isNotEqualTo(first.taskId());
  }

  // ================================================================ 派工 / 开始

  @Test
  @DisplayName("派工与开始：都不动库存，开始后路由字段被锁死")
  void assignAndStartMoveNoStock() {
    var task = newTask();
    UUID assignee = UUID.randomUUID();

    var assigned =
        asAU(
            () ->
                putaway.assign(
                    task.taskId(),
                    new PutawayTaskModels.PutawayTaskAssignInput(assignee, "小王"),
                    user));
    assertThat(assigned.assigneeId()).isEqualTo(assignee);
    assertThat(assigned.assignedAt()).isNotNull();
    assertThat(assigned.status()).isEqualTo("PENDING");

    var started = asAU(() -> putaway.start(task.taskId(), null, user));
    assertThat(started.status()).isEqualTo("IN_PROGRESS");
    assertThat(started.startedAt()).isNotNull();
    // 货仍然全压在收货暂存位
    assertThat(onHand(a, skuA, locReceivingA)).isEqualByComparingTo("100");
    assertThat(onHand(a, skuA, locStorageA)).isNull();
  }

  // ================================================================ 扫码：只改位置

  @Test
  @DisplayName("扫码：源位扣、目标位加、成对分录、净额恰好 0（蓝图加粗句的正面用例）")
  void scanMovesQuantityBetweenBucketsWithoutChangingTheTotal() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    BigDecimal balanceBefore =
        asA(
            () ->
                sql.fetchOne("select on_hand from inventory_balance where id = ?", line.balanceId())
                    .get("on_hand", BigDecimal.class));

    var scanned =
        asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "100", "SCAN-1"), user));

    // 一减一加，两个 delta 互为相反数 —— 这就是「只改位置不改数量」在单次作业上的读数
    assertThat(scanned.scannedQuantity()).isEqualByComparingTo("100");
    assertThat(scanned.onHandDeltaOut()).isEqualByComparingTo("-100");
    assertThat(scanned.onHandDeltaIn()).isEqualByComparingTo("100");
    assertThat(scanned.onHandDeltaOut().add(scanned.onHandDeltaIn())).isEqualByComparingTo("0");
    assertThat(scanned.lineCompleted()).isTrue();
    assertThat(scanned.taskStatus()).isEqualTo("IN_PROGRESS");

    // **数量真的没变**：货位换了，总量一模一样，balance 一个数都没动
    assertThat(onHand(a, skuA, locReceivingA)).isEqualByComparingTo("0");
    assertThat(onHand(a, skuA, locStorageA)).isEqualByComparingTo("100");
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select on_hand from inventory_balance where id = ?", line.balanceId())
                        .get("on_hand", BigDecimal.class)))
        .isEqualByComparingTo(balanceBefore);

    // 分录成对，且都落在同一条余额行上（这是 P1 净额为 0 的前提）
    var detail = asA(() -> putaway.detail(task.taskId()));
    assertThat(detail.entries()).hasSize(2);
    assertThat(detail.entries())
        .allSatisfy(e -> assertThat(e.balanceId()).isEqualTo(line.balanceId()));
    assertThat(detail.netOnHandDelta()).isEqualByComparingTo("0");
    assertThat(detail.scans()).hasSize(1);
    assertThat(detail.task().scanCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("扫码：可以分多次搬，执行中的净额非 0 是真实中间态（货在路上）")
  void partialScanIsAllowedWhileInProgress() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);

    var first = asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "40", "S1"), user));
    assertThat(first.lineCompleted()).isFalse();
    assertThat(first.linePutawayQuantity()).isEqualByComparingTo("40");
    assertThat(first.linePlannedQuantity()).isEqualByComparingTo("100");

    // 进行中：货 40 在目标位、60 在收货暂存位，总量仍然是 100
    assertThat(onHand(a, skuA, locStorageA)).isEqualByComparingTo("40");
    assertThat(onHand(a, skuA, locReceivingA)).isEqualByComparingTo("60");
    // 净额**仍然是 0**：一次扫码写的就是一对反向分录，成对的两条加起来恒为 0。
    // 所以净额不是「做完没做完」的判据 —— 做没做完看明细量（这才是 P2/P3 管的）。
    assertThat(asA(() -> putaway.detail(task.taskId())).netOnHandDelta()).isEqualByComparingTo("0");
    assertThat(asA(() -> putaway.detail(task.taskId())).task().putawayQuantity())
        .isEqualByComparingTo("40");

    var second =
        asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "60", "S2"), user));
    assertThat(second.lineCompleted()).isTrue();
    assertThat(onHand(a, skuA, locStorageA)).isEqualByComparingTo("100");
    assertThat(onHand(a, skuA, locReceivingA)).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("扫码：累计超量被拒（只改位置不改数量，超量等于凭空搬出库外）")
  void cumulativeOverScanIsRejected() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "90", "S1"), user));
    assertThatThrownBy(
            () ->
                asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "20", "S2"), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("超过应上架量");
    assertThat(onHand(a, skuA, locStorageA)).isEqualByComparingTo("90");
  }

  @Test
  @DisplayName("扫码：源位手数不足被拒，且失败时目标位没有被加（没有半截账）")
  void sourceShortfallLeavesNoHalfState() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    // 把源位的手数抽走一部分（造「货不在那儿了」的场景）
    asAU(
        () -> {
          OffsetDateTime later = OffsetDateTime.now().plusMinutes(1);
          sql.execute(
              "update stock_bucket set on_hand=5, last_movement_at=?::timestamptz where id=?",
              later,
              line.sourceBucketId());
          // V2 的守恒触发器要求 Σ bucket == balance，所以两边必须同步改。
          // （这不是本模块的写路径 —— 它只是把夹具摆成「货已经不在源位了」的现实状态。）
          sql.execute(
              "update inventory_balance set on_hand=5, updated_at=?::timestamptz where id=?",
              later,
              line.balanceId());
          return null;
        });
    assertThatThrownBy(
            () ->
                asAU(
                    () -> putaway.scan(task.taskId(), scanInput(line.lineId(), "100", "S1"), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("在手");
    // 关键：目标位一个数都没被加 —— 失败即回滚，不留半截账
    assertThat(onHand(a, skuA, locStorageA)).isNull();
  }

  @Test
  @DisplayName("扫码：同一个 scanReference 重复提交被幂等挡下（PDA 网络抖动重发）")
  void duplicateScanReferenceIsRejectedByIdempotency() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    // 第一次：搬 50。第二次是**完全相同的重发**（累计恰好 100，不会被超量检查先拒），
    // 于是它一定能走到写扫码台账那一步 —— 只有幂等索引能挡下它。
    asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "50", "PDA-DUP-1"), user));
    assertThatThrownBy(
            () ->
                asAU(
                    () ->
                        putaway.scan(
                            task.taskId(), scanInput(line.lineId(), "50", "PDA-DUP-1"), user)))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("putaway_task_scan_idempotent");
    // 货只搬了一遍：一次重发没有变成「搬两遍」
    assertThat(onHand(a, skuA, locStorageA)).isEqualByComparingTo("50");
    assertThat(onHand(a, skuA, locReceivingA)).isEqualByComparingTo("50");
    assertThat(asA(() -> putaway.detail(task.taskId())).entries()).hasSize(2);
    assertThat(asA(() -> putaway.detail(task.taskId())).scans()).hasSize(1);
  }

  @Test
  @DisplayName("扫码：超 4 位小数的量被显式拒绝（不静默取整，取整会让 P1 看起来成立而实物对不上）")
  void overScaleQuantityIsRejected() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    assertThatThrownBy(
            () ->
                asAU(
                    () ->
                        putaway.scan(
                            task.taskId(), scanInput(line.lineId(), "1.00005", "S1"), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("4 位小数");
  }

  @Test
  @DisplayName("扫码：scanned_at 是业务时间，写回值必须一模一样（偏 8 小时最容易蒙混过关）")
  void scannedAtIsPersistedAsGivenBusinessTime() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    // 带时区偏移的时刻：偏 -28800s 的经典写法在这里会被当场抓住
    OffsetDateTime businessTime = OffsetDateTime.parse("2026-03-15T14:30:00+08:00");
    asAU(
        () ->
            putaway.scan(
                task.taskId(),
                new PutawayTaskModels.PutawayTaskScanInput(
                    line.lineId(), new BigDecimal("100"), "T-1", "PDA", businessTime, "补录"),
                user));

    OffsetDateTime written =
        asA(
            () ->
                sql.fetchOne(
                        "select scanned_at from putaway_task_scan where task_id = ?", task.taskId())
                    .get("scanned_at", OffsetDateTime.class));
    // 断言写回的值本身，而不是「大概对」：同一个瞬间，秒级一致
    assertThat(written.toInstant()).isEqualTo(businessTime.toInstant());
    assertThat(written.withOffsetSameInstant(java.time.ZoneOffset.ofHours(8)).toString())
        .isEqualTo("2026-03-15T14:30+08:00");
  }

  // ================================================================ 完成：三条不变量

  @Test
  @DisplayName("完成：全部搬完后转已完成，净额归 0（P1/P2/P3 全部通过）")
  void completeAfterAllLinesMoved() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "100", "S1"), user));

    var done = asAU(() -> putaway.complete(task.taskId(), null, user));
    assertThat(done.status()).isEqualTo("COMPLETED");
    assertThat(done.completedAt()).isNotNull();
    assertThat(done.putawayQuantity()).isEqualByComparingTo("100");
    assertThat(asA(() -> putaway.detail(task.taskId())).netOnHandDelta()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("P2：半张单搬到一半就说完成，被数据库拒绝")
  void completeWithUnfinishedLineIsRejected() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "40", "S1"), user));
    assertThatThrownBy(() -> asAU(() -> putaway.complete(task.taskId(), null, user)))
        .hasMessageContaining("P2");
    assertThat(asA(() -> putaway.detail(task.taskId())).task().status()).isEqualTo("IN_PROGRESS");
  }

  @Test
  @DisplayName("P1：绕过服务层直连 SQL 写一条净额不为 0 的上架交易，完成时同样被拒")
  void netZeroInvariantIsEnforcedInTheDatabaseEvenViaRawSql() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "100", "S1"), user));

    // **绕过服务层**：直接往这条上架交易上补一条只有出、没有入的分录，
    // 于是净额从 0 变成 -10 —— 账上凭空少了 10 件货。
    asAU(
        () -> {
          var tx =
              sql.fetchOne(
                      "select inventory_transaction_id from putaway_task where id = ?",
                      task.taskId())
                  .get("inventory_transaction_id", UUID.class);
          sql.execute(
              "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,"
                  + "reserved_delta) values(?,?,?,?,?,0)",
              UUID.randomUUID(),
              a,
              tx,
              line.balanceId(),
              new BigDecimal("-10"));
          return null;
        });
    // 完成任务时守卫回读账本，发现净额 -10 ≠ 0 → 拒绝
    assertThatThrownBy(() -> asAU(() -> putaway.complete(task.taskId(), null, user)))
        .hasMessageContaining("P1")
        .hasMessageContaining("净额");
  }

  @Test
  @DisplayName("P3：账上只搬了 90、单据却写着搬完 100，净额仍是 0 —— 靠 P3 抓")
  void magnitudeInvariantCatchesUnderMovement() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    // 先老老实实搬 90：账上得到一对 -90/+90，净额恰好 0（P1 过），明细实量 90。
    asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "90", "S1"), user));

    // **绕过服务层**：把明细的实上架量直接改成 100（等于单头声称「搬完了」）。
    // 这是守卫唯一允许的方向（往回改会被拒），所以它恰好是「绕过服务就能造出假账」的那种写法。
    asAU(
        () -> {
          sql.execute(
              "update putaway_task_line set putaway_quantity=? where id=?",
              new BigDecimal("100"),
              line.lineId());
          return null;
        });
    // 此时 P2 已经过了（每行 100 = 100），P1 也会过（净额仍是 0）。
    // 只有独立核对「搬了多少」的 P3 抓得住它。
    assertThatThrownBy(() -> asAU(() -> putaway.complete(task.taskId(), null, user)))
        .hasMessageContaining("P3");
  }

  @Test
  @DisplayName("完成：终态不可逆，且已完成的单子不能再扫码或取消")
  void terminalStateIsIrreversible() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "100", "S1"), user));
    asAU(() -> putaway.complete(task.taskId(), null, user));

    // 终态再改状态 → 守卫直接拒
    assertThatThrownBy(
            () ->
                asAU(
                    () -> {
                      sql.execute(
                          "update putaway_task set status='PENDING' where id=?", task.taskId());
                      return null;
                    }))
        .hasMessageContaining("终态");

    // 再扫一次 → 409（货已经在正式库位上了）
    assertThatThrownBy(
            () -> asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "1", "X"), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能继续扫码");
    // 取消已完成的 → 409
    assertThatThrownBy(
            () ->
                asAU(
                    () ->
                        putaway.cancel(
                            task.taskId(),
                            new PutawayTaskModels.PutawayTaskCancelInput("反悔"),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能取消");
  }

  @Test
  @DisplayName("取消：只对尚未扫码的任务开放，且一条分录都不产生")
  void cancelBeforeAnyScanMovesNoStock() {
    var task = newTask();
    var cancelled =
        asAU(
            () ->
                putaway.cancel(
                    task.taskId(), new PutawayTaskModels.PutawayTaskCancelInput("货还没到齐"), user));
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(cancelled.cancelReason()).isEqualTo("货还没到齐");

    var detail = asA(() -> putaway.detail(task.taskId()));
    assertThat(detail.entries()).isEmpty();
    assertThat(detail.netOnHandDelta()).isEqualByComparingTo("0");
    assertThat(onHand(a, skuA, locStorageA)).isNull();
    assertThat(onHand(a, skuA, locReceivingA)).isEqualByComparingTo("100");
  }

  @Test
  @DisplayName("取消：已经扫过码的任务不能取消（货已经在往目标库位搬了）")
  void cancelAfterScanIsRejected() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "10", "S1"), user));
    assertThatThrownBy(
            () ->
                asAU(
                    () ->
                        putaway.cancel(
                            task.taskId(),
                            new PutawayTaskModels.PutawayTaskCancelInput("反悔"),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经扫了");
  }

  @Test
  @DisplayName("执行中：路由字段（目标库位/应上架量）被锁死，实上架量不许往回改")
  void routingFieldsAreFrozenWhileInProgress() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "10", "S1"), user));

    assertThatThrownBy(
            () ->
                asAU(
                    () -> {
                      sql.execute(
                          "update putaway_task_line set target_location_id=? where id=?",
                          locStorageA2,
                          line.lineId());
                      return null;
                    }))
        .hasMessageContaining("锁死");
    assertThatThrownBy(
            () ->
                asAU(
                    () -> {
                      sql.execute(
                          "update putaway_task_line set putaway_quantity=0 where id=?",
                          line.lineId());
                      return null;
                    }))
        .hasMessageContaining("往回改");
  }

  // ================================================================ 查询

  @Test
  @DisplayName("列表：真分页 + 真筛选 + 排序白名单 + 汇总 + 目标库位分布")
  void listPageFiltersAndSummary() {
    addSecondReceiptLine("60");
    stockAt(a, skuB, whA, leA, qSellA, locReceivingA, null, "60");
    newTask(locStorageA);
    var second =
        asAU(
            () ->
                putaway.create(
                    new PutawayTaskModels.PutawayTaskCreateInput(
                        receiptA,
                        null,
                        List.of(
                            new PutawayTaskModels.PutawayTaskLineInput(
                                idFor(a, 25), locStorageA2, null, null, null, null)),
                        null),
                    user));
    asAU(
        () ->
            putaway.assign(
                second.taskId(),
                new PutawayTaskModels.PutawayTaskAssignInput(UUID.randomUUID(), null),
                user));

    var all = asA(() -> putaway.page(null, null, null, null, null, null, null, 0, 50));
    assertThat(all.total()).isEqualTo(2);
    // 两张任务：100（A-01 那行）+ 60（A-02 那行）。合计来自标量子查询，不被行数放大。
    assertThat(all.summary().plannedQuantity()).isEqualByComparingTo("160");
    assertThat(all.summary().unassignedCount()).isEqualTo(1);
    // 目标库位分布：这一批货正在往哪个库位搬
    assertThat(all.targetStats()).hasSize(2);
    assertThat(all.targetStats())
        .anySatisfy(
            s -> {
              assertThat(s.locationCode()).isEqualTo("A-01");
              assertThat(s.plannedQuantity()).isEqualByComparingTo("100");
            })
        .anySatisfy(
            s -> {
              assertThat(s.locationCode()).isEqualTo("A-02");
              assertThat(s.plannedQuantity()).isEqualByComparingTo("60");
            });

    // 真分页
    assertThat(asA(() -> putaway.page(null, null, null, null, null, null, null, 0, 1)).items())
        .hasSize(1);
    assertThat(asA(() -> putaway.page(null, null, null, null, null, null, null, 1, 1)).items())
        .hasSize(1);

    // 真筛选
    assertThat(
            asA(() -> putaway.page("PENDING", null, null, null, null, null, null, 0, 50)).total())
        .isEqualTo(2);
    assertThat(
            asA(() -> putaway.page("COMPLETED", null, null, null, null, null, null, 0, 50)).total())
        .isZero();
    assertThat(asA(() -> putaway.page(null, null, receiptA, null, null, null, null, 0, 50)).total())
        .isEqualTo(2);
    // 按 SKU 筛必须落到明细上（单头没有 sku_id）：第一张任务搬 skuA，第二张搬 skuB
    assertThat(asA(() -> putaway.page(null, null, null, null, skuA, null, null, 0, 50)).total())
        .isEqualTo(1);
    assertThat(asA(() -> putaway.page(null, null, null, null, skuB, null, null, 0, 50)).total())
        .isEqualTo(1);
    assertThat(
            asA(
                () ->
                    putaway
                        .page(null, null, null, null, UUID.randomUUID(), null, null, 0, 50)
                        .total()))
        .isEqualTo(0L);
    // 真关键词搜索
    assertThat(asA(() -> putaway.page(null, null, null, null, null, "GR-A-1", null, 0, 50)).total())
        .isEqualTo(2);
    assertThat(asA(() -> putaway.page(null, null, null, null, null, "GR-B-1", null, 0, 50)).total())
        .isZero();

    // 排序白名单：非法键直接报错，而不是静默忽略
    assertThatThrownBy(
            () -> asA(() -> putaway.page(null, null, null, null, null, null, "DROP TABLE", 0, 50)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的排序键");
    // 非法状态同样
    assertThatThrownBy(
            () ->
                asA(() -> putaway.page("NOT_A_STATUS", null, null, null, null, null, null, 0, 50)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的状态");
  }

  @Test
  @DisplayName("详情：把「只改位置不改数量」摆到页面能核的位置（源位/目标位/两量/净额）")
  void detailExposesBothQuantitiesAndTheNet() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "30", "S1"), user));

    var detail = asA(() -> putaway.detail(task.taskId()));
    var shown = detail.lines().get(0);
    assertThat(shown.sourceLocationCode()).isEqualTo("RCV-01");
    assertThat(shown.targetLocationCode()).isEqualTo("A-01");
    assertThat(shown.plannedQuantity()).isEqualByComparingTo("100");
    assertThat(shown.putawayQuantity()).isEqualByComparingTo("30");
    assertThat(shown.remainingQuantity()).isEqualByComparingTo("70");
    assertThat(detail.netOnHandDelta()).isEqualByComparingTo("0");
    assertThat(detail.note()).contains("上架只改位置不改数量").contains("净额为 0 **不代表**任务做完了");
    // 方向标注可用，前端不必自己猜哪条是出哪条是进
    assertThat(detail.entries())
        .anySatisfy(e -> assertThat(e.direction()).isEqualTo("SOURCE_OUT"))
        .anySatisfy(e -> assertThat(e.direction()).isEqualTo("TARGET_IN"));
  }

  // ================================================================ 多租户三断言

  @Test
  @DisplayName("多租户断言 1：两租户互不可见")
  void tenantsCannotSeeEachOther() {
    var taskA = newTask();
    var taskB =
        asBU(
            () ->
                putaway.create(
                    new PutawayTaskModels.PutawayTaskCreateInput(
                        receiptB,
                        null,
                        List.of(
                            new PutawayTaskModels.PutawayTaskLineInput(
                                lineB, locStorageB, null, null, null, null)),
                        null),
                    user));

    assertThat(asA(() -> putaway.page(null, null, null, null, null, null, null, 0, 50)).total())
        .isEqualTo(1);
    assertThat(
            asA(() -> putaway.page(null, null, null, null, null, null, null, 0, 50))
                .items()
                .get(0)
                .taskId())
        .isEqualTo(taskA.taskId());
    assertThatThrownBy(() -> asA(() -> putaway.detail(taskB.taskId())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在或无权操作");
    // B 自己看得到
    assertThat(asB(() -> putaway.detail(taskB.taskId())).task().taskCode())
        .isEqualTo(taskB.taskCode());
  }

  @Test
  @DisplayName("多租户断言 2：伪造 X-Tenant-Id 头无效（端点只认已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "100", "S1"), user));
    asAU(() -> putaway.complete(task.taskId(), null, user));

    // 带伪造的 B 租户头访问：身份仍由 Bearer 决定，落到 A 租户，读到的是 A 的数据。
    http.perform(
            get("/warehouse/putaway-tasks/" + task.taskId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.task.taskId").value(task.taskId().toString()));

    // 伪造租户头也拿不到 B 的单子
    http.perform(
            get("/warehouse/putaway-tasks")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString())
                .param("receiptId", receiptB.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  @DisplayName("多租户断言 3：连接池归还后不串租户（池压到 1，20 轮往返）")
  void connectionPoolDoesNotLeakTenant() {
    TenantTransactions tenants = tenants();
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "100", "S1"), user));
    asAU(() -> putaway.complete(task.taskId(), null, user));

    for (int i = 0; i < 20; i++) {
      assertThat(asA(() -> putaway.detail(task.taskId()).task().taskCode()))
          .isEqualTo(task.taskCode());
      // B 租户事务内看不见 A 的上架任务
      assertThat(countTasksUnder(b)).isZero();
      // 事务外（没有任何租户上下文）同样一行都看不见
      assertThat(countTasksWithoutTenant()).isZero();
    }
    assertThat(tenants).isNotNull();
  }

  long countTasksUnder(UUID tenant) {
    Long count =
        tenants()
            .run(
                tenant, () -> sql.fetchOne("select count(*) from putaway_task").get(0, Long.class));
    return count == null ? 0L : count;
  }

  long countTasksWithoutTenant() {
    Long count = sql.fetchOne("select count(*) from putaway_task").get(0, Long.class);
    return count == null ? 0L : count;
  }

  // ================================================================ HTTP 契约

  @Test
  @DisplayName("HTTP 契约：401 未认证 / 403 只读 / 404 不存在 / 400 数量非法")
  void httpContract() throws Exception {
    http.perform(get("/warehouse/putaway-tasks/capabilities")).andExpect(status().isUnauthorized());

    http.perform(
            get("/warehouse/putaway-tasks/capabilities")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk());

    // 只读身份不能扫码（那会真的搬货）
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    http.perform(
            post("/warehouse/putaway-tasks/" + task.taskId() + "/scan")
                .header("Authorization", "Bearer read-only")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(scanInput(line.lineId(), "10", "H-1"))))
        .andExpect(status().isForbidden());

    // 取消原因必填（空串 → 400）
    http.perform(
            post("/warehouse/putaway-tasks/" + task.taskId() + "/cancel")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"\"}"))
        .andExpect(status().isBadRequest());

    // 不存在的任务 → 404
    http.perform(
            get("/warehouse/putaway-tasks/" + UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());

    // 非法设备类型 → 400
    http.perform(
            post("/warehouse/putaway-tasks/" + task.taskId() + "/scan")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new PutawayTaskModels.PutawayTaskScanInput(
                            line.lineId(), BigDecimal.ONE, "H-2", "TELEPATHY", null, null))))
        .andExpect(status().isBadRequest());

    // 建单必须给至少一行 → Bean Validation 400
    http.perform(
            post("/warehouse/putaway-tasks")
                .header("Authorization", "Bearer tenant-a")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new PutawayTaskModels.PutawayTaskCreateInput(
                            receiptA, null, List.of(), null))))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("扫码台账是 append-only：权限层与触发器两道都要挡得住")
  void scanLedgerIsAppendOnly() throws Exception {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "100", "S1"), user));

    // ---- 第一道：权限层。用应用角色 warehouse_app 直连，它只有 SELECT/INSERT。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "warehouse_app", "test-only");
        var s = c.createStatement()) {
      assertThatThrownBy(() -> s.execute("update putaway_task_scan set note='x'"))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("permission denied");
      assertThatThrownBy(() -> s.execute("delete from putaway_task_scan"))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("permission denied");
    }

    // ---- 第二道：触发器。表 owner / 超级用户绕过权限层，所以必须另有一道 ——
    // 少这一道的话「改作业记录」对 DBA 就是一扇开着的门。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      assertThatThrownBy(() -> s.execute("update putaway_task_scan set scanned_quantity=1"))
          .hasMessageContaining("append-only");
      assertThatThrownBy(() -> s.execute("delete from putaway_task_scan"))
          .hasMessageContaining("append-only");
    }

    // 台账内容没被动过
    assertThat(asA(() -> putaway.detail(task.taskId())).scans()).hasSize(1);
  }

  @Test
  @DisplayName("列表与详情里的数量合计来自标量子查询，不被明细行数放大")
  void summaryIsNotInflatedByLineCount() {
    var task = newTask();
    var line = asA(() -> putaway.detail(task.taskId())).lines().get(0);
    asAU(() -> putaway.scan(task.taskId(), scanInput(line.lineId(), "100", "S1"), user));
    asAU(() -> putaway.complete(task.taskId(), null, user));

    var page = asA(() -> putaway.page(null, null, null, null, null, null, null, 0, 50));
    // 一行明细扫一次 = 2 条分录。若聚合被 join 放大，这里会变成 200 / 200。
    assertThat(page.summary().plannedQuantity()).isEqualByComparingTo("100");
    assertThat(page.summary().putawayQuantity()).isEqualByComparingTo("100");
    assertThat(page.summary().completedCount()).isEqualTo(1);
    assertThat(asA(() -> putaway.detail(task.taskId())).entries()).hasSize(2);
  }

  /** 保持 ArrayList 导入被使用（建单输入里逐行收集）。 */
  @Test
  @DisplayName("建单：明细行可以一次给多行，各自冻结各自的实收量")
  void multipleLinesAreSupported() {
    addSecondReceiptLine("60");
    stockAt(a, skuB, whA, leA, qSellA, locReceivingA, null, "60");

    var task =
        asAU(
            () ->
                putaway.create(
                    new PutawayTaskModels.PutawayTaskCreateInput(
                        receiptA,
                        null,
                        List.of(
                            new PutawayTaskModels.PutawayTaskLineInput(
                                lineA, locStorageA, lotA, null, null, null),
                            new PutawayTaskModels.PutawayTaskLineInput(
                                idFor(a, 25), locStorageA, null, null, null, null)),
                        null),
                    user));
    // 100 + 60 = 160（两个不同 SKU，各自的实收量）
    assertThat(task.plannedQuantity()).isEqualByComparingTo("160");
    assertThat(task.detailLineCount()).isEqualTo(2);

    var lines = new ArrayList<>(asA(() -> putaway.detail(task.taskId())).lines());
    assertThat(lines).hasSize(2);
    for (var l : lines) {
      asAU(
          () ->
              putaway.scan(
                  task.taskId(),
                  scanInput(l.lineId(), l.plannedQuantity().toPlainString(), "M-" + l.skuCode()),
                  user));
    }
    var done = asAU(() -> putaway.complete(task.taskId(), null, user));
    assertThat(done.status()).isEqualTo("COMPLETED");
    assertThat(asA(() -> putaway.detail(task.taskId())).netOnHandDelta()).isEqualByComparingTo("0");
  }
}
