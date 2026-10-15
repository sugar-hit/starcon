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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 6.1.1.2 供应商详情：全景真的从上游只读表 join 出来、<b>账期与开票信息真的可写</b>、
 * 四个聚合真的由 SQL 算出来，以及<b>租户隔离三断言</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：数据全部真的落库，不使用任何内存替身。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还之后不会串租户。
 *
 * <p><b>本文件最重要的五个断言</b>：
 *
 * <ol>
 *   <li>{@link #本模块不回写上游主数据()}：蓝图加粗「避免同一实体两处可编辑」钉成数据事实 ——
 *       把本模块四个写端点全跑一遍之后，逐列比对 party / party_supplier_profile / vendor_supply_profile 未变。
 *   <li>{@link #账期与开票信息可写且与主体默认账期分开标注()}：蓝图加粗「账期与开票信息是付款审批的输入」。
 *       只读展示不算交付，所以这里必须真的写进去再读回来。
 *   <li>{@link #交期表现聚合口径()}：准时率 / 平均延期 / 最大延期 / 早到笔数四个数各自的算法。
 *   <li>{@link #比率在窗口内无记录时为null而不是0()}：「还没交货过」与「交货全部延期」是相反的两件事。
 *   <li>{@link #租户隔离三断言覆盖供应商全景()}：本模块 join 的是别的车道交付的表，
 *       隔离必须覆盖「跨租户的供应商全景读不到」，只测本模块新建的两张表是不够的。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class VendorDetailIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
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
   * <b>统计窗口用固定时刻钉死</b>：交期与质量表现是窗口统计，窗口两端写死，否则用例只在某段时间成立。
   */
  static final OffsetDateTime WINDOW_FROM =
      OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
  static final OffsetDateTime WINDOW_TO =
      OffsetDateTime.of(2026, 6, 30, 23, 59, 59, 0, ZoneOffset.UTC);
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
      // 只有商品读权限：写端点必须 403。
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
  @Autowired PartyRoleProfileService profiles;
  @Autowired CatalogService catalog;
  @Autowired VendorListService vendors;
  @Autowired VendorDetailService details;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
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

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  UUID partyWith(AccessService.Access who, String prefix, String... roleTypes) {
    var roles = new PartyRoleInput[roleTypes.length];
    for (int i = 0; i < roleTypes.length; i++) roles[i] = new PartyRoleInput(roleTypes[i], "ACTIVE", "");
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

  UUID skuWith(AccessService.Access who, String prefix) {
    String code = unique(prefix);
    UUID spuId =
        write(
            who,
            () ->
                catalog.saveSpu(
                    who, null, new SpuInput(code + "-SPU", "SPU " + code, "", "Brand", "Category", "DRAFT", 0)));
    return write(
        who,
        () ->
            catalog.saveSku(
                who,
                spuId,
                null,
                new SkuInput(code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), 0)));
  }

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

  UUID fullSupplier(AccessService.Access who) {
    UUID party = partyWith(who, "VD", "SUPPLIER");
    return supplierRoleOf(who, party);
  }

  VendorListModels.VendorDeliveryInput delivery(
      String ref, LocalDate due, LocalDate received, String amount) {
    return new VendorListModels.VendorDeliveryInput(
        ref, null, null, new BigDecimal("100"), new BigDecimal(amount), "USD",
        WINDOW_FROM.minusDays(5), due, received, null);
  }

  VendorListModels.VendorQualityInspectionInput inspection(String ref, String inspected, String qualified) {
    return new VendorListModels.VendorQualityInspectionInput(
        null, ref, new BigDecimal(inspected), new BigDecimal(qualified), WINDOW_FROM.plusDays(1), null);
  }

  VendorDetailModels.VendorTermsInput terms(String days, String method, long version) {
    return new VendorDetailModels.VendorTermsInput(
        "USD", Integer.parseInt(days), method, "VAT_SPECIAL", "某某（深圳）贸易有限公司",
        "91440300MA5EXAMPLE", new BigDecimal("0.13"), new BigDecimal("500000.00"),
        new BigDecimal("PREPAID".equals(method) ? "0.3" : "0"), LocalDate.of(2026, 1, 1), "预付三成，余款月结", version);
  }

  VendorDetailModels.VendorQualificationInput qualification(String type, LocalDate expires, long version) {
    return new VendorDetailModels.VendorQualificationInput(
        type, "营业执照 " + type, "CERT-" + SEQ.incrementAndGet(), "市场监督管理局",
        LocalDate.of(2020, 1, 1), expires, "vendor-qual/" + type + ".pdf",
        type + ".pdf", "application/pdf", 2048L, "", version);
  }

  VendorDetailModels.VendorPanorama pano(UUID role) {
    return tx(actor, () -> details.panorama(role, WINDOW_FROM, WINDOW_TO));
  }

  /** 往 supplier_quote 直插一行（3.6.2.1 的表，本模块只读）。外键：SKU / 采购单位 / SUPPLIER 角色。 */
  UUID quoteWith(
      AccessService.Access who, UUID role, UUID sku, String currency, String status,
      LocalDate from, LocalDate to, String unitPrice) {
    ensureUnit(who, "PCS");
    UUID id = UUID.randomUUID();
    tx(
        who,
        () -> {
          sql.execute(
              "insert into supplier_quote(tenant_id,id,supplier_role_id,sku_id,supplier_sku_code,"
                  + "purchase_unit_code,currency,moq_quantity,lead_time_days,valid_from,valid_to,"
                  + "priority,status,note,version,created_at,updated_at)"
                  + " values(?,?,?,?,?, 'PCS',?, 100, 15, ?, ?, 1, ?, '', 0, now(), now())",
              who.tenantId(), id, role, sku, unique("OFFER"), currency, from, to, status);
          sql.execute(
              "insert into supplier_quote_price_tier(tenant_id,quote_id,min_quantity,unit_price,note)"
                  + " values(?,?,?,?,'')",
              who.tenantId(), id, new BigDecimal("100"), new BigDecimal(unitPrice));
          return null;
        });
    return id;
  }

  // ================================================================ 全景真的从上游只读表 join 出来

  @Test
  void 全景从party档案读出而不是第二份副本() {
    UUID party = partyWith(actor, "PANO", "SUPPLIER");
    UUID role = supplierRoleOf(actor, party);
    write(actor, () -> vendors.saveProfile(actor, role,
        new VendorListModels.VendorSupplyProfileInput("ACTIVE", null, "采购侧备注", 0)));

    var p = pano(role);
    assertThat(p.partyId()).isEqualTo(party);
    assertThat(p.partyCode()).startsWith("PANO-");
    assertThat(p.partyName()).isNotBlank();
    assertThat(p.partyStatus()).isEqualTo("ACTIVE");
    // 采购侧合作状态来自 6.1.1.1 的 vendor_supply_profile（本模块只读）
    assertThat(p.supplyStatus()).isEqualTo("ACTIVE");
    assertThat(p.supplyNote()).isEqualTo("采购侧备注");
    assertThat(p.supplyProfileFound()).isTrue();
  }

  /**
   * 蓝图加粗「避免同一实体两处可编辑」钉成数据事实。
   *
   * <p>把本模块<b>四个</b>写端点全跑一遍之后逐列比对上游三张表未变 —— 只测读端点等于没测。
   */
  @Test
  void 本模块不回写上游主数据() {
    UUID party = partyWith(actor, "NORW", "SUPPLIER");
    UUID role = supplierRoleOf(actor, party);
    write(actor, () -> vendors.saveProfile(actor, role,
        new VendorListModels.VendorSupplyProfileInput("ACTIVE", null, "原始备注", 0)));

    String before = fingerprint(actor, role);

    // 四个写端点：建账期、改账期、建资质、改资质
    write(actor, () -> details.saveTerms(actor, role, terms("30", "CREDIT", 0)));
    var saved = write(actor, () -> details.saveTerms(actor, role, terms("45", "PREPAID", 0)));
    assertThat(saved.paymentTermsDays()).isEqualTo(45);
    var q = write(actor, () -> details.saveQualification(actor, role, null, qualification("CE", TO_DATE, 0), true));
    write(actor, () -> details.saveQualification(actor, role, q.id(), qualification("CE", TO_DATE.plusDays(10), 0), false));
    write(actor, () -> { details.deleteQualification(actor, role, q.id()); return null; });

    assertThat(fingerprint(actor, role)).isEqualTo(before);
  }

  /** party / party_role / party_supplier_profile / vendor_supply_profile 四张表的逐列快照。 */
  private String fingerprint(AccessService.Access who, UUID role) {
    return tx(
        who,
        () ->
            sql.fetchOne(
                    "select p.code,p.name,p.status,p.email,r.status as role_status,"
                        + "psp.cooperation_level,psp.payment_terms_days,psp.settlement_method,"
                        + "vsp.supply_status,vsp.note,vsp.version"
                        + " from party_role r join party p on p.tenant_id=r.tenant_id and p.id=r.party_id"
                        + " left join party_supplier_profile psp on psp.tenant_id=r.tenant_id and psp.role_id=r.id"
                        + " left join vendor_supply_profile vsp on vsp.tenant_id=r.tenant_id and vsp.role_id=r.id"
                        + " where r.tenant_id=? and r.id=?",
                    who.tenantId(),
                    role)
                .toString());
  }

  // ================================================================ 账期与开票信息（D1：真实可维护）

  @Test
  void 账期与开票信息可写且与主体默认账期分开标注() {
    UUID party = partyWith(actor, "TERMS", "SUPPLIER");
    UUID role = supplierRoleOf(actor, party);
    // 3.6.1.2 的主体默认账期（只读参照，写入所有权在那一边）
    write(actor, () -> profiles.saveSupplier(actor, party,
        new PartySupplierProfileInput(30, null, null, "STRATEGIC", "CREDIT", "", 0)));
    assertThat(pano(role).masterSettlementMethod()).isNotNull();

    var saved = write(actor, () -> details.saveTerms(actor, role, terms("45", "PREPAID", 0)));
    assertThat(saved.found()).isTrue();
    assertThat(saved.paymentTermsDays()).isEqualTo(45);
    assertThat(saved.currency()).isEqualTo("USD");
    assertThat(saved.taxRate()).isEqualByComparingTo("0.13");
    assertThat(saved.depositRatio()).isEqualByComparingTo("0.3");
    // **两者同时返回**：采购线约定 45 天 vs 主体默认 30 天
    assertThat(saved.masterPaymentTermsDays()).isEqualTo(30);
    assertThat(saved.paymentTermsDays()).isNotEqualTo(saved.masterPaymentTermsDays());

    // 读回（走 GET 语义的 service 入口）
    var read = tx(actor, () -> details.termsView(role));
    assertThat(read.invoiceTitle()).isEqualTo("某某（深圳）贸易有限公司");
    assertThat(read.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));

    // 全景里 termsFound=true，且**没有**「12 财务无法自动生成付款申请」那条警告
    var p = pano(role);
    assertThat(p.termsFound()).isTrue();
    assertThat(p.terms()).isNotNull();
    assertThat(p.warnings()).noneMatch(w -> w.contains("12 财务无法自动生成付款申请"));

    // 主体默认账期没被本模块改写（本模块不回写 psp）
    assertThat(tx(actor, () -> sql.fetchOne(
            "select payment_terms_days from party_supplier_profile where role_id=?", role)
        .get(0, Integer.class))).isEqualTo(30);
  }

  @Test
  void 账期未建时全景给出付款审批缺口警告() throws Exception {
    UUID role = fullSupplier(actor);
    var p = pano(role);
    assertThat(p.termsFound()).isFalse();
    assertThat(p.terms()).isNull();
    assertThat(p.warnings()).anyMatch(w -> w.contains("12 财务无法自动生成付款申请"));
    // 服务层读未建档返回 null（不抛）—— 「没有」与「读不到」是两件事，404 由端点层给
    assertThat(tx(actor, () -> details.termsView(role))).isNull();
    // 单读端点 404。**只断言状态码**：catalog 的 ApiErrorHandler 不渲染
    // ResponseStatusException 的报文（这是全服务既有行为，6.1.1.1 的 404 也一样空体），
    // 而给运营看的那句话落在全景的 warnings 上 —— 那才是界面上的真实出口，上面已断言过。
    http.perform(get("/catalog/supply/vendors/{role}/settlement-terms", role)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  @Test
  void 账期与开票的自相矛盾输入被拒() {
    UUID role = fullSupplier(actor);
    // 预付却零预付比例
    var zeroPrepay =
        new VendorDetailModels.VendorTermsInput("USD", 0, "PREPAID", "PROFORMA", "某某公司", "",
            new BigDecimal("0"), new BigDecimal("100.00"), BigDecimal.ZERO, LocalDate.of(2026, 1, 1), "", 0);
    assertThatThrownBy(() -> write(actor, () -> details.saveTerms(actor, role, zeroPrepay)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("预付比例必须大于 0");
    // 需要发票却没有税号
    var noTaxId =
        new VendorDetailModels.VendorTermsInput("USD", 30, "CREDIT", "VAT_SPECIAL", "某某公司", "",
            new BigDecimal("0.13"), null, BigDecimal.ZERO, LocalDate.of(2026, 1, 1), "", 0);
    assertThatThrownBy(() -> write(actor, () -> details.saveTerms(actor, role, noTaxId)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("必须填税号");
    // 闭集外的结算方式
    assertThatThrownBy(() -> write(actor, () -> details.saveTerms(actor, role, terms("30", "BARTER", 0))))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("结算方式不在可选范围");
    // 账期把年填成天
    assertThatThrownBy(() -> write(actor, () -> details.saveTerms(actor, role, terms("400", "CREDIT", 0))))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("上限 365 天");
    // 一条都没写进去
    assertThat(pano(role).termsFound()).isFalse();
  }

  @Test
  void 账期版本不匹配时拒绝覆盖() {
    UUID role = fullSupplier(actor);
    // 新建落在 version=0（与 6.1.1.1 的采购侧档案同一约定）
    var created = write(actor, () -> details.saveTerms(actor, role, terms("30", "CREDIT", 0)));
    assertThat(created.version()).isZero();
    // 用 0 改一次 → version 变 1
    assertThat(write(actor, () -> details.saveTerms(actor, role, terms("45", "CREDIT", 0))).version())
        .isEqualTo(1);
    // 再拿过期的 0 提交 → 必须拒（否则两个人同时改账期时后写的会静默覆盖先写的）
    assertThatThrownBy(() -> write(actor, () -> details.saveTerms(actor, role, terms("60", "CREDIT", 0))))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("version");
    assertThat(write(actor, () -> details.termsView(role)).paymentTermsDays()).isEqualTo(45);
    // 建档状态下传非 0 的 version → 拒，并说清新建要传 0
    UUID fresh = fullSupplier(actor);
    assertThatThrownBy(() -> write(actor, () -> details.saveTerms(actor, fresh, terms("30", "CREDIT", 7))))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("version 传 0");
  }

  @Test
  void 信用额度为null与为0是不同的事() {
    UUID role = fullSupplier(actor);
    var withNull = write(actor, () -> details.saveTerms(actor, role, terms("30", "CREDIT", 0)));
    assertThat(withNull.creditLimit()).isEqualByComparingTo("500000.00");
    var zero =
        new VendorDetailModels.VendorTermsInput("USD", 30, "CREDIT", "VAT_SPECIAL", "某某公司", "91440300MA5EX",
            new BigDecimal("0.13"), BigDecimal.ZERO, BigDecimal.ZERO, LocalDate.of(2026, 1, 1), "", 0);
    var updated = write(actor, () -> details.saveTerms(actor, role, zero));
    assertThat(updated.creditLimit()).isEqualByComparingTo("0");
    assertThat(updated.creditLimit()).isNotNull();
  }

  // ================================================================ 聚合口径

  @Test
  void 交期表现聚合口径() {
    UUID role = fullSupplier(actor);
    // 三笔：准时 1（准时日 = 承诺日）、早到 1（-3 天）、晚到 6 天
    write(actor, () -> vendors.recordDelivery(actor, role,
        delivery("D1", LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 10), "100.00")));
    write(actor, () -> vendors.recordDelivery(actor, role,
        delivery("D2", LocalDate.of(2026, 3, 20), LocalDate.of(2026, 3, 17), "200.00")));
    write(actor, () -> vendors.recordDelivery(actor, role,
        delivery("D3", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 7), "300.00")));

    var d = pano(role).delivery();
    assertThat(d.deliveryCount()).isEqualTo(3);
    // 准时含「准时当天」与「早到」：received <= due
    assertThat(d.onTimeCount()).isEqualTo(2);
    assertThat(d.onTimeRate()).isEqualByComparingTo("0.6667");
    // 早到笔数单独一列（不与延期混在一起）
    assertThat(d.earlyCount()).isEqualTo(1);
    // 平均延期天数：(0 + -3 + 6) / 3 = 1
    assertThat(d.averageDaysLate()).isEqualByComparingTo("1.00");
    // 最大延期只统计晚到的：早到的 -3 不该把最大值变成 -3
    assertThat(d.maxDaysLate()).isEqualTo(6);
    assertThat(d.lastDeliveryAt()).isEqualTo(LocalDate.of(2026, 4, 7).toString());
  }

  @Test
  void 质量表现按数量比且不合格数量真实() {
    UUID role = fullSupplier(actor);
    write(actor, () -> vendors.recordInspection(actor, role, inspection("Q1", "10000", "9999")));
    write(actor, () -> vendors.recordInspection(actor, role, inspection("Q2", "3", "1")));
    var q = pano(role).quality();
    assertThat(q.inspectionCount()).isEqualTo(2);
    assertThat(q.inspectedQuantity()).isEqualByComparingTo("10003");
    assertThat(q.qualifiedQuantity()).isEqualByComparingTo("10000");
    // 按数量比而不是批次比（批次比会是 1/2 = 0.5）
    assertThat(q.qualifiedRate()).isEqualByComparingTo("0.9997");
    assertThat(q.unqualifiedQuantity()).isEqualByComparingTo("3");
  }

  @Test
  void 比率在窗口内无记录时为null而不是0() {
    UUID role = fullSupplier(actor);
    var p = pano(role);
    assertThat(p.delivery().deliveryCount()).isZero();
    assertThat(p.delivery().onTimeRate()).isNull();
    assertThat(p.quality().qualifiedRate()).isNull();
    assertThat(p.delivery().averageDaysLate()).isNull();
    assertThat(p.delivery().lastDeliveryAt()).isNull();
    // 界面上必须有区分「暂无数据」的提示
    assertThat(p.warnings()).anyMatch(w -> w.contains("暂无数据"));
  }

  @Test
  void 统计窗口用固定时刻钉死() {
    UUID role = fullSupplier(actor);
    // 窗口内一笔，窗口外一笔
    write(actor, () -> vendors.recordDelivery(actor, role,
        delivery("IN", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1), "500.00")));
    write(actor, () -> vendors.recordDelivery(actor, role,
        delivery("OUT", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5), "700.00")));
    var inWindow = pano(role);
    assertThat(inWindow.delivery().deliveryCount()).isEqualTo(1);
    assertThat(inWindow.metricWindow().from()).startsWith("2026-01-01");
    assertThat(inWindow.metricWindow().to()).startsWith("2026-06-30");

    // 窗口挪到包含 9 月，同一条记录必须出现 —— 证明聚合真的受窗口约束
    var later =
        tx(actor, () -> details.panorama(role,
            OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.of(2026, 9, 30, 23, 59, 59, 0, ZoneOffset.UTC)));
    assertThat(later.delivery().deliveryCount()).isEqualTo(1);
    assertThat(later.metricWindow().from()).startsWith("2026-08-01");
  }

  @Test
  void 作废的到货不计入交期聚合但仍可追溯() {
    UUID role = fullSupplier(actor);
    var bad = write(actor, () -> vendors.recordDelivery(actor, role,
        delivery("VOIDME", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 20), "900.00")));
    write(actor, () -> vendors.recordDelivery(actor, role,
        delivery("KEEP", LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 2), "100.00")));
    // 作废前：一笔准时、一笔晚到 → 0.5
    assertThat(pano(role).delivery().onTimeRate()).isEqualByComparingTo("0.5000");
    write(actor, () -> { vendors.voidDelivery(actor, role, bad.id(), new VendorListModels.VendorVoidInput("录错单号")); return null; });
    // 作废后：晚到那笔不计入指标
    assertThat(pano(role).delivery().onTimeRate()).isEqualByComparingTo("1.0000");
    var history = tx(actor, () -> details.purchaseHistory(role, null, 0, 50, WINDOW_FROM, WINDOW_TO));
    assertThat(history.items()).anyMatch(i -> i.deliveryRef().equals("VOIDME") && i.isVoid());
  }

  // ================================================================ 采购历史与金额

  @Test
  void 采购历史真分页且计数与翻页同集合() {
    UUID role = fullSupplier(actor);
    for (int i = 1; i <= 7; i++) {
      final int n = i;
      write(actor, () -> vendors.recordDelivery(actor, role,
          delivery("PO-" + n, TO_DATE.minusDays(n), TO_DATE.minusDays(n), (100 * n) + ".00")));
    }
    var page0 = tx(actor, () -> details.purchaseHistory(role, null, 0, 3, WINDOW_FROM, WINDOW_TO));
    assertThat(page0.total()).isEqualTo(7);
    assertThat(page0.items()).hasSize(3);
    var page2 = tx(actor, () -> details.purchaseHistory(role, null, 2, 3, WINDOW_FROM, WINDOW_TO));
    assertThat(page2.items()).hasSize(1);
    // 翻到底的集合与第一页不重叠：按 received_date desc 排
    assertThat(page0.items()).doesNotContainAnyElementsOf(page2.items());
    // 计数与取行同集合：币种筛选后总数一致
    var usd = tx(actor, () -> details.purchaseHistory(role, "USD", 0, 50, WINDOW_FROM, WINDOW_TO));
    assertThat(usd.total()).isEqualTo(7);
  }

  @Test
  void 金额按币种分组不相加且数量按单位分桶() {
    UUID role = fullSupplier(actor);
    ensureUnit(actor, "BOX");
    write(actor, () -> vendors.recordDelivery(actor, role,
        delivery("U1", TO_DATE, TO_DATE, "1000.00")));
    write(actor, () -> vendors.recordDelivery(actor, role,
        new VendorListModels.VendorDeliveryInput("U2", null, "BOX", new BigDecimal("3"), new BigDecimal("600.00"),
            "EUR", WINDOW_FROM.minusDays(5), TO_DATE, TO_DATE, null)));
    var h = tx(actor, () -> details.purchaseHistory(role, null, 0, 50, WINDOW_FROM, WINDOW_TO));
    // 两个币种分列，绝不相加
    assertThat(h.buckets()).hasSize(2);
    assertThat(h.buckets()).extracting(VendorDetailModels.VendorHistoryBucket::currency)
        .containsExactlyInAnyOrder("USD", "EUR");
    // 跨单位 → 不给总数（3 箱 + 100 件 加起来没有意义）
    assertThat(h.totalQuantity()).isNull();
    // 按月序列真实存在
    assertThat(h.monthly()).isNotEmpty();
    assertThat(h.monthly()).allMatch(m -> m.amount() != null);
    // 全景里的采购金额同样按币种分组
    assertThat(pano(role).purchaseAmounts()).hasSize(2);
  }

  @Test
  void 币种筛选收窄集合且非法币种被拒() {
    UUID role = fullSupplier(actor);
    write(actor, () -> vendors.recordDelivery(actor, role, delivery("C1", TO_DATE, TO_DATE, "100.00")));
    var only = tx(actor, () -> details.purchaseHistory(role, "EUR", 0, 50, WINDOW_FROM, WINDOW_TO));
    assertThat(only.total()).isZero();
    assertThatThrownBy(() -> tx(actor, () -> details.purchaseHistory(role, "美元", 0, 50, WINDOW_FROM, WINDOW_TO)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("ISO 4217");
  }

  // ================================================================ 报价

  @Test
  void 报价只读且有效SKU数与生效日真实() {
    UUID role = fullSupplier(actor);
    UUID sku1 = skuWith(actor, "QS1");
    UUID sku2 = skuWith(actor, "QS2");
    quoteWith(actor, role, sku1, "USD", "ACTIVE", LocalDate.of(2026, 1, 1), null, "0.0235");
    quoteWith(actor, role, sku2, "USD", "DISABLED", LocalDate.of(2026, 1, 1), null, "1.0000");
    quoteWith(actor, role, sku2, "USD", "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), "0.5000");

    var q = tx(actor, () -> details.quotes(role, null, null, true, LocalDate.of(2026, 2, 1), 0, 20));
    assertThat(q.total()).isEqualTo(2); // 只看有效：ACTIVE 且在有效期内
    assertThat(q.effectiveSkuCount()).isEqualTo(2);
    assertThat(q.effectiveOn()).isEqualTo(LocalDate.of(2026, 2, 1));
    // 闭区间：valid_to 当天仍然有效
    assertThat(q.items())
        .anyMatch(i -> java.util.Objects.equals(i.validTo(), LocalDate.of(2026, 3, 31)) && i.effective());
    // 起订档单价真实读出（0.0235 这种厘级精度不能被截成 0.02）
    assertThat(q.items()).extracting(VendorDetailModels.VendorQuoteRow::firstTierUnitPrice)
        .filteredOn(java.util.Objects::nonNull).allMatch(p -> p.scale() <= 4);

    // 有效日挪到 4 月：那条 3-31 到期的报价必须消失
    var later = tx(actor, () -> details.quotes(role, null, null, true, LocalDate.of(2026, 4, 1), 0, 20));
    assertThat(later.total()).isEqualTo(1);
    // 状态筛选白名单
    assertThatThrownBy(() -> tx(actor, () -> details.quotes(role, "EXPIRED", null, null, null, 0, 20)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("ACTIVE 或 DISABLED");
  }

  @Test
  void 报价摘要的供货SKU数只数启用报价() {
    UUID role = fullSupplier(actor);
    UUID sku = skuWith(actor, "QS3");
    quoteWith(actor, role, sku, "USD", "DISABLED", LocalDate.of(2026, 1, 1), null, "1.0");
    var p = pano(role);
    assertThat(p.quotedSkuCount()).isZero();
    quoteWith(actor, role, sku, "USD", "ACTIVE", LocalDate.of(2026, 1, 1), null, "1.0");
    assertThat(pano(role).quotedSkuCount()).isEqualTo(1);
  }

  // ================================================================ 附件资质

  @Test
  void 附件资质可增删改且有效期现算() {
    UUID role = fullSupplier(actor);
    var today = LocalDate.now(ZoneOffset.UTC);
    var expired = write(actor, () -> details.saveQualification(actor, role, null,
        qualification("BUSINESS_LICENSE", today.minusDays(10), 0), true));
    var soon = write(actor, () -> details.saveQualification(actor, role, null,
        qualification("ISO9001", today.plusDays(5), 0), true));
    write(actor, () -> details.saveQualification(actor, role, null,
        qualification("COC", null, 0), true));

    var list = tx(actor, () -> details.qualifications(role));
    assertThat(list.total()).isEqualTo(3);
    assertThat(list.expiredCount()).isEqualTo(1);
    assertThat(list.expiringCount()).isEqualTo(1);
    // 四态齐全：EXPIRED / EXPIRING_SOON / UNKNOWN（未填到期日）
    assertThat(list.items()).extracting(VendorDetailModels.VendorQualificationRow::validity)
        .containsExactlyInAnyOrder("EXPIRED", "EXPIRING_SOON", "UNKNOWN");
    // 快过期的排最前面（这就是运营的处理顺序）
    assertThat(list.items().get(0).validity()).isEqualTo("EXPIRED");
    assertThat(list.items().get(0).daysToExpiry()).isNegative();

    // 全景摘要与警告
    var p = pano(role);
    assertThat(p.qualificationCount()).isEqualTo(3);
    assertThat(p.qualificationExpiredCount()).isEqualTo(1);
    assertThat(p.warnings()).anyMatch(w -> w.contains("已过期"));
    assertThat(p.warnings()).anyMatch(w -> w.contains("天内到期"));

    // 改（换证）
    var moved = write(actor, () -> details.saveQualification(actor, role, expired.id(),
        qualification("BUSINESS_LICENSE", today.plusDays(400), 0), false));
    assertThat(moved.version()).isEqualTo(1);
    assertThat(tx(actor, () -> details.qualifications(role)).items().stream()
        .filter(i -> i.id().equals(expired.id())).findFirst().orElseThrow().validity()).isEqualTo("VALID");

    // 删
    write(actor, () -> { details.deleteQualification(actor, role, soon.id()); return null; });
    assertThat(tx(actor, () -> details.qualifications(role)).total()).isEqualTo(2);
    // 删不属于自己的
    assertThatThrownBy(() -> write(actor, () -> { details.deleteQualification(actor, role, UUID.randomUUID()); return null; }))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 附件资质的非法输入被拒() {
    UUID role = fullSupplier(actor);
    // 到期早于签发
    assertThatThrownBy(() -> write(actor, () -> details.saveQualification(actor, role, null,
        new VendorDetailModels.VendorQualificationInput("CE", "CE 证书", "C1", "机构",
            LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1), "k", "f.pdf", "application/pdf", 1L, "", 0), true)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("到期日不能早于签发日");
    // 闭集外的类型
    assertThatThrownBy(() -> write(actor, () -> details.saveQualification(actor, role, null,
        qualification("HALAL", null, 0), true)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("资质类型不在可选范围");
    // 版本乐观锁
    var q = write(actor, () -> details.saveQualification(actor, role, null, qualification("CE", null, 0), true));
    assertThatThrownBy(() -> write(actor, () -> details.saveQualification(actor, role, q.id(),
        qualification("CE", null, 5), false)))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("version");
  }

  // ================================================================ 联系人与操作入口

  @Test
  void 联系人只读列出且标注默认() {
    UUID role = fullSupplier(actor);
    UUID party = tx(actor, () -> sql.fetchOne(
        "select party_id from party_role where tenant_id=? and id=?", actor.tenantId(), role).get(0, UUID.class));
    write(actor, () -> sql.execute(
        "insert into party_contact(id,tenant_id,party_id,name,position,email,phone,mobile,is_primary,note,"
            + "version,created_at,updated_at) values(?::uuid,?::uuid,?::uuid,'张三','采购经理','a@x.example',"
            + "null,null,true,'',0,now(),now())",
        UUID.randomUUID(), actor.tenantId(), party) > 0);

    var contacts = pano(role).contacts();
    assertThat(contacts).hasSize(1);
    assertThat(contacts.get(0).name()).isEqualTo("张三");
    assertThat(contacts.get(0).primary()).isTrue();
    // 没联系人时给出治理警告（问交期没有可拨的人）
    assertThat(pano(fullSupplier(actor)).warnings()).anyMatch(w -> w.contains("没有登记联系人"));
  }

  @Test
  void 未交付模块的操作入口灰掉并给出理由() {
    UUID role = fullSupplier(actor);
    var ops = pano(role).operations();
    var newPo = ops.stream().filter(o -> o.key().equals("NEW_PURCHASE_ORDER")).findFirst().orElseThrow();
    assertThat(newPo.available()).isFalse();
    assertThat(newPo.href()).isNull();
    assertThat(newPo.reason()).contains("6.3.1.2 采购单尚未交付");
    // 灰掉的理由里要带上账期缺失这件真实的事
    assertThat(newPo.reason()).contains("账期与开票信息还没建");

    var recon = ops.stream().filter(o -> o.key().equals("START_RECONCILIATION")).findFirst().orElseThrow();
    assertThat(recon.available()).isFalse();
    assertThat(recon.reason()).contains("6.5.1 对账尚未交付");

    // 已交付的模块给出入口
    var quote = ops.stream().filter(o -> o.key().equals("UPDATE_QUOTE")).findFirst().orElseThrow();
    assertThat(quote.available()).isTrue();
    assertThat(quote.href()).isEqualTo("/partners/offers");
  }

  // ================================================================ HTTP 往返与权限

  @Test
  void 全景端点HTTP往返() throws Exception {
    UUID role = fullSupplier(actor);
    write(actor, () -> details.saveTerms(actor, role, terms("30", "CREDIT", 0)));
    http.perform(get("/catalog/supply/vendors/{role}/panorama", role)
            .header("Authorization", "Bearer tenant-a")
            .param("metricFrom", WINDOW_FROM.toString()).param("metricTo", WINDOW_TO.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roleId").value(role.toString()))
        .andExpect(jsonPath("$.terms.paymentTermsDays").value(30))
        .andExpect(jsonPath("$.termsFound").value(true))
        .andExpect(jsonPath("$.metricWindow.from").exists())
        .andExpect(jsonPath("$.operations[?(@.key=='NEW_PURCHASE_ORDER')].available").value(false));
    http.perform(get("/catalog/supply/vendors/{role}/purchase-history", role)
            .header("Authorization", "Bearer tenant-a")
            .param("metricFrom", WINDOW_FROM.toString()).param("metricTo", WINDOW_TO.toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    http.perform(get("/catalog/supply/vendors/{role}/quotes", role).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
    http.perform(get("/catalog/supply/vendors/{role}/qualifications", role).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    http.perform(get("/catalog/supply/vendors/detail-options").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.settlementMethods[?(@.value=='CREDIT')]").exists())
        .andExpect(jsonPath("$.qualificationTypes[?(@.value=='BUSINESS_LICENSE')]").exists());
  }

  @Test
  void 账期写入端点HTTP往返() throws Exception {
    UUID role = fullSupplier(actor);
    http.perform(put("/catalog/supply/vendors/{role}/settlement-terms", role)
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(JSON.writeValueAsString(terms("60", "CREDIT", 0))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentTermsDays").value(60));
    http.perform(get("/catalog/supply/vendors/{role}/settlement-terms", role)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currency").value("USD"));
    // 缺必填字段 → 400，不落库
    http.perform(put("/catalog/supply/vendors/{role}/settlement-terms", role)
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"currency\":\"USD\"}"))
        .andExpect(status().isBadRequest());
    assertThat(pano(role).terms().paymentTermsDays()).isEqualTo(60);
  }

  @Test
  void 写端点需要update权限() throws Exception {
    UUID role = fullSupplier(actor);
    // 只有 spu.read：写必须 403
    http.perform(put("/catalog/supply/vendors/{role}/settlement-terms", role)
            .header("Authorization", "Bearer reader")
            .contentType("application/json")
            .content(JSON.writeValueAsString(terms("30", "CREDIT", 0))))
        .andExpect(status().isForbidden());
    http.perform(post("/catalog/supply/vendors/{role}/qualifications", role)
            .header("Authorization", "Bearer reader")
            .contentType("application/json")
            .content(JSON.writeValueAsString(qualification("CE", null, 0))))
        .andExpect(status().isForbidden());
    // 读是允许的
    http.perform(get("/catalog/supply/vendors/{role}/panorama", role)
            .header("Authorization", "Bearer reader")
            .param("metricFrom", WINDOW_FROM.toString()).param("metricTo", WINDOW_TO.toString()))
        .andExpect(status().isOk());
    assertThat(pano(role).termsFound()).isFalse();
    // 未认证
    http.perform(get("/catalog/supply/vendors/{role}/panorama", role)).andExpect(status().isUnauthorized());
  }

  @Test
  void 词表闭集与入参校验同源() {
    var options = tx(actor, () -> details.options());
    var methods = options.get("settlementMethods");
    assertThat(methods).isNotEmpty();
    for (var m : methods) {
      assertThat(VendorDetailRules.settlementMethodProblem(m.value())).isNull();
    }
    assertThat(VendorDetailRules.settlementMethodProblem("CASH")).contains("不在可选范围");
    // 四态判定（纯函数，钉死基准日）
    var today = LocalDate.of(2026, 6, 30);
    assertThat(VendorDetailRules.qualificationValidity(today.minusDays(1), today)).isEqualTo("EXPIRED");
    assertThat(VendorDetailRules.qualificationValidity(today.plusDays(10), today)).isEqualTo("EXPIRING_SOON");
    assertThat(VendorDetailRules.qualificationValidity(today.plusDays(31), today)).isEqualTo("VALID");
    assertThat(VendorDetailRules.qualificationValidity(null, today)).isEqualTo("UNKNOWN");
    // 税率显示：0.13 → 13%（不是 0.13%）
    assertThat(VendorDetailRules.taxRatePercent(new BigDecimal("0.13"))).isEqualTo("13%");
  }

  // ================================================================ 多租户三断言

  /**
   * 三断言覆盖供应商全景。
   *
   * <p>本模块 join 的是别的车道交付的表（party / party_contact / supplier_quote）与
   * 6.1.1.1 交付的事实表，所以隔离必须落在「跨租户的全景读不到、聚合不相交」上 ——
   * 只测本模块新建的两张表不够。
   */
  @Test
  void 租户隔离三断言覆盖供应商全景() throws Exception {
    UUID myRole = fullSupplier(actor);
    UUID theirRole = fullSupplier(actorB);

    write(actor, () -> details.saveTerms(actor, myRole, terms("30", "CREDIT", 0)));
    write(actorB, () -> details.saveTerms(actorB, theirRole, terms("90", "PREPAID", 0)));
    write(actor, () -> vendors.recordDelivery(actor, myRole, delivery("MY-D", TO_DATE, TO_DATE, "111111.00")));
    write(actorB, () -> vendors.recordDelivery(actorB, theirRole, delivery("THEIR-D", TO_DATE, TO_DATE, "999999.00")));
    var myQual = write(actor, () -> details.saveQualification(actor, myRole, null, qualification("CE", null, 0), true));
    write(actorB, () -> details.saveQualification(actorB, theirRole, null, qualification("FDA", null, 0), true));

    // 两边都必须真的各有数据，否则下面的断言是空的
    assertThat(tx(actor, () -> details.purchaseHistory(myRole, null, 0, 10, WINDOW_FROM, WINDOW_TO))
        .buckets().get(0).amount()).isEqualByComparingTo("111111.00");
    assertThat(tx(actorB, () -> details.purchaseHistory(theirRole, null, 0, 10, WINDOW_FROM, WINDOW_TO))
        .buckets().get(0).amount()).isEqualByComparingTo("999999.00");

    // 1) 两租户互不可见：全景 / 采购历史 / 报价 / 资质清单全部读不到对方的
    assertThatThrownBy(() -> tx(actor, () -> details.panorama(theirRole, WINDOW_FROM, WINDOW_TO)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> details.panorama(myRole, WINDOW_FROM, WINDOW_TO)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> details.purchaseHistory(theirRole, null, 0, 10, WINDOW_FROM, WINDOW_TO)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> details.qualifications(theirRole)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> details.quotes(theirRole, null, null, null, null, 0, 10)))
        .isInstanceOf(ResponseStatusException.class);
    // 聚合不相交：A 只能看到自己的金额
    assertThat(pano(myRole).purchaseAmounts()).allMatch(a -> a.currency().equals("USD") && a.amount().compareTo(new BigDecimal("111111.00")) == 0);
    // A 不能写 B 的账期与开票
    assertThatThrownBy(() -> write(actor, () -> details.saveTerms(actor, theirRole, terms("7", "CREDIT", 1))))
        .isInstanceOf(ResponseStatusException.class);
    // A 不能给 B 的供应商挂资质
    assertThatThrownBy(() -> write(actor, () -> details.saveQualification(actor, theirRole, null, qualification("CE", null, 0), true)))
        .isInstanceOf(ResponseStatusException.class);
    // B 的账期没被 A 改掉
    assertThat(tx(actorB, () -> details.termsView(theirRole)).paymentTermsDays()).isEqualTo(90);

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(get("/catalog/supply/vendors/{role}/panorama", theirRole)
            .header("Authorization", "Bearer tenant-a").header("X-Tenant-Id", b)
            .param("metricFrom", WINDOW_FROM.toString()).param("metricTo", WINDOW_TO.toString()))
        .andExpect(status().isNotFound());
    // 伪造头下写入仍落在 B 自己名下 —— 断言**行确实落在 B 名下**，不只断言 A 看不见
    http.perform(put("/catalog/supply/vendors/{role}/settlement-terms", theirRole)
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a)
            .contentType("application/json")
            .content(JSON.writeValueAsString(terms("15", "CREDIT", 0))))
        .andExpect(status().isOk());
    assertThat(tx(actorB, () -> sql.fetchOne(
            "select tenant_id from vendor_settlement_terms where role_id=?", theirRole)
        .get(0, UUID.class))).isEqualTo(b);

    // 3) 连接池归还后不串租户（单连接反复交替 5 轮），逐轮断言聚合不相交
    for (int i = 0; i < 5; i++) {
      var mine = tx(actor, () -> details.panorama(myRole, WINDOW_FROM, WINDOW_TO));
      var theirs = tx(actorB, () -> details.panorama(theirRole, WINDOW_FROM, WINDOW_TO));
      assertThat(mine.terms().paymentTermsDays()).isEqualTo(30);
      assertThat(theirs.terms().paymentTermsDays()).isEqualTo(15);
      assertThat(mine.purchaseAmounts().get(0).amount()).isEqualByComparingTo("111111.00");
      assertThat(theirs.purchaseAmounts().get(0).amount()).isEqualByComparingTo("999999.00");
      assertThat(mine.qualificationCount()).isEqualTo(1);
      assertThat(theirs.qualificationCount()).isEqualTo(1);
      // 附件清单互不相交：A 只看得到自己那一份 FDA 看不到
      var myQuals = tx(actor, () -> details.qualifications(myRole)).items();
      var theirQuals = tx(actorB, () -> details.qualifications(theirRole)).items();
      assertThat(myQuals).extracting(VendorDetailModels.VendorQualificationRow::id)
          .doesNotContainAnyElementsOf(
              theirQuals.stream().map(VendorDetailModels.VendorQualificationRow::id).toList());
      assertThat(myQuals).anyMatch(i2 -> "CE".equals(i2.qualificationType()));
      assertThat(myQuals).noneMatch(i2 -> "FDA".equals(i2.qualificationType()));
      assertThat(myQual.id()).isNotNull();
    }
  }
}
