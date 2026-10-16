package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 6.1.1.1 供应商列表：清单真的从 party_* join 出来、四个指标的口径、筛选与导出，以及<b>租户隔离三断言</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：数据全部真的落库，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还之后不会串租户。
 *
 * <p><b>本文件最重要的五个断言</b>：
 *
 * <ol>
 *   <li>{@link #租户隔离三断言覆盖供应商清单()}：本模块 join 的是 <b>别的车道交付的 party 三张表</b>，
 *       所以隔离断言必须覆盖「跨租户的供应商在列表里查不到」—— 只测本模块新建的三张表是不够的。
 *   <li>{@link #供货sku数与采购金额来自真实表()}：M1 / M2 必须真的从 supplier_quote 与 vendor_delivery_record 聚合出来。
 *   <li>{@link #比率在窗口内无记录时为null而不是0()}：规则 R3。「还没交货过」与「交货全部延期」是相反的两件事。
 *   <li>{@link #统计窗口用固定时刻钉死()}：准时率与合格率是<b>窗口统计</b>，测试必须钉死窗口的起止，
 *       否则用例只在某个时间段通过 —— 那是一个按批次栅格引爆的定时炸弹。
 *   <li>{@link #本模块不回写party主数据()}：把蓝图那句加粗「避免同一实体两处可编辑」钉成数据事实。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class VendorListIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  // findAndRegisterModules()：裸 ObjectMapper 不带 JavaTimeModule，
  // 序列化 VendorDeliveryInput.orderedAt 会直接抛 InvalidDefinitionException。
  // 应用侧由 Spring Boot 自动配置的 mapper 带着它，这个 mapper 只服务于测试里手写请求体。
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("spu", "sku", "attribute", "research", "compliance", "assets")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "catalog." + p + "." + op))
          .toList();

  /**
   * <b>统计窗口用固定时刻钉死</b>。
   *
   * <p>准时交付率与质量合格率是窗口内统计，如果测试让服务端取 {@code now()} 而断言去比对「最近 365 天」，
   * 那条用例只会在某个时间段内成立。窗口两端在这里写死，所有指标断言都引用它。
   */
  static final OffsetDateTime WINDOW_FROM =
      OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
  static final OffsetDateTime WINDOW_TO = OffsetDateTime.of(2026, 6, 30, 23, 59, 59, 0, ZoneOffset.UTC);

  static final LocalDate FROM_DATE = WINDOW_FROM.toLocalDate();
  static final LocalDate TO_DATE = WINDOW_TO.toLocalDate();

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
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      // 只有商品读权限：写端点必须 403（供应商档案沿用商品主数据域权限）。
      actors.put(
          "Bearer reader", actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read")))));
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
    // 单连接：只有复用同一条连接，才能真正证明归还之后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired PartyService parties;
  @Autowired CatalogService catalog;
  @Autowired PartyRoleProfileService profiles;
  @Autowired VendorListService vendors;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired PlatformTransactionManager manager;
  @Autowired MockMvc http;

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setUp() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  /** 写端点走 update 权限上下文（读端点与写端点的权限词不同，见 VendorListController）。 */
  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  /** 同上，但面向返回 void 的写端点（作废）。 */
  void writeVoid(AccessService.Access who, Runnable work) {
    access.run(who, "catalog.spu.update", () -> {
      work.run();
      return null;
    });
  }

  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  /** 建一个带指定角色的 ACTIVE 主体，返回主体 id。 */
  UUID partyWith(AccessService.Access who, String prefix, String... roleTypes) {
    var roles = new PartyRoleInput[roleTypes.length];
    for (int i = 0; i < roleTypes.length; i++)
      roles[i] = new PartyRoleInput(roleTypes[i], "ACTIVE", "");
    String code = unique(prefix);
    var saved =
        tx(
            who,
            () ->
                parties.save(
                    who,
                    null,
                    new PartyInput(
                        code,
                        "主体 " + code,
                        "ORGANIZATION",
                        "CN",
                        "mail@" + code.toLowerCase() + ".example",
                        null,
                        null,
                        "ACTIVE",
                        List.of(roles),
                        0)));
    return saved.id();
  }

  /** 该主体的 SUPPLIER 角色 id（本模块一切操作的锚点）。 */
  UUID supplierRoleOf(AccessService.Access who, UUID partyId) {
    return tx(
        who,
        () ->
            sql.fetchOne(
                    "select id from party_role where tenant_id=? and party_id=? and role_type='SUPPLIER'",
                    who.tenantId(),
                    partyId)
                .get(0, UUID.class));
  }

  /**
   * 建一个本租户的 SKU（供货 SKU 数的原料，3.6.2.1 的报价要引用它）。
   *
   * <p>走真实的 {@code saveSpu} / {@code saveSku} 而不是直接 INSERT：sku 上 {@code spu_id} 与
   * {@code variant_signature} 是 NOT NULL，直接插一行会绕开一整层校验，
   * 而本模块的 M1 指标恰恰是数「这个供应商的有效报价覆盖了几个真实 SKU」。
   * SPU 建 DRAFT：3.1.1.1 的生命周期要求先保存为草稿再补 SKU，本模块只要求 SKU 真实存在。
   */
  UUID skuWith(AccessService.Access who, String prefix) {
    String code = unique(prefix);
    UUID spuId =
        write(
            who,
            () -> catalog.saveSpu(who, null, new SpuInput(
                code + "-SPU", "SPU " + code, "", "Brand", "Category", "DRAFT", 0)));
    return write(
        who,
        () -> catalog.saveSku(who, spuId, null,
            new SkuInput(code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), 0)));
  }

  /** 供应商报价与到货记录的采购单位都要引用 3.3.3 的 unit_of_measure（外键兜底）。 */
  void ensureUnit(AccessService.Access who, String code) {
    tx(
        who,
        () -> {
          sql.execute(
              "insert into unit_of_measure(id,tenant_id,code,name,dimension,scale,allow_purchase,"
                  + "allow_sale,allow_storage,enabled) values(?::uuid,?::uuid,?,?, 'COUNT',0,true,"
                  + "true,true,true) on conflict (tenant_id,code) do nothing",
              UUID.randomUUID(), who.tenantId(), code, code);
          return null;
        });
  }

  VendorListModels.VendorDeliveryInput delivery(
      String ref, LocalDate due, LocalDate received, String amount) {
    return new VendorListModels.VendorDeliveryInput(
        ref,
        null,
        null,
        new BigDecimal("100"),
        new BigDecimal(amount),
        "USD",
        WINDOW_FROM.minusDays(5),
        due,
        received,
        null);
  }

  VendorListModels.VendorQualityInspectionInput inspection(String ref, String inspected, String qualified) {
    return new VendorListModels.VendorQualityInspectionInput(
        null, ref, new BigDecimal(inspected), new BigDecimal(qualified),
        WINDOW_FROM.plusDays(1), null);
  }

  /** 造一家「什么记录都有」的供应商并返回它的角色 id。 */
  UUID fullSupplier(AccessService.Access who) {
    UUID party = partyWith(who, "VEND", "SUPPLIER");
    return supplierRoleOf(who, party);
  }

  VendorListModels.VendorListQuery windowedQuery(int page, int size) {
    return new VendorListModels.VendorListQuery(
        null, null, null, null, null, null, null, WINDOW_FROM, WINDOW_TO, null, null, page, size);
  }

  // ================================================================ 清单真的从 party_* join 出来

  @Test
  void 清单从party档案读出而不是第二份副本() {
    UUID party = partyWith(actor, "JOIN", "SUPPLIER");
    UUID role = supplierRoleOf(actor, party);
    write(actor, () -> vendors.saveProfile(actor, role,
        new VendorListModels.VendorSupplyProfileInput("ACTIVE", null, "采购侧备注", 0)));

    var page = tx(actor, () -> vendors.list(windowedQuery(0, 20)));
    var row = page.items().stream().filter(r -> r.roleId().equals(role)).findFirst().orElseThrow();
    // 编码 / 名称 / 联系方式 / 合作等级 / 主体状态全部来自 party 与 party_supplier_profile
    assertThat(row.partyId()).isEqualTo(party);
    assertThat(row.partyCode()).isNotBlank();
    assertThat(row.partyName()).startsWith("主体 ");
    assertThat(row.email()).isNotBlank();
    assertThat(row.partyStatus()).isEqualTo("ACTIVE");
    assertThat(row.cooperationLevel()).isNull(); // 还没建 3.6.1.2 的角色档案
    assertThat(row.supplierProfileFound()).isFalse();
    // 本模块自有的采购侧状态
    assertThat(row.supplyStatus()).isEqualTo("ACTIVE");
    assertThat(row.supplyProfileFound()).isTrue();
  }

  @Test
  void 建了合作等级之后列表直接显示() {
    UUID party = partyWith(actor, "LEVEL", "SUPPLIER");
    UUID role = supplierRoleOf(actor, party);
    write(actor, () -> profiles.saveSupplier(actor, party,
        new PartySupplierProfileInput(30, null, null, "STRATEGIC", "CREDIT", "", 0)));

    var row =
        tx(actor, () -> vendors.list(windowedQuery(0, 200))).items().stream()
            .filter(r -> r.roleId().equals(role)).findFirst().orElseThrow();
    assertThat(row.cooperationLevel()).isEqualTo("STRATEGIC");
    assertThat(row.paymentTermsDays()).isEqualTo(30);
    assertThat(row.settlementMethod()).isEqualTo("CREDIT");
    assertThat(row.supplierProfileFound()).isTrue();
  }

  @Test
  void 本模块不回写party主数据() {
    UUID party = partyWith(actor, "NOWRITE", "SUPPLIER");
    UUID role = supplierRoleOf(actor, party);
    String codeBefore = tx(actor, () -> partyRow(party).code());
    String nameBefore = tx(actor, () -> partyRow(party).name());
    String emailBefore = tx(actor, () -> partyRow(party).email());
    String statusBefore = tx(actor, () -> partyRow(party).status());

    // 本模块的全部写端点都跑一遍
    write(actor, () -> vendors.saveProfile(actor, role,
        new VendorListModels.VendorSupplyProfileInput("SUSPENDED", null, "暂停", 0)));
    write(actor, () -> vendors.recordDelivery(actor, role, delivery("DN-1", TO_DATE, TO_DATE, "100.00")));
    write(actor, () -> vendors.recordInspection(actor, role, inspection("QN-1", "100", "90")));

    // party 表一个字节都没动
    assertThat(tx(actor, () -> partyRow(party).code())).isEqualTo(codeBefore);
    assertThat(tx(actor, () -> partyRow(party).name())).isEqualTo(nameBefore);
    assertThat(tx(actor, () -> partyRow(party).email())).isEqualTo(emailBefore);
    assertThat(tx(actor, () -> partyRow(party).status())).isEqualTo(statusBefore);
    // 而采购侧状态确实落到了本模块自己的表上
    assertThat(tx(actor, () -> vendorSupplyStatus(role))).isEqualTo("SUSPENDED");
  }

  private record PartyRow(String code, String name, String email, String status) {}

  private PartyRow partyRow(UUID partyId) {
    var r = tx(actor, () -> sql.fetchOne(
        "select code,name,email,status from party where id=?::uuid", partyId));
    return new PartyRow(r.get(0, String.class), r.get(1, String.class),
        r.get(2, String.class), r.get(3, String.class));
  }

  private String vendorSupplyStatus(UUID roleId) {
    return tx(actor, () -> sql.fetchOne(
            "select supply_status from vendor_supply_profile where role_id=?::uuid", roleId)
        .get(0, String.class));
  }

  // ================================================================ 四个指标的口径

  @Test
  void 供货sku数与采购金额来自真实表() {
    UUID role = fullSupplier(actor);
    UUID sku1 = skuWith(actor, "SUP-A");
    UUID sku2 = skuWith(actor, "SUP-B");
    UUID sku3 = skuWith(actor, "SUP-C");
    write(actor, () -> {
      quote(actor, role, sku1, "ACTIVE");
      quote(actor, role, sku2, "ACTIVE");
      quote(actor, role, sku3, "DISABLED"); // 停用报价不计入「供得上」
      return null;
    });
    write(actor, () -> vendors.recordDelivery(actor, role,
        delivery("D1", TO_DATE.minusDays(2), TO_DATE.minusDays(2), "1200.00")));
    write(actor, () -> vendors.recordDelivery(actor, role,
        delivery("D2", TO_DATE.minusDays(2), TO_DATE.minusDays(2), "800.00")));

    var row = tx(actor, () -> vendors.list(windowedQuery(0, 200))).items().stream()
        .filter(r -> r.roleId().equals(role)).findFirst().orElseThrow();
    // M1：两个不同 SKU 的有效报价（第三个是 DISABLED）
    assertThat(row.supplySkuCount()).isEqualTo(2L);
    // M2：1200 + 800
    assertThat(row.purchaseAmount()).isEqualByComparingTo("2000.00");
    assertThat(row.purchaseAmountCurrency()).isEqualTo("USD");
    assertThat(row.purchaseAmounts()).hasSize(1);
    assertThat(row.purchaseAmounts().get(0).amount()).isEqualByComparingTo("2000.00");
    // M3：两条都准时
    assertThat(row.deliveryCount()).isEqualTo(2L);
    assertThat(row.onTimeDeliveryCount()).isEqualTo(2L);
    assertThat(row.onTimeRate()).isEqualByComparingTo("1.0000");
  }

  /** 建一条供应商报价（供货 SKU 数的原料，3.6.2.1 的表）。 */
  void quote(AccessService.Access who, UUID role, UUID sku, String status) {
    ensureUnit(who, "PCS");
    sql.execute(
        "insert into supplier_quote(id,tenant_id,supplier_role_id,sku_id,supplier_sku_code,"
            + "purchase_unit_code,currency,moq_quantity,valid_from,status)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,'PCS','USD',1,current_date,?)",
        UUID.randomUUID(), who.tenantId(), role, sku, "Q-" + UUID.randomUUID(), status);
  }

  @Test
  void 准时交付率按到货日期与承诺日期之比算() {
    UUID role = fullSupplier(actor);
    // 3 条：准时 2、迟 1
    write(actor, () -> vendors.recordDelivery(actor, role, delivery("T1", TO_DATE.minusDays(5), TO_DATE.minusDays(5), "100.00")));
    write(actor, () -> vendors.recordDelivery(actor, role, delivery("T2", TO_DATE.minusDays(5), TO_DATE.minusDays(6), "100.00")));
    write(actor, () -> vendors.recordDelivery(actor, role, delivery("T3", TO_DATE.minusDays(5), TO_DATE.minusDays(1), "100.00")));

    var row = tx(actor, () -> vendors.list(windowedQuery(0, 200))).items().stream()
        .filter(r -> r.roleId().equals(role)).findFirst().orElseThrow();
    assertThat(row.deliveryCount()).isEqualTo(3L);
    assertThat(row.onTimeDeliveryCount()).isEqualTo(2L);
    assertThat(row.onTimeRate()).isEqualByComparingTo("0.6667");
  }

  @Test
  void 质量合格率按数量比而不是批次数比() {
    UUID role = fullSupplier(actor);
    // 一批 10000 件合格 9999、一批 3 件合格 1
    // 按批次数算是 50%，按数量算是 99.97% —— 后者才是采购真正承担的风险大小
    write(actor, () -> vendors.recordInspection(actor, role, inspection("Q1", "10000", "9999")));
    write(actor, () -> vendors.recordInspection(actor, role, inspection("Q2", "3", "1")));

    var row = tx(actor, () -> vendors.list(windowedQuery(0, 200))).items().stream()
        .filter(r -> r.roleId().equals(role)).findFirst().orElseThrow();
    assertThat(row.inspectedQuantity()).isEqualByComparingTo("10003");
    assertThat(row.qualifiedQuantity()).isEqualByComparingTo("10000");
    assertThat(row.qualityRate()).isEqualByComparingTo("0.9997");
  }

  @Test
  void 比率在窗口内无记录时为null而不是0() {
    UUID role = fullSupplier(actor);
    // 记录都在窗口之外：窗口内必须「暂无数据」而不是 0%
    // 窗口之前的那一条：下单与到货都在窗口外，所以 received 不能早于 orderedAt
    write(actor, () -> vendors.recordDelivery(actor, role,
        new VendorListModels.VendorDeliveryInput("OUT", null, null, new BigDecimal("100"),
            new BigDecimal("500.00"), "USD",
            WINDOW_FROM.minusDays(40),
            WINDOW_FROM.toLocalDate().minusDays(30),
            WINDOW_FROM.toLocalDate().minusDays(30), null)));

    var row = tx(actor, () -> vendors.list(windowedQuery(0, 200))).items().stream()
        .filter(r -> r.roleId().equals(role)).findFirst().orElseThrow();
    assertThat(row.deliveryCount()).isEqualTo(0L);
    assertThat(row.onTimeRate()).isNull();
    assertThat(row.qualityRate()).isNull();
    // 窗口一放宽，窗口外的那条就进来了 —— 证明它是窗口过滤而不是数据丢失
    var wide = tx(actor, () -> vendors.list(new VendorListModels.VendorListQuery(
        null, null, null, null, null, null, null,
        WINDOW_FROM.minusDays(60), WINDOW_TO, null, null, 0, 200)));
    var wideRow = wide.items().stream().filter(r -> r.roleId().equals(role)).findFirst().orElseThrow();
    assertThat(wideRow.deliveryCount()).isEqualTo(1L);
    assertThat(wideRow.onTimeRate()).isEqualByComparingTo("1.0000");
  }

  @Test
  void 统计窗口用固定时刻钉死() {
    UUID role = fullSupplier(actor);
    write(actor, () -> vendors.recordDelivery(actor, role, delivery("IN", TO_DATE, TO_DATE, "10.00")));
    write(actor, () -> vendors.recordInspection(actor, role, inspection("QIN", "10", "10")));

    var page = tx(actor, () -> vendors.list(windowedQuery(0, 200)));
    // 窗口原样回显：界面上必须能显示「按哪一段算」
    assertThat(page.metricWindow().from()).isEqualTo(WINDOW_FROM.toInstant().toString());
    assertThat(page.metricWindow().to()).isEqualTo(WINDOW_TO.toInstant().toString());
    var row = page.items().stream().filter(r -> r.roleId().equals(role)).findFirst().orElseThrow();
    assertThat(row.deliveryCount()).isEqualTo(1L);
    assertThat(row.qualityRate()).isEqualByComparingTo("1.0000");

    // 窗口挪到记录之后，同一条记录就必须消失 —— 这钉死了「窗口真的在过滤」
    var shifted = tx(actor, () -> vendors.list(new VendorListModels.VendorListQuery(
        null, null, null, null, null, null, null,
        TO_DATE.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC),
        TO_DATE.plusDays(30).atStartOfDay().atOffset(ZoneOffset.UTC), null, null, 0, 200)));
    var shiftedRow = shifted.items().stream()
        .filter(r -> r.roleId().equals(role)).findFirst().orElseThrow();
    assertThat(shiftedRow.deliveryCount()).isEqualTo(0L);
    assertThat(shiftedRow.onTimeRate()).isNull();
  }

  @Test
  void 作废的记录不计入指标但仍可追溯() {
    UUID role = fullSupplier(actor);
    var saved = write(actor, () -> vendors.recordDelivery(actor, role,
        delivery("VOIDME", TO_DATE.minusDays(3), TO_DATE.minusDays(1), "999.00")));
    assertThat(tx(actor, () -> vendors.list(windowedQuery(0, 200))).items().stream()
        .filter(r -> r.roleId().equals(role)).findFirst().orElseThrow().deliveryCount()).isEqualTo(1L);

    writeVoid(actor, () -> vendors.voidDelivery(actor, role, saved.id(),
        new VendorListModels.VendorVoidInput("录错了批次")));

    var row = tx(actor, () -> vendors.list(windowedQuery(0, 200))).items().stream()
        .filter(r -> r.roleId().equals(role)).findFirst().orElseThrow();
    assertThat(row.deliveryCount()).isEqualTo(0L);
    // 但它仍然在下钻视图里，带着作废理由
    var view = tx(actor, () -> vendors.purchaseView(role));
    assertThat(view.deliveries()).hasSize(1);
    assertThat(view.deliveries().get(0).isVoid()).isTrue();
    assertThat(view.deliveries().get(0).voidReason()).isEqualTo("录错了批次");
  }

  @Test
  void 跨币种不相加() {
    UUID role = fullSupplier(actor);
    write(actor, () -> vendors.recordDelivery(actor, role,
        new VendorListModels.VendorDeliveryInput("U1", null, null, new BigDecimal("10"),
            new BigDecimal("100.00"), "USD", WINDOW_FROM, TO_DATE, TO_DATE, null)));
    write(actor, () -> vendors.recordDelivery(actor, role,
        new VendorListModels.VendorDeliveryInput("E1", null, null, new BigDecimal("10"),
            new BigDecimal("90.00"), "EUR", WINDOW_FROM, TO_DATE, TO_DATE, null)));

    var row = tx(actor, () -> vendors.list(windowedQuery(0, 200))).items().stream()
        .filter(r -> r.roleId().equals(role)).findFirst().orElseThrow();
    // 明细按币种分组，绝不相加成 190
    assertThat(row.purchaseAmounts()).hasSize(2);
    assertThat(row.purchaseAmounts().stream().map(VendorListModels.VendorPurchaseAmount::currency))
        .containsExactly("USD", "EUR");
    // 代表值取金额最大的那一笔，并写明是哪一笔
    assertThat(row.purchaseAmount()).isEqualByComparingTo("100.00");
    assertThat(row.purchaseAmountCurrency()).isEqualTo("USD");
    // 指定币种后只看那个币种
    var usd = tx(actor, () -> vendors.list(new VendorListModels.VendorListQuery(
        null, null, null, null, null, "USD", null, WINDOW_FROM, WINDOW_TO, null, null, 0, 200)));
    var usdRow = usd.items().stream().filter(r -> r.roleId().equals(role)).findFirst().orElseThrow();
    assertThat(usdRow.purchaseAmounts()).hasSize(1);
    assertThat(usdRow.purchaseAmounts().get(0).currency()).isEqualTo("USD");
  }

  // ================================================================ 筛选 / 排序 / 分页

  @Test
  void 筛选按合作状态类目与合作等级() {
    UUID role = fullSupplier(actor);
    write(actor, () -> vendors.saveProfile(actor, role,
        new VendorListModels.VendorSupplyProfileInput("SUSPENDED", null, "", 0)));

    var byStatus = tx(actor, () -> vendors.list(new VendorListModels.VendorListQuery(
        null, "SUSPENDED", null, null, null, null, null, WINDOW_FROM, WINDOW_TO, null, null, 0, 200)));
    assertThat(byStatus.items()).extracting(VendorListModels.VendorRow::roleId).contains(role);
    assertThat(byStatus.total()).isPositive();

    var byOtherStatus = tx(actor, () -> vendors.list(new VendorListModels.VendorListQuery(
        null, "ACTIVE", null, null, null, null, null, WINDOW_FROM, WINDOW_TO, null, null, 0, 200)));
    assertThat(byOtherStatus.items()).extracting(VendorListModels.VendorRow::roleId).doesNotContain(role);

    var missing = tx(actor, () -> vendors.list(new VendorListModels.VendorListQuery(
        null, null, null, null, null, null, Boolean.TRUE, WINDOW_FROM, WINDOW_TO, null, null, 0, 200)));
    assertThat(missing.items()).extracting(VendorListModels.VendorRow::roleId).doesNotContain(role);
  }

  @Test
  void 无效筛选值给可读原因而不是空列表() {
    assertThatThrownBy(() -> tx(actor, () -> vendors.list(new VendorListModels.VendorListQuery(
        null, "NOT_A_STATUS", null, null, null, null, null, WINDOW_FROM, WINDOW_TO, null, null, 0, 20))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("合作状态");
    assertThatThrownBy(() -> tx(actor, () -> vendors.list(new VendorListModels.VendorListQuery(
        null, null, null, "GOLD", null, null, null, WINDOW_FROM, WINDOW_TO, null, null, 0, 20))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("合作等级");
    assertThatThrownBy(() -> tx(actor, () -> vendors.list(new VendorListModels.VendorListQuery(
        null, null, null, null, null, "US_DOLLAR", null, WINDOW_FROM, WINDOW_TO, null, null, 0, 20))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("ISO 4217");
    assertThatThrownBy(() -> tx(actor, () -> vendors.list(new VendorListModels.VendorListQuery(
        null, null, null, null, null, null, null, WINDOW_FROM, WINDOW_TO, "1;drop table", null, 0, 20))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("排序键");
  }

  @Test
  void 计数与翻页翻到底对得上() {
    UUID first = fullSupplier(actor);
    UUID second = fullSupplier(actor);
    assertThat(first).isNotEqualTo(second);

    var page0 = tx(actor, () -> vendors.list(new VendorListModels.VendorListQuery(
        null, "PENDING_REVIEW", null, null, null, null, null, WINDOW_FROM, WINDOW_TO, null, null, 0, 1)));
    long total = page0.total();
    assertThat(total).isGreaterThanOrEqualTo(2L);

    var seen = new java.util.LinkedHashSet<UUID>();
    for (int index = 0; index < total + 4; index++) {
      final int page = index;
      var p = tx(actor, () -> vendors.list(new VendorListModels.VendorListQuery(
          null, "PENDING_REVIEW", null, null, null, null, null, WINDOW_FROM, WINDOW_TO,
          "partyCode", null, page, 1)));
      p.items().forEach(r -> seen.add(r.roleId()));
      if (p.items().isEmpty()) break;
    }
    // 「筛出来的总数」与「翻页翻到底」必须是同一个集合
    assertThat(seen).hasSize((int) total);
  }

  @Test
  void 排序命中白名单且方向可换() {
    UUID role = fullSupplier(actor);
    write(actor, () -> vendors.recordDelivery(actor, role, delivery("S1", TO_DATE, TO_DATE, "500.00")));

    var asc = tx(actor, () -> vendors.list(new VendorListModels.VendorListQuery(
        null, null, null, null, null, null, null, WINDOW_FROM, WINDOW_TO,
        "purchaseAmount", Boolean.FALSE, 0, 200)));
    var desc = tx(actor, () -> vendors.list(new VendorListModels.VendorListQuery(
        null, null, null, null, null, null, null, WINDOW_FROM, WINDOW_TO,
        "purchaseAmount", Boolean.TRUE, 0, 200)));
    assertThat(asc.items()).extracting(VendorListModels.VendorRow::roleId).contains(role);
    assertThat(desc.items()).extracting(VendorListModels.VendorRow::roleId).contains(role);
    assertThat(asc.items().get(asc.items().size() - 1).roleId()).isEqualTo(role);
    assertThat(desc.items().get(0).roleId()).isEqualTo(role);
  }

  // ================================================================ 写入路径的校验

  @Test
  void 合格数量不得超过检验数量() {
    UUID role = fullSupplier(actor);
    assertThatThrownBy(() -> write(actor, () -> vendors.recordInspection(actor, role,
        new VendorListModels.VendorQualityInspectionInput(
            null, "BAD", new BigDecimal("100"), new BigDecimal("120"), WINDOW_FROM, null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能超过");
  }

  @Test
  void 合格数校验在DB层也有一道() {
    UUID role = fullSupplier(actor);
    assertThatThrownBy(() -> write(actor, () -> sql.execute(
        "insert into vendor_quality_record(id,tenant_id,role_id,inspection_ref,inspected_qty,"
            + "qualified_qty,inspected_at) values(?::uuid,?::uuid,?::uuid,'DBCHK',100,120,now())",
        UUID.randomUUID(), actor.tenantId(), role)))
        .hasMessageContaining("vendor_quality_record_check");
  }

  @Test
  void 写端点需要update权限() throws Exception {
    UUID role = fullSupplier(actor);
    http.perform(
            put("/catalog/supply/vendors/" + role + "/profile")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{\"supplyStatus\":\"ACTIVE\",\"version\":0}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 端点往返真实走HTTP() throws Exception {
    UUID party = partyWith(actor, "HTTP", "SUPPLIER");
    UUID role = supplierRoleOf(actor, party);

    http.perform(get("/catalog/supply/vendors").header("Authorization", "Bearer tenant-a")
            .param("metricFrom", WINDOW_FROM.toString())
            .param("metricTo", WINDOW_TO.toString()))
        .andExpect(status().isOk());
    http.perform(get("/catalog/supply/vendors/options").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.supplyStatuses").isArray())
        .andExpect(jsonPath("$.cooperationLevels").isArray())
        .andExpect(jsonPath("$.sorts").isArray());
    http.perform(get("/catalog/supply/vendors/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.supplierCount").isNumber());

    http.perform(put("/catalog/supply/vendors/" + role + "/profile")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"supplyStatus\":\"ACTIVE\",\"note\":\"HTTP 建的\",\"version\":0}"))
        .andExpect(status().isOk());

    http.perform(post("/catalog/supply/vendors/" + role + "/deliveries")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(JSON.writeValueAsString(delivery("HTTP-D1", TO_DATE, TO_DATE, "77.00"))))
        .andExpect(status().isOk());

    http.perform(post("/catalog/supply/vendors/" + role + "/quality-inspections")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(JSON.writeValueAsString(inspection("HTTP-Q1", "50", "45"))))
        .andExpect(status().isOk());

    http.perform(get("/catalog/supply/vendors/" + role).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.roleId").value(role.toString()))
        .andExpect(jsonPath("$.deliveries").isArray())
        .andExpect(jsonPath("$.inspections").isArray());
  }

  @Test
  void 导出声明里能看见本模块() throws Exception {
    http.perform(get("/catalog/export-sources").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.key=='VENDOR_LIST')].available").value(true))
        .andExpect(jsonPath("$.items[?(@.key=='VENDOR_LIST')].upstreamModule").value("6.1.1.1 供应商列表"));
  }

  @Test
  void 时间字段真的写回了正确时刻() throws Exception {
    UUID role = fullSupplier(actor);
    OffsetDateTime ordered = WINDOW_FROM.plusDays(3).plusHours(9);
    write(actor, () -> vendors.recordDelivery(actor, role,
        new VendorListModels.VendorDeliveryInput("TZ", null, null, new BigDecimal("1"),
            new BigDecimal("1.00"), "USD", ordered, TO_DATE, TO_DATE, null)));
    var stored =
        tx(actor, () -> sql.fetchOne(
                "select ordered_at from vendor_delivery_record where delivery_ref='TZ'")
            .get(0, OffsetDateTime.class));
    // 断言的是写回的值本身，不是「没抛异常」——
    // 用 Timestamp.valueOf(LocalDateTime) 绑 timestamptz 会静默偏 −28800s，而这个偏移在「最近到货」上完全看不出来
    assertThat(stored.toInstant()).isEqualTo(ordered.toInstant());

    OffsetDateTime inspected = WINDOW_FROM.plusDays(4).plusHours(9);
    write(actor, () -> vendors.recordInspection(actor, role,
        new VendorListModels.VendorQualityInspectionInput(
            null, "TZ-Q", new BigDecimal("10"), new BigDecimal("10"), inspected, null)));
    var storedInspected =
        tx(actor, () -> sql.fetchOne(
                "select inspected_at from vendor_quality_record where inspection_ref='TZ-Q'")
            .get(0, OffsetDateTime.class));
    assertThat(storedInspected.toInstant()).isEqualTo(inspected.toInstant());
  }

  // ================================================================ 租户隔离三断言

  /**
   * 三断言覆盖供应商清单。
   *
   * <p><b>本模块 join 的是别的车道交付的 party 三张表</b>，所以隔离断言必须落在「跨租户的供应商在列表里查不到」上 ——
   * 只测本模块新建的三张表是不够的：那些表我自己的 where 已经带了 tenant_id，而 party 的可见性由**它自己的 FORCE RLS** 把关。
   */
  @Test
  void 租户隔离三断言覆盖供应商清单() throws Exception {
    UUID mine = partyWith(actor, "ISO-MINE", "SUPPLIER");
    UUID myRole = supplierRoleOf(actor, mine);
    UUID theirs = partyWith(actorB, "ISO-THEIRS", "SUPPLIER");
    UUID theirRole = supplierRoleOf(actorB, theirs);

    write(actor, () -> vendors.saveProfile(actor, myRole,
        new VendorListModels.VendorSupplyProfileInput("ACTIVE", null, "我的", 0)));
    write(actorB, () -> vendors.saveProfile(actorB, theirRole,
        new VendorListModels.VendorSupplyProfileInput("SUSPENDED", null, "他们的", 0)));
    write(actor, () -> vendors.recordDelivery(actor, myRole, delivery("MY-D", TO_DATE, TO_DATE, "111111.00")));
    write(actorB, () -> vendors.recordDelivery(actorB, theirRole, delivery("THEIR-D", TO_DATE, TO_DATE, "999999.00")));
    write(actor, () -> vendors.recordInspection(actor, myRole, inspection("MY-Q", "100", "99")));
    write(actorB, () -> vendors.recordInspection(actorB, theirRole, inspection("THEIR-Q", "100", "1")));

    // 两边都必须真的各有数据 —— 否则下面的断言就是空的
    assertThat(tx(actor, () -> vendors.list(windowedQuery(0, 200))).items().stream()
        .filter(r -> r.roleId().equals(myRole)).findFirst().orElseThrow()
        .purchaseAmount()).isEqualByComparingTo("111111.00");

    // 1) 两租户互不可见：跨租户的供应商在列表里查不到，下钻也读不到，金额不相交
    assertThat(tx(actor, () -> vendors.list(windowedQuery(0, 200))).items())
        .extracting(VendorListModels.VendorRow::roleId).doesNotContain(theirRole);
    assertThat(tx(actorB, () -> vendors.list(windowedQuery(0, 200))).items())
        .extracting(VendorListModels.VendorRow::roleId).doesNotContain(myRole);
    assertThatThrownBy(() -> tx(actor, () -> vendors.purchaseView(theirRole)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> vendors.purchaseView(myRole)))
        .isInstanceOf(ResponseStatusException.class);
    // A 不能把采购侧档案写到 B 的供应商上
    assertThatThrownBy(() -> write(actor, () -> vendors.saveProfile(actor, theirRole,
        new VendorListModels.VendorSupplyProfileInput("TERMINATED", null, "越权", 0))))
        .isInstanceOf(ResponseStatusException.class);
    // A 不能给 B 的供应商追加到货（否则它的准时率会被 A 的记录污染）
    assertThatThrownBy(() -> write(actor, () -> vendors.recordDelivery(actor, theirRole,
        delivery("CROSS", TO_DATE, TO_DATE, "666666.00"))))
        .isInstanceOf(ResponseStatusException.class);
    // 金额不相交：这是本模块最要紧的一条
    assertThat(tx(actor, () -> vendors.list(windowedQuery(0, 200))).items().stream()
        .filter(r -> r.roleId().equals(myRole)).findFirst().orElseThrow()
        .purchaseAmount()).isEqualByComparingTo("111111.00");
    assertThat(tx(actorB, () -> vendors.list(windowedQuery(0, 200))).items().stream()
        .filter(r -> r.roleId().equals(theirRole)).findFirst().orElseThrow()
        .purchaseAmount()).isEqualByComparingTo("999999.00");

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(get("/catalog/supply/vendors/" + theirRole)
            .header("Authorization", "Bearer tenant-a").header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    http.perform(get("/catalog/supply/vendors").header("Authorization", "Bearer tenant-b")
            .header("X-Tenant-Id", a)
            .param("metricFrom", WINDOW_FROM.toString()).param("metricTo", WINDOW_TO.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.roleId=='" + myRole + "')]").isEmpty());
    // 伪造头下写入仍落在 B 自己名下 —— 断言**行确实落在 B 名下**，不只断言 A 看不见
    UUID forgedRole = fullSupplier(actorB);
    write(actorB, () -> vendors.recordDelivery(actorB, forgedRole,
        delivery("FORGED", TO_DATE, TO_DATE, "42.00")));
    assertThatThrownBy(() -> tx(actor, () -> vendors.purchaseView(forgedRole)))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actorB, () -> sql.fetchOne(
            "select tenant_id from vendor_delivery_record where delivery_ref='FORGED'")
        .get(0, UUID.class))).isEqualTo(b);

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言供应商集合不相交
    for (int i = 0; i < 5; i++) {
      var mineRoles = rolesOf(actor);
      var theirRoles = rolesOf(actorB);
      assertThat(mineRoles).isNotEmpty();
      assertThat(theirRoles).isNotEmpty();
      assertThat(java.util.Collections.disjoint(mineRoles, theirRoles)).isTrue();
      assertThat(tx(actor, () -> vendors.purchaseView(myRole).summary().qualityRate()))
          .isEqualByComparingTo("0.9900");
      assertThat(tx(actorB, () -> vendors.purchaseView(theirRole).summary().qualityRate()))
          .isEqualByComparingTo("0.0100");
    }
  }

  private java.util.Set<UUID> rolesOf(AccessService.Access who) {
    return tx(
            who,
            () ->
                vendors.list(new VendorListModels.VendorListQuery(
                        null, null, null, null, null, null, null, WINDOW_FROM, WINDOW_TO, null, null, 0, 200))
                    .items().stream()
                    .map(VendorListModels.VendorRow::roleId)
                    .collect(java.util.stream.Collectors.toSet()))
        .stream()
        .collect(java.util.stream.Collectors.toSet());
  }
}
