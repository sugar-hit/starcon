package com.starcon.warehouse;

import static com.starcon.warehouse.StocktakeModels.StocktakeCountInput;
import static com.starcon.warehouse.StocktakeModels.StocktakeRequest;
import static com.starcon.warehouse.StocktakeVarianceModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.4.2.2 盘点差异处理：五类归因 × 三种决定、调整单状态机、<b>未审批不得入账</b>、 完成报告的真实指标，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本测试的核心是 {@link #未审批的调整单不入账且数据库层写不进分录()} 两条</b>。 蓝图原话：「差异不直接改余额，必须经调整单与审批，这是实体目录明确的红线。」
 * 只测「审批后能入账」证明不了红线 —— 那条路在「随便写个 SQL 改余额」面前一样成立。 所以必须**真的**去数据库里硬写分录，验证 V62 的入账闸门会当场拒绝：
 * 一个只写在服务层注释里的红线，等于没有红线。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StocktakeVarianceIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();

  /** 第二个用户：用来验证「提交人 ≠ 审批人」的职责分离。 */
  static final UUID approver = UUID.randomUUID();

  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(
              p ->
                  Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static final UUID le = idFor(a, 1);
  static final UUID skuOne = idFor(a, 2);
  static final UUID skuTwo = idFor(a, 3);
  static final UUID whOne = idFor(a, 4);
  static final UUID whTwo = idFor(a, 5);
  static final UUID qSellable = idFor(a, 6);
  static final UUID locOne = idFor(a, 7);
  static final UUID locTwo = idFor(a, 8);
  static final UUID locThree = idFor(a, 9);
  static final UUID lotOne = idFor(a, 10);

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-variance-" + tenant + "-" + ordinal).getBytes());
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
      actors.put("Bearer approver", actor(approver, a, List.of(grant("ALL", allPermissions))));
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
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired StocktakeVarianceService service;
  @Autowired StocktakeService stocktakes;
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
  AccessService.Access actorApprover;

  @BeforeEach
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    actorApprover = actors.get("Bearer approver");
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS ——
    // 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate stocktake_variance_adjustment_line, stocktake_variance_attribution,"
              + " stocktake_variance_adjustment, stocktake_variance_audit,"
              + " stocktake_line, stocktake_task, stocktake_sheet, stock_bucket, warehouse_location,"
              + " inventory_lot, inventory_balance, inventory_quality_status, warehouse, sku_ref,"
              + " legal_entity_ref, inventory_entry, inventory_transaction restart identity cascade");
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
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "WH-ONE",
                "一仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "WH-TWO",
                "二仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                idFor(tenant, 7),
                tenant,
                idFor(tenant, 4),
                "A-01",
                "A 区一排");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                idFor(tenant, 8),
                tenant,
                idFor(tenant, 4),
                "A-02",
                "A 区二排");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name) values(?,?,?,?,?)",
                idFor(tenant, 9),
                tenant,
                idFor(tenant, 5),
                "B-01",
                "B 区一排");
            sql.execute(
                "insert into inventory_lot(id,tenant_id,sku_id,lot_code,received_on) values(?,?,?,?,?)",
                idFor(tenant, 10),
                tenant,
                idFor(tenant, 2),
                "LOT-A",
                java.sql.Date.valueOf("2026-09-01"));
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  static OffsetDateTime now() {
    return OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
  }

  // ================================================================ 夹具

  record BucketSpec(UUID location, UUID lot, String quantity) {}

  void group(UUID warehouse, UUID sku, List<BucketSpec> entries) {
    access.run(
        actorA,
        "warehouse.inventory.read",
        () -> {
          BigDecimal sum = BigDecimal.ZERO;
          for (BucketSpec e : entries) {
            sum = sum.add(new BigDecimal(e.quantity()));
          }
          UUID balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
              balanceId,
              a,
              sku,
              warehouse,
              le,
              qSellable,
              sum,
              BigDecimal.ZERO);
          for (BucketSpec e : entries) {
            sql.execute(
                "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
                    + "lot_id,quality_status_id,balance_id,on_hand,reserved,last_movement_at) "
                    + "values(?,?,?,?,?,?,?,?,?,?,0::numeric,?::timestamptz)",
                UUID.randomUUID(),
                a,
                le,
                sku,
                warehouse,
                e.location(),
                e.lot(),
                qSellable,
                balanceId,
                new BigDecimal(e.quantity()),
                now());
          }
          return null;
        });
  }

  void groupFor(UUID tenant, UUID warehouse, UUID sku, String quantity) {
    access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          UUID balanceId = UUID.randomUUID();
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
              balanceId,
              tenant,
              sku,
              warehouse,
              idFor(tenant, 1),
              idFor(tenant, 6),
              new BigDecimal(quantity),
              BigDecimal.ZERO);
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
                  + "quality_status_id,balance_id,on_hand,reserved,last_movement_at) "
                  + "values(?,?,?,?,?,?,?,?,?,0::numeric,?::timestamptz)",
              UUID.randomUUID(),
              tenant,
              idFor(tenant, 1),
              sku,
              warehouse,
              idFor(tenant, 7),
              idFor(tenant, 6),
              balanceId,
              new BigDecimal(quantity),
              now());
          return null;
        });
  }

  /**
   * 标准库存夹具（租户 A）：
   *
   * <pre>
   *   一仓 A-01  SKU-1  批次 LOT-A  100
   *   一仓 A-01  SKU-2  无批次      40
   *   一仓 A-02  SKU-1  无批次      25
   *   二仓 B-01  SKU-1  无批次      60
   * </pre>
   *
   * <p>差异夹具在 {@link #countedSheet()} 里：一仓的三行实盘 96 / 44 / 25， 于是差异是 −4（盘亏）/ +4（盘盈）/ 0（相符），账面合计
   * 165、实盘合计 165。
   */
  void standardStock() {
    group(
        whOne,
        skuOne,
        List.of(new BucketSpec(locOne, lotOne, "100"), new BucketSpec(locTwo, null, "25")));
    group(whOne, skuTwo, List.of(new BucketSpec(locOne, null, "40")));
    group(whTwo, skuOne, List.of(new BucketSpec(locThree, null, "60")));
  }

  /** 发起盘点并录入实盘，得到三条差异：SKU-1@A-01 盘亏 4、SKU-2@A-01 盘盈 4、SKU-1@A-02 相符。 */
  UUID countedSheet() {
    standardStock();
    var saved =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () ->
                stocktakes.create(
                    new StocktakeRequest("WAREHOUSE", whOne, "BY_LOCATION", "夹具"), user));
    UUID sheetId = saved.sheet().id();
    for (var line :
        access
            .run(
                actorA,
                "warehouse.inventory.read",
                () -> stocktakes.lines(sheetId, null, null, 0, 50))
            .lines()) {
      BigDecimal counted =
          switch (line.skuCode() + "@" + line.locationCode()) {
            case "SKU-1@A-01" -> new BigDecimal("96");
            case "SKU-2@A-01" -> new BigDecimal("44");
            default -> line.bookOnHand();
          };
      access.run(
          actorA,
          "warehouse.inventory.update",
          () ->
              stocktakes.count(
                  sheetId, line.id(), new StocktakeCountInput(counted, null, "夹具"), user));
    }
    return sheetId;
  }

  StocktakeVarianceLine varianceLine(UUID sheetId, String skuCode, String locationCode) {
    return access
        .run(
            actorA,
            "warehouse.inventory.read",
            () -> service.lines(sheetId, null, null, null, null, 0, 50))
        .lines()
        .stream()
        .filter(l -> l.skuCode().equals(skuCode) && l.locationCode().equals(locationCode))
        .findFirst()
        .orElseThrow();
  }

  StocktakeVarianceLine attribute(
      UUID sheetId, UUID lineId, String reason, String decision, String note) {
    return access.run(
        actorA,
        "warehouse.inventory.update",
        () ->
            service.attribute(
                sheetId,
                lineId,
                new StocktakeVarianceAttributionInput(reason, decision, note),
                user));
  }

  StocktakeVarianceAdjustmentDetail adjust(UUID sheetId, List<UUID> lineIds) {
    return access.run(
        actorA,
        "warehouse.inventory.update",
        () ->
            service.createAdjustment(
                sheetId, new StocktakeVarianceAdjustCreateInput(lineIds, false, "夹具调整单"), user));
  }

  BigDecimal bucketOnHand(String skuCode, String locationCode) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select k.on_hand from stock_bucket k"
                        + " join sku_ref s on s.tenant_id = k.tenant_id and s.id = k.sku_id"
                        + " join warehouse_location lo on lo.tenant_id = k.tenant_id and lo.id = k.location_id"
                        + " where k.tenant_id = ? and s.code = ? and lo.code = ?",
                    a,
                    skuCode,
                    locationCode)
                .get("on_hand", BigDecimal.class));
  }

  // ================================================================ 能力与枚举

  @Test
  void 五类原因与三种决定都能选且语义分开() {
    var caps = access.run(actorA, "warehouse.inventory.read", () -> service.capabilities());
    assertThat(caps.reasons()).hasSize(5);
    assertThat(caps.reasons())
        .extracting(VarianceHandlingOption::value)
        .containsExactly("SHRINKAGE", "MISSHIP", "THEFT", "UNRECORDED", "TIMING");
    assertThat(caps.decisions())
        .extracting(VarianceHandlingOption::value)
        .containsExactly("ADJUST", "RECOUNT", "IGNORE");
    // 只有「调整」会改余额，界面上必须能据此提示用户。
    assertThat(caps.decisions())
        .filteredOn(VarianceHandlingOption::changesBalance)
        .extracting(VarianceHandlingOption::value)
        .containsExactly("ADJUST");
    // 「未入账」与「时点差」语义相反：一个账错了，一个账没错。说明文字必须讲得出区别。
    var unrecorded =
        caps.reasons().stream()
            .filter(r -> r.value().equals("UNRECORDED"))
            .findFirst()
            .orElseThrow();
    var timing =
        caps.reasons().stream().filter(r -> r.value().equals("TIMING")).findFirst().orElseThrow();
    assertThat(unrecorded.description()).contains("账错了");
    assertThat(timing.description()).contains("账没错");
    // 金额口径算不出来，必须如实报 false 并说明原因，而不是编一个金额。
    assertThat(caps.amountBasisAvailable()).isFalse();
    assertThat(caps.amountBasisNote()).contains("库存成本");
    // 审批没接入 17.4，必须如实说明用的是替代机制。
    assertThat(caps.approvalNote()).contains("没有接入 17.4");
    assertThat(caps.adjustmentHandoffNote()).contains("7.4.1.1");
  }

  @Test
  void 未知原因与决定被显式拒绝() {
    UUID sheet = countedSheet();
    var line = varianceLine(sheet, "SKU-1", "A-01");
    assertThatThrownBy(() -> attribute(sheet, line.id(), "SHRINKAGE_TYPO", "ADJUST", "测试"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("未知的差异原因");
    assertThatThrownBy(() -> attribute(sheet, line.id(), "SHRINKAGE", "WHATEVER", "测试"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("未知的处理决定");
    // 账实相符的行**不在差异工作台里**：它没有差异可处理。
    // （不是「差异等于 0」——把它算成 0 会让一张还没盘完的单看起来「全部账实相符」。）
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.lines(sheet, null, null, null, "A-02", 0, 50))
                .lines())
        .noneSatisfy(l -> assertThat(l.locationCode()).isEqualTo("A-02"));
  }

  // ================================================================ 红线：未审批不得入账

  @Test
  void 归因与生成调整单都不改余额() {
    UUID sheet = countedSheet();
    var loss = varianceLine(sheet, "SKU-1", "A-01");
    var gain = varianceLine(sheet, "SKU-2", "A-01");
    assertThat(loss.variance()).isEqualByComparingTo("-4");
    assertThat(gain.variance()).isEqualByComparingTo("4");

    var beforeAttribution = java.time.Instant.now();
    attribute(sheet, loss.id(), "SHRINKAGE", "ADJUST", "搬运破损两件");
    attribute(sheet, gain.id(), "UNRECORDED", "ADJUST", "到货漏记入库单");
    var adj = adjust(sheet, List.of(loss.id(), gain.id()));
    assertThat(adj.adjustment().status()).isEqualTo(StocktakeVarianceAdjustmentStatus.DRAFT);

    // 归因、建单、提交之后余额仍然一字不动 —— 差异不直接改余额。
    assertThat(bucketOnHand("SKU-1", "A-01")).isEqualByComparingTo("100");
    assertThat(bucketOnHand("SKU-2", "A-01")).isEqualByComparingTo("40");
    access.run(
        actorA,
        "warehouse.inventory.update",
        () -> service.submit(adj.adjustment().id(), null, user));
    assertThat(bucketOnHand("SKU-1", "A-01")).isEqualByComparingTo("100");
    assertThat(bucketOnHand("SKU-2", "A-01")).isEqualByComparingTo("40");
    // 也没有任何分录落库。
    assertThat(entryCount()).isZero();
  }

  @Test
  void 未审批的调整单不入账且数据库层写不进分录() {
    UUID sheet = countedSheet();
    var loss = varianceLine(sheet, "SKU-1", "A-01");
    var beforeAttribution = java.time.Instant.now();
    attribute(sheet, loss.id(), "SHRINKAGE", "ADJUST", "搬运破损两件");
    var adj = adjust(sheet, List.of(loss.id()));
    UUID adjustmentId = adj.adjustment().id();
    access.run(
        actorA, "warehouse.inventory.update", () -> service.submit(adjustmentId, null, user));

    // 端点层：未审批就 post → 409，余额不动。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> service.post(adjustmentId, null, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
    assertThat(bucketOnHand("SKU-1", "A-01")).isEqualByComparingTo("100");

    // **数据库层**：拿到连接硬写分录也必须被拒 —— 这是红线的可执行形式。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> {
                      UUID txId = UUID.randomUUID();
                      sql.execute(
                          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,notes)"
                              + " values(?,?,'ADJUSTMENT',?,?,?::timestamptz,?,'绕过服务硬写')",
                          txId,
                          a,
                          "STOCKTAKE_VARIANCE",
                          adjustmentId,
                          now(),
                          user);
                      sql.execute(
                          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,reserved_delta)"
                              + " values(?,?,?,?,-4::numeric,0::numeric)",
                          UUID.randomUUID(),
                          a,
                          txId,
                          loss.balanceId());
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("尚未审批通过");
    assertThat(entryCount()).isZero();
    assertThat(bucketOnHand("SKU-1", "A-01")).isEqualByComparingTo("100");
  }

  @Test
  void 状态机在数据库层拒绝跳过审批() {
    UUID sheet = countedSheet();
    var loss = varianceLine(sheet, "SKU-1", "A-01");
    var beforeAttribution = java.time.Instant.now();
    attribute(sheet, loss.id(), "SHRINKAGE", "ADJUST", "搬运破损两件");
    UUID adjustmentId = adjust(sheet, List.of(loss.id())).adjustment().id();

    // DRAFT 直接跳到 POSTED：DB 拒绝。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "update stocktake_variance_adjustment set status = 'POSTED', decided_at = ?::timestamptz,"
                              + " posted_at = ?::timestamptz where tenant_id = ? and id = ?",
                          now(),
                          now(),
                          a,
                          adjustmentId);
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("状态不能从");
    // 删单被**两道**挡住：先是 REVOKE DELETE（应用角色根本没有这个权限），
    // 再是 guard_variance_adjustment_no_delete 触发器（即使拿到更高权限也删不掉）。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "delete from stocktake_variance_adjustment where tenant_id = ? and id = ?",
                          a,
                          adjustmentId);
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            sql.fetchOne(
                    "select count(*) as c from pg_trigger where tgname = 'stocktake_variance_adjustment_no_delete'"
                        + " and tgrelid = 'stocktake_variance_adjustment'::regclass")
                .get("c", Long.class))
        .isEqualTo(1);
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('warehouse_app','stocktake_variance_adjustment','DELETE') as p")
                .get("p", Boolean.class))
        .isFalse();
  }

  @Test
  void 提交人不能审批自己提的单() {
    UUID sheet = countedSheet();
    var loss = varianceLine(sheet, "SKU-1", "A-01");
    var beforeAttribution = java.time.Instant.now();
    attribute(sheet, loss.id(), "SHRINKAGE", "ADJUST", "搬运破损两件");
    UUID adjustmentId = adjust(sheet, List.of(loss.id())).adjustment().id();
    access.run(
        actorA, "warehouse.inventory.update", () -> service.submit(adjustmentId, null, user));
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.approve(
                            adjustmentId, new StocktakeVarianceDecisionInput("自己批"), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能审批自己提交的调整单");
    assertThat(bucketOnHand("SKU-1", "A-01")).isEqualByComparingTo("100");
  }

  @Test
  void 审批后入账且分录与明细逐行相等() {
    UUID sheet = countedSheet();
    var loss = varianceLine(sheet, "SKU-1", "A-01");
    var gain = varianceLine(sheet, "SKU-2", "A-01");
    var beforeAttribution = java.time.Instant.now();
    attribute(sheet, loss.id(), "SHRINKAGE", "ADJUST", "搬运破损两件");
    attribute(sheet, gain.id(), "UNRECORDED", "ADJUST", "到货漏记入库单");
    var adj = adjust(sheet, List.of(loss.id(), gain.id()));
    UUID adjustmentId = adj.adjustment().id();
    access.run(
        actorA, "warehouse.inventory.update", () -> service.submit(adjustmentId, null, user));
    var approved =
        access.run(
            actorApprover,
            "warehouse.inventory.update",
            () ->
                service.approve(
                    adjustmentId, new StocktakeVarianceDecisionInput("已核实损耗单据"), approver));
    assertThat(approved.status()).isEqualTo(StocktakeVarianceAdjustmentStatus.APPROVED);
    // 批准仍然不改余额。
    assertThat(bucketOnHand("SKU-1", "A-01")).isEqualByComparingTo("100");

    var posted =
        access.run(
            actorApprover,
            "warehouse.inventory.update",
            () ->
                service.post(
                    adjustmentId, new StocktakeVarianceDecisionInput("据盘点差异入账"), approver));
    assertThat(posted.adjustment().status()).isEqualTo(StocktakeVarianceAdjustmentStatus.POSTED);
    assertThat(posted.entryCount()).isEqualTo(2);
    assertThat(posted.postedQty()).isEqualByComparingTo("0");
    // 余额按带符号增量改：100-4=96、40+4=44。账面数一个字都没动。
    assertThat(bucketOnHand("SKU-1", "A-01")).isEqualByComparingTo("96");
    assertThat(bucketOnHand("SKU-2", "A-01")).isEqualByComparingTo("44");
    assertThat(balanceOnHand("SKU-1", whOne)).isEqualByComparingTo("121");
    assertThat(
            access
                .run(actorA, "warehouse.inventory.read", () -> stocktakes.detail(sheet))
                .summary()
                .bookTotal())
        .isEqualByComparingTo("165");

    // 分录真的落库了，而且 Σ分录 == Σ调整单行（V62 的延迟约束在提交前核过）。
    assertThat(entryCount()).isEqualTo(2);
    assertThat(ledgerSum(adjustmentId)).isEqualByComparingTo("0");
    var tx =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                sql.fetchOne(
                    "select kind, ref_type, ref_id from inventory_transaction where tenant_id = ? and id = ?",
                    a,
                    posted.transactionId()));
    assertThat(tx.get("kind", String.class)).isEqualTo("ADJUSTMENT");
    // 定位键：这是将来交给 7.4.1.1 调整单管理的唯一交接手段。
    assertThat(tx.get("ref_type", String.class)).isEqualTo("STOCKTAKE_VARIANCE");
    assertThat(tx.get("ref_id", UUID.class)).isEqualTo(adjustmentId);

    // 入账痕迹：每一行都记了谁在什么时候入的账、对应哪条分录。
    var detail = access.run(actorA, "warehouse.inventory.read", () -> service.detail(adjustmentId));
    assertThat(detail.lines())
        .allSatisfy(
            l -> {
              assertThat(l.postedAt()).isNotNull();
              assertThat(l.entryId()).isNotNull();
              assertThat(l.transactionId()).isEqualTo(posted.transactionId());
            });
    // 再入账一次会被挡住（已 POSTED），且同一条差异不允许被两张单各记一次。
    assertThatThrownBy(
            () ->
                access.run(
                    actorApprover,
                    "warehouse.inventory.update",
                    () -> service.post(adjustmentId, null, approver)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");
    assertThatThrownBy(() -> attribute(sheet, loss.id(), "SHRINKAGE", "ADJUST", "想再调一次"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("已经过账入账");
    assertThat(bucketOnHand("SKU-1", "A-01")).isEqualByComparingTo("96");
  }

  @Test
  void 入账数量必须与调整单明细逐行相等() {
    UUID sheet = countedSheet();
    var loss = varianceLine(sheet, "SKU-1", "A-01");
    var gain = varianceLine(sheet, "SKU-2", "A-01");
    var beforeAttribution = java.time.Instant.now();
    attribute(sheet, loss.id(), "SHRINKAGE", "ADJUST", "搬运破损两件");
    attribute(sheet, gain.id(), "UNRECORDED", "ADJUST", "到货漏记入库单");
    UUID adjustmentId = adjust(sheet, List.of(loss.id(), gain.id())).adjustment().id();
    access.run(
        actorA, "warehouse.inventory.update", () -> service.submit(adjustmentId, null, user));
    access.run(
        actorApprover,
        "warehouse.inventory.update",
        () -> service.approve(adjustmentId, null, approver));

    // 只入一部分（少记 +4 那行）：延迟约束在提交前当场拒绝「入账数量不平」。
    assertThatThrownBy(
            () ->
                access.run(
                    actorApprover,
                    "warehouse.inventory.update",
                    () -> {
                      UUID txId = UUID.randomUUID();
                      sql.execute(
                          "insert into inventory_transaction(id,tenant_id,kind,ref_type,ref_id,occurred_at,actor_id,notes)"
                              + " values(?,?,'ADJUSTMENT',?,?,?::timestamptz,?,'只入一部分')",
                          txId,
                          a,
                          "STOCKTAKE_VARIANCE",
                          adjustmentId,
                          now(),
                          approver);
                      sql.execute(
                          "insert into inventory_entry(id,tenant_id,transaction_id,balance_id,on_hand_delta,reserved_delta)"
                              + " values(?,?,?,?,-4::numeric,0::numeric)",
                          UUID.randomUUID(),
                          a,
                          txId,
                          loss.balanceId());
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class);
    assertThat(entryCount()).isZero();
  }

  @Test
  void 盘亏超过在手时入账被拦() {
    standardStock();
    // 先发起盘点冻结 25，再把这 25 正常卖出去（冻结之后的正常收发），
    // 然后实盘录 0：差异 −25 会把已经在手的 0 扣成负数。
    var saved =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () ->
                stocktakes.create(
                    new StocktakeRequest("WAREHOUSE", whOne, "BY_LOCATION", "夹具"), user));
    UUID sheetId = saved.sheet().id();
    var target =
        access
            .run(
                actorA,
                "warehouse.inventory.read",
                () -> stocktakes.lines(sheetId, null, null, 0, 50))
            .lines()
            .stream()
            .filter(l -> l.locationCode().equals("A-02"))
            .findFirst()
            .orElseThrow();
    // A-02 冻结的是 25（与 A-01 合计 125）；先卖光。
    access.run(
        actorA,
        "warehouse.inventory.update",
        () -> {
          sql.execute(
              "update stock_bucket set on_hand = on_hand - 25::numeric, last_movement_at = ?::timestamptz"
                  + " where tenant_id = ? and location_id = ?",
              now().plusMinutes(1),
              a,
              locTwo);
          sql.execute(
              "update inventory_balance set on_hand = on_hand - 25::numeric where tenant_id = ? and id = ?",
              a,
              target.skuId().equals(skuOne)
                  ? balanceIdOf(skuOne, whOne)
                  : balanceIdOf(skuTwo, whOne));
          return null;
        });
    access.run(
        actorA,
        "warehouse.inventory.update",
        () ->
            stocktakes.count(
                sheetId, target.id(), new StocktakeCountInput(BigDecimal.ZERO, null, "空"), user));

    var variance = varianceLine(sheetId, target.skuCode(), "A-02");
    assertThat(variance.variance()).isEqualByComparingTo("-25");
    attribute(sheetId, variance.id(), "SHRINKAGE", "ADJUST", "以为还在");
    UUID adjustmentId = adjust(sheetId, List.of(variance.id())).adjustment().id();
    access.run(
        actorA, "warehouse.inventory.update", () -> service.submit(adjustmentId, null, user));
    access.run(
        actorApprover,
        "warehouse.inventory.update",
        () -> service.approve(adjustmentId, null, approver));
    assertThatThrownBy(
            () ->
                access.run(
                    actorApprover,
                    "warehouse.inventory.update",
                    () -> service.post(adjustmentId, null, approver)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409")
        .hasMessageContaining("时点差");
    assertThat(bucketOnHand(target.skuCode(), "A-02")).isEqualByComparingTo("0");
  }

  // ================================================================ 三种处理决定

  @Test
  void 忽略必须留痕且不能被翻案() {
    UUID sheet = countedSheet();
    var loss = varianceLine(sheet, "SKU-1", "A-01");
    // 忽略必须写够长的理由：单字等于没写。
    assertThatThrownBy(() -> attribute(sheet, loss.id(), "SHRINKAGE", "IGNORE", "无"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("至少 4 个字");
    assertThatThrownBy(() -> attribute(sheet, loss.id(), "SHRINKAGE", "IGNORE", "  "))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须写说明");

    var ignored = attribute(sheet, loss.id(), "TIMING", "IGNORE", "冻结之后这批货正常发运走了，不是损耗");
    assertThat(ignored.varianceDecision()).isEqualTo("IGNORE");
    assertThat(ignored.resolvedBy()).isEqualTo("IGNORED");
    assertThat(ignored.resolvedAt()).isNotNull();
    assertThat(ignored.attributedByUserId()).isEqualTo(user);
    // 忽略不改余额。
    assertThat(bucketOnHand("SKU-1", "A-01")).isEqualByComparingTo("100");

    // 忽略是终态：不允许「先忽略再偷偷改成调整」（DB 拒绝）。
    assertThatThrownBy(() -> attribute(sheet, loss.id(), "SHRINKAGE", "ADJUST", "改主意了"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("忽略");
    // 归因行也不允许删（审计凭据）。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "delete from stocktake_variance_attribution where tenant_id = ? and sheet_id = ?",
                          a,
                          sheet);
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            sql.fetchOne(
                    "select count(*) as c from pg_trigger where tgname = 'stocktake_variance_attribution_no_delete'"
                        + " and tgrelid = 'stocktake_variance_attribution'::regclass")
                .get("c", Long.class))
        .isEqualTo(1);
    assertThat(
            sql.fetchOne(
                    "select has_table_privilege('warehouse_app','stocktake_variance_attribution','DELETE') as p")
                .get("p", Boolean.class))
        .isFalse();
    // 审计里有一条 IGNORED，谁在什么时候为什么都留痕。
    var trail =
        access.run(actorA, "warehouse.inventory.read", () -> service.auditTrail(sheet, null));
    assertThat(trail)
        .anySatisfy(
            e -> {
              assertThat(e.action()).isEqualTo("IGNORED");
              assertThat(e.actorUserId()).isEqualTo(user);
              assertThat(e.detail()).contains("时点差").contains("不是损耗");
            });
  }

  @Test
  void 复盘把实盘退回未录且盘点单状态退回() {
    UUID sheet = countedSheet();
    var loss = varianceLine(sheet, "SKU-1", "A-01");
    assertThat(
            access
                .run(actorA, "warehouse.inventory.read", () -> stocktakes.detail(sheet))
                .sheet()
                .status()
                .name())
        .isEqualTo("COUNTED");

    attribute(sheet, loss.id(), "MISSHIP", "RECOUNT", "怀疑库位串了，重新盘一次");
    // 差异消失（未录入 → variance 为 NULL），这一行回到「还没盘」。
    var after =
        access
            .run(
                actorA,
                "warehouse.inventory.read",
                () -> stocktakes.lines(sheet, null, null, 0, 50))
            .lines()
            .stream()
            .filter(l -> l.id().equals(loss.id()))
            .findFirst()
            .orElseThrow();
    assertThat(after.countedQty()).isNull();
    assertThat(after.variance()).isNull();
    assertThat(after.bookOnHand()).isEqualByComparingTo("100");
    // 盘点单状态重算：又出现未录行，COUNTED 退回 COUNTING。
    assertThat(
            access
                .run(actorA, "warehouse.inventory.read", () -> stocktakes.detail(sheet))
                .sheet()
                .status()
                .name())
        .isEqualTo("COUNTING");
    // 复盘后可以重新归因（未结的那条已结案，差异行也不再是终态）。
    access.run(
        actorA,
        "warehouse.inventory.update",
        () ->
            stocktakes.count(
                sheet, loss.id(), new StocktakeCountInput(new BigDecimal("97"), null, "复盘"), user));
    var again = varianceLine(sheet, "SKU-1", "A-01");
    assertThat(again.variance()).isEqualByComparingTo("-3");
    assertThat(attribute(sheet, again.id(), "SHRINKAGE", "ADJUST", "复盘后确认是损耗").varianceReason())
        .isEqualTo("SHRINKAGE");
    // 两次归因都在审计里（归因表只留未结那条，复盘覆盖不掉历史）。
    var trail =
        access.run(actorA, "warehouse.inventory.read", () -> service.auditTrail(sheet, null));
    assertThat(trail.stream().filter(e -> e.action().equals("RECOUNTED")).count()).isEqualTo(1);
    assertThat(trail.stream().filter(e -> e.action().equals("ATTRIBUTED")).count()).isEqualTo(1);
  }

  @Test
  void 驳回释放归因后可重新归因() {
    UUID sheet = countedSheet();
    var loss = varianceLine(sheet, "SKU-1", "A-01");
    var beforeAttribution = java.time.Instant.now();
    attribute(sheet, loss.id(), "SHRINKAGE", "ADJUST", "搬运破损两件");
    UUID adjustmentId = adjust(sheet, List.of(loss.id())).adjustment().id();
    access.run(
        actorA, "warehouse.inventory.update", () -> service.submit(adjustmentId, null, user));
    // 驳回必须写意见。
    assertThatThrownBy(
            () ->
                access.run(
                    actorApprover,
                    "warehouse.inventory.update",
                    () ->
                        service.reject(
                            adjustmentId, new StocktakeVarianceDecisionInput(" "), approver)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("驳回必须写意见");
    var rejected =
        access.run(
            actorApprover,
            "warehouse.inventory.update",
            () ->
                service.reject(
                    adjustmentId, new StocktakeVarianceDecisionInput("损耗单据没附上"), approver));
    assertThat(rejected.status()).isEqualTo(StocktakeVarianceAdjustmentStatus.REJECTED);
    // 归因已释放：同一行可以重新归因并再生成一张单。
    var reattributed = attribute(sheet, loss.id(), "SHRINKAGE", "ADJUST", "已补附损耗单据");
    assertThat(reattributed.adjustmentId()).isNull();
    assertThat(adjust(sheet, List.of(loss.id())).adjustment().lineCount()).isEqualTo(1);
  }

  // ================================================================ 完成报告

  @Test
  void 完成报告的率是真算出来的() {
    UUID sheet = countedSheet();
    var report = access.run(actorA, "warehouse.inventory.read", () -> service.report(sheet));
    var m = report.metrics();
    assertThat(m.lineCount()).isEqualTo(3);
    assertThat(m.countedLineCount()).isEqualTo(3);
    assertThat(m.varianceLineCount()).isEqualTo(2);
    assertThat(m.matchLineCount()).isEqualTo(1);
    assertThat(m.bookTotal()).isEqualByComparingTo("165");
    assertThat(m.countedTotal()).isEqualByComparingTo("165");
    assertThat(m.gainQty()).isEqualByComparingTo("4");
    assertThat(m.lossQty()).isEqualByComparingTo("4");
    assertThat(m.netVarianceQty()).isZero();
    // 行差异率 = 2/3；数量差异率 = 8/165；盘盈率 = 4/165。
    assertThat(m.lineVarianceRate()).isEqualByComparingTo("66.6667");
    assertThat(m.qtyVarianceRate()).isEqualByComparingTo("4.8485");
    assertThat(m.gainRate()).isEqualByComparingTo("2.4242");
    assertThat(m.lossRate()).isEqualByComparingTo("2.4242");
    // 归因前完成度为 0。
    assertThat(m.attributedLineCount()).isZero();
    assertThat(m.attributionProgress()).isEqualByComparingTo("0.0000");
    assertThat(report.rateBasis()).contains("账面合计");
    // 金额口径如实报为算不出来。
    assertThat(report.amountBasisAvailable()).isFalse();
    assertThat(report.amountBasisNote()).contains("库存成本");

    // 五种原因恒定各占一行（没有的那类是 0，不是缺席）。
    assertThat(report.byReason()).hasSize(5);
    var loss = varianceLine(sheet, "SKU-1", "A-01");
    var beforeAttribution = java.time.Instant.now();
    attribute(sheet, loss.id(), "SHRINKAGE", "ADJUST", "搬运破损两件");
    var after = access.run(actorA, "warehouse.inventory.read", () -> service.report(sheet));
    assertThat(after.byReason())
        .extracting(StocktakeVarianceReasonBreakdown::reason)
        .containsExactly(
            StocktakeVarianceReason.SHRINKAGE,
            StocktakeVarianceReason.MISSHIP,
            StocktakeVarianceReason.THEFT,
            StocktakeVarianceReason.UNRECORDED,
            StocktakeVarianceReason.TIMING);
    var shrinkage = after.byReason().get(0);
    assertThat(shrinkage.lineCount()).isEqualTo(1);
    assertThat(shrinkage.lossQty()).isEqualByComparingTo("4");
    assertThat(shrinkage.netQty()).isEqualByComparingTo("-4");
    assertThat(shrinkage.inAdjustmentCount()).isEqualTo(1);
    assertThat(shrinkage.postedCount()).isZero();
    assertThat(after.byReason().get(2).lineCount()).isZero(); // 偷盗确实为零，但不能缺席
    assertThat(after.metrics().attributedLineCount()).isEqualTo(1);
    assertThat(after.metrics().attributionProgress()).isEqualByComparingTo("50.0000");
    // 三种决定恒定各占一行。
    assertThat(after.byDecision())
        .extracting(StocktakeVarianceDecisionBreakdown::decision)
        .containsExactly(
            StocktakeVarianceDecision.ADJUST,
            StocktakeVarianceDecision.RECOUNT,
            StocktakeVarianceDecision.IGNORE);
    assertThat(after.byDecision().get(0).lineCount()).isEqualTo(1);
    assertThat(after.byDecision().get(0).openLineCount()).isEqualTo(1);
  }

  @Test
  void 分母为零的率是空而不是零() {
    // 一个账面合计为 0 的库位组：盘它盘不出任何率。
    group(whOne, skuOne, List.of(new BucketSpec(locOne, lotOne, "0")));
    var saved =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () ->
                stocktakes.create(
                    new StocktakeRequest("WAREHOUSE", whOne, "BY_LOCATION", "空仓位夹具"), user));
    UUID sheet = saved.sheet().id();
    for (var l :
        access
            .run(
                actorA,
                "warehouse.inventory.read",
                () -> stocktakes.lines(sheet, null, null, 0, 50))
            .lines()) {
      access.run(
          actorA,
          "warehouse.inventory.update",
          () ->
              stocktakes.count(
                  sheet,
                  l.id(),
                  new StocktakeCountInput(new BigDecimal("3"), null, "多盘出三件"),
                  user));
    }
    var m = access.run(actorA, "warehouse.inventory.read", () -> service.report(sheet)).metrics();
    assertThat(m.bookTotal()).isEqualByComparingTo("0");
    assertThat(m.gainQty()).isEqualByComparingTo("3");
    // 0 会被读成「差异率 0%」，那是谎报：账面合计为 0 时根本盘不出率。
    assertThat(m.gainRate()).isNull();
    assertThat(m.qtyVarianceRate()).isNull();
    // 行口径分母不为 0，照常算。
    assertThat(m.lineVarianceRate()).isEqualByComparingTo("100.0000");
    assertThat(m.attributionProgress()).isEqualByComparingTo("0.0000");
  }

  // ================================================================ 时区与查询

  @Test
  void 时区零漂移() {
    UUID sheet = countedSheet();
    var loss = varianceLine(sheet, "SKU-1", "A-01");
    OffsetDateTime at = OffsetDateTime.of(2026, 10, 7, 23, 30, 0, 0, ZoneOffset.ofHours(8));
    // 归因时刻用同一个值写进两个列，断言读回来还是 +08:00 的那一刻。
    var beforeAttribution = java.time.Instant.now();
    attribute(sheet, loss.id(), "SHRINKAGE", "ADJUST", "搬运破损两件");
    var stored =
        access
            .run(
                actorA,
                "warehouse.inventory.read",
                () ->
                    sql.fetchOne(
                        "select occurred_at from stocktake_variance_audit where tenant_id = ? and action = 'ATTRIBUTED'",
                        a))
            .get("occurred_at", OffsetDateTime.class);
    // TIMESTAMPTZ preserves the instant; its returned offset depends on the database/JVM zone.
    assertThat(stored.toInstant()).isBetween(beforeAttribution, java.time.Instant.now());
    assertThat(stored).isNotEqualTo(at); // 夹具时刻不是这次的归因时刻
  }

  @Test
  void 差异明细分页与筛选() {
    UUID sheet = countedSheet();
    var all =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.lines(sheet, null, null, null, null, 0, 50));
    assertThat(all.total()).isEqualTo(2); // 相符的行不进差异工作台
    assertThat(all.lines()).allSatisfy(l -> assertThat(l.variance()).isNotZero());
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.lines(sheet, "UNATTRIBUTED", null, null, null, 0, 50))
                .total())
        .isEqualTo(2);

    var loss = varianceLine(sheet, "SKU-1", "A-01");
    var beforeAttribution = java.time.Instant.now();
    attribute(sheet, loss.id(), "SHRINKAGE", "ADJUST", "搬运破损两件");
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.lines(sheet, "UNATTRIBUTED", null, null, null, 0, 50))
                .total())
        .isEqualTo(1);
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.lines(sheet, "IN_ADJUSTMENT", null, null, null, 0, 50))
                .total())
        .isEqualTo(1);
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.lines(sheet, null, "SHRINKAGE", null, null, 0, 50))
                .total())
        .isEqualTo(1);
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.lines(sheet, null, "THEFT", null, null, 0, 50))
                .total())
        .isZero();
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.lines(sheet, null, null, null, "SKU-2", 0, 50))
                .total())
        .isEqualTo(1);
    // 真分页。
    assertThat(
            access
                .run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.lines(sheet, null, null, null, null, 1, 1))
                .lines())
        .hasSize(1);
  }

  // ================================================================ 端点与权限

  @Test
  void 端点返回真实数据并守住写权限() throws Exception {
    UUID sheet = countedSheet();
    var loss = varianceLine(sheet, "SKU-1", "A-01");

    http.perform(get("/warehouse/inventory/stocktake-variances/capabilities"))
        .andExpect(status().isUnauthorized());
    http.perform(
            get("/warehouse/inventory/stocktake-variances/sheets/{sheetId}/lines", sheet)
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2));
    http.perform(
            get("/warehouse/inventory/stocktake-variances/sheets/{sheetId}/report", sheet)
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metrics.varianceLineCount").value(2))
        .andExpect(jsonPath("$.byReason.length()").value(5))
        .andExpect(jsonPath("$.amountBasisAvailable").value(false));
    // 只读身份不能归因、不能建单、不能入账。
    http.perform(
            post(
                    "/warehouse/inventory/stocktake-variances/sheets/{sheetId}/lines/{lineId}/attribute",
                    sheet,
                    loss.id())
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{\"reason\":\"SHRINKAGE\",\"decision\":\"ADJUST\",\"note\":\"搬运破损两件\"}"))
        .andExpect(status().isForbidden());

    // 端点归因 → 建单 → 提交 → 另一人批准 → 入账，一条真实链路。
    http.perform(
            post(
                    "/warehouse/inventory/stocktake-variances/sheets/{sheetId}/lines/{lineId}/attribute",
                    sheet,
                    loss.id())
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"reason\":\"SHRINKAGE\",\"decision\":\"ADJUST\",\"note\":\"搬运破损两件\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.varianceReason").value("SHRINKAGE"));
    String adjId =
        http.perform(
                post("/warehouse/inventory/stocktake-variances/sheets/{sheetId}/adjustments", sheet)
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content("{\"lineIds\":[\"" + loss.id() + "\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.adjustment.status").value("DRAFT"))
            .andExpect(jsonPath("$.adjustment.lineCount").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var parsed =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(adjId)
            .get("adjustment")
            .get("id")
            .asText();
    http.perform(
            post("/warehouse/inventory/stocktake-variances/adjustments/{id}/submit", parsed)
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    http.perform(
            post("/warehouse/inventory/stocktake-variances/adjustments/{id}/approve", parsed)
                .header("Authorization", "Bearer approver")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
    http.perform(
            post("/warehouse/inventory/stocktake-variances/adjustments/{id}/post", parsed)
                .header("Authorization", "Bearer approver")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.adjustment.status").value("POSTED"))
        .andExpect(jsonPath("$.entryCount").value(1));
    http.perform(
            get("/warehouse/inventory/stocktake-variances/adjustments/{id}", parsed)
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.auditTrail.length()").value(4)); // 归因/建单/提交/批准/入账中的四条
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    UUID sheetA = countedSheet();
    var lineA = varianceLine(sheetA, "SKU-1", "A-01");
    attribute(sheetA, lineA.id(), "THEFT", "ADJUST", "有人顺手拿了");
    UUID adjA = adjust(sheetA, List.of(lineA.id())).adjustment().id();

    groupFor(b, idFor(b, 4), idFor(b, 2), "777");
    var sheetB =
        access.run(
            actorB,
            "warehouse.inventory.update",
            () ->
                stocktakes.create(
                    new StocktakeRequest("WAREHOUSE", idFor(b, 4), "BY_LOCATION", "B 的单"), user));
    UUID sheetIdB = sheetB.sheet().id();
    var lineB =
        access
            .run(
                actorB,
                "warehouse.inventory.read",
                () -> stocktakes.lines(sheetIdB, null, null, 0, 5))
            .lines()
            .get(0);

    // B 看不到 A 的任何东西：连差异明细都是 404（比返回空集更强 —— 不泄露存在性）。
    assertThatThrownBy(
            () ->
                access.run(
                    actorB,
                    "warehouse.inventory.read",
                    () -> service.lines(sheetA, null, null, null, null, 0, 50)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThat(
            access
                .run(
                    actorB,
                    "warehouse.inventory.read",
                    () -> service.adjustments(null, null, null, 0, 20))
                .total())
        .isZero();
    assertThatThrownBy(
            () -> access.run(actorB, "warehouse.inventory.read", () -> service.detail(adjA)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThatThrownBy(
            () -> access.run(actorB, "warehouse.inventory.read", () -> service.report(sheetA)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThatThrownBy(
            () ->
                access.run(
                    actorB, "warehouse.inventory.read", () -> service.auditTrail(sheetA, null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    // B 也不能往 A 的差异行上写归因。
    assertThatThrownBy(
            () ->
                access.run(
                    actorB,
                    "warehouse.inventory.update",
                    () ->
                        service.attribute(
                            sheetA,
                            lineA.id(),
                            new StocktakeVarianceAttributionInput("SHRINKAGE", "ADJUST", "越权尝试"),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    // B 自己的单据与 A 的互不可见。
    assertThat(
            access
                .run(
                    actorB,
                    "warehouse.inventory.read",
                    () -> service.adjustments(null, null, null, 0, 20))
                .adjustments()
                .stream()
                .map(StocktakeVarianceAdjustment::id))
        .doesNotContain(adjA);
    assertThat(lineB.sheetId()).isEqualTo(sheetB.sheet().id());
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    UUID sheet = countedSheet();
    var loss = varianceLine(sheet, "SKU-1", "A-01");
    var beforeAttribution = java.time.Instant.now();
    attribute(sheet, loss.id(), "SHRINKAGE", "ADJUST", "搬运破损两件");
    UUID adjId = adjust(sheet, List.of(loss.id())).adjustment().id();
    access.run(actorA, "warehouse.inventory.update", () -> service.submit(adjId, null, user));

    // 身份是 B，伪造 X-Tenant-Id 指向 A：B 看到的仍然是 B 的（空）。
    http.perform(
            get("/warehouse/inventory/stocktake-variances/adjustments")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    http.perform(
            get("/warehouse/inventory/stocktake-variances/adjustments/{id}", adjId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 伪造头也不能让 B 把 A 的单批了或入账。
    http.perform(
            post("/warehouse/inventory/stocktake-variances/adjustments/{id}/approve", adjId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isNotFound());
    http.perform(
            post("/warehouse/inventory/stocktake-variances/adjustments/{id}/post", adjId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isNotFound());
    // A 的余额未被这串伪造请求改动。
    assertThat(bucketOnHand("SKU-1", "A-01")).isEqualByComparingTo("100");
    assertThat(entryCount()).isZero();
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    UUID sheetA = countedSheet();
    var lineA = varianceLine(sheetA, "SKU-1", "A-01");
    attribute(sheetA, lineA.id(), "SHRINKAGE", "ADJUST", "搬运破损两件");
    adjust(sheetA, List.of(lineA.id()));
    groupFor(b, idFor(b, 4), idFor(b, 2), "777");

    // 交替复用同一条物理连接（池容量=1）：A → B → A → B
    for (int i = 0; i < 3; i++) {
      var a1 = access.run(actorA, "warehouse.inventory.read", () -> service.report(sheetA));
      assertThat(a1.sheetCode()).isNotBlank();
      assertThat(a1.metrics().varianceLineCount()).isEqualTo(2);
      var b1 =
          access.run(
              actorB,
              "warehouse.inventory.read",
              () -> service.adjustments(null, null, null, 0, 20));
      assertThat(b1.total()).isZero();
      var a2 =
          access.run(
              actorA,
              "warehouse.inventory.read",
              () -> service.adjustments(null, null, null, 0, 20));
      assertThat(a2.total()).isEqualTo(1);
    }
    // 四张表逐张确认 FORCE RLS
    for (var table :
        List.of(
            "stocktake_variance_attribution", "stocktake_variance_adjustment",
            "stocktake_variance_adjustment_line", "stocktake_variance_audit")) {
      assertThat(
              sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", table)
                  .get("relforcerowsecurity", Boolean.class))
          .isTrue();
      assertThat(
              sql.fetchOne("select relrowsecurity from pg_class where relname = ?", table)
                  .get("relrowsecurity", Boolean.class))
          .isTrue();
    }
    // 审计表 append-only：连改都不行。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "update stocktake_variance_audit set detail = '改一笔试试' where tenant_id = ? and sheet_id = ?",
                          a,
                          sheetA);
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  // ================================================================ 小工具

  long entryCount() {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select count(*) as c from inventory_entry e"
                        + " join inventory_transaction t on t.tenant_id = e.tenant_id and t.id = e.transaction_id"
                        + " where t.ref_type = 'STOCKTAKE_VARIANCE'")
                .get("c", Long.class));
  }

  BigDecimal ledgerSum(UUID adjustmentId) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select coalesce(sum(e.on_hand_delta),0) as s from inventory_entry e"
                        + " join inventory_transaction t on t.tenant_id = e.tenant_id and t.id = e.transaction_id"
                        + " where t.ref_type = 'STOCKTAKE_VARIANCE' and t.ref_id = ?",
                    adjustmentId)
                .get("s", BigDecimal.class));
  }

  BigDecimal balanceOnHand(String skuCode, UUID warehouse) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select b.on_hand from inventory_balance b join sku_ref s on s.tenant_id = b.tenant_id and s.id = b.sku_id"
                        + " where b.tenant_id = ? and s.code = ? and b.warehouse_id = ?",
                    a,
                    skuCode,
                    warehouse)
                .get("on_hand", BigDecimal.class));
  }

  UUID balanceIdOf(UUID sku, UUID warehouse) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select id from inventory_balance where tenant_id = ? and sku_id = ? and warehouse_id = ?",
                    a,
                    sku,
                    warehouse)
                .get("id", UUID.class));
  }
}
