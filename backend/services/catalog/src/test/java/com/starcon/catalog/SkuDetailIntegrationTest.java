package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
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
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.1.2.2 SKU 详情：分区聚合、属性有效值解析、编辑入口乐观锁、未接入投影的处置，
 * 以及租户隔离三断言。真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SkuDetailIntegrationTest {
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
      // 只有 sku 读权限：媒体分区必须因此不可用，而不是回一个空数组。
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired CatalogService catalog;
  @Autowired SkuDetailService detail;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actor;

  @BeforeEach
  void setup() {
    actor = actors.get("Bearer tenant-a");
  }

  AccessService.Access actor(UUID tenant) {
    return actors.get("Bearer tenant-a").tenantId().equals(tenant)
        ? actor
        : actors.get("Bearer tenant-b");
  }

  <T> T call(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  <T> T tx(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actor(tenant), "catalog.sku.read", work);
  }

  Detail detail(UUID spu) {
    return call(actor, "catalog.spu.read", () -> catalog.detail(spu));
  }

  // ------------------------------------------------------------ 测试夹具

  /**
   * 建一个四种取值模式都覆盖的 SPU + 单个 SKU。
   *
   * <p>属性一律走真实的 attribute_definition / attribute_value / attribute_value_item，
   * 详情页的有效值就是从这三张表解析出来的，不存在任何冗余的"有效值"列。
   */
  record Fixture(
      UUID spuId,
      UUID skuId,
      UUID colorId,
      UUID capacityId,
      UUID materialId,
      UUID warrantyId) {}

  Fixture fixture(String prefix) {
    UUID p =
        call(
            actor,
            "catalog.spu.create",
            () ->
                catalog.saveSpu(
                    actor,
                    null,
                    new SpuInput(prefix, "SKU 详情测试商品", "描述", "品牌", "3C 配件", "DRAFT", 0)));

    // 规格轴：OPTION / SKU_ONLY / 必填单选，满足 V1 的 CHECK 约束。
    UUID color =
        createAttribute(
            p,
            new AttributeInput(
                "COLOR", "颜色", "OPTION", "SKU_ONLY", false, true, true, "", "规格",
                List.of("黑色", "白色"), List.of(), 0));
    // COMMON_LOCKED：只能继承公共值，SKU 侧不允许覆写。
    UUID material =
        createAttribute(
            p,
            new AttributeInput(
                "MATERIAL", "材质", "TEXT", "COMMON_LOCKED", false, false, false, "", "基本参数",
                List.of(), List.of(), 0));
    // COMMON_DEFAULT：公共值为默认，SKU 可覆写也可清空。
    UUID warranty =
        createAttribute(
            p,
            new AttributeInput(
                "WARRANTY", "质保", "TEXT", "COMMON_DEFAULT", false, false, false, "", "基本参数",
                List.of(), List.of(), 0));
    // SKU_ONLY 但非必填、也不是规格轴：SKU 不填就是未赋值，
    // 用来验证"未赋值"不会被误显示成继承。
    UUID capacity =
        createAttribute(
            p,
            new AttributeInput(
                "CAPACITY", "容量", "OPTION", "SKU_ONLY", false, false, false, "GB", "技术参数",
                List.of("128", "256"), List.of(), 0));

    UUID sku =
        call(
            actor,
            "catalog.sku.create",
            () ->
                catalog.saveSku(
                    actor,
                    p,
                    null,
                    new SkuInput(
                        prefix + "-1",
                        "SKU 详情 1",
                        "69" + prefix.hashCode(),
                        "件",
                        "ACTIVE",
                        // 颜色：SKU 赋值；质保：SKU 显式清空；材质：只能继承。
                        List.of(
                            new ValueInput(color, "SET", List.of("黑色")),
                            new ValueInput(warranty, "CLEAR", List.of())),
                        detail(p).product().version())));
    return new Fixture(p, sku, color, capacity, material, warranty);
  }

  /** 建属性并回读它的 ID（saveAttribute 不返回 ID，由调用方回读）。 */
  UUID createAttribute(UUID spu, AttributeInput input) {
    return call(
        actor,
        "catalog.attribute.create",
        () -> {
          // saveAttribute 走乐观锁，version 必须在进事务的那一刻取，因此在这里补而不是在夹具里。
          catalog.saveAttribute(
              actor,
              spu,
              null,
              new AttributeInput(
                  input.code(),
                  input.name(),
                  input.dataType(),
                  input.valueMode(),
                  input.multiple(),
                  input.required(),
                  input.variantAxis(),
                  input.unit(),
                  input.groupName(),
                  input.options(),
                  input.defaultValues(),
                  detail(spu).product().version()));
          return catalog.attributes(spu).stream()
              .filter(x -> x.code().equals(input.code()))
              .findFirst()
              .orElseThrow()
              .id();
        });
  }

  /** 给 COMMON_* 属性写 SPU 公共值，走 CatalogService 的公共值入口而不是裸 SQL。 */
  void commonValue(UUID spu, UUID attribute, String... values) {
    call(
        actor,
        "catalog.attribute.update",
        () -> {
          var current = detail(spu).product().version();
          var all = catalog.attributes(spu);
          var target = all.stream().filter(x -> x.id().equals(attribute)).findFirst().orElseThrow();
          catalog.saveAttribute(
              actor,
              spu,
              attribute,
              new AttributeInput(
                  target.code(),
                  target.name(),
                  target.dataType(),
                  target.valueMode(),
                  false,
                  false,
                  false,
                  "",
                  target.groupName(),
                  List.of(),
                  List.of(values),
                  current));
          return null;
        });
  }

  SkuEffectiveAttribute attributeOf(SkuDetail d, String code) {
    return d.attributes().stream()
        .filter(x -> x.code().equals(code))
        .findFirst()
        .orElseThrow(() -> new AssertionError("详情里没有属性 " + code));
  }

  // ------------------------------------------------------------ 有效值解析

  @Test
  void attributeSectionShowsResolvedEffectiveValuesNotRawStoredValues() {
    var f = fixture("EFFECTIVE");
    commonValue(f.spuId(), f.materialId(), "棉");
    commonValue(f.spuId(), f.warrantyId(), "一年");

    var d = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));

    // 公共固定：只能继承公共值。
    var material = attributeOf(d, "MATERIAL");
    assertThat(material.effectiveValues()).containsExactly("棉");
    assertThat(material.source()).isEqualTo("SPU_COMMON");
    assertThat(material.sourceLabel()).isEqualTo("SPU 公共值");
    assertThat(material.inherited()).isTrue();
    assertThat(material.overrideState()).isEqualTo("INHERIT");
    assertThat(material.valueModeLabel()).isEqualTo("公共固定");

    // SKU 赋值：有效值是 SKU 的值，原始值也是同一个。
    var color = attributeOf(d, "COLOR");
    assertThat(color.effectiveValues()).containsExactly("黑色");
    assertThat(color.source()).isEqualTo("SKU_OVERRIDE");
    assertThat(color.sourceLabel()).isEqualTo("SKU 覆写");
    assertThat(color.variantAxis()).isTrue();
    assertThat(color.valueModeLabel()).isEqualTo("SKU 独立");

    // 关键一格：SKU 显式清空了公共值，有效值必须为空，**不能退回公共值**。
    var warranty = attributeOf(d, "WARRANTY");
    assertThat(warranty.overrideState()).isEqualTo("CLEAR");
    assertThat(warranty.source()).isEqualTo("SKU_CLEARED");
    assertThat(warranty.effectiveValues()).isEmpty();
    assertThat(warranty.rawValues()).isEmpty();
    assertThat(warranty.inherited()).isFalse();
  }

  @Test
  void skuOnlyAttributeWithoutAssignmentIsReportedAsUnsetNotInherited() {
    var f = fixture("UNSET");
    var d = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    // SKU_ONLY 属性没有赋值行就是"未赋值"，不能退回任何公共值。
    var capacity = attributeOf(d, "CAPACITY");
    assertThat(capacity.source()).isEqualTo("UNSET");
    assertThat(capacity.sourceLabel()).isEqualTo("未赋值");
    assertThat(capacity.effectiveValues()).isEmpty();
    assertThat(capacity.inherited()).isFalse();
    assertThat(capacity.overrideState()).isEqualTo("INHERIT");
    // 非必填所以不算待补全，但"没填"这件事仍然要能被统计出来。
    assertThat(capacity.missingRequired()).isFalse();
    assertThat(d.attributeSummary().unset()).isGreaterThanOrEqualTo(1);
    // 选项仍然来自 attribute_option，所以界面还能提示"可以填哪些值"。
    assertThat(capacity.options()).containsExactly("128", "256");
    assertThat(capacity.unit()).isEqualTo("GB");
  }

  @Test
  void requiredAttributeWithNoValueAnywhereIsFlaggedForCompletion() {
    // 必填属性真的可以缺值：saveSku 只对启用中的 SKU 与规格轴强制必填校验，
    // 草稿 SKU 允许先建档后补全 —— 这正是详情页补全清单要抓出来的那一类。
    UUID p =
        call(
            actor,
            "catalog.spu.create",
            () ->
                catalog.saveSpu(
                    actor, null, new SpuInput("COMPLETE", "补全清单商品", "", "品牌", "家居", "DRAFT", 0)));
    UUID required =
        createAttribute(
            p,
            new AttributeInput(
                "MODEL", "型号", "TEXT", "COMMON_DEFAULT", false, true, false, "", "基本参数",
                List.of(), List.of(), 0));
    UUID sku =
        call(
            actor,
            "catalog.sku.create",
            () ->
                catalog.saveSku(
                    actor,
                    p,
                    null,
                    new SkuInput(
                        "COMPLETE-1", "草稿 SKU", "6900000010", "件", "DRAFT", List.of(),
                        detail(p).product().version())));

    var d = call(actor, "catalog.sku.read", () -> detail.detail(actor, sku));
    var model = attributeOf(d, "MODEL");
    assertThat(model.required()).isTrue();
    assertThat(model.source()).isEqualTo("UNSET");
    assertThat(model.effectiveValues()).isEmpty();
    // 必填且没值：必须进补全清单，否则运营永远发现不了这个洞。
    assertThat(model.missingRequired()).isTrue();
    assertThat(d.attributeSummary().missingRequired()).isEqualTo(1);
    assertThat(required).isNotNull();
  }

  @Test
  void requiredAttributeCannotBeLeftUnsetOnAnActiveSku() {
    // 与上一条对照：V1/CatalogService 的不变量是真的在生效，不是靠界面拦的。
    var f = fixture("REQUIRED-ACTIVE");
    UUID p =
        call(
            actor,
            "catalog.spu.create",
            () ->
                catalog.saveSpu(
                    actor, null, new SpuInput("REQ-ACTIVE", "必填校验", "", "品牌", "家居", "DRAFT", 0)));
    UUID color =
        createAttribute(
            p,
            new AttributeInput(
                "COLOR", "颜色", "OPTION", "SKU_ONLY", false, true, true, "", "规格",
                List.of("黑色"), List.of(), 0));
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.create",
                    () ->
                        catalog.saveSku(
                            actor,
                            p,
                            null,
                            new SkuInput(
                                "REQ-ACTIVE-1", "缺规格", "6900000020", "件", "ACTIVE", List.of(),
                                detail(p).product().version()))))
        .hasMessageContaining("请填写必填属性")
        .hasMessageContaining("颜色");
    assertThat(color).isNotNull();
  }

  @Test
  void attributeSummaryCountsEverySourceSeparately() {
    var f = fixture("SUMMARY");
    commonValue(f.spuId(), f.materialId(), "棉");
    commonValue(f.spuId(), f.warrantyId(), "一年");
    var d = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));

    assertThat(d.attributeSummary().total()).isEqualTo(d.attributes().size());
    assertThat(d.attributeSummary().overridden()).isEqualTo(1); // COLOR
    assertThat(d.attributeSummary().cleared()).isEqualTo(1); // WARRANTY
    assertThat(d.attributeSummary().inherited()).isEqualTo(1); // MATERIAL
    assertThat(d.attributeSummary().variantAxes()).isEqualTo(1);
    assertThat(d.attributeSummary().total())
        .isEqualTo(d.attributeSummary().overridden()
            + d.attributeSummary().cleared()
            + d.attributeSummary().inherited()
            + d.attributeSummary().unset());
  }

  @Test
  void specSectionIsParsedFromRealVariantAxes() {
    var f = fixture("SPEC");
    var d = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    assertThat(d.specs()).extracting(SkuSpec::axis).contains("颜色");
    assertThat(d.specs()).extracting(SkuSpec::value).contains("黑色");
    assertThat(d.variantSignature()).isNotEmpty();
  }

  // ------------------------------------------------------------ 编码规格与条码

  @Test
  void identitySectionCarriesCodeSpecsAndBarcode() {
    var f = fixture("IDENT");
    var d = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    assertThat(d.id()).isEqualTo(f.skuId());
    assertThat(d.code()).isEqualTo("IDENT-1");
    assertThat(d.status()).isEqualTo("ACTIVE");
    assertThat(d.baseUnit()).isEqualTo("件");
    assertThat(d.spuCode()).isEqualTo("IDENT");
    assertThat(d.category()).isEqualTo("3C 配件");
    // 条码区必须把 seller_sku 的归属讲清楚：它是平台属性，不是内部编码。
    assertThat(d.barcode().primary()).isNotBlank();
    assertThat(d.barcode().identifierOwner()).isEqualTo("3.1.2.3 编码与条码");
    assertThat(d.barcode().identifierNote()).contains("seller_sku").contains("不是内部编码");
    assertThat(d.createdAt()).isNotBlank();
    assertThat(d.updatedAt()).isNotBlank();
  }

  // ------------------------------------------------------------ 未接入投影

  @Test
  void crossServiceSectionsAreReportedNotFaked() {
    var f = fixture("UNWIRED");
    var d = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));

    assertThat(d.sources())
        .extracting(SkuDetailSource::code)
        .containsExactlyInAnyOrder(
            "INVENTORY_DISTRIBUTION",
            "COST",
            "SALE_PRICE",
            "LISTINGS",
            "DOCUMENT_REFERENCES");
    for (var s : d.sources()) {
      assertThat(s.available()).as(s.code()).isFalse();
      assertThat(s.note()).contains("上游投影未接入");
      assertThat(s.owner()).isNotBlank();
    }
    // 归属口径：库存以 07 为准、售价以 05 为准、成本 06、listing 04。
    assertThat(d.sources()).extracting(SkuDetailSource::owner).contains("07 库存", "05 定价");
  }

  @Test
  void platformListingSectionStatesThatSellerSkuIsNotTheInternalCode() {
    var f = fixture("SELLER");
    var d = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    var listing =
        d.sources().stream()
            .filter(s -> s.code().equals("LISTINGS"))
            .findFirst()
            .orElseThrow();
    assertThat(listing.available()).isFalse();
    assertThat(listing.owner()).isEqualTo("04 刊登");
    assertThat(d.barcode().identifierNote()).contains("04 刊登");
  }

  // ------------------------------------------------------------ 包装与单位换算

  @Test
  void packagingIsOptionalAndVolumeIsOnlyComputedWhenAllThreeSidesExist() {
    var f = fixture("PACK");
    var before = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    // 没维护包装时是真的没有这一行，而不是一行全 null 装作维护过。
    assertThat(before.packaging()).isNull();

    var saved =
        call(
            actor,
            "catalog.sku.update",
            () ->
                detail.update(
                    actor,
                    f.skuId(),
                    input(before.version(), "箱", 200, 150, 100, 250, 300)));
    assertThat(saved.packagingChanged()).isTrue();
    var after = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    assertThat(after.packaging().lengthMm()).isEqualByComparingTo("200");
    assertThat(after.packaging().netWeightG()).isEqualByComparingTo("250");
    assertThat(after.packaging().packagingType()).isEqualTo("箱");
    // 200×150×100 mm = 3000000 mm³ = 3000 cm³
    assertThat(after.packaging().volumeCm3()).isEqualByComparingTo("3000");

    // 只填一个维度就不给体积：缺一维拿 0 顶替会算出错误的体积重。
    var partial =
        call(
            actor,
            "catalog.sku.update",
            () -> detail.update(actor, f.skuId(), input(after.version(), "箱", 200, null, null, null, null)));
    var partialView = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    assertThat(partialView.packaging().lengthMm()).isEqualByComparingTo("200");
    assertThat(partialView.packaging().volumeCm3()).isNull();
    assertThat(partialView.packaging().packagingType()).isEqualTo("箱");

    // 全空 → 删掉这一行。
    var cleared =
        call(
            actor,
            "catalog.sku.update",
            () -> detail.update(actor, f.skuId(), input(partial.version(), "", null, null, null, null, null)));
    assertThat(cleared.packagingChanged()).isTrue();
    var clearedView = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    assertThat(clearedView.packaging()).isNull();
  }

  @Test
  void packagingRejectsGrossWeightBelowNetWeightAndNegativeValues() {
    var f = fixture("PACK-BAD");
    var d = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.update",
                    () ->
                        detail.update(
                            actor, f.skuId(), input(d.version(), "箱", 10, 10, 10, 500, 300))))
        .hasMessageContaining("毛重不能小于净重");
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.update",
                    () ->
                        detail.update(
                            actor, f.skuId(), input(d.version(), "箱", -1, 10, 10, 1, 2))))
        .hasMessageContaining("长不能为负数");
  }

  @Test
  void unitConversionVersionsAreListedAndOnlyTheEffectiveOneIsMarkedCurrent() {
    var f = fixture("UNITS");
    tx(
        a,
        () -> {
          sql.execute(
              "insert into sku_unit_conversion(id,tenant_id,sku_id,unit_code,role,quantity_per_base,effective_from,effective_to)"
                  + " values(?::uuid,?::uuid,?::uuid,'箱','PURCHASE',10,'2018-01-01','2023-12-31')",
              UUID.randomUUID(), a, f.skuId());
          sql.execute(
              "insert into sku_unit_conversion(id,tenant_id,sku_id,unit_code,role,quantity_per_base,effective_from,effective_to)"
                  + " values(?::uuid,?::uuid,?::uuid,'箱','PURCHASE',12,'2024-01-01',null)",
              UUID.randomUUID(), a, f.skuId());
          return null;
        });
    var d = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    assertThat(d.unitConversions()).hasSize(2);
    var current = d.unitConversions().stream().filter(SkuUnitConversion::current).toList();
    assertThat(current).hasSize(1);
    assertThat(current.getFirst().quantityPerBase()).isEqualByComparingTo("12");
    assertThat(current.getFirst().roleLabel()).isEqualTo("采购单位");
    // 历史版本仍然可见：单据冻结的是当时的比例，查不到旧版就没法解释历史单据。
    assertThat(d.unitConversions().stream().filter(c -> !c.current())).hasSize(1);
  }

  @Test
  void overlappingUnitConversionWindowsAreRejectedByTheDatabase() {
    var f = fixture("UNITS-OVERLAP");
    tx(
        a,
        () -> {
          sql.execute(
              "insert into sku_unit_conversion(id,tenant_id,sku_id,unit_code,role,quantity_per_base,effective_from,effective_to)"
                  + " values(?::uuid,?::uuid,?::uuid,'箱','PURCHASE',12,'2024-01-01',null)",
              UUID.randomUUID(), a, f.skuId());
          return null;
        });
    assertThatThrownBy(
            () ->
                tx(
                    a,
                    () -> {
                      sql.execute(
                          "insert into sku_unit_conversion(id,tenant_id,sku_id,unit_code,role,quantity_per_base,effective_from,effective_to)"
                              + " values(?::uuid,?::uuid,?::uuid,'箱','PURCHASE',10,'2023-06-01',null)",
                          UUID.randomUUID(), a, f.skuId());
                      return null;
                    }))
        .hasMessageContaining("生效区间与已有版本重叠");
  }

  // ------------------------------------------------------------ 编辑入口

  @Test
  void editEntryUpdatesProfileAndBumpsVersion() {
    var f = fixture("EDIT");
    var before = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    var saved =
        call(
            actor,
            "catalog.sku.update",
            () ->
                detail.update(
                    actor,
                    f.skuId(),
                    new SkuDetailInput(
                        "改名后的 SKU", "6900000001", "箱", "", null, null, null, null, null, before.version())));
    assertThat(saved.version()).isEqualTo(before.version() + 1);
    var after = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    assertThat(after.name()).isEqualTo("改名后的 SKU");
    assertThat(after.barcode().primary()).isEqualTo("6900000001");
    assertThat(after.baseUnit()).isEqualTo("箱");
    assertThat(after.version()).isEqualTo(before.version() + 1);
    // 下游事件已发布（04 刊登可能要同步 listing 文案）。
    assertThat(
            tx(a, () -> sql.fetch("select * from outbox_event where aggregate_id=? and event_type='SkuProfileSaved'", f.skuId())))
        .isNotEmpty();
  }

  @Test
  void staleVersionIsRejectedWithConflictInsteadOfOverwriting() {
    var f = fixture("CONFLICT");
    var before = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    call(
        actor,
        "catalog.sku.update",
        () -> detail.update(actor, f.skuId(), input(before.version(), "箱", 1, 1, 1, 1, 1)));
    // 第二次用同一个 version 提交：必须 409，不能把别人的修改覆盖掉。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.update",
                    () ->
                        detail.update(
                            actor,
                            f.skuId(),
                            new SkuDetailInput(
                                "并发写入", "6900000002", "件", "", null, null, null, null, null, before.version()))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(
                        ((org.springframework.web.server.ResponseStatusException) e)
                            .getStatusCode()
                            .value())
                    .isEqualTo(409));
    var after = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    assertThat(after.name()).isNotEqualTo("并发写入");
  }

  SkuDetailInput input(
      long version,
      String type,
      Number l,
      Number w,
      Number h,
      Number net,
      Number gross) {
    return new SkuDetailInput(
        "SKU 详情测试商品-1",
        "69" + Math.abs(type.hashCode() % 100000000),
        "件",
        type,
        decimal(l),
        decimal(w),
        decimal(h),
        decimal(net),
        decimal(gross),
        version);
  }

  /** 数量列是 NUMERIC（禁浮点），所以连测试夹具也一律走 BigDecimal。 */
  private static BigDecimal decimal(Number value) {
    if (value == null) return null;
    if (value instanceof BigDecimal d) return d;
    return BigDecimal.valueOf(value.longValue());
  }

  // ------------------------------------------------------------ 媒体区

  @Test
  void mediaSectionReadsOnlyExistingColumnsAndReportsTheTableDefect() {
    var f = fixture("MEDIA");
    call(
        actor,
        "catalog.assets.create",
        () -> {
          catalog.saveRecord(
              actor,
              "assets",
              null,
              new RecordInput(f.spuId(), "主图", "产品白底图", "https://cdn.example.com/a.png", "DRAFT", "zh-CN", "US", null, 0));
          return null;
        });
    var d = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    assertThat(d.mediaAvailable()).isTrue();
    assertThat(d.media()).extracting(SkuAsset::title).contains("主图");
    // 缺陷必须如实返回，不能靠编一个缩略图 URL 蒙混过去。
    assertThat(d.mediaLimitation()).contains("缺少文件键").contains("没有 sku_id");
    assertThat(d.mediaLimitation()).contains("3.4.1.1");
  }

  @Test
  void mediaSectionIsUnavailableWithoutAssetPermissionNotAnEmptyList() {
    var f = fixture("MEDIA-PERM");
    call(
        actor,
        "catalog.assets.create",
        () -> {
          catalog.saveRecord(
              actor,
              "assets",
              null,
              new RecordInput(f.spuId(), "细节图", "", "https://cdn.example.com/b.png", "DRAFT", "zh-CN", "US", null, 0));
          return null;
        });
    var reader = actors.get("Bearer reader");
    var d = call(reader, "catalog.sku.read", () -> detail.detail(reader, f.skuId()));
    // 没有 assets 权限：整区不可用，且明确说明是权限问题。
    // 回空数组会被读成"这个 SKU 没有任何素材"。
    assertThat(d.mediaAvailable()).isFalse();
    assertThat(d.media()).isEmpty();
    assertThat(d.mediaLimitation()).contains("catalog.assets.read");
  }

  // ------------------------------------------------------------ 多租户三断言

  @Test
  void skuDetailIsTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    var mine = fixture("ISO-MINE");
    var theirs =
        call(
            actor(b),
            "catalog.sku.create",
            () ->
                catalog.saveSpu(
                    actor(b),
                    null,
                    new SpuInput(
                        "ISO-THEIRS-" + UUID.randomUUID(), "B 租户商品", "", "品牌", "家居", "DRAFT", 0)));

    // 1) 两租户互不可见
    var mineView = tx(a, () -> detail.detail(actor(a), mine.skuId()));
    assertThat(mineView.code()).isEqualTo("ISO-MINE-1");
    // 拿别人的 SKU ID 去查：404「记录不存在或没有访问权限」，不泄露存在性。
    assertThatThrownBy(() -> tx(b, () -> detail.detail(actor(b), mine.skuId())))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((org.springframework.web.server.ResponseStatusException) e).getStatusCode().value())
                    .isEqualTo(404));
    // 拿自己的 ID 去查对方租户的 SPU，同样不可见。
    assertThatThrownBy(() -> tx(b, () -> detail.detail(actor(b), theirs)))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    // 越权写入：SKU 版本不变。
    long beforeVersion = tx(a, () -> version(mine.skuId()));
    assertThatThrownBy(
            () ->
                tx(
                    b,
                    () ->
                        call(
                            actor(b),
                            "catalog.sku.update",
                            () ->
                                detail.update(
                                    actor(b),
                                    mine.skuId(),
                                    new SkuDetailInput(
                                        "越权改名", "1", "件", "", null, null, null, null, null, 0)))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    assertThat(tx(a, () -> version(mine.skuId()))).isEqualTo(beforeVersion);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/skus/" + mine.skuId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/skus/" + mine.skuId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("ISO-MINE-1"));
    // 伪造头也不能借道写接口。
    http.perform(
            put("/catalog/skus/" + mine.skuId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content(
                    "{\"name\":\"伪造\",\"barcode\":\"1\",\"baseUnit\":\"件\",\"version\":0}"))
        .andExpect(status().isNotFound());
    assertThat(tx(a, () -> version(mine.skuId()))).isEqualTo(beforeVersion);

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(a, () -> detail.detail(actor(a), mine.skuId())).code()).isEqualTo("ISO-MINE-1");
      assertThatThrownBy(() -> tx(b, () -> detail.detail(actor(b), mine.skuId())))
          .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
  }

  long version(UUID sku) {
    return sql.fetchOne("select version from sku where id=?", sku).get("version", Long.class);
  }

  @Test
  void everyNewTableHasForcedRlsAndTenantNotNull() {
    var weak =
        sql.fetch(
            "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                + " where n.nspname='public' and relkind='r'"
                + " and relname in ('sku_package','sku_unit_conversion')"
                + " and (not relrowsecurity or not relforcerowsecurity)");
    assertThat(weak).isEmpty();
    var role = sql.fetchOne("select rolsuper,rolbypassrls from pg_roles where rolname=current_user");
    assertThat(role.get("rolsuper", Boolean.class)).isFalse();
    assertThat(role.get("rolbypassrls", Boolean.class)).isFalse();
    for (String table : List.of("sku_package", "sku_unit_conversion")) {
      var column =
          sql.fetchOne(
              "select is_nullable from information_schema.columns where table_name=? and column_name='tenant_id'",
              table);
      assertThat(column.get("is_nullable", String.class)).as(table).isEqualTo("NO");
    }
    // 数量与金额列必须是 NUMERIC，绝不能是浮点。
    for (String column : List.of("length_mm", "net_weight_g", "gross_weight_g", "quantity_per_base")) {
      var type =
          sql.fetchOne(
              "select data_type,numeric_precision,numeric_scale from information_schema.columns"
                  + " where table_name in ('sku_package','sku_unit_conversion') and column_name=?",
              column);
      assertThat(type.get("data_type", String.class)).as(column).isEqualTo("numeric");
      assertThat(type.get("numeric_precision", Integer.class)).isEqualTo(19);
      assertThat(type.get("numeric_scale", Integer.class)).isEqualTo(6);
    }
  }

  // ------------------------------------------------------------ HTTP 契约

  @Test
  void httpEndpointsExposeDetailAndEditWithProperPermission() throws Exception {
    var f = fixture("HTTP");
    commonValue(f.spuId(), f.materialId(), "棉");
    commonValue(f.spuId(), f.warrantyId(), "一年");

    var response =
        http.perform(
                get("/catalog/skus/" + f.skuId()).header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("HTTP-1"))
            .andExpect(jsonPath("$.version").exists())
            .andExpect(jsonPath("$.specs[0].value").value("黑色"))
            .andExpect(jsonPath("$.attributes").isArray())
            // 未接入的分区逐个标 available=false
            .andExpect(jsonPath("$.sources[0].available").value(false))
            // 媒体区带缺陷说明
            .andExpect(jsonPath("$.mediaLimitation").exists())
            .andReturn();
    long version =
        ((Number) com.jayway.jsonpath.JsonPath.read(
                response.getResponse().getContentAsString(), "$.version"))
            .longValue();

    // 编辑入口走 version 乐观锁。
    http.perform(
            put("/catalog/skus/" + f.skuId())
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"name\":\"HTTP 改名\",\"barcode\":\"6912345678\",\"baseUnit\":\"箱\","
                        + "\"packagingType\":\"彩盒\",\"lengthMm\":200,\"widthMm\":150,\"heightMm\":100,"
                        + "\"netWeightG\":250,\"grossWeightG\":300,\"version\":"
                        + version
                        + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.packagingChanged").value(true));
    http.perform(
            get("/catalog/skus/" + f.skuId()).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("HTTP 改名"))
        .andExpect(jsonPath("$.packaging.packagingType").value("彩盒"))
        .andExpect(jsonPath("$.packaging.volumeCm3").value(3000.0));

    // 过期 version → 409
    http.perform(
            put("/catalog/skus/" + f.skuId())
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"name\":\"再改\",\"barcode\":\"1\",\"baseUnit\":\"件\",\"version\":"
                        + version
                        + "}"))
        .andExpect(status().isConflict());

    // 只读角色不能编辑
    http.perform(
            put("/catalog/skus/" + f.skuId())
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{\"name\":\"x\",\"barcode\":\"1\",\"baseUnit\":\"件\",\"version\":0}"))
        .andExpect(status().isForbidden());
    // 跨租户 → 404，不区分"不存在"和"没权限"
    http.perform(get("/catalog/skus/" + f.skuId()).header("Authorization", "Bearer tenant-b"))
        .andExpect(status().isNotFound());
    // 未认证 → 401
    http.perform(get("/catalog/skus/" + f.skuId())).andExpect(status().isUnauthorized());
    // 3.1.2.1 的静态路由不能被详情通配吃掉
    http.perform(get("/catalog/skus/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fields").isArray());
  }

  @Test
  void detailReadsStatusHistoryRecordedBySkuListBatch() {
    var f = fixture("HISTORY");
    var ids = List.of(f.skuId());
    call(
        actor,
        "catalog.sku.update",
        () ->
            new SkuListService(sql, new UnwiredSkuProjectionPort(), access)
                .batch(actor, new SkuBatchInput("STOP_SELLING", ids, "INACTIVE", "供应商停供", null, false)));
    var d = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    assertThat(d.statusHistory()).isNotEmpty();
    var change = d.statusHistory().getFirst();
    assertThat(change.fromStatus()).isEqualTo("ACTIVE");
    assertThat(change.toStatus()).isEqualTo("INACTIVE");
    assertThat(change.reason()).isEqualTo("供应商停供");
    // 业务时间与系统时间分列存储。
    assertThat(change.occurredAt()).isNotBlank();
    assertThat(change.createdAt()).isNotBlank();
  }

  @Test
  void unitConversionEffectiveWindowAcceptsAClosedThenOpenHistory() {
    // 与上面的重叠用例对照：合法版本化写入必须成功，不被触发器误伤。
    var f = fixture("UNITS-OK");
    var rows =
        tx(
            a,
            () -> {
              sql.execute(
                  "insert into sku_unit_conversion(id,tenant_id,sku_id,unit_code,role,quantity_per_base,effective_from,effective_to)"
                      + " values(?::uuid,?::uuid,?::uuid,'箱','PURCHASE',10,'2018-01-01','2023-12-31')",
                  UUID.randomUUID(), a, f.skuId());
              sql.execute(
                  "insert into sku_unit_conversion(id,tenant_id,sku_id,unit_code,role,quantity_per_base,effective_from,effective_to)"
                      + " values(?::uuid,?::uuid,?::uuid,'箱','PURCHASE',12,'2024-01-01',null)",
                  UUID.randomUUID(), a, f.skuId());
              return null;
            });
    assertThat(rows).isNull();
    var d = call(actor, "catalog.sku.read", () -> detail.detail(actor, f.skuId()));
    assertThat(d.unitConversions().stream().filter(SkuUnitConversion::current)).hasSize(1);
    assertThat(LocalDate.now(java.time.ZoneOffset.UTC).getYear()).isGreaterThan(2024);
  }
}