package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.*;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.1.2.4 单位与换算：单位字典 CRUD、换算版本区间不重叠、按生效日期取版本、
 * 基础数量换算、**单据冻结快照不被后续改比例影响**，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnitConversionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("spu", "sku", "attribute", "research", "compliance", "assets")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "catalog." + p + "." + op))
          .toList();

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
        s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
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
      // 只有 sku 读权限：所有写端点必须 403。
      actors.put(
          "Bearer reader",
          actor(
              user,
              a,
              null,
              List.of(
                  grant(
                      "ALL",
                      List.of("catalog.spu.read", "catalog.sku.read", "catalog.attribute.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
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
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired CatalogService catalog;
  @Autowired UnitConversionService units;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setup() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  <T> T call(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.sku.read", work);
  }

  void run(AccessService.Access who, String permission, Runnable work) {
    access.run(who, permission, () -> {
      work.run();
      return null;
    });
  }

  // ------------------------------------------------------------ 夹具

  record Fixture(UUID spuId, UUID skuId, String baseUnit) {}

  /**
   * 建 SPU + SKU，基础单位可指定。
   *
   * <p>基础单位会**先登记进单位字典**：量纲校验要求两侧都已登记，基础单位没进字典时
   * 建换算会被显式拒绝（这正是 dimensionMismatchIsRejectedWithUnregisteredBaseUnit 覆盖的行为）。
   *
   * <p>基础单位同时决定量纲：baseUnit=件（COUNT）时千克不可换算；
   * baseUnit=千克（WEIGHT）时同量纲单位可换算。
   */
  Fixture fixture(String prefix, String baseUnit) {
    ensureUnit(baseUnit, "COUNT");
    return build(prefix, baseUnit);
  }

  /** 基础单位**不**进字典的 SKU：专供"未登记基础单位"的治理缺口用例。 */
  Fixture fixtureWithUnregisteredBase(String prefix, String baseUnit) {
    return build(prefix, baseUnit);
  }

  private Fixture build(String prefix, String baseUnit) {
    UUID spu =
        call(
            actor,
            "catalog.spu.create",
            () ->
                catalog.saveSpu(
                    actor,
                    null,
                    new SpuInput(
                        prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                        "单位换算测试商品",
                        "描述",
                        "品牌",
                        "3C 配件",
                        "DRAFT",
                        0)));
    long version = tx(actor, () -> catalog.detail(spu).product().version());
    UUID sku =
        call(
            actor,
            "catalog.sku.create",
            () ->
                catalog.saveSku(
                    actor,
                    spu,
                    null,
                    new SkuInput(
                        prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                        "单位换算 SKU",
                        "",
                        baseUnit,
                        "ACTIVE",
                        List.of(),
                        version)));
    return new Fixture(spu, sku, baseUnit);
  }

  /** 幂等登记单位：单位编码租户内唯一，多个用例共用「件」时只能建一次。 */
  void ensureUnit(String code, String dimension) {
    boolean exists = tx(actor, () -> sql.fetchOne("select id from unit_of_measure where code=?", code) != null);
    if (!exists) saveUnit(code, code, dimension);
  }

  /** 单位编码在租户内唯一：每个用例用独立编码，避免用例之间互相抢占字典位。 */
  String uniqueCode(String hint) {
    return hint + UUID.randomUUID().toString().substring(0, 6);
  }

  UUID saveUnit(String code, String name, String dimension) {
    return call(actor, "catalog.sku.update", () -> units.saveUnit(actor, null, unitInput(code, name, dimension, 0, true)))
        .id();
  }

  UnitOfMeasureInput unitInput(
      String code, String name, String dimension, int scale, boolean enabled) {
    return new UnitOfMeasureInput(
        code, name, dimension, scale, true, true, true, enabled, 0);
  }

  UnitOfMeasureInput unitInput(
      String code, String name, String dimension, int scale, boolean enabled, long version) {
    return new UnitOfMeasureInput(
        code, name, dimension, scale, true, true, true, enabled, version);
  }

  // ------------------------------------------------------------ 单位字典

  @Test
  void unitDictionaryIsCreatedListedAndUpdatedWithOptimisticLock() {
    String code = uniqueCode("BOX");
    var saved =
        call(
            actor,
            "catalog.sku.update",
            () ->
                units.saveUnit(
                    actor, null, unitInput(code, "箱", "PACKAGE", 0, true)));
    assertThat(saved.version()).isZero();

    var listed = tx(actor, units::units).stream().filter(u -> u.id().equals(saved.id())).findFirst().orElseThrow();
    assertThat(listed.code()).isEqualTo(code);
    assertThat(listed.name()).isEqualTo("箱");
    assertThat(listed.dimensionLabel()).isEqualTo("包装");
    assertThat(listed.enabled()).isTrue();

    // 更新走乐观锁
    var updated =
        call(
            actor,
            "catalog.sku.update",
            () -> units.saveUnit(actor, saved.id(), unitInput(code, "外箱", "PACKAGE", 2, true, 0)));
    assertThat(updated.version()).isEqualTo(1);
    assertThat(tx(actor, units::units).stream().filter(u -> u.id().equals(saved.id())).findFirst().orElseThrow().name())
        .isEqualTo("外箱");

    // 旧 version → 409
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.update",
                    () -> units.saveUnit(actor, saved.id(), unitInput(code, "再改", "PACKAGE", 2, true, 0))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
  }

  @Test
  void unitCodeIsUniquePerTenantAndAcceptsChinese() {
    String code = uniqueCode("件");
    saveUnit(code, "件", "COUNT");
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.update", () -> units.saveUnit(actor, null, unitInput(code, "件", "COUNT", 0, true))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已存在");
    // 中文单位码合法：真实数据里就是 '件' / '箱'。
    assertThat(UnitRules.codeProblem("件")).isNull();
  }

  @Test
  void unitCodeAndDimensionAreImmutableAfterCreation() {
    String code = uniqueCode("KG");
    UUID id = saveUnit(code, "千克", "WEIGHT");
    // 换算行与快照按编码引用单位，改码会让它们指向不存在的定义。
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.update", () -> units.saveUnit(actor, id, unitInput(code + "X", "千克", "WEIGHT", 0, true, 0))))
        .hasMessageContaining("单位编码建后不可修改");
    // 改量纲会让既有换算在守卫触发器下次写入时突然报量纲不匹配。
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.update", () -> units.saveUnit(actor, id, unitInput(code, "千克", "VOLUME", 0, true, 0))))
        .hasMessageContaining("量纲建后不可修改");
  }

  @Test
  void unitRejectsUnknownDimensionAndIllegalCode() {
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.update", () -> units.saveUnit(actor, null, unitInput(uniqueCode("X"), "怪", "MASS", 0, true))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("量纲只能是");
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.update", () -> units.saveUnit(actor, null, unitInput("件 件", "空格", "COUNT", 0, true))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只能使用");
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.update", () -> units.saveUnit(actor, null, unitInput(uniqueCode("Y"), "精度", "COUNT", 9, true))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("0~6");
  }

  @Test
  void unitInUseCannotBeDeletedOnlyDisabled() {
    var f = fixture("DEL", "件");
    String box = uniqueCode("BOX");
    // 量纲要与基础单位「件」（COUNT）一致，否则会先被量纲校验拦下，测不到删除保护。
    saveUnit(box, "箱", "COUNT");
    call(
        actor,
        "catalog.sku.update",
        () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("12"), "2024-01-01", null)));
    var row = tx(actor, () -> sql.fetchOne("select id,version from unit_of_measure where code=?", box));
    // 被换算引用时不能删：应提示停用。
    assertThatThrownBy(() -> run(actor, "catalog.sku.update", () -> units.deleteUnit(actor, row.get("id", UUID.class), row.get("version", Long.class))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能删除")
        .hasMessageContaining("停用");
  }

  // ------------------------------------------------------------ 换算版本

  @Test
  void conversionVersionsMustNotOverlapAndCurrentVersionIsResolvedByDate() {
    var f = fixture("VER", "件");
    String box = uniqueCode("BOX");
    saveUnit(box, "箱", "COUNT");
    call(
        actor,
        "catalog.sku.update",
        () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("10"), "2018-01-01", "2023-12-31")));
    call(
        actor,
        "catalog.sku.update",
        () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("12"), "2024-01-01", null)));

    // 2023 走 10，2024 走 12：同一时刻只有一个版本生效。
    var in2023 = tx(actor, () -> units.convert(new UnitConvertInput(f.skuId(), new BigDecimal("2"), box, "PURCHASE", "2023-06-01")));
    assertThat(in2023.quantityPerBase()).isEqualByComparingTo("10");
    assertThat(in2023.baseQuantity()).isEqualByComparingTo("20.000000");
    var in2024 = tx(actor, () -> units.convert(new UnitConvertInput(f.skuId(), new BigDecimal("2"), box, "PURCHASE", "2024-06-01")));
    assertThat(in2024.quantityPerBase()).isEqualByComparingTo("12");
    assertThat(in2024.baseQuantity()).isEqualByComparingTo("24.000000");

    // 区间重叠 → 409 并列出冲突区间
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.update", () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("15"), "2024-06-01", null))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409))
        .hasMessageContaining("重叠");
  }

  @Test
  void conversionWithoutEffectiveVersionOnThatDateFailsInsteadOfFallingBack() {
    var f = fixture("NOBACK", "件");
    String box = uniqueCode("BOX");
    saveUnit(box, "箱", "COUNT");
    call(
        actor,
        "catalog.sku.update",
        () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("10"), "2024-01-01", "2024-12-31")));
    // 2023 无生效版本：必须报错，不回退到"最近的一版"——
    // 静默回退会让一张按过期比例生成的采购单看起来完全正常。
    assertThatThrownBy(
            () -> tx(actor, () -> units.convert(new UnitConvertInput(f.skuId(), new BigDecimal("1"), box, "PURCHASE", "2023-06-01"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有生效的");
  }

  @Test
  void dimensionMismatchIsRejected() {
    // 基础单位「件」是 COUNT，「千克」是 WEIGHT：不能换算。
    var f = fixture("DIM", "件");
    String kg = uniqueCode("KG");
    saveUnit(kg, "千克", "WEIGHT");
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.update", () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(kg, "PURCHASE", new BigDecimal("2"), "2024-01-01", null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("量纲不匹配");
  }

  @Test
  void conversionIsRejectedWhenBaseUnitIsNotRegistered() {
    // sku.base_unit 是 V1 的自由文本列，3.1.2.2 能写入字典里没有的字符串。
    // 这时无法判量纲，必须显式拒绝而不是放行——放行等于让「件 → 千克」从后门进来。
    var f = fixtureWithUnregisteredBase("NOBASE", uniqueCode("未登记单位"));
    String box = uniqueCode("BOX");
    saveUnit(box, "箱", "COUNT");
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.update", () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("2"), "2024-01-01", null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("尚未登记到单位字典");
  }

  @Test
  void conversionRequiresRegisteredEnabledUnit() {
    var f = fixture("REG", "件");
    String ghost = uniqueCode("GHOST");
    // 未登记的单位不能建换算：量纲无从校验。
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.update", () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(ghost, "PURCHASE", new BigDecimal("2"), "2024-01-01", null))))
        .hasMessageContaining("尚未登记");
    // 停用单位也不能新建换算。
    String box = uniqueCode("BOX");
    UUID id = saveUnit(box, "箱", "COUNT");
    call(actor, "catalog.sku.update", () -> units.saveUnit(actor, id, unitInput(box, "箱", "COUNT", 0, false, 0)));
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.update", () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("2"), "2024-01-01", null))))
        .hasMessageContaining("已停用");
  }

  @Test
  void baseUnitItselfCannotHaveAConversionRow() {
    var f = fixture("BASE", "件");
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.update", () -> units.addConversion(actor, f.skuId(), new UnitConversionInput("件", "PURCHASE", new BigDecimal("1"), "2024-01-01", null))))
        .hasMessageContaining("不需要换算行");
    // BASE 角色也不该占换算行。
    assertThatThrownBy(
            () -> call(actor, "catalog.sku.update", () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(uniqueCode("X"), "BASE", new BigDecimal("1"), "2024-01-01", null))))
        .hasMessageContaining("换算角色");
  }

  @Test
  void baseUnitConvertUsesRatioOneWithoutConversionRow() {
    var f = fixture("R1", "件");
    var result = tx(actor, () -> units.convert(new UnitConvertInput(f.skuId(), new BigDecimal("7"), "件", null, null)));
    assertThat(result.role()).isEqualTo("BASE");
    assertThat(result.quantityPerBase()).isEqualByComparingTo("1");
    assertThat(result.baseQuantity()).isEqualByComparingTo("7");
    assertThat(result.conversionId()).isNull();
  }

  // ------------------------------------------------------------ 单据冻结快照

  @Test
  void frozenSnapshotSurvivesLaterRatioChange() {
    var f = fixture("FREEZE", "件");
    String box = uniqueCode("BOX");
    saveUnit(box, "箱", "COUNT");
    // 旧比例 10（2024 全年）
    call(
        actor,
        "catalog.sku.update",
        () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("10"), "2024-01-01", "2025-12-31")));
    // 生成一张业务时间为 2024-06-01 的采购单，冻结当时比例 10。
    // 冻结取的是**单据业务时间**命中的版本，不是「今天」的那一版。
    var snapshot =
        call(
            actor,
            "catalog.sku.update",
            () ->
                units.freeze(
                    actor,
                    new UnitSnapshotInput(
                        "PURCHASE",
                        "PO-" + UUID.randomUUID().toString().substring(0, 8),
                        "2024-06-01T08:30:00Z",
                        List.of(new UnitSnapshotLineInput(1, f.skuId(), box, "PURCHASE", new BigDecimal("3"))))));
    assertThat(snapshot.createdCount()).isEqualTo(1);
    var line = snapshot.lines().get(0);
    assertThat(line.quantityPerBase()).isEqualByComparingTo("10");
    assertThat(line.baseQuantity()).isEqualByComparingTo("30.000000");
    assertThat(line.effectiveFrom()).isEqualTo("2024-01-01");

    // 改比例：开新版（比例 20，2026 起）
    call(
        actor,
        "catalog.sku.update",
        () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("20"), "2026-01-01", null)));

    // 已冻结的单据不受影响：比例与结果都还在快照行上。
    var reread = tx(actor, () -> units.snapshot(snapshot.documentRef(), "PURCHASE"));
    var frozen = reread.lines().get(0);
    assertThat(frozen.quantityPerBase()).isEqualByComparingTo("10");
    assertThat(frozen.baseQuantity()).isEqualByComparingTo("30.000000");
    // 同一张单据按业务时间重算，命中���是 10 而不是新版的 20。
    var historical = tx(actor, () -> units.convert(new UnitConvertInput(f.skuId(), new BigDecimal("3"), box, "PURCHASE", "2024-06-01")));
    assertThat(historical.quantityPerBase()).isEqualByComparingTo("10");
    // 而 2026 的新单据走新比例。
    var newResult = tx(actor, () -> units.convert(new UnitConvertInput(f.skuId(), new BigDecimal("3"), box, "PURCHASE", "2026-06-01")));
    assertThat(newResult.quantityPerBase()).isEqualByComparingTo("20");
    assertThat(newResult.baseQuantity()).isEqualByComparingTo("60.000000");
  }

  @Test
  void snapshotIsImmutableAtTheDatabaseLevel() throws Exception {
    var f = fixture("IMMUT", "件");
    String box = uniqueCode("BOX");
    saveUnit(box, "箱", "COUNT");
    call(
        actor,
        "catalog.sku.update",
        () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("10"), "2024-01-01", null)));
    var snapshot =
        call(
            actor,
            "catalog.sku.update",
            () ->
                units.freeze(
                    actor,
                    new UnitSnapshotInput(
                        "PURCHASE",
                        "PO-" + UUID.randomUUID().toString().substring(0, 8),
                        "2024-06-01T08:30:00Z",
                        List.of(new UnitSnapshotLineInput(1, f.skuId(), box, "PURCHASE", new BigDecimal("2"))))));
    // 快照不可变由**两层**独立保证，两层都要断言：
    //   1) GRANT：运行时角色 catalog_app 只有 SELECT/INSERT，没有 UPDATE/DELETE ——
    //      权限层先挡下，驱动报 permission denied。
    //   2) 触发器 refuse_snapshot_mutation：即使有人用高权限角色直连（迁移账号、超级用户），
    //      BEFORE UPDATE OR DELETE 仍会拒绝。这才是"直连库也改不动"的兜底。
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        sql.execute(
                            "update sku_unit_conversion_snapshot set quantity_per_base=999 where id=?",
                            snapshot.id())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        sql.execute(
                            "delete from sku_unit_conversion_snapshot where id=?", snapshot.id())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");

    // 用迁移账号（表 owner，绕过 GRANT）直连，触发器仍然拒绝。
    // 不用 try-with-resources：块内断言失败会让隐式 close 抛异常并盖掉真正的失败原因。
    var owner = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    try {
      var statement =
          owner.prepareStatement("update sku_unit_conversion_snapshot set quantity_per_base=999 where id=?");
      statement.setObject(1, snapshot.id());
      assertThatThrownBy(statement::executeUpdate)
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("不可修改");
    } finally {
      owner.close();
    }
    // 比例确实没被改掉。
    var stillTen =
        tx(actor, () -> sql.fetchOne("select quantity_per_base from sku_unit_conversion_snapshot where id=?", snapshot.id())
            .get("quantity_per_base", BigDecimal.class));
    assertThat(stillTen).isEqualByComparingTo("10.000000");
  }

  @Test
  void snapshotFreezeIsIdempotentPerDocumentLine() {
    var f = fixture("IDEM", "件");
    String box = uniqueCode("BOX");
    saveUnit(box, "箱", "COUNT");
    call(
        actor,
        "catalog.sku.update",
        () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("10"), "2024-01-01", null)));
    String ref = "PO-" + UUID.randomUUID().toString().substring(0, 8);
    var input =
        new UnitSnapshotInput(
            "PURCHASE", ref, null, List.of(new UnitSnapshotLineInput(1, f.skuId(), box, "PURCHASE", new BigDecimal("2"))));
    var first = call(actor, "catalog.sku.update", () -> units.freeze(actor, input));
    assertThat(first.createdCount()).isEqualTo(1);
    // 重跑生成：命中已有行，返回原行，不重复计数。
    var second = call(actor, "catalog.sku.update", () -> units.freeze(actor, input));
    assertThat(second.createdCount()).isZero();
    assertThat(second.reusedCount()).isEqualTo(1);
    assertThat(second.lines().get(0).baseQuantity()).isEqualByComparingTo(first.lines().get(0).baseQuantity());
    long count = tx(actor, () -> sql.fetchOne("select count(*) from sku_unit_conversion_snapshot where document_ref=?", ref).get(0, Long.class));
    assertThat(count).isEqualTo(1);
  }

  @Test
  void snapshotRejectsWholeDocumentWhenOneLineCannotConvert() {
    var f = fixture("PARTIAL", "件");
    String box = uniqueCode("BOX");
    saveUnit(box, "箱", "COUNT");
    call(
        actor,
        "catalog.sku.update",
        () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("10"), "2024-01-01", "2024-12-31")));
    String ref = "PO-" + UUID.randomUUID().toString().substring(0, 8);
    String other = uniqueCode("OTHER");
    // 第二行的单位没有任何换算版本 → 整单拒绝，避免半张单据冻结了比例。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.update",
                    () ->
                        units.freeze(
                            actor,
                            new UnitSnapshotInput(
                                "PURCHASE",
                                ref,
                                // 业务时间落在 2024 年内：命中第一行的 10:1 比例，
                                // 而第二行的单位没有任何版本 → 整单拒绝。
                                "2024-06-01T08:30:00Z",
                                List.of(
                                    new UnitSnapshotLineInput(1, f.skuId(), box, "PURCHASE", new BigDecimal("2")),
                                    new UnitSnapshotLineInput(2, f.skuId(), other, "PURCHASE", new BigDecimal("2")))))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有生效的");
    long count = tx(actor, () -> sql.fetchOne("select count(*) from sku_unit_conversion_snapshot where document_ref=?", ref).get(0, Long.class));
    assertThat(count).isZero();
  }

  @Test
  void snapshotBindsBusinessTimeAndSystemTimeSeparately() {
    var f = fixture("TIME", "件");
    String box = uniqueCode("BOX");
    saveUnit(box, "箱", "COUNT");
    call(
        actor,
        "catalog.sku.update",
        () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("10"), "2024-01-01", null)));
    String occurredAt = "2024-06-01T08:30:00Z";
    call(
        actor,
        "catalog.sku.update",
        () ->
            units.freeze(
                actor,
                new UnitSnapshotInput(
                    "PURCHASE",
                    "PO-" + UUID.randomUUID().toString().substring(0, 8),
                    occurredAt,
                    List.of(new UnitSnapshotLineInput(1, f.skuId(), box, "PURCHASE", new BigDecimal("2"))))));
    // 业务时间写回必须精确等于传入的 ISO 串（timestamptz 不被时区偏移改写）。
    var readBack =
        tx(
            actor,
            () ->
                sql.fetchOne(
                        "select occurred_at from sku_unit_conversion_snapshot where document_ref in"
                            + " (select document_ref from sku_unit_conversion_snapshot order by created_at desc limit 1)")
                    .get("occurred_at", java.time.OffsetDateTime.class));
    assertThat(readBack.toInstant().toString()).isEqualTo("2024-06-01T08:30:00Z");
  }

  // ------------------------------------------------------------ 概览与面板

  @Test
  void panelGroupsVersionsAndFlagsUnregisteredBaseUnit() {
    // 用**未登记**的基础单位建 SKU：面板必须把它标成治理缺口，而不是当成正常。
    String orphanBase = uniqueCode("孤儿单位");
    var orphan = fixtureWithUnregisteredBase("PANEL-ORPHAN", orphanBase);
    var orphanPanel = tx(actor, () -> units.panel(orphan.skuId()));
    assertThat(orphanPanel.baseUnit()).isEqualTo(orphanBase);
    assertThat(orphanPanel.baseUnitRegistered()).isFalse();
    assertThat(orphanPanel.baseUnitName()).isNull();

    var f = fixture("PANEL", "件");
    String box = uniqueCode("BOX");
    saveUnit(box, "箱", "COUNT");
    // 两个版本：2018~2023 与 2024~至今
    call(
        actor,
        "catalog.sku.update",
        () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("10"), "2018-01-01", "2023-12-31")));
    call(
        actor,
        "catalog.sku.update",
        () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("12"), "2024-01-01", null)));
    var panel = tx(actor, () -> units.panel(f.skuId()));
    assertThat(panel.baseUnit()).isEqualTo("件");
    assertThat(panel.baseUnitRegistered()).isTrue();
    var group = panel.groups().stream().filter(g -> g.unitCode().equals(box)).findFirst().orElseThrow();
    // 同一单位同一角色的版本聚成一组，而不是平铺成长列表。
    assertThat(group.versions()).hasSize(2);
    // 版本按生效起始日倒序，当前生效的是 2024 版。
    assertThat(group.versions().get(0).effectiveFrom()).isEqualTo("2024-01-01");
    assertThat(group.currentQuantityPerBase()).isEqualByComparingTo("12");
    assertThat(group.registered()).isTrue();
    assertThat(group.roleLabel()).isEqualTo("采购单位");
  }

  @Test
  void overviewCountsUnitsAndFlagsUnregisteredBaseUnits() {
    var f = fixture("OV", "件");
    String box = uniqueCode("BOX");
    saveUnit(box, "箱", "COUNT");
    call(
        actor,
        "catalog.sku.update",
        () -> units.addConversion(actor, f.skuId(), new UnitConversionInput(box, "PURCHASE", new BigDecimal("10"), "2024-01-01", null)));
    // 造一个基础单位未登记的 SKU：治理缺口指标必须把它算进去。
    fixtureWithUnregisteredBase("OV-ORPHAN", uniqueCode("孤儿"));
    var overview = tx(actor, units::overview);
    assertThat(overview.unitCount()).isGreaterThan(0);
    assertThat(overview.enabledUnitCount()).isGreaterThan(0);
    assertThat(overview.conversionCount()).isGreaterThan(0);
    assertThat(overview.unregisteredUnitCount()).isGreaterThan(0);
    assertThat(overview.dimensions()).isNotEmpty();
    assertThat(overview.note()).contains("effective_to");
  }

  // ------------------------------------------------------------ 租户隔离三断言

  @Test
  void unitConversionIsTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    var mine = fixture("ISO", "件");
    String myBox = uniqueCode("BOX");
    saveUnit(myBox, "箱", "COUNT");
    call(
        actor,
        "catalog.sku.update",
        () -> units.addConversion(actor, mine.skuId(), new UnitConversionInput(myBox, "PURCHASE", new BigDecimal("12"), "2024-01-01", null)));
    UUID myUnitId = tx(actor, () -> sql.fetchOne("select id from unit_of_measure where code=?", myBox).get("id", UUID.class));

    // B 租户造自己的数据（它的单位字典与 A 租户完全隔离）
    access.run(
        actorB,
        "catalog.sku.update",
        () -> {
          if (sql.fetchOne("select id from unit_of_measure where code=?", "件") == null)
            sql.execute(
                "insert into unit_of_measure(id,tenant_id,code,name,dimension,scale,allow_purchase,"
                    + "allow_sale,allow_storage,enabled) values(?::uuid,?::uuid,'件','件','COUNT',0,true,true,true,true)",
                UUID.randomUUID(),
                b);
          return null;
        });
    var theirs = new Object[] {null};
    access.run(
        actorB,
        "catalog.sku.create",
        () -> {
          UUID spu = catalog.saveSpu(actorB, null, new SpuInput("ISO-B-" + UUID.randomUUID().toString().substring(0, 8), "B 租户商品", "", "品牌", "家居", "DRAFT", 0));
          long version = catalog.detail(spu).product().version();
          UUID sku = catalog.saveSku(actorB, spu, null, new SkuInput("ISO-B-" + UUID.randomUUID().toString().substring(0, 8), "B SKU", "", "件", "ACTIVE", List.of(), version));
          theirs[0] = sku;
          return null;
        });
    UUID theirSku = (UUID) theirs[0];

    // 1) 两租户互不可见
    // B 看不到 A 的单位字典条目
    assertThat(tx(actorB, units::units).stream().map(UnitOfMeasure::id)).doesNotContain(myUnitId);
    // B 看不到 A 的 SKU 面板
    assertThatThrownBy(() -> tx(actorB, () -> units.panel(mine.skuId())))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    // B 拿 A 的 SKU 去建换算 → 404
    assertThatThrownBy(
            () -> call(actorB, "catalog.sku.update", () -> units.addConversion(actorB, mine.skuId(), new UnitConversionInput(myBox, "SALE", new BigDecimal("3"), "2024-01-01", null))))
        .isInstanceOf(ResponseStatusException.class);
    // B 拿 A 的 SKU 换算 → 404
    assertThatThrownBy(() -> tx(actorB, () -> units.convert(new UnitConvertInput(mine.skuId(), new BigDecimal("1"), myBox, "PURCHASE", "2024-06-01"))))
        .isInstanceOf(ResponseStatusException.class);
    // A 的换算仍在
    assertThat(tx(actor, () -> units.panel(mine.skuId())).groups()).isNotEmpty();

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/unit-conversion/skus/" + mine.skuId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/unit-conversion/skus/" + mine.skuId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());
    // 伪造头也不能借道写：建换算时 SKU 不可见 → 404
    http.perform(
            post("/catalog/unit-conversion/skus/" + mine.skuId() + "/conversions")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content("{\"unitCode\":\"箱\",\"role\":\"SALE\",\"quantityPerBase\":3,\"effectiveFrom\":\"2024-01-01\"}"))
        .andExpect(status().isNotFound());

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> units.panel(mine.skuId()).skuId())).isEqualTo(mine.skuId());
      assertThat(tx(actorB, () -> units.panel(theirSku).skuId())).isEqualTo(theirSku);
      assertThatThrownBy(() -> tx(actorB, () -> units.panel(mine.skuId())))
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  @Test
  void writeEndpointsRequireSkuUpdatePermission() throws Exception {
    String code = uniqueCode("RBAC");
    http.perform(
            post("/catalog/unit-conversion/units")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{\"code\":\"" + code + "\",\"name\":\"只读\",\"dimension\":\"COUNT\",\"scale\":0}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void unitDictionaryTablesAreRlsProtected() {
    // 强制 RLS + tenant_id NOT NULL：与 3.1.2.2 对 sku_unit_conversion 的断言同口径。
    var weak =
        sql.fetch(
            "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                + " where n.nspname='public' and relkind='r'"
                + " and relname in ('unit_of_measure','sku_unit_conversion_snapshot')"
                + " and (not relrowsecurity or not relforcerowsecurity)");
    assertThat(weak).isEmpty();
    for (String table : List.of("unit_of_measure", "sku_unit_conversion_snapshot")) {
      var nullable =
          sql.fetchOne(
              "select is_nullable from information_schema.columns where table_name=? and column_name='tenant_id'",
              table);
      assertThat(nullable.get("is_nullable", String.class)).as(table).isEqualTo("NO");
    }
    // 数量列必须是 numeric(19,6)，不得退化成浮点。
    for (String column : List.of("quantity", "quantity_per_base", "base_quantity")) {
      var type =
          sql.fetchOne(
              "select data_type,numeric_precision,numeric_scale from information_schema.columns"
                  + " where table_name='sku_unit_conversion_snapshot' and column_name=?",
              column);
      assertThat(type.get("data_type", String.class)).as(column).isEqualTo("numeric");
      assertThat(type.get("numeric_precision", Integer.class)).as(column).isEqualTo(19);
      assertThat(type.get("numeric_scale", Integer.class)).as(column).isEqualTo(6);
    }
  }
}
