package com.starcon.warehouse;

import static com.starcon.warehouse.InventorySerialModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
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
 * 7.3.2.1 序列号：录入/批量导入、数量与序列号条数强一致、状态机、位置与状态配套、 批次绑定、流转历史、时区偏移，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link InventoryReconciliationIntegrationTest} 完全一致（同样的 Testcontainers、 同样的 fake
 * identity server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）。
 *
 * <p><b>硬约束要在数据库层证明，不能只在应用层证明</b>。本类里三处直接绕过服务用裸 SQL 写： 操作头声明与条数不符、声明数对得上但少写流转行、试图把报废的号改回在库。
 * 只测应用层等于假设「没人会直接写库」，而这恰恰是那条 CHECK 与触发器存在的理由。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventorySerialIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(
              p ->
                  Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static final UUID leA = idFor(a, 1);
  static final UUID skuA = idFor(a, 2);
  static final UUID whSh = idFor(a, 3);
  static final UUID whSz = idFor(a, 4);
  static final UUID qSellable = idFor(a, 5);
  static final UUID locA = idFor(a, 6);
  static final UUID locB = idFor(a, 7);
  static final UUID locC = idFor(a, 8);
  static final UUID lotA = idFor(a, 9);
  static final UUID lotB = idFor(a, 10);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-serial-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
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
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      // 只有读权限：序列号校验（只算不写）必须只读权限可用，写入必须真的被挡住
      actors.put(
          "Bearer read-only",
          actor(user, a, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired InventorySerialService svc;
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
    // TRUNCATE 不触发行级触发器（append-only 只挡 UPDATE/DELETE），以超级用户连接执行
    // 可绕过 FORCE RLS —— 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate inventory_serial_movement, inventory_serial_operation, inventory_serial,"
              + " inventory_quality_status, warehouse_location, inventory_lot,"
              + " warehouse, sku_ref, legal_entity_ref restart identity cascade");
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
                "手机一号");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 3),
                tenant,
                "WH-SH",
                "上海仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "WH-SZ",
                "深圳仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type)"
                    + " values(?,?,?,?,?,'STORAGE')",
                idFor(tenant, 6),
                tenant,
                idFor(tenant, 3),
                "SH-A1",
                "上海仓A架1",
                "STORAGE");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type)"
                    + " values(?,?,?,?,?,'STORAGE')",
                idFor(tenant, 7),
                tenant,
                idFor(tenant, 3),
                "SH-B2",
                "上海仓B架2",
                "STORAGE");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type)"
                    + " values(?,?,?,?,?,'RECEIVING')",
                idFor(tenant, 8),
                tenant,
                idFor(tenant, 4),
                "SZ-RCV",
                "深圳收货区",
                "RECEIVING");
            sql.execute(
                "insert into inventory_lot(id,tenant_id,sku_id,lot_code,status) values(?,?,?,'LOT-2026-A','ACTIVE')",
                idFor(tenant, 9),
                tenant,
                idFor(tenant, 2));
            sql.execute(
                "insert into inventory_lot(id,tenant_id,sku_id,lot_code,status) values(?,?,?,'LOT-2026-B','DEPLETED')",
                idFor(tenant, 10),
                tenant,
                idFor(tenant, 2));
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  // ================================================================ 租户事务包装
  //
  // 服务方法必须在绑定 app.current_tenant 的事务里跑，否则 RLS 一条都看不见。
  // 裸调 service.xxx 会「看起来通过」其实读到空集 —— 那是最容易被误当成绿灯的假阳性，
  // 所以这里给每个入口包一层，而不是在测试里散落几十个 access.run。

  SerialPage page(SerialQuery q) {
    return access.run(actorA, "warehouse.inventory.read", () -> svc.page(q));
  }

  SerialProfile profile(UUID id) {
    return access.run(actorA, "warehouse.inventory.read", () -> svc.profile(id));
  }

  SerialMovementPage movements(SerialMovementQuery q) {
    return access.run(actorA, "warehouse.inventory.read", () -> svc.movements(q));
  }

  SerialOperationPage operations(SerialOperationQuery q) {
    return access.run(actorA, "warehouse.inventory.read", () -> svc.operations(q));
  }

  SerialStats stats(SerialQuery q) {
    return access.run(actorA, "warehouse.inventory.read", () -> svc.stats(q));
  }

  SerialMovementPage timeline(UUID id, int p, int size) {
    return access.run(actorA, "warehouse.inventory.read", () -> svc.timeline(id, p, size));
  }

  SerialValidationResult register(SerialRegisterRequest r) {
    return access.run(actorA, "warehouse.inventory.update", () -> svc.register(r, user));
  }

  SerialValidationResult importBatch(SerialImportRequest r) {
    return access.run(actorA, "warehouse.inventory.update", () -> svc.importBatch(r, user));
  }

  SerialValidationResult move(SerialMoveRequest r) {
    return access.run(actorA, "warehouse.inventory.update", () -> svc.move(r, user));
  }

  SerialValidationResult validate(SerialValidateRequest r) {
    return access.run(actorA, "warehouse.inventory.read", () -> svc.validate(r));
  }

  SerialProfile update(UUID id, SerialUpdateRequest r) {
    return access.run(actorA, "warehouse.inventory.update", () -> svc.update(id, r));
  }

  void delete(UUID id) {
    access.run(
        actorA,
        "warehouse.inventory.delete",
        () -> {
          svc.delete(id);
          return null;
        });
  }

  // --- B 侧读口：租户隔离的两条断言必须真的换身份去读，
  // 用 A 的包装器「顺便看一眼」证明不了任何事。
  SerialPage pageB(SerialQuery q) {
    return access.run(actorB, "warehouse.inventory.read", () -> svc.page(q));
  }

  SerialStats statsB(SerialQuery q) {
    return access.run(actorB, "warehouse.inventory.read", () -> svc.stats(q));
  }

  SerialMovementPage movementsB(SerialMovementQuery q) {
    return access.run(actorB, "warehouse.inventory.read", () -> svc.movements(q));
  }

  SerialProfile profileB(UUID id) {
    return access.run(actorB, "warehouse.inventory.read", () -> svc.profile(id));
  }

  // ================================================================ 夹具写入（绕过服务）

  /** 直接插一条序列号档案（跳过流转），用来造「已被业务引用」的删除边界场景。 */
  UUID rawSerial(String number, String status, UUID locationId, UUID lotId) {
    var id = UUID.randomUUID();
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_serial(id,tenant_id,serial_number,sku_id,lot_id,status,"
                  + "current_location_id) values(?,current_setting('app.current_tenant',true)::uuid,?,?,?,?,?)",
              id,
              number,
              skuA,
              lotId,
              status,
              locationId);
          return null;
        });
    return id;
  }

  /**
   * 裸 SQL 插一条操作头。
   *
   * <p>存在的意义是**故意写出自相矛盾的操作头**：服务永远不会写出 「声明 2 件、实际 1 件」这种东西，只有直接写库才造得出来。
   */
  UUID rawOperation(int declaredQuantity, int serialCount) {
    var id = UUID.randomUUID();
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_serial_operation(id,tenant_id,direction,sku_id,"
                  + "declared_quantity,serial_count,actor_id,occurred_at)"
                  + " values(?,current_setting('app.current_tenant',true)::uuid,'INBOUND',?,?,?,?,"
                  + "now())",
              id,
              skuA,
              new BigDecimal(declaredQuantity),
              serialCount,
              user);
          return null;
        });
    return id;
  }

  /** 裸 SQL 插一条流转行。 */
  void rawMovement(UUID operationId, UUID serialId, String number) {
    sql.execute(
        "insert into inventory_serial_movement(id,tenant_id,serial_id,operation_id,movement_kind,"
            + "serial_number,from_status,to_status,to_location_id,actor_id,occurred_at)"
            + " values(?,current_setting('app.current_tenant',true)::uuid,?,?,'INBOUND',?,'IN_STOCK',"
            + "'IN_STOCK',?,?,now())",
        UUID.randomUUID(),
        serialId,
        operationId,
        number,
        locA,
        user);
  }

  // ================================================================ 录入 / 批量导入

  @Test
  void 扫码录入写下档案操作头与一条流转() {
    var at = OffsetDateTime.parse("2026-10-01T09:30:00+08:00");
    var result =
        register(
            new SerialRegisterRequest(
                "SN-0001", skuA, lotA, locA, "PO", UUID.randomUUID(), "首批到货", at));

    assertThat(result.passed()).isTrue();
    assertThat(result.applied()).isTrue();
    assertThat(result.issues()).isEmpty();
    // 蓝图那条硬约束的单条形态：数量 1 ↔ 恰好 1 个序列号
    assertThat(result.declaredQuantity()).isEqualByComparingTo("1");
    assertThat(result.operation().serialCount()).isEqualTo(1);
    assertThat(result.operation().direction()).isEqualTo(SerialMovementDirection.INBOUND);
    assertThat(result.serials()).hasSize(1);

    var profile = profile(result.serials().get(0).id());
    assertThat(profile.serialNumber()).isEqualTo("SN-0001");
    assertThat(profile.status()).isEqualTo(SerialStatus.IN_STOCK);
    assertThat(profile.statusLabel()).isEqualTo("在库");
    assertThat(profile.currentLocationId()).isEqualTo(locA);
    assertThat(profile.lotId()).isEqualTo(lotA);
    assertThat(profile.lotCode()).isEqualTo("LOT-2026-A");
    assertThat(profile.skuCode()).isEqualTo("SKU-1");
    assertThat(profile.locationCode()).isEqualTo("SH-A1");
    assertThat(profile.movementCount()).isEqualTo(1);

    var timeline = timeline(profile.id(), 0, 50);
    assertThat(timeline.total()).isEqualTo(1);
    var move = timeline.movements().get(0);
    assertThat(move.movementKind()).isEqualTo(SerialMovementKind.INBOUND);
    assertThat(move.movementLabel()).isEqualTo("入库");
    assertThat(move.toStatus()).isEqualTo(SerialStatus.IN_STOCK);
    assertThat(move.toLocationCode()).isEqualTo("SH-A1");
    assertThat(move.fromLocationId()).isNull();
    assertThat(move.refType()).isEqualTo("PO");
  }

  @Test
  void 时区偏移写回后逐字一致不偏八小时() {
    // 故意用 +05:30 这个既不是本地也不是整点的偏移：任何「按服务器本地时区猜一次」的实现
    // 都会在这里偏出一个具体值，而正确实现必须原样回到同一个瞬间。
    var written = OffsetDateTime.parse("2026-10-01T09:30:00+05:30");
    var result =
        register(new SerialRegisterRequest("SN-TZ", skuA, null, locA, null, null, null, written));
    assertThat(result.applied()).isTrue();

    // 逐字比对：取 epoch 秒而不是读 Java 对象，绕开任何时区转换的可能。
    var epoch =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetchValue(
                    "select extract(epoch from last_movement_at) from inventory_serial"
                        + " where serial_number = ?",
                    "SN-TZ"));
    assertThat(((Number) epoch).longValue()).isEqualTo(written.toInstant().getEpochSecond());

    var readBack = profile(result.serials().get(0).id()).lastMovementAt();
    assertThat(readBack.toInstant()).isEqualTo(written.toInstant());
    // 库里存的是绝对时刻，读回来的偏移量取决于会话时区，但瞬间必须一模一样。
    assertThat(readBack).isNotNull();
  }

  @Test
  void 批量导入一次落库并可先试跑() {
    var dry =
        importBatch(
            new SerialImportRequest(
                skuA,
                lotA,
                locA,
                List.of("SN-B1", "SN-B2", "SN-B3"),
                "PO",
                null,
                "整箱",
                null,
                true));
    assertThat(dry.passed()).isTrue();
    assertThat(dry.applied()).isFalse();
    assertThat(dry.operation()).isNull();
    // 试跑一条都不能落库
    assertThat(page(new SerialQuery(null, null, null, null, null, null, null, 0, 50)).total())
        .isZero();

    var result =
        importBatch(
            new SerialImportRequest(
                skuA,
                lotA,
                locA,
                List.of("SN-B1", "SN-B2", "SN-B3"),
                "PO",
                null,
                "整箱",
                null,
                false));
    assertThat(result.applied()).isTrue();
    assertThat(result.declaredQuantity()).isEqualByComparingTo("3");
    assertThat(result.operation().serialCount()).isEqualTo(3);
    assertThat(result.serials()).hasSize(3);
    assertThat(page(new SerialQuery(null, null, null, null, null, null, null, 0, 50)).total())
        .isEqualTo(3);
  }

  @Test
  void 批量导入少于两条被拒() {
    assertThatThrownBy(
            () ->
                importBatch(
                    new SerialImportRequest(
                        skuA, null, locA, List.of("SN-ONLY-1"), null, null, null, null, false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("批量导入至少");
  }

  // ================================================================ 硬约束：数量 ↔ 序列号条数

  @Test
  void 声明数量与序列号条数不一致时一条都不写() {
    // 蓝图原话「不允许大概齐」的第一层：应用层逐条比对，不过就整体不落库。
    // 先把号建出来，让本用例只命中条数这一条约束 —— 号不存在是另一回事，混进来就测不准了。
    rawSerial("SN-1", "IN_STOCK", locA, lotA);
    rawSerial("SN-2", "IN_STOCK", locA, lotA);
    var result =
        move(
            new SerialMoveRequest(
                "OUTBOUND",
                skuA,
                new BigDecimal("3"),
                List.of("SN-1", "SN-2"),
                null,
                null,
                null,
                "SOLD",
                null,
                null,
                null,
                null,
                false));

    assertThat(result.passed()).isFalse();
    assertThat(result.applied()).isFalse();
    assertThat(result.issues()).hasSize(1);
    var issue = result.issues().get(0);
    assertThat(issue.code()).isEqualTo(SerialIssueCode.SERIAL_COUNT_MISMATCH);
    assertThat(issue.expected()).isEqualTo("3");
    assertThat(issue.actual()).isEqualTo("2");
    assertThat(issue.message()).contains("不允许大概齐");

    // 夹具里的两个号一条流转都没有 —— 操作头与流转历史都还是空的，
    // 这才是「整体不落库」该看到的样子（只断言号数量会被夹具本身骗过去）。
    assertThat(operations(null).total()).isZero();
    assertThat(movements(null).total()).isZero();
    assertThat(profile(serialId("SN-1")).movementCount()).isZero();
    assertThat(profile(serialId("SN-2")).movementCount()).isZero();
  }

  @Test
  void 数量带小数按离散件拒绝() {
    var result =
        move(
            new SerialMoveRequest(
                "OUTBOUND",
                skuA,
                new BigDecimal("1.5"),
                List.of("SN-1"),
                null,
                null,
                null,
                "SOLD",
                null,
                null,
                null,
                null,
                false));
    assertThat(result.passed()).isFalse();
    assertThat(result.issues())
        .extracting(SerialIssue::code)
        .contains(SerialIssueCode.QUANTITY_NOT_INTEGER);
  }

  @Test
  void 数据库拒绝自相矛盾的操作头() {
    // 绕过服务直接写：声明 2 件、声明条数 1 条。服务的 CHECK 必须真的在库里。
    assertThatThrownBy(() -> rawOperation(2, 1))
        .hasMessageContaining("inventory_serial_operation_check")
        .satisfies(e -> assertThat(root(e)).contains("inventory_serial_operation"));
  }

  @Test
  void 延迟约束挡住少写流转行的半成品操作() {
    // 这一条是应用层**验不出来**的：声明数、声明条数、实际条数三者都自洽（2/2/2），
    // 但只有 1 条真的写了。第 4 层触发器在 COMMIT 时数一遍，把它整笔拒掉。
    var serial = rawSerial("SN-HALF", "IN_STOCK", locA, lotA);
    assertThatThrownBy(
            () -> {
              var op = rawOperation(1, 1);
              // 少写一条：operation 说 1 条，实际写 0 条 —— 触发器会在这里失败
              assertThat(op).isNotNull();
            })
        .satisfies(e -> assertThat(root(e)).contains("出入库数量必须与序列号条数完全一致"));
    assertThat(serial).isNotNull();
  }

  @Test
  void 延迟约束在条数补齐后放行() {
    var serial = rawSerial("SN-FULL", "IN_STOCK", locA, lotA);
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          var op = UUID.randomUUID();
          sql.execute(
              "insert into inventory_serial_operation(id,tenant_id,direction,sku_id,"
                  + "declared_quantity,serial_count,actor_id,occurred_at)"
                  + " values(?,current_setting('app.current_tenant',true)::uuid,'INBOUND',?,1,1,?,now())",
              op,
              skuA,
              user);
          rawMovement(op, serial, "SN-FULL");
          return null;
        });
    assertThat(
            movements(new SerialMovementQuery(null, null, null, null, null, null, null, 0, 50))
                .total())
        .isEqualTo(1);
  }

  // ================================================================ 状态机

  @Test
  void 状态流转按状态机放行非法流转被逐条拒绝() {
    var id = rawSerial("SN-SM", "SOLD", null, lotA);
    var ok =
        move(
            new SerialMoveRequest(
                "INBOUND",
                skuA,
                BigDecimal.ONE,
                List.of("SN-SM"),
                locB,
                null,
                lotA,
                null,
                "RETURN",
                null,
                "退货入库",
                null,
                false));
    assertThat(ok.applied()).isTrue();
    var profile = profile(id);
    assertThat(profile.status()).isEqualTo(SerialStatus.IN_STOCK);
    assertThat(profile.currentLocationId()).isEqualTo(locB);

    // 在库 → 在途 → 在库，来回一次；然后试图在库直接跳到「维修以外」的目标状态
    assertThat(
            move(new SerialMoveRequest(
                    "OUTBOUND",
                    skuA,
                    BigDecimal.ONE,
                    List.of("SN-SM"),
                    null,
                    null,
                    null,
                    "IN_TRANSIT",
                    null,
                    null,
                    "发往深圳",
                    null,
                    false))
                .applied())
        .isTrue();
    assertThat(profile(id).status()).isEqualTo(SerialStatus.IN_TRANSIT);
    assertThat(profile(id).currentLocationId()).isNull();

    assertThat(
            move(new SerialMoveRequest(
                    "INBOUND",
                    skuA,
                    BigDecimal.ONE,
                    List.of("SN-SM"),
                    locC,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "到货入库",
                    null,
                    false))
                .applied())
        .isTrue();
    assertThat(profile(id).status()).isEqualTo(SerialStatus.IN_STOCK);

    // 状态流转只能改「有库位」的两档：已售必须走出库方向
    var wrong =
        move(
            new SerialMoveRequest(
                "STATUS_CHANGE",
                skuA,
                BigDecimal.ONE,
                List.of("SN-SM"),
                null,
                null,
                null,
                "SOLD",
                null,
                null,
                "直接标已售",
                null,
                false));
    assertThat(wrong.passed()).isFalse();
    assertThat(wrong.issues().get(0).code()).isEqualTo(SerialIssueCode.STATUS_NOT_ALLOWED);
    assertThat(wrong.issues().get(0).message()).contains("出库方向");
  }

  @Test
  void 同一批次内混合状态时逐号给出各自的原因() {
    rawSerial("SN-IN", "IN_STOCK", locA, lotA);
    // 已售的号没有库位：出库的第一步就卡在这里，而不是卡在状态机上
    rawSerial("SN-OUT", "SOLD", null, lotA);
    var result =
        move(
            new SerialMoveRequest(
                "OUTBOUND",
                skuA,
                new BigDecimal("2"),
                List.of("SN-IN", "SN-OUT"),
                null,
                null,
                null,
                "SOLD",
                null,
                null,
                null,
                null,
                false));
    assertThat(result.passed()).isFalse();
    assertThat(result.serials()).isNull();
    // 逐条归因是本用例的全部意义：错在哪个号上必须当场说清，
    // 而同一个号不该被重复报成好几种不同的错。
    assertThat(result.issues())
        .filteredOn(i -> i.serialNumber().equals("SN-OUT"))
        .extracting(SerialIssue::code)
        .containsExactly(SerialIssueCode.STATUS_NOT_ALLOWED);
    assertThat(result.issues())
        .filteredOn(i -> i.serialNumber().equals("SN-OUT"))
        .extracting(SerialIssue::message)
        .allMatch(m -> m.contains("不在任何库位上"));
    // 能出库的那个号不该被牵连
    assertThat(result.issues()).noneMatch(i -> i.serialNumber().equals("SN-IN"));
    // 整单不落库：SN-IN 的状态也没被动过
    assertThat(profile(serialId("SN-IN")).status()).isEqualTo(SerialStatus.IN_STOCK);
  }

  @Test
  void 报废是终态服务与数据库都不放行复活() {
    var id = rawSerial("SN-DEAD", "IN_STOCK", locA, lotA);
    assertThat(
            move(new SerialMoveRequest(
                    "OUTBOUND",
                    skuA,
                    BigDecimal.ONE,
                    List.of("SN-DEAD"),
                    null,
                    null,
                    null,
                    "SCRAPPED",
                    null,
                    null,
                    "摔坏了",
                    null,
                    false))
                .applied())
        .isTrue();
    assertThat(profile(id).status()).isEqualTo(SerialStatus.SCRAPPED);
    assertThat(profile(id).currentLocationId()).isNull();

    // 应用层：报废 → 在库 不在状态机里
    var revive =
        move(
            new SerialMoveRequest(
                "INBOUND",
                skuA,
                BigDecimal.ONE,
                List.of("SN-DEAD"),
                locA,
                null,
                null,
                null,
                null,
                null,
                "想复活",
                null,
                false));
    assertThat(revive.passed()).isFalse();
    assertThat(revive.issues().get(0).code()).isEqualTo(SerialIssueCode.TRANSITION_NOT_ALLOWED);

    // 数据库层：绕过服务直接 UPDATE 同样不行
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> {
                      sql.execute(
                          "update inventory_serial set status = 'IN_STOCK', current_location_id = ?"
                              + " where id = ?",
                          locA,
                          id);
                      return null;
                    }))
        .satisfies(e -> assertThat(root(e)).contains("报废是终态"));
  }

  @Test
  void 位置与状态不配套时数据库直接拒绝() {
    // 已售却挂在库位上 —— 这正是那对真实现场事故，CHECK 必须在 INSERT 那一刻就失败。
    assertThatThrownBy(() -> rawSerial("SN-BAD1", "SOLD", locA, lotA))
        .satisfies(e -> assertThat(root(e)).contains("inventory_serial"));
    // 在库却没有库位
    assertThatThrownBy(() -> rawSerial("SN-BAD2", "IN_STOCK", null, lotA))
        .satisfies(e -> assertThat(root(e)).contains("inventory_serial"));
    // 维修在本仓维修库位上，必须有位置
    assertThat(rawSerial("SN-OK-REPAIR", "REPAIR", locB, lotA)).isNotNull();
  }

  @Test
  void 序列号与sku的绑定不可变() {
    var id = rawSerial("SN-FIX", "IN_STOCK", locA, lotA);
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> {
                      sql.execute(
                          "update inventory_serial set sku_id = ? where id = ?",
                          UUID.randomUUID(),
                          id);
                      return null;
                    }))
        .satisfies(e -> assertThat(root(e)).contains("不可变"));
    // 档案编辑端点压根没有 skuId 字段
    assertThat(update(id, new SerialUpdateRequest(lotB, false, "备注")).skuId()).isEqualTo(skuA);
  }

  // ================================================================ 库位转移 / 批次

  @Test
  void 库位转移不改售卖属性() {
    rawSerial("SN-MOVE", "IN_STOCK", locA, lotA);
    var result =
        move(
            new SerialMoveRequest(
                "RELOCATION",
                skuA,
                BigDecimal.ONE,
                List.of("SN-MOVE"),
                locB,
                locA,
                null,
                null,
                null,
                null,
                "挪到B架",
                null,
                false));
    assertThat(result.applied()).isTrue();
    assertThat(profile(serialId("SN-MOVE")).currentLocationId()).isEqualTo(locB);
    assertThat(profile(serialId("SN-MOVE")).status()).isEqualTo(SerialStatus.IN_STOCK);

    var timeline = timeline(serialId("SN-MOVE"), 0, 10);
    assertThat(timeline.movements().get(0).movementKind()).isEqualTo(SerialMovementKind.RELOCATION);
    assertThat(timeline.movements().get(0).fromLocationCode()).isEqualTo("SH-A1");
    assertThat(timeline.movements().get(0).toLocationCode()).isEqualTo("SH-B2");
  }

  @Test
  void 库位转移时来源库位对不上被拒() {
    rawSerial("SN-HERE", "IN_STOCK", locA, lotA);
    var result =
        move(
            new SerialMoveRequest(
                "RELOCATION",
                skuA,
                BigDecimal.ONE,
                List.of("SN-HERE"),
                locB,
                locC,
                null,
                null,
                null,
                null,
                "从C架挪到B架",
                null,
                false));
    assertThat(result.passed()).isFalse();
    var issue = result.issues().get(0);
    assertThat(issue.code()).isEqualTo(SerialIssueCode.LOCATION_MISMATCH);
    assertThat(issue.actual()).isEqualTo("SH-A1");
  }

  @Test
  void 批次改绑留下可追溯的流转() {
    var id = rawSerial("SN-LOT", "IN_STOCK", locA, lotA);
    var result =
        move(
            new SerialMoveRequest(
                "REBIND",
                skuA,
                BigDecimal.ONE,
                List.of("SN-LOT"),
                null,
                null,
                lotB,
                null,
                null,
                null,
                "批次录错了",
                null,
                false));
    assertThat(result.applied()).isTrue();
    assertThat(profile(id).lotId()).isEqualTo(lotB);

    var move = timeline(id, 0, 10).movements().get(0);
    assertThat(move.movementKind()).isEqualTo(SerialMovementKind.REBIND);
    assertThat(move.fromLotCode()).isEqualTo("LOT-2026-A");
    assertThat(move.toLotCode()).isEqualTo("LOT-2026-B");
    assertThat(move.fromLocationCode()).isEqualTo(move.toLocationCode());
  }

  @Test
  void 批次必须属于该sku且档案编辑能解绑() {
    var id = rawSerial("SN-UNBIND", "IN_STOCK", locA, lotA);
    var other = rawSerial("SN-OTHER", "IN_STOCK", locA, lotA);
    assertThatThrownBy(() -> update(id, new SerialUpdateRequest(UUID.randomUUID(), false, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("批次不存在");

    assertThat(update(id, new SerialUpdateRequest(null, true, "非批次品")).lotId()).isNull();
    assertThat(update(other, new SerialUpdateRequest(lotB, false, "改绑到已耗尽批次")).lotId())
        .isEqualTo(lotB);
    // 已耗尽/已过期的批次仍然算有效批次：换个批次不需要它还有货，只需它确实属于这个 SKU
    assertThat(stats(null).lotMismatchCount()).isEqualTo(1);
  }

  @Test
  void 什么都不会改变的流转被单独指出() {
    rawSerial("SN-SAME", "IN_STOCK", locA, lotA);
    var result =
        move(
            new SerialMoveRequest(
                "RELOCATION",
                skuA,
                BigDecimal.ONE,
                List.of("SN-SAME"),
                locA,
                locA,
                null,
                null,
                null,
                null,
                null,
                null,
                false));
    assertThat(result.passed()).isFalse();
    assertThat(result.issues().get(0).code()).isEqualTo(SerialIssueCode.NO_CHANGE);
  }

  @Test
  void 同一次操作里重复的序列号被逐条指出() {
    var result =
        move(
            new SerialMoveRequest(
                "OUTBOUND",
                skuA,
                new BigDecimal("2"),
                List.of("SN-DUP", "SN-DUP"),
                null,
                null,
                null,
                "SOLD",
                null,
                null,
                null,
                null,
                false));
    assertThat(result.passed()).isFalse();
    assertThat(result.issues())
        .filteredOn(i -> i.serialNumber().equals("SN-DUP"))
        .extracting(SerialIssue::code)
        .contains(SerialIssueCode.SERIAL_DUPLICATED);
  }

  // ================================================================ 删除边界

  @Test
  void 未被引用的档案可以删除被引用的删不掉() {
    // 录错了：只有建档那一次入库，仍在库 → 可删
    var fresh =
        register(new SerialRegisterRequest("SN-ERR", skuA, null, locA, null, null, null, null))
            .serials()
            .get(0)
            .id();
    delete(fresh);
    assertThat(page(new SerialQuery(null, null, null, null, null, null, null, 0, 50)).total())
        .isZero();

    // 已被业务引用过（产生过 2 条流转）→ 只能报废
    var used = rawSerial("SN-USED", "IN_STOCK", locA, lotA);
    move(
        new SerialMoveRequest(
            "RELOCATION",
            skuA,
            BigDecimal.ONE,
            List.of("SN-USED"),
            locB,
            locA,
            null,
            null,
            null,
            null,
            "挪过位",
            null,
            false));
    assertThatThrownBy(() -> delete(used)).satisfies(e -> assertThat(root(e)).contains("已被业务引用"));
    assertThat(profile(used)).isNotNull();
  }

  // ================================================================ 列表 / 统计 / 历史

  @Test
  void 列表是真分页且筛选排序都生效() {
    for (int i = 1; i <= 7; i++) {
      rawSerial("SN-" + String.format("%03d", i), "IN_STOCK", locA, lotA);
    }
    rawSerial("SN-999", "SOLD", null, lotA);

    var page0 = page(new SerialQuery(null, null, null, null, null, null, "SERIAL_NUMBER", 0, 3));
    var page1 = page(new SerialQuery(null, null, null, null, null, null, "SERIAL_NUMBER", 1, 3));
    var page2 = page(new SerialQuery(null, null, null, null, null, null, "SERIAL_NUMBER", 2, 3));
    assertThat(page0.total()).isEqualTo(8);
    assertThat(page0.serials()).hasSize(3);
    assertThat(page1.serials()).hasSize(3);
    assertThat(page2.serials()).hasSize(2);
    // 翻页不重不漏
    assertThat(
            java.util.stream.Stream.of(page0, page1, page2)
                .flatMap(p -> p.serials().stream())
                .map(SerialProfile::serialNumber)
                .distinct()
                .count())
        .isEqualTo(8);

    // 状态筛选
    assertThat(page(new SerialQuery(null, null, null, null, "SOLD", null, null, 0, 50)).total())
        .isEqualTo(1);
    // 仓库筛选只看在库的号：已售的号没有库位，不属于任何仓库
    assertThat(page(new SerialQuery(null, whSh, null, null, null, null, null, 0, 50)).total())
        .isEqualTo(7);
    // 关键字：序列号 / SKU 编码 / 批次号都能命中
    assertThat(page(new SerialQuery(null, null, null, null, null, "SN-001", null, 0, 50)).total())
        .isEqualTo(1);
    assertThat(
            page(new SerialQuery(null, null, null, null, null, "LOT-2026-A", null, 0, 50)).total())
        .isEqualTo(8);

    assertThatThrownBy(
            () -> page(new SerialQuery(null, null, null, null, null, null, "DROP TABLE", 0, 50)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的排序键");
  }

  @Test
  void 状态分布守恒并且总数等于五档之和() {
    rawSerial("SN-A", "IN_STOCK", locA, lotA);
    rawSerial("SN-B", "IN_STOCK", locA, lotA);
    rawSerial("SN-C", "SOLD", null, lotA);
    rawSerial("SN-D", "IN_TRANSIT", null, null);
    rawSerial("SN-E", "REPAIR", locB, lotB);
    rawSerial("SN-F", "SCRAPPED", null, lotA);

    var stats = stats(null);
    assertThat(stats.total()).isEqualTo(6);
    assertThat(stats.inStock()).isEqualTo(2);
    assertThat(stats.sold()).isEqualTo(1);
    assertThat(stats.inTransit()).isEqualTo(1);
    assertThat(stats.repair()).isEqualTo(1);
    assertThat(stats.scrapped()).isEqualTo(1);
    // 守恒式必须显式成立，否则界面上的每个单独数字都不可信
    assertThat(stats.sumMatches()).isTrue();
    assertThat(stats.unboundCount()).isEqualTo(1);
    assertThat(stats.lotMismatchCount()).isEqualTo(1);
  }

  @Test
  void 流转历史与操作头可按来源单据回查() {
    var refId = UUID.randomUUID();
    rawSerial("SN-H1", "IN_STOCK", locA, lotA);
    rawSerial("SN-H2", "IN_STOCK", locA, lotA);
    move(
        new SerialMoveRequest(
            "RELOCATION",
            skuA,
            new BigDecimal("2"),
            List.of("SN-H1", "SN-H2"),
            locB,
            locA,
            null,
            null,
            "ADJUST",
            refId,
            "整批挪位",
            null,
            false));

    var movements =
        movements(new SerialMovementQuery(null, null, null, null, "ADJUST", refId, null, 0, 50));
    assertThat(movements.total()).isEqualTo(2);
    // 一次操作的两行共用 operation_id —— 「这一单动了哪几个号」是一次 group by
    assertThat(movements.movements().get(0).operationId())
        .isEqualTo(movements.movements().get(1).operationId());

    var ops = operations(new SerialOperationQuery("RELOCATION", skuA, "ADJUST", refId, 0, 50));
    assertThat(ops.total()).isEqualTo(1);
    var op = ops.operations().get(0);
    assertThat(op.declaredQuantity()).isEqualByComparingTo("2");
    assertThat(op.serialCount()).isEqualTo(2);
    // 声明数量与实际条数并列摆在同一份响应里，界面上直接可比
    assertThat(op.declaredQuantity().intValueExact()).isEqualTo((int) op.serialCount());

    assertThat(
            movements(new SerialMovementQuery(null, op.id(), null, null, null, null, null, 0, 50))
                .total())
        .isEqualTo(2);
    assertThat(
            movements(
                    new SerialMovementQuery(
                        serialId("SN-H1"), null, null, null, null, null, null, 0, 50))
                .total())
        .isEqualTo(1);
  }

  // ================================================================ HTTP 与权限

  @Test
  void 端点返回真实分页与逐条校验错误() throws Exception {
    rawSerial("SN-HTTP", "IN_STOCK", locA, lotA);
    http.perform(get("/warehouse/inventory/serials").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.serials[0].serialNumber").value("SN-HTTP"))
        .andExpect(jsonPath("$.serials[0].status").value("IN_STOCK"))
        .andExpect(jsonPath("$.serials[0].locationCode").value("SH-A1"));

    http.perform(
            post("/warehouse/inventory/serials/validate-movement")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"direction\":\"OUTBOUND\",\"declaredQuantity\":5,"
                        + "\"serialNumbers\":[\"SN-HTTP\"],\"toStatus\":\"SOLD\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passed").value(false))
        .andExpect(jsonPath("$.issues[0].code").value("SERIAL_COUNT_MISMATCH"));

    http.perform(
            get("/warehouse/inventory/serial-vocabularies")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statuses.length()").value(5))
        .andExpect(jsonPath("$.directions.length()").value(5));
  }

  @Test
  void 只读角色能校验不能写入() throws Exception {
    rawSerial("SN-RO", "IN_STOCK", locA, lotA);
    http.perform(
            post("/warehouse/inventory/serials/validate-movement")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content(
                    "{\"direction\":\"OUTBOUND\",\"declaredQuantity\":1,"
                        + "\"serialNumbers\":[\"SN-RO\"],\"toStatus\":\"SOLD\"}"))
        .andExpect(status().isOk());

    http.perform(
            post("/warehouse/inventory/serials")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content(
                    "{\"serialNumber\":\"SN-NEW\",\"skuId\":\""
                        + skuA
                        + "\",\"locationId\":\""
                        + locA
                        + "\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 未认证请求不碰数据库() throws Exception {
    http.perform(get("/warehouse/inventory/serials")).andExpect(status().isUnauthorized());
  }

  // ================================================================ 租户隔离三断言

  /** 造一批 B 租户的序列号档案，返回其中一台的 id。 */
  UUID tenantBFixture() {
    return access.run(
        actorB,
        "warehouse.inventory.read",
        () -> {
          var id = UUID.randomUUID();
          sql.execute(
              "insert into inventory_serial(id,tenant_id,serial_number,sku_id,status,current_location_id)"
                  + " values(?,current_setting('app.current_tenant',true)::uuid,'SN-B-TENANT',?,"
                  + "'IN_STOCK',?)",
              id,
              idFor(b, 2),
              idFor(b, 6));
          return id;
        });
  }

  @Test
  void 断言一_两租户互不可见() {
    rawSerial("SN-A-TENANT", "IN_STOCK", locA, lotA);
    var bId = tenantBFixture();

    var listA = page(new SerialQuery(null, null, null, null, null, null, null, 0, 50));
    var listB = pageB(new SerialQuery(null, null, null, null, null, null, null, 0, 50));
    assertThat(listA.total()).isEqualTo(1);
    assertThat(listB.total()).isEqualTo(1);
    assertThat(listA.serials().get(0).serialNumber()).isEqualTo("SN-A-TENANT");
    assertThat(listB.serials().get(0).serialNumber()).isEqualTo("SN-B-TENANT");

    // 拿对方的 id 直接查 → 404，不泄露「这个号存在」
    assertThatThrownBy(() -> profile(bId))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(root(e)).contains("序列号不存在"));
    // 历史与操作头同样隔离
    assertThat(
            movementsB(new SerialMovementQuery(bId, null, null, null, null, null, null, 0, 50))
                .total())
        .isZero();
    assertThat(access.run(actorB, "warehouse.inventory.read", () -> svc.operations(null)).total())
        .isZero();
    assertThat(statsB(null).total()).isEqualTo(1);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    rawSerial("SN-A-TENANT", "IN_STOCK", locA, lotA);
    tenantBFixture();

    // 身份是 B 但伪造 X-Tenant-Id 指向 A → 只看得见 B 自己那一个
    http.perform(
            get("/warehouse/inventory/serials")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.serials[0].serialNumber").value("SN-B-TENANT"));

    // 反向同理
    http.perform(
            get("/warehouse/inventory/serials")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.serials[0].serialNumber").value("SN-A-TENANT"));

    // 写入路径同样不受伪造头影响：B 的请求落在 A 的号上会因「号不存在」被拒，而不是写到 A 上去
    http.perform(
            post("/warehouse/inventory/serials/movements")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content(
                    "{\"direction\":\"OUTBOUND\",\"declaredQuantity\":1,"
                        + "\"serialNumbers\":[\"SN-A-TENANT\"],\"toStatus\":\"SOLD\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.passed").value(false))
        .andExpect(jsonPath("$.issues[0].code").value("SERIAL_NOT_FOUND"));

    assertThat(profile(serialId("SN-A-TENANT")).status()).isEqualTo(SerialStatus.IN_STOCK);
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    rawSerial("SN-A-TENANT", "IN_STOCK", locA, lotA);
    var bId = tenantBFixture();

    // 池大小为 1，同一条连接被反复借还：交替跑两个租户 20 轮，
    // 每一轮都要同时校验写入与回查两条路径都不串租户。
    for (int i = 0; i < 20; i++) {
      var listA = page(new SerialQuery(null, null, null, null, null, null, null, 0, 50));
      var listB = pageB(new SerialQuery(null, null, null, null, null, null, null, 0, 50));
      assertThat(listA.serials())
          .extracting(SerialProfile::serialNumber)
          .containsExactly("SN-A-TENANT");
      assertThat(listB.serials())
          .extracting(SerialProfile::serialNumber)
          .containsExactly("SN-B-TENANT");

      // 写入路径：A 那边写一条历史，交替之后 B 那边绝不该看见它。
      // 只给目标库位、不钉住来源：钉死 locA 的话第二轮就因「不在指定库位」被拒，
      // 那测的就不是「交替租户」而是「我的测试数据自相矛盾」了。
      move(
          new SerialMoveRequest(
              "RELOCATION",
              skuA,
              BigDecimal.ONE,
              List.of("SN-A-TENANT"),
              i % 2 == 0 ? locB : locA,
              null,
              null,
              null,
              null,
              null,
              "第 " + i + " 次挪位",
              null,
              false));
      assertThat(
              movements(new SerialMovementQuery(null, null, null, null, null, null, null, 0, 200))
                  .total())
          .isEqualTo(i + 1L);
      assertThat(
              movementsB(new SerialMovementQuery(null, null, null, null, null, null, null, 0, 200))
                  .total())
          .isZero();
      assertThat(profileB(bId).serialNumber()).isEqualTo("SN-B-TENANT");
      assertThatThrownBy(() -> profileB(serialId("SN-A-TENANT")))
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  // ================================================================ 工具

  UUID serialId(String number) {
    var rows =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> sql.fetch("select id from inventory_serial where serial_number = ?", number));
    return rows.get(0).get(0, UUID.class);
  }

  /** 取最内层异常链的消息：DataAccessException 层层包装，只看最外层会漏掉真正的原因。 */
  static String root(Throwable e) {
    var messages = new StringBuilder();
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t.getMessage() != null) {
        messages.append(t.getMessage()).append('\n');
      }
    }
    return messages.toString();
  }
}
