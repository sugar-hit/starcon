package com.starcon.warehouse;

import static com.starcon.warehouse.VmiModels.*;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * 7.7.2.1 VMI 供应商管理库存：协议 → 水位监控 → 补货信号 → 售出事实 → 结算对账单，
 * <b>以及「可售 ≠ 自有」这条口径</b>，外加租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本测试的核心是 {@link #数据库层挡住把VMI货算成自有资产()} 与
 * {@link #水位监控把可售与自有分成两个数()} 两条。</b>
 * 蓝图把它写成加粗约束：「可售量要计入可售额，货值不能计入自有资产，两者必须分开统计」。
 * 光断言「接口返回的 ownedAssetValue 是 null」证明不了这条红线 —— 那只是服务层自觉，
 * 任何一次绕过服务层的写入都能把它变成真的。所以必须**真的**去数据库里硬写不该存在的行，
 * 验证 V115 的闸门会当场拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
class VmiIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(p -> Stream.of("read", "create", "update", "delete").map(op -> "warehouse." + p + "." + op))
          .toList();

  static final UUID supplier = UUID.randomUUID();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-vmi-" + tenant + "-" + ordinal).getBytes());
  }

  /**
   * 货主档案按租户各建一份：{@code stock_owner.id} 是**全局**主键（不是 (tenant_id,id) 复合），
   * 同一个 UUID 插两个租户会撞主键。
   */
  static UUID selfOwnerOf(UUID tenant) {
    return idFor(tenant, 9);
  }

  /** VMI 供应商货主（SUPPLIER_CONSIGNMENT，counts_as_owned_asset 为 false）。 */
  static UUID vmiOwnerOf(UUID tenant) {
    return idFor(tenant, 10);
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(), grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
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

  @Autowired VmiService service;
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
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS ——
    // 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate vmi_settlement_line, vmi_settlement, vmi_sale, vmi_replenishment_signal,"
              + " vmi_stock_snapshot, vmi_agreement_line, vmi_agreement, vmi_audit,"
              + " stock_bucket, inventory_balance, warehouse_location, stock_ownership_rule,"
              + " stock_owner, inventory_quality_status, warehouse, sku_ref, legal_entity_ref"
              + " restart identity cascade");
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
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 4), tenant, "WH-ONE", "一仓", "CN", "Asia/Shanghai");
            sql.execute(
                "insert into warehouse_location(id,tenant_id,warehouse_id,code,name,type,status,pickable)"
                    + " values(?,?,?,?,?,'STORAGE','ACTIVE',true)",
                idFor(tenant, 6), tenant, idFor(tenant, 4), "LOC-1", "库位一");
            // SELLABLE 质量状态：VMI 的可售量只认这一类（与 7.1.1.3 同口径）。
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 7), tenant, "GOOD", "良品", "SELLABLE");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 8), tenant, "PENDING", "待检", "PENDING_INSPECTION");
            // 货主档案（7.7.1.1 V67）：SELF 计入自有资产，SUPPLIER_CONSIGNMENT 不计入。
            sql.execute(
                "insert into stock_owner(id,tenant_id,code,name,owner_kind,owner_ref_type,"
                    + "owner_ref_id,owner_ref_label,created_by_membership_id) "
                    + "values(?,?,?,?,'SELF','LEGAL_ENTITY',?,?,?)",
                selfOwnerOf(tenant), tenant, "OWNER-SELF", "我方自有", idFor(tenant, 1), "主体一",
                UUID.randomUUID());
            sql.execute(
                "insert into stock_owner(id,tenant_id,code,name,owner_kind,owner_ref_type,"
                    + "owner_ref_id,owner_ref_label,created_by_membership_id) "
                    + "values(?,?,?,?,'SUPPLIER_CONSIGNMENT','SUPPLIER',?,?,?)",
                vmiOwnerOf(tenant), tenant, "OWNER-VMI", "供应商一（VMI）", supplier, "供应商一",
                UUID.randomUUID());
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

  /** 建一份已启用的 VMI 协议：SKU-1 水位 20 / 倍数 2 / 结算价 12.5。 */
  UUID activeAgreement(String cycle, String mode, String target) {
    return activeAgreement(cycle, mode, target, "20", "2", "12.5", "100");
  }

  UUID activeAgreement(
      String cycle, String mode, String target, String safety, String multiple, String price,
      String supplied) {
    var input =
        new VmiAgreementInput(
            supplier, "SUP-1", "供应商一", idFor(a, 1), "CNY", cycle, mode, target,
            LocalDate.now().minusDays(3), null, "VMI 协议",
            List.of(
                new VmiLineInput(
                    idFor(a, 2), idFor(a, 4), vmiOwnerOf(a), new BigDecimal(safety),
                    new BigDecimal(multiple), new BigDecimal(price), new BigDecimal(supplied),
                    new BigDecimal(supplied))));
    var id =
        access.run(actorA, "warehouse.inventory.update", () -> service.createAgreement(input, user))
            .agreement().id();
    return access.run(actorA, "warehouse.inventory.update", () -> service.activate(id, null, user)).id();
  }

  UUID lineOf(UUID agreementId, UUID skuId) {
    return access.run(actorA, "warehouse.inventory.read", () -> service.agreement(agreementId)).lines()
        .stream()
        .filter(l -> l.skuId().equals(skuId))
        .findFirst()
        .orElseThrow()
        .id();
  }

  /**
   * 往账本里放一批货（balance + bucket 同事务，守恒触发器才成立）。
   *
   * <p><b>balance 上没有货主维度</b> —— 7.7.1.1（V67）刻意没往 {@code inventory_balance} 的键里
   * 加 stock_owner，所以同一 SKU×仓×法人×质量 只有一行余额，货主差异落在 bucket 上。
   * 这正是「货权是 bucket（最小事实单位）的维度、balance 是它的汇总」，
   * 也是 VMI 货与自有货能在同一个 SKU×仓 里并存而不互相覆盖的原因。
   *
   * @param ownerId 货主：VMI 供应商货主 → 被 VMI 水位监控算成 VMI 可售量；
   *                  SELF 货主 → 落进 {@code selfOwnedSplit} 对照口径
   */
  void putOnHand(UUID tenant, UUID skuId, UUID ownerId, String onHand) {
    access.run(
        actorFor(tenant),
        "warehouse.inventory.update",
        () -> {
          var existing =
              sql.fetchOne(
                  "select id from inventory_balance where tenant_id=? and sku_id=? and warehouse_id=?"
                      + " and legal_entity_id=? and quality_status_id=?",
                  tenant, skuId, idFor(tenant, 4), idFor(tenant, 1), idFor(tenant, 7));
          UUID balance;
          if (existing == null) {
            balance = UUID.randomUUID();
            sql.execute(
                "insert into inventory_balance(id,tenant_id,legal_entity_id,sku_id,warehouse_id,"
                    + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,0)",
                balance, tenant, idFor(tenant, 1), skuId, idFor(tenant, 4), idFor(tenant, 7),
                new BigDecimal(onHand));
          } else {
            balance = existing.get("id", UUID.class);
            sql.execute(
                "update inventory_balance set on_hand = on_hand + ? where tenant_id=? and id=?",
                new BigDecimal(onHand), tenant, balance);
          }
          sql.execute(
              "insert into stock_bucket(id,tenant_id,legal_entity_id,sku_id,warehouse_id,location_id,"
                  + "lot_id,quality_status_id,balance_id,on_hand,reserved,stock_owner_id)"
                  + " values(?,?,?,?,?,?,null,?,?,?,0,?)",
              UUID.randomUUID(), tenant, idFor(tenant, 1), skuId, idFor(tenant, 4), idFor(tenant, 6),
              idFor(tenant, 7), balance, new BigDecimal(onHand), ownerId);
          return null;
        });
  }

  VmiSale sale(UUID agreementId, UUID lineId, String qty, String ref, OffsetDateTime at) {
    return access.run(
        actorA,
        "warehouse.inventory.update",
        () ->
            service.recordSale(
                agreementId, lineId,
                new VmiSaleInput(new BigDecimal(qty), ref, null, null, at, null),
                user));
  }

  // ================================================================ 数据库闸门直写

  /**
   * 绕过服务层直接写数据库 —— 这才是验证「红线真的在数据库里」的正确姿势。
   *
   * <p>必须在租户事务里做：RLS 开着 FORCE，事务外裸写连行都看不见（UPDATE 静默影响 0 行），
   * 那会让「数据库挡住了我」变成一句自欺欺人的话。
   */
  void rawAsA(Runnable write) {
    access.run(actorA, "warehouse.inventory.update", () -> {
      write.run();
      return null;
    });
  }

  /** 断言「被**数据库**拒绝」，而不是被应用层挡住。 */
  void assertDbRejects(String expectedFragment, Runnable write) {
    assertThatThrownBy(() -> rawAsA(write))
        .satisfies(
            e -> {
              var messages = new ArrayList<String>();
              for (Throwable c = e; c != null; c = c.getCause()) {
                if (c.getMessage() == null) continue;
                messages.add(c.getClass().getSimpleName() + ": " + c.getMessage());
                if (c.getMessage().contains(expectedFragment)) return;
              }
              // 逐条打印异常链上的**消息**（不只 toString()）：jOOQ/Spring 包装后的
              // toString() 里没有 PostgreSQL 的原始原因，只看它会得到一个指不到问题的报错。
              fail("期望被数据库拒绝（错误消息含 '" + expectedFragment + "'），实际异常链消息："
                  + messages);
            });
  }

  // ================================================================ 能力与「可售≠自有」

  @Test
  void 能力如实说明可售不等于自有且未接入环节显式报出() {
    var cap = access.run(actorA, "warehouse.inventory.read", () -> service.capabilities());
    // ownedAssetValueAvailable 恒 false：数据库里就不存在「VMI 自有货值」这个口径。
    assertThat(cap.ownedAssetValueAvailable()).isFalse();
    assertThat(cap.separationNote()).contains("分开统计");
    assertThat(cap.ownedAssetNote()).contains("不计入");
    assertThat(cap.sellableNote()).contains("计入可售额");
    // 上游/下游未接入必须显式点名模块与原因，而不是让人从 404 或空数组里猜。
    assertThat(cap.unavailable()).isNotEmpty();
    assertThat(cap.unavailable())
        .extracting(VmiUnavailableCapability::ownerModule)
        .contains("13.1.1.1", "6.2.1.2", "12.1.2.1", "9.3.1.1");
    for (var u : cap.unavailable()) {
      assertThat(u.reason()).isNotBlank();
      assertThat(u.handoff()).isNotBlank();
    }
    assertThat(cap.replenishmentModes()).hasSize(2);
    assertThat(cap.stockSources()).hasSize(3);
  }

  @Test
  void 数据库层挡住把VMI货算成自有资产() {
    var input =
        new VmiAgreementInput(
            supplier, "SUP-1", "供应商一", idFor(a, 1), "CNY", "MONTHLY", "SYSTEM_SIGNAL",
            "SUPPLIER", LocalDate.now(), null, null,
            List.of(new VmiLineInput(
                idFor(a, 2), idFor(a, 4), vmiOwnerOf(a), new BigDecimal("20"), BigDecimal.ONE,
                new BigDecimal("12.5"), new BigDecimal("100"), new BigDecimal("100"))));
    var id =
        access.run(actorA, "warehouse.inventory.update", () -> service.createAgreement(input, user))
            .agreement().id();

    // ① 直接写 counts_as_owned_asset=true —— CHECK 钉死 false。
    assertDbRejects(
        "counts_as_owned_asset",
        () ->
            sql.execute(
                "insert into vmi_agreement_line(id,tenant_id,agreement_id,sku_id,warehouse_id,"
                    + "stock_owner_id,safety_level,replenishment_multiple,settlement_price,"
                    + "counts_as_owned_asset,supplied_quantity) values(?,?,?,?,?,?,?,?,?,true,?)",
                UUID.randomUUID(), a, id, idFor(a, 3), idFor(a, 4), vmiOwnerOf(a),
                new BigDecimal("5"), BigDecimal.ONE, new BigDecimal("1"), new BigDecimal("1")));

    // ② 引用「计入自有资产」的货主（SELF）—— 这是最关键的一条：
    //    光在自己表里写 false 不够，真要把 VMI 货算成资产必须去动 7.7.1.1 的货主档案，
    //    V115 把那扇门用触发器焊死了。
    assertDbRejects(
        "计入自有资产",
        () ->
            sql.execute(
                "insert into vmi_agreement_line(id,tenant_id,agreement_id,sku_id,warehouse_id,"
                    + "stock_owner_id,safety_level,replenishment_multiple,settlement_price,"
                    + "supplied_quantity) values(?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), a, id, idFor(a, 3), idFor(a, 4), selfOwnerOf(a),
                new BigDecimal("5"), BigDecimal.ONE, new BigDecimal("1"), new BigDecimal("1")));

    // ③ 服务层把货主换成 SELF 同样被数据库挡住（错误信息讲清为什么）。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA, "warehouse.inventory.update",
                    () ->
                        service.addLine(
                            id,
                            new VmiAddLineInput(
                                idFor(a, 3), idFor(a, 4), selfOwnerOf(a), new BigDecimal("5"), BigDecimal.ONE,
                                new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1")),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("自有资产");

    // ④ 事后把货主改成 SELF 也被挡住：V115 在 stock_owner 上挂了延迟约束触发器，
    //    否则「建协议时合规、改完货主档案就变资产」这条路是通的。
    assertDbRejects(
        "计入自有资产",
        () -> sql.execute("update stock_owner set owner_kind='SELF', owner_ref_type='INTERNAL',"
            + " owner_ref_id=null, owner_ref_label='' where tenant_id=? and id=?", a, vmiOwnerOf(a)));
  }

  @Test
  void 水位监控把可售与自有分成两个数() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    // 账本里：VMI 货主 30 件、SELF 货主 15 件。
    putOnHand(a, idFor(a, 2), vmiOwnerOf(a), "30");
    putOnHand(a, idFor(a, 2), selfOwnerOf(a), "15");

    var monitor =
        access.run(actorA, "warehouse.inventory.read", () -> service.waterLevelMonitor(agreement));

    // VMI 侧：可售量 30、可售额 = 30 × 12.5 = 375；自有量恒 0、自有货值恒 null。
    assertThat(monitor.ownershipSplit().sellableQuantity()).isEqualByComparingTo("30.000000");
    assertThat(monitor.ownershipSplit().sellableValue()).isEqualByComparingTo("375.0000");
    assertThat(monitor.ownershipSplit().ownedAssetQuantity()).isEqualByComparingTo("0.000000");
    assertThat(monitor.ownershipSplit().ownedAssetValue()).isNull();
    assertThat(monitor.ownershipSplit().countsAsOwnedAsset()).isFalse();
    // 我方自有侧：15 件，**不含** VMI 的 30 件。
    assertThat(monitor.selfOwnedSplit().selfOwnedQuantity()).isEqualByComparingTo("15.000000");
    // 两张卡片不是同一个数 —— 也没有任何字段把它们合并成「库存价值」。
    assertThat(monitor.totalCurrentSellable()).isEqualByComparingTo("30.000000");
    assertThat(monitor.lines().get(0).ownershipSplit().ownedAssetValue()).isNull();
  }

  @Test
  void 账本没有桶时回落到租户登记的观测并标明来源() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    UUID line = lineOf(agreement, idFor(a, 2));

    var snap =
        access.run(
            actorA, "warehouse.inventory.update",
            () ->
                service.recordSnapshot(
                    agreement,
                    new VmiSnapshotInput(
                        line, "TENANT_MAINTAINED", null, now(), new BigDecimal("45"),
                        new BigDecimal("12"), "供应商仓实测"),
                    user));
    assertThat(snap.stockSource()).isEqualTo("TENANT_MAINTAINED");

    var monitor =
        access.run(actorA, "warehouse.inventory.read", () -> service.waterLevelMonitor(agreement));
    assertThat(monitor.lines().get(0).stockSource()).isEqualTo("TENANT_MAINTAINED");
    assertThat(monitor.lines().get(0).stockSourceLabel()).isNotBlank();
    assertThat(monitor.lines().get(0).currentSellableQuantity()).isEqualByComparingTo("45.000000");
    assertThat(monitor.lines().get(0).observedAt()).isNotNull();
    assertThat(monitor.missingStockSourceLineCount()).isZero();

    // WAREHOUSE_BUCKET 是系统算的口径，不接受手工登记：手工写会让「系统算的」
    // 与「人报的」混在一起，来源标注就失去意义了。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA, "warehouse.inventory.update",
                    () ->
                        service.recordSnapshot(
                            agreement,
                            new VmiSnapshotInput(
                                line, "WAREHOUSE_BUCKET", null, now(), new BigDecimal("1"),
                                null, null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不接受手工登记");
  }

  @Test
  void 两边都查不到时如实报缺来源而不是猜() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    var monitor =
        access.run(actorA, "warehouse.inventory.read", () -> service.waterLevelMonitor(agreement));
    assertThat(monitor.missingStockSourceLineCount()).isEqualTo(1);
    assertThat(monitor.lines().get(0).stockSource()).isEqualTo("MISSING");
    // 没有来源时库存数如实是 0，而不是拿「累计供货 − 已售」凑一个数出来。
    assertThat(monitor.lines().get(0).currentSellableQuantity()).isEqualByComparingTo("0.000000");
    assertThat(monitor.stockoutLineCount()).isEqualTo(1);
  }

  // ================================================================ 水位与补货信号

  @Test
  void 低于安全水位按倍数出补货信号() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    // 水位 20、倍数 2、账本在库 8 → 缺口 12 × 倍数 2 = 24。
    putOnHand(a, idFor(a, 2), vmiOwnerOf(a), "8");
    var result =
        access.run(
            actorA, "warehouse.inventory.update",
            () -> service.generateSignals(agreement, new VmiSignalInput(null, "补货"), user));
    assertThat(result.createdCount()).isEqualTo(1);
    var signal = result.signals().get(0);
    assertThat(signal.triggerReason()).isEqualTo("BELOW_SAFETY_LEVEL");
    assertThat(signal.suggestedQuantity()).isEqualByComparingTo("24.000000");
    assertThat(signal.currentSellableQuantity()).isEqualByComparingTo("8.000000");
    assertThat(signal.safetyLevel()).isEqualByComparingTo("20.000000");
    assertThat(signal.signalTarget()).isEqualTo("SUPPLIER");
    assertThat(signal.status()).isEqualTo("OPEN");
  }

  @Test
  void 断货时按断货原因出信号() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    var result =
        access.run(
            actorA, "warehouse.inventory.update",
            () -> service.generateSignals(agreement, new VmiSignalInput(null, null), user));
    assertThat(result.createdCount()).isEqualTo(1);
    assertThat(result.signals().get(0).triggerReason()).isEqualTo("STOCKOUT");
  }

  @Test
  void 已有未了结信号时不重复出单并说明原因() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    access.run(
        actorA, "warehouse.inventory.update",
        () -> service.generateSignals(agreement, new VmiSignalInput(null, null), user));
    var again =
        access.run(
            actorA, "warehouse.inventory.update",
            () -> service.generateSignals(agreement, new VmiSignalInput(null, null), user));
    assertThat(again.createdCount()).isZero();
    assertThat(again.skippedOpenCount()).isEqualTo(1);
    // 跳过原因不报出来的话，「我出了 0 张信号」会被读成「系统没发现问题」。
    assertThat(again.skippedReasons()).anyMatch(s -> s.contains("未了结"));
  }

  @Test
  void 水位健康时不出信号且给出跳过原因() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    // 水位 20、倍数 2 → 缓冲线 = 20 × (1+2) = 60；账本在库 70 属于健康。
    putOnHand(a, idFor(a, 2), vmiOwnerOf(a), "70");
    var monitor =
        access.run(actorA, "warehouse.inventory.read", () -> service.waterLevelMonitor(agreement));
    assertThat(monitor.lines().get(0).waterLevelStatus()).isEqualTo("HEALTHY");
    assertThat(monitor.lines().get(0).bufferLine()).isEqualByComparingTo("60.000000");
    assertThat(monitor.healthyLineCount()).isEqualTo(1);

    var result =
        access.run(
            actorA, "warehouse.inventory.update",
            () -> service.generateSignals(agreement, new VmiSignalInput(null, null), user));
    assertThat(result.createdCount()).isZero();
    assertThat(result.skippedHealthyCount()).isEqualTo(1);
    assertThat(result.skippedReasons()).anyMatch(s -> s.contains("水位健康"));
  }

  @Test
  void 供应商自行补货模式不出信号并说明原因() {
    UUID agreement = activeAgreement("MONTHLY", "VENDOR_OWN", "SUPPLIER");
    var result =
        access.run(
            actorA, "warehouse.inventory.update",
            () -> service.generateSignals(agreement, new VmiSignalInput(null, null), user));
    assertThat(result.createdCount()).isZero();
    assertThat(result.skippedVendorOwnCount()).isEqualTo(1);
    assertThat(result.skippedReasons()).anyMatch(s -> s.contains("供应商自行补货"));
  }

  @Test
  void 信号走完受理下发到货后供货累加() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    var created =
        access.run(
                actorA, "warehouse.inventory.update",
                () -> service.generateSignals(agreement, new VmiSignalInput(null, null), user))
            .signals().get(0);

    access.run(actorA, "warehouse.inventory.update", () -> service.acknowledgeSignal(created.id(), null, user));
    var dispatched =
        access.run(
            actorA, "warehouse.inventory.update", () -> service.dispatchSignal(created.id(), null, user));
    assertThat(dispatched.status()).isEqualTo("DISPATCHED");
    // 下游 6 采购 / 供应商尚未交付：只写交接定位键，不代它们建单。
    assertThat(dispatched.referenceId()).isNotBlank();
    assertThat(dispatched.sourceSystem()).isEqualTo("WAREHOUSE");

    // 一次卖超过累计供货 → 服务层当场拒绝（好让人看得懂）。
    UUID line = lineOf(agreement, idFor(a, 2));
    assertThatThrownBy(() -> sale(agreement, line, "101", "SO-1", now()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("超过该行累计供货");
    // 分两次卖：每次增量都不超供货，但累计超了 → 由数据库的 CHECK 兜底并翻成 409。
    sale(agreement, line, "100", "SO-2", now());
    assertThatThrownBy(() -> sale(agreement, line, "1", "SO-3", now()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("累计已售超过该行累计供货");

    // 到货 50 后供货累加到 150，可以继续卖完剩下 50。
    var fulfilled =
        access.run(
            actorA, "warehouse.inventory.update",
            () ->
                service.fulfilSignal(
                    created.id(), new VmiSignalFulfilInput(new BigDecimal("50"), "到货"), user));
    assertThat(fulfilled.status()).isEqualTo("FULFILLED");
    assertThat(fulfilled.fulfilledQuantity()).isEqualByComparingTo("50.000000");
    sale(agreement, line, "50", "SO-4", now());

    var detail = access.run(actorA, "warehouse.inventory.read", () -> service.agreement(agreement));
    assertThat(detail.lines().get(0).suppliedQuantity()).isEqualByComparingTo("150.000000");
    assertThat(detail.lines().get(0).soldQuantity()).isEqualByComparingTo("150.000000");
  }

  // ================================================================ 售出事实

  @Test
  void 售出不得超过累计供货() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    UUID line = lineOf(agreement, idFor(a, 2));
    assertThatThrownBy(() -> sale(agreement, line, "101", "SO-X", now()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("凭空生成一笔应付");
  }

  @Test
  void 草稿协议不能记销售() {
    var input =
        new VmiAgreementInput(
            supplier, "SUP-1", "供应商一", idFor(a, 1), "CNY", "MONTHLY", "SYSTEM_SIGNAL",
            "SUPPLIER", LocalDate.now(), null, null,
            List.of(new VmiLineInput(
                idFor(a, 2), idFor(a, 4), vmiOwnerOf(a), new BigDecimal("20"), BigDecimal.ONE,
                new BigDecimal("12.5"), new BigDecimal("100"), new BigDecimal("100"))));
    var id =
        access.run(actorA, "warehouse.inventory.update", () -> service.createAgreement(input, user))
            .agreement().id();
    var line = lineOf(id, idFor(a, 2));
    assertThatThrownBy(() -> sale(id, line, "1", "SO-1", now()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有生效中的协议");
  }

  @Test
  void 同一条销售事实不会重复计入() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    UUID line = lineOf(agreement, idFor(a, 2));
    sale(agreement, line, "10", "SO-DUP", now());
    assertThatThrownBy(() -> sale(agreement, line, "10", "SO-DUP", now()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经记过");
    var detail = access.run(actorA, "warehouse.inventory.read", () -> service.agreement(agreement));
    assertThat(detail.lines().get(0).soldQuantity()).isEqualByComparingTo("10.000000");
  }

  @Test
  void 关闭协议后明细被数据库冻结() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    var id = access.run(actorA, "warehouse.inventory.update", () -> service.close(agreement, null, user)).id();
    var line = lineOf(id, idFor(a, 2));
    // 冻结是数据库闸门（vmi_line_frozen 触发器），所以断言落在 DB 的拒绝上。
    assertDbRejects(
        "frozen",
        () ->
            sql.execute(
                "update vmi_agreement_line set on_hand_quantity = ? where tenant_id = ? and id = ?",
                new BigDecimal("1"), a, line));
  }

  // ================================================================ 结算对账单

  @Test
  void 对账明细金额由数量乘结算价生成且保留四位() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    UUID line = lineOf(agreement, idFor(a, 2));
    sale(agreement, line, "3", "SO-1", now());

    var detail =
        access.run(
            actorA, "warehouse.inventory.update",
            () ->
                service.createSettlement(
                    agreement,
                    new VmiSettlementInput(
                        LocalDate.now().minusDays(2), LocalDate.now().plusDays(1), null),
                    user));
    var settlementLine = detail.lines().get(0);
    // 3 × 12.5 = 37.5，numeric(19,4) 读回必须是 4 位。
    assertThat(settlementLine.amount()).isEqualByComparingTo("37.5000");
    assertThat(settlementLine.amount().scale()).isEqualTo(4);
    assertThat(settlementLine.unitPrice()).isEqualByComparingTo("12.5000");
    assertThat(settlementLine.unitPrice().scale()).isEqualTo(4);
    assertThat(settlementLine.quantity().scale()).isEqualTo(6);
    // 每行都指回触发它的那条售出事实，应付从哪来一眼可追。
    assertThat(settlementLine.saleRef()).isEqualTo("SO-1");
    assertThat(detail.settlement().lineCount()).isEqualTo(1);
  }

  @Test
  void 单据级应付HALF_UP到分而明细保留四位() {
    // 结算价 0.3333 × 数量 3 = 0.9999，到分是 1.00；
    // 这条断言的意义是「表头可付、明细精确」两套 scale 各自成立，不会互相污染。
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER", "20", "2", "0.3333", "100");
    UUID line = lineOf(agreement, idFor(a, 2));
    sale(agreement, line, "3", "SO-R", now());

    var detail =
        access.run(
            actorA, "warehouse.inventory.update",
            () ->
                service.createSettlement(
                    agreement,
                    new VmiSettlementInput(
                        LocalDate.now().minusDays(2), LocalDate.now().plusDays(1), null),
                    user));
    assertThat(detail.lines().get(0).amount()).isEqualByComparingTo("0.9999");
    var total = detail.settlement().totalAmount();
    assertThat(total.scale()).isEqualTo(2);
    assertThat(total).isEqualByComparingTo("1.00");
  }

  @Test
  void 未售出不产生任何应付() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    // 账本里放着 100 件货但一件没卖。
    putOnHand(a, idFor(a, 2), vmiOwnerOf(a), "100");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA, "warehouse.inventory.update",
                    () ->
                        service.createSettlement(
                            agreement,
                            new VmiSettlementInput(
                                LocalDate.now().minusDays(2), LocalDate.now().plusDays(1), null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有已售出但尚未对账");
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.settlements(null, null, 0, 20))
        .total()).isZero();
  }

  @Test
  void 同一条销售事实不能进两张对账单() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    UUID line = lineOf(agreement, idFor(a, 2));
    sale(agreement, line, "10", "SO-1", now());
    var window =
        new VmiSettlementInput(
            LocalDate.now().minusDays(5), LocalDate.now().plusDays(1), null);
    access.run(actorA, "warehouse.inventory.update", () -> service.createSettlement(agreement, window, user));
    // 区间重叠再来一次 → 已进过对账单的售出事实不会被收第二次。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA, "warehouse.inventory.update",
                    () ->
                        service.createSettlement(
                            agreement,
                            new VmiSettlementInput(
                                LocalDate.now().minusDays(3), LocalDate.now().plusDays(2), null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有已售出但尚未对账");
    // 数据库层也不让同一条 sale 进两张单：建第二张单，硬把同一条售出事实塞进去。
    var settlementId =
        access.run(actorA, "warehouse.inventory.read", () -> service.settlements(agreement, null, 0, 20))
            .settlements().get(0).id();
    var lineId =
        access.run(actorA, "warehouse.inventory.read", () -> service.sales(agreement, null, 0, 20))
            .sales().get(0).agreementLineId();
    var saleId =
        access.run(actorA, "warehouse.inventory.read", () -> service.sales(agreement, null, 0, 20))
            .sales().get(0).id();
    // 第二张单必须在**租户事务里**建：FORCE RLS 开着，事务外裸查连行都看不见。
    var secondSettlement = new UUID[1];
    rawAsA(
        () -> {
          sql.execute(
              "insert into vmi_settlement(id,tenant_id,settlement_code,agreement_id,period_start,"
                  + "period_end,currency,status,created_by_user_id) "
                  + "values(?,?,?,?,?::date,?::date,'CNY','OPEN',?)",
              UUID.randomUUID(), a, "VST-SECOND", agreement,
              LocalDate.now().minusDays(90), LocalDate.now().minusDays(80), user);
          secondSettlement[0] =
              sql.fetchOne(
                      "select id from vmi_settlement where tenant_id=? and settlement_code='VST-SECOND'",
                      a)
                  .get("id", UUID.class);
        });
    assertDbRejects(
        "duplicate key",
        () ->
            sql.execute(
                "insert into vmi_settlement_line(id,tenant_id,settlement_id,agreement_line_id,"
                    + "sale_id,quantity,unit_price) values(?,?,?,?,?,?,?)",
                UUID.randomUUID(), a, secondSettlement[0], lineId, saleId,
                new BigDecimal("10"), new BigDecimal("12.5000")));
    assertThat(settlementId).isNotNull();
    assertThat(saleId).isNotNull();
  }

  @Test
  void 已确认对账单明细被数据库冻结() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    UUID line = lineOf(agreement, idFor(a, 2));
    sale(agreement, line, "10", "SO-1", now());
    var settlement =
        access.run(
                actorA, "warehouse.inventory.update",
                () ->
                    service.createSettlement(
                        agreement,
                        new VmiSettlementInput(
                            LocalDate.now().minusDays(2), LocalDate.now().plusDays(1), null),
                        user))
            .settlement();
    var confirmed =
        access.run(
            actorA, "warehouse.inventory.update",
            () -> service.confirmSettlement(settlement.id(), null, user));
    assertThat(confirmed.status()).isEqualTo("CONFIRMED");
    assertThat(confirmed.referenceId()).isNotBlank();

    var detail =
        access.run(
            actorA, "warehouse.inventory.read", () -> service.settlementDetail(settlement.id()));
    var target = detail.lines().get(0);
    assertDbRejects(
        "frozen",
        () ->
            sql.execute(
                "update vmi_settlement_line set quantity=? where tenant_id=? and id=?",
                new BigDecimal("999"), a, target.id()));
  }

  @Test
  void 结算区间按结算周期真算() {
    UUID weekly = activeAgreement("WEEKLY", "SYSTEM_SIGNAL", "SUPPLIER");
    var w = access.run(actorA, "warehouse.inventory.read", () -> service.settlementWindow(weekly));
    assertThat(w.periodStart().getDayOfWeek().getValue()).isEqualTo(1);
    assertThat(w.periodEnd()).isEqualTo(w.periodStart().plusDays(6));

    UUID monthly = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    var m = access.run(actorA, "warehouse.inventory.read", () -> service.settlementWindow(monthly));
    assertThat(m.periodStart().getDayOfMonth()).isEqualTo(1);
    assertThat(m.periodEnd()).isEqualTo(m.periodStart().withDayOfMonth(m.periodStart().lengthOfMonth()));
  }

  @Test
  void 售出时刻写回不被时区偏移() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    UUID line = lineOf(agreement, idFor(a, 2));
    // 东八区的一个时刻：若用 Timestamp.valueOf(LocalDateTime) 绑定会静默偏 −28800s。
    var at = OffsetDateTime.of(2026, 10, 7, 15, 30, 0, 0, ZoneOffset.ofHours(8));
    var written = sale(agreement, line, "1", "SO-TZ", at);
    // 断言写回的值，而不是断言「没报错」。
    var readBack =
        access.run(actorA, "warehouse.inventory.read", () -> service.sales(agreement, null, 0, 10))
            .sales().stream()
            .filter(s -> s.id().equals(written.id()))
            .findFirst()
            .orElseThrow();
    assertThat(readBack.occurredAt().toInstant()).isEqualTo(at.toInstant());
    assertThat(readBack.occurredAt().getHour()).isEqualTo(15);
  }

  @Test
  void 观测时刻写回不被时区偏移() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    var at = OffsetDateTime.of(2026, 10, 7, 15, 30, 0, 0, ZoneOffset.ofHours(8));
    var written =
        access.run(
            actorA, "warehouse.inventory.update",
            () ->
                service.recordSnapshot(
                    agreement,
                    new VmiSnapshotInput(
                        lineOf(agreement, idFor(a, 2)), "VENDOR_FEED", "SUPPLIER-PORTAL", at,
                        new BigDecimal("77"), null, null),
                    user));
    assertThat(written.observedAt().toInstant()).isEqualTo(at.toInstant());
    assertThat(written.observedAt().getHour()).isEqualTo(15);
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    UUID line = lineOf(agreement, idFor(a, 2));
    sale(agreement, line, "10", "SO-1", now());

    // B 看不到 A 的任何东西：连详情都是 404（比返回空集更强 —— 不泄露存在性）。
    assertThatThrownBy(() -> access.run(actorB, "warehouse.inventory.read", () -> service.agreement(agreement)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    assertThatThrownBy(() -> access.run(actorB, "warehouse.inventory.read", () -> service.waterLevelMonitor(agreement)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    assertThatThrownBy(() -> access.run(actorB, "warehouse.inventory.read", () -> service.sales(agreement, null, 0, 10)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    assertThatThrownBy(() -> access.run(actorB, "warehouse.inventory.read", () -> service.auditTrail(agreement, null)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    assertThat(access.run(actorB, "warehouse.inventory.read", () -> service.agreements(null, null, null, 0, 20))
        .total()).isZero();
    // B 不能往 A 的协议行上写销售。
    assertThatThrownBy(() -> saleFor(actorB, agreement, line, "1", "SO-X"))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    // B 不能出补货信号，也不能给 A 的单据出对账单。
    assertThatThrownBy(
            () ->
                access.run(
                    actorB, "warehouse.inventory.update",
                    () -> service.generateSignals(agreement, new VmiSignalInput(null, null), user)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    assertThatThrownBy(
            () ->
                access.run(
                    actorB, "warehouse.inventory.update",
                    () ->
                        service.createSettlement(
                            agreement,
                            new VmiSettlementInput(
                                LocalDate.now().minusDays(2), LocalDate.now().plusDays(1), null),
                            user)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
    // B 自己的协议与 A 的互不可见。
    var bInput =
        new VmiAgreementInput(
            supplier, "SUP-B", "B 的供应商", idFor(b, 1), "CNY", "MONTHLY", "SYSTEM_SIGNAL",
            "SUPPLIER", LocalDate.now(), null, null,
            List.of(new VmiLineInput(
                idFor(b, 2), idFor(b, 4), vmiOwnerOf(b), new BigDecimal("5"), BigDecimal.ONE,
                new BigDecimal("2"), new BigDecimal("5"), new BigDecimal("5"))));
    var bAgreement =
        access.run(actorB, "warehouse.inventory.update", () -> service.createAgreement(bInput, user))
            .agreement().id();
    assertThat(access.run(actorB, "warehouse.inventory.read", () -> service.agreements(null, null, null, 0, 20))
        .agreements().stream().map(VmiAgreement::id)).containsExactly(bAgreement);
  }

  VmiSale saleFor(AccessService.Access actor, UUID agreementId, UUID lineId, String qty, String ref) {
    return access.run(
        actor, "warehouse.inventory.update",
        () ->
            service.recordSale(
                agreementId, lineId,
                new VmiSaleInput(new BigDecimal(qty), ref, null, null, now(), null),
                user));
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    UUID line = lineOf(agreement, idFor(a, 2));
    sale(agreement, line, "10", "SO-1", now());

    // 身份是 B，伪造 X-Tenant-Id 指向 A：B 看到的仍然是 B 的（空）。
    http.perform(get("/warehouse/inventory/vmi/agreements")
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    http.perform(get("/warehouse/inventory/vmi/agreements/{id}", agreement)
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/warehouse/inventory/vmi/agreements/{id}/water-levels", agreement)
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 伪造头也不能让 B 给 A 的单出补货信号（出信号 = 让供应商备货）。
    http.perform(post("/warehouse/inventory/vmi/agreements/{id}/replenishment-signals", agreement)
            .header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a.toString())
            .contentType("application/json").content("{}"))
        .andExpect(status().isNotFound());
    // A 的数据未被这串伪造请求改动。
    assertThat(access.run(actorA, "warehouse.inventory.read", () -> service.signals(agreement, null, null, 0, 20))
        .total()).isZero();
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    UUID agreement = activeAgreement("MONTHLY", "SYSTEM_SIGNAL", "SUPPLIER");
    sale(agreement, lineOf(agreement, idFor(a, 2)), "10", "SO-1", now());
    var bInput =
        new VmiAgreementInput(
            supplier, "SUP-B", "B 的供应商", idFor(b, 1), "CNY", "MONTHLY", "SYSTEM_SIGNAL",
            "SUPPLIER", LocalDate.now(), null, null,
            List.of(new VmiLineInput(
                idFor(b, 2), idFor(b, 4), vmiOwnerOf(b), new BigDecimal("5"), BigDecimal.ONE,
                new BigDecimal("2"), new BigDecimal("5"), new BigDecimal("5"))));
    access.run(actorB, "warehouse.inventory.update", () -> service.createAgreement(bInput, user));

    // 交替复用同一条物理连接（池容量=1）：A → B → A → B
    for (int i = 0; i < 3; i++) {
      var a1 = access.run(actorA, "warehouse.inventory.read", () -> service.agreement(agreement));
      assertThat(a1.lines().get(0).soldQuantity()).isEqualByComparingTo("10.000000");
      var b1 = access.run(actorB, "warehouse.inventory.read", () -> service.agreements(null, null, null, 0, 20));
      assertThat(b1.total()).isEqualTo(1);
      var a2 = access.run(actorA, "warehouse.inventory.read", () -> service.agreements(null, null, null, 0, 20));
      assertThat(a2.total()).isEqualTo(1);
    }
    // 八张表逐张确认 FORCE RLS
    for (var table : List.of(
        "vmi_agreement", "vmi_agreement_line", "vmi_stock_snapshot", "vmi_sale",
        "vmi_replenishment_signal", "vmi_settlement", "vmi_settlement_line", "vmi_audit")) {
      assertThat(sql.fetchOne("select relforcerowsecurity from pg_class where relname = ?", table)
          .get("relforcerowsecurity", Boolean.class)).isTrue();
      assertThat(sql.fetchOne("select relrowsecurity from pg_class where relname = ?", table)
          .get("relrowsecurity", Boolean.class)).isTrue();
    }
  }

  // ================================================================ 权限

  @Test
  void 只读身份不能写() throws Exception {
    var readOnly = actor(user, a, List.of(grant("ALL", List.of("warehouse.inventory.read"))));
    actors.put("Bearer vmi-read-only", readOnly);
    var cap = access.run(readOnly, "warehouse.inventory.read", () -> service.capabilities());
    assertThat(cap.settlementCycles()).hasSize(4);
    http.perform(post("/warehouse/inventory/vmi/agreements")
            .header("Authorization", "Bearer vmi-read-only")
            .contentType("application/json")
            .content("{}"))
        .andExpect(status().isForbidden());
  }
}