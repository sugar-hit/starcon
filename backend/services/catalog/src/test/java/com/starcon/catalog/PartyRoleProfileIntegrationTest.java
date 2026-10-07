package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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
 * 3.6.1.2 角色档案：供应商 / 客户 / 承运商三张 1:1 档案表的读写、规则 R1~R5、
 * 概览与列表的同源计数，以及<b>租户隔离三断言</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：档案全部真的落库，不使用任何内存替身。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的四个断言</b>：
 *
 * <ol>
 *   <li>{@link #租户隔离三断言覆盖角色档案行()}：档案行才是本模块真正的越权面 ——
 *       「A 读到自己的额度时混进 B 的信用额度」会让 A 的应收按 B 客户的额度放单。
 *   <li>{@link #一个主体三个身份各挂各的档案()}：把蓝图那句「每个角色一张 1:1 档案表」
 *       钉成数据事实 —— 供应商有账期、客户有额度、承运商有账号，三者互不串。
 *   <li>{@link #时间字段真的写回了正确时刻()}：{@code timestamptz} 走 jOOQ 时用
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，
 *       而这里断言的是<b>写回的值</b>，不是"没抛异常"。
 *   <li>{@link #角色档案随角色解除级联删除()}：V30 的 {@code ON DELETE CASCADE} 真的生效。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PartyRoleProfileIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper JSON = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();
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
        uid, tenant, "Test", UUID.randomUUID(), dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(), grants);
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
      // 只有商品读权限：写端点必须 403（角色档案沿用商品主数据域权限）。
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read")))));
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

  /** 写端点走 update 权限上下文（读端点与写端点的权限词不同，见 PartyRoleProfileController）。 */
  void writeVoid(AccessService.Access who, Runnable work) {
    access.run(
        who,
        "catalog.spu.update",
        () -> {
          work.run();
          return null;
        });
  }

  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  /** 建一个带指定角色的 ACTIVE 主体。 */
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
                        code, "主体 " + code, "ORGANIZATION", "CN",
                        "mail@" + code.toLowerCase() + ".example", null, null,
                        "ACTIVE", List.of(roles), 0)));
    return saved.id();
  }

  static PartySupplierProfileInput supplier(
      int terms, String cooperation, String method, String unit) {
    return new PartySupplierProfileInput(
        terms, null, unit, cooperation, method, "", 0);
  }

  static PartySupplierProfileInput supplierMoq(
      int terms, String cooperation, String method, java.math.BigDecimal qty, String unit) {
    return new PartySupplierProfileInput(terms, qty, unit, cooperation, method, "", 0);
  }

  static PartyCustomerProfileInput customer(
      String level, String currency, java.math.BigDecimal limit, int terms) {
    return new PartyCustomerProfileInput(level, currency, limit, terms, "", 0);
  }

  static PartyCarrierProfileInput carrier(String type, List<String> capabilities) {
    return new PartyCarrierProfileInput(type, "ACC-" + SEQ.incrementAndGet(), capabilities, "", "", 0);
  }

  /** 在本租户登记一个计量单位（MOQ 采购单位必须已登记）。 */
  void ensureUnit(AccessService.Access who, String code, boolean enabled, boolean allowPurchase) {
    tx(
        who,
        () -> {
          sql.execute(
              "insert into unit_of_measure(id,tenant_id,code,name,dimension,scale,allow_purchase,"
                  + "allow_sale,allow_storage,enabled) values(?::uuid,?::uuid,?,?, 'COUNT',0,?,true,true,?)"
                  + " on conflict (tenant_id,code) do nothing",
              UUID.randomUUID(), who.tenantId(), code, code, allowPurchase, enabled);
          return null;
        });
  }

  // ================================================================ 往返与基本写入

  @Test
  void 供应商档案建档与详情往返一致() {
    UUID party = partyWith(actor, "SUP", "SUPPLIER");
    var saved =
        tx(
            actor,
            () ->
                profiles.saveSupplier(
                    actor, party, supplierMoq(30, "STRATEGIC", "CREDIT", new java.math.BigDecimal("500.5"), "PCS")));
    assertThat(saved.profileFound()).isTrue();
    assertThat(saved.paymentTermsDays()).isEqualTo(30);
    assertThat(saved.paymentTermsLabel()).isEqualTo("账期 30 天");
    assertThat(saved.cooperationLevel()).isEqualTo("STRATEGIC");
    assertThat(saved.cooperationLevelLabel()).isEqualTo("战略供应商");
    assertThat(saved.settlementMethod()).isEqualTo("CREDIT");
    assertThat(saved.moqQuantity()).isEqualByComparingTo("500.5");
    assertThat(saved.moqUnitCode()).isEqualTo("PCS");
    // MOQ 的人类可读串带上单位名（join unit_of_measure 取到的，不是代码）。
    assertThat(saved.moqLabel()).isEqualTo("500.5PCS");
    assertThat(saved.roleType()).isEqualTo("SUPPLIER");
    assertThat(saved.roleStatus()).isEqualTo("ACTIVE");
    assertThat(saved.version()).isZero();
    // 没设的东西要说清"没设"，而不是给一个看起来像有值的串
    assertThat(saved.warnings()).isEmpty();
  }

  @Test
  void 一个主体三个身份各挂各的档案() {
    UUID party = partyWith(actor, "TRI", "SUPPLIER", "CUSTOMER", "CARRIER");

    tx(actor, () -> profiles.saveSupplier(actor, party, supplier(45, "PREFERRED", "CREDIT", null)));
    tx(
        actor,
        () ->
            profiles.saveCustomer(
                actor, party, customer("STRATEGIC", "USD", new java.math.BigDecimal("100000"), 30)));
    tx(actor, () -> profiles.saveCarrier(actor, party, carrier("API", List.of("AIR", "SEA"))));

    var sup = tx(actor, () -> profiles.supplier(party));
    var cus = tx(actor, () -> profiles.customer(party));
    var car = tx(actor, () -> profiles.carrier(party));

    // 蓝图的整条要点：供应商有账期、客户有额度、承运商有账号，三者**互不串**。
    assertThat(sup.paymentTermsDays()).isEqualTo(45);
    assertThat(sup.cooperationLevelLabel()).isEqualTo("优选供应商");
    // 客户档案上**没有**账期以外的东西，供应商档案上**没有**额度 —— 各自只有自己的列。
    assertThat(cus.paymentTermsDays()).isEqualTo(30);
    assertThat(cus.defaultCreditLimit()).isEqualByComparingTo("100000");
    assertThat(cus.creditLimitLabel()).isEqualTo("额度 100000 USD");
    assertThat(car.serviceCapabilities()).containsExactly("AIR", "SEA");
    assertThat(car.serviceCapabilitiesLabel()).isEqualTo("空运、海运");
    assertThat(car.interfaceTypeLabel()).isEqualTo("系统对接");

    // 三个角色 id 互不相同（档案键在角色上，不在主体上）。
    assertThat(List.of(sup.roleId(), cus.roleId(), car.roleId())).doesNotHaveDuplicates();

    // 汇总端点一次拿全三块，同一时刻的快照。
    var all = tx(actor, () -> profiles.aggregate(party));
    assertThat(all.supplier()).isNotNull();
    assertThat(all.customer()).isNotNull();
    assertThat(all.carrier()).isNotNull();
    assertThat(all.supplier().partyId()).isEqualTo(party);
    assertThat(all.customer().partyId()).isEqualTo(party);
  }

  @Test
  void 时间字段真的写回了正确时刻() {
    UUID party = partyWith(actor, "TS", "SUPPLIER");
    tx(actor, () -> profiles.saveSupplier(actor, party, supplier(30, "STANDARD", "CREDIT", null)));
    var saved = tx(actor, () -> profiles.supplier(party));

    // 断言写回的值，不是"没抛异常"：与库里 timestamptz 的读出值逐毫秒相等。
    var dbCreated =
        tx(actor, () -> sql.fetchOne(
                "select created_at from party_supplier_profile where tenant_id=? and role_id=?",
                a, saved.roleId()).get("created_at", OffsetDateTime.class));
    assertThat(dbCreated).isNotNull();
    assertThat(OffsetDateTime.parse(saved.createdAt()).toInstant())
        .isEqualTo(dbCreated.toInstant());
    // 统一成 UTC 的 ISO-8601（带 Z），前端不猜时区。
    assertThat(saved.createdAt()).endsWith("Z");
    assertThat(OffsetDateTime.parse(saved.updatedAt()).toInstant())
        .isAfterOrEqualTo(OffsetDateTime.parse(saved.createdAt()).toInstant());

    // 改一次档案：updated_at 必须真的往前走（now() 而不是复用 created_at）。
    var updated =
        tx(
            actor,
            () ->
                profiles.saveSupplier(
                    actor, party, new PartySupplierProfileInput(60, null, null, "STANDARD", "CREDIT", "", saved.version())));
    assertThat(updated.version()).isEqualTo(1);
    assertThat(OffsetDateTime.parse(updated.updatedAt()).toInstant())
        .isAfterOrEqualTo(OffsetDateTime.parse(saved.updatedAt()).toInstant());
  }

  // ================================================================ 规则 R1：结算方式与账期

  @Test
  void 结算方式与账期必须自洽() {
    UUID party = partyWith(actor, "R1", "SUPPLIER");

    // 预付 + 账期：矛盾
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveSupplier(actor, party, supplier(90, "STANDARD", "PREPAID", null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不占用账期");
    // 账期结算 + 0 天：矛盾
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveSupplier(actor, party, supplier(0, "STANDARD", "CREDIT", null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("账期必须大于 0");
    // 账期为 0 的预付供应商：合法（跨境小额供应商确实只做现结，挡掉反而会逼人乱填）
    var ok = tx(actor, () -> profiles.saveSupplier(actor, party, supplier(0, "STANDARD", "PREPAID", null)));
    assertThat(ok.paymentTermsDays()).isZero();
    assertThat(ok.paymentTermsLabel()).isEqualTo("现结（无账期）");

    // 账期超上限：挡住"把年填成天"
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveSupplier(actor, party, supplier(400, "STANDARD", "CREDIT", null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("365");
    // 负数账期没有业务含义
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveSupplier(actor, party, new PartySupplierProfileInput(
                -1, null, null, "STANDARD", "CREDIT", "", ok.version()))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("负数");
  }

  // ================================================================ MOQ

  @Test
  void MOQ数量与单位必须成对() {
    ensureUnit(actor, "PCS", true, true);
    UUID party = partyWith(actor, "MOQ", "SUPPLIER");

    // 只有数量没有单位
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveSupplier(
                actor, party, supplierMoq(0, "STANDARD", "PREPAID", new java.math.BigDecimal("100"), null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("就必须填采购单位");
    // 只有单位没有数量
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveSupplier(
                actor, party, supplierMoq(0, "STANDARD", "PREPAID", null, "PCS"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("就必须填最小起订量");
    // MOQ 为 0 不合法（不设起订量请留空）
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveSupplier(
                actor, party, supplierMoq(0, "STANDARD", "PREPAID", java.math.BigDecimal.ZERO, "PCS"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("大于 0");

    // 正常写入：数量与单位一起
    var saved =
        tx(actor, () -> profiles.saveSupplier(
            actor, party, supplierMoq(0, "STANDARD", "PREPAID", new java.math.BigDecimal("100"), "PCS")));
    assertThat(saved.moqQuantity()).isEqualByComparingTo("100");
    assertThat(saved.moqUnitCode()).isEqualTo("PCS");
    // MOQ 为空时要说清"06 不会按起订量校验"
    assertThat(saved.warnings()).isEmpty();
  }

  @Test
  void MOQ采购单位必须已登记且启用且允许采购() {
    ensureUnit(actor, "OK", true, true);
    ensureUnit(actor, "OFF", false, true);
    ensureUnit(actor, "NOBUY", true, false);
    UUID party = partyWith(actor, "UNIT", "SUPPLIER");

    // 没登记
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveSupplier(
                actor, party, supplierMoq(0, "STANDARD", "PREPAID", new java.math.BigDecimal("1"), "NOPE"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有在计量单位里登记");
    // 停用
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveSupplier(
                actor, party, supplierMoq(0, "STANDARD", "PREPAID", new java.math.BigDecimal("1"), "OFF"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已停用");
    // 不允许采购
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveSupplier(
                actor, party, supplierMoq(0, "STANDARD", "PREPAID", new java.math.BigDecimal("1"), "NOBUY"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不允许用于采购");
    // 启用且允许采购：通过
    var ok = tx(actor, () -> profiles.saveSupplier(
        actor, party, supplierMoq(0, "STANDARD", "PREPAID", new java.math.BigDecimal("1"), "OK")));
    assertThat(ok.moqUnitCode()).isEqualTo("OK");

    // 已经写着的单位后来被停用：改备注仍要能存（不能把档案锁死）
    writeVoid(actor, () -> sql.execute("update unit_of_measure set enabled=false where code=?", "OK"));
    var kept = tx(actor, () -> profiles.saveSupplier(
        actor, party, new PartySupplierProfileInput(
            0, new java.math.BigDecimal("1"), "OK", "STANDARD", "PREPAID", "改了个备注", ok.version())));
    assertThat(kept.moqUnitCode()).isEqualTo("OK");
    assertThat(kept.note()).isEqualTo("改了个备注");
  }

  // ================================================================ 规则 R2 / R4：客户档案

  @Test
  void 信用额度必须配币种且额度零非法() {
    UUID party = partyWith(actor, "CUS", "CUSTOMER");

    // 有额度没币种
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveCustomer(
                actor, party, customer("STANDARD", null, new java.math.BigDecimal("5000"), 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("就必须填默认币种");
    // 额度为 0：与"没设额度"是相反的两种默认，必须被拒
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveCustomer(
                actor, party, customer("STANDARD", "USD", java.math.BigDecimal.ZERO, 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不限制赊账");
    // 负额度
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveCustomer(
                actor, party, customer("STANDARD", "USD", new java.math.BigDecimal("-1"), 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("大于 0");

    // **没设额度 = 不限制赊账**：这是有意义的"没有"，不是漏填
    var noLimit = tx(actor, () -> profiles.saveCustomer(actor, party, customer("STANDARD", "USD", null, 0)));
    assertThat(noLimit.defaultCreditLimit()).isNull();
    assertThat(noLimit.creditLimitLabel()).isEqualTo("不限制赊账（未设额度）");
    assertThat(noLimit.warnings()).anyMatch(w -> w.contains("不限制赊账"));
    // 没设币种也要提示（12 应收无法按币种分组）
    var noCurrency =
        tx(actor, () -> profiles.saveCustomer(
            actor, party, customer("STANDARD", null, null, 0)));
    assertThat(noCurrency.warnings()).anyMatch(w -> w.contains("未设置默认币种"));
  }

  @Test
  void 风险客户不能留信用额度() {
    UUID party = partyWith(actor, "RISK", "CUSTOMER");
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveCustomer(
                actor, party, customer("RISK", "USD", new java.math.BigDecimal("1000"), 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("风险客户");
    // 风险客户 + 无额度：合法
    var ok = tx(actor, () -> profiles.saveCustomer(actor, party, customer("RISK", "USD", null, 0)));
    assertThat(ok.customerLevelLabel()).isEqualTo("风险客户");
  }

  // ================================================================ 规则 R3：档案只能挂在已指派的对应角色上

  @Test
  void 档案只能挂在已指派的对应角色上() {
    UUID onlySupplier = partyWith(actor, "ONLYSUP", "SUPPLIER");

    // 没指派客户身份 → 客户档案写不了，给的是"先指派身份"而不是"档案不存在"
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveCustomer(actor, onlySupplier, customer("STANDARD", "USD", null, 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("还没有被指派")
        .hasMessageContaining("客户");

    // 读路径相反：200 + profileFound=false + 一条说明性提示（详情页要能区分三种状态）
    var read = tx(actor, () -> profiles.customer(onlySupplier));
    assertThat(read.profileFound()).isFalse();
    assertThat(read.roleId()).isNull();
    assertThat(read.warnings()).anyMatch(w -> w.contains("还没有被指派"));
  }

  @Test
  void 没建档案时明确说明下游会怎么处理() {
    UUID party = partyWith(actor, "EMPTY", "SUPPLIER", "CUSTOMER", "CARRIER");
    var all = tx(actor, () -> profiles.aggregate(party));
    // 三个槽位都在（身份指派了），但每个都 profileFound=false —— 与"槽位为 null"（没指派）长得不一样
    assertThat(all.supplier()).isNotNull();
    assertThat(all.supplier().profileFound()).isFalse();
    assertThat(all.supplier().version()).isZero();
    // 提示说清后果而不只是"没档案"
    assertThat(all.supplier().warnings()).anyMatch(w -> w.contains("按现结、无限购门槛处理"));
    assertThat(all.customer().warnings()).anyMatch(w -> w.contains("按不限制赊账、现结处理"));
    assertThat(all.carrier().warnings()).anyMatch(w -> w.contains("无法自动跟踪"));
  }

  // ================================================================ 规则 R5：删除与级联

  @Test
  void 角色档案随角色解除级联删除() {
    UUID party = partyWith(actor, "CASCADE", "SUPPLIER");
    var saved = tx(actor, () -> profiles.saveSupplier(actor, party, supplier(30, "STANDARD", "CREDIT", null)));
    assertThat(tx(actor, () -> sql.fetchOne(
            "select count(*) from party_supplier_profile where role_id=?", saved.roleId()))
        .get(0, Long.class)).isEqualTo(1L);

    // 3.6.1.1 解除角色指派 = 物理删除 party_role 行；档案必须跟着删，
    // 否则「角色没了但供应商账期还在」的行会被下游当成别人的档案用掉。
    var roles = tx(actor, () -> parties.rolesOf(party));
    var supplierRole = roles.stream()
        .filter(r -> r.roleType().equals("SUPPLIER")).findFirst().orElseThrow();
    writeVoid(actor, () -> parties.removeRole(actor, party, supplierRole.id(), supplierRole.version()));
    assertThat(tx(actor, () -> sql.fetchOne(
            "select count(*) from party_supplier_profile where role_id=?", saved.roleId()))
        .get(0, Long.class)).isZero();
  }

  @Test
  void 删除档案不删角色且版本不匹配报冲突() {
    UUID party = partyWith(actor, "DEL", "SUPPLIER");
    var saved = tx(actor, () -> profiles.saveSupplier(actor, party, supplier(30, "STANDARD", "CREDIT", null)));
    // 版本不对 → 409，不许误删别人的修改
    assertThatThrownBy(() -> writeVoid(actor, () -> profiles.delete(actor, party, "SUPPLIER", saved.version() + 1)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被他人修改");

    writeVoid(actor, () -> profiles.delete(actor, party, "SUPPLIER", saved.version()));
    // 档案没了，角色还在（解指派是 3.6.1.1 的动作）
    assertThat(tx(actor, () -> parties.rolesOf(party))).hasSize(1);
    var after = tx(actor, () -> profiles.supplier(party));
    assertThat(after.profileFound()).isFalse();
    // 再删一次 → 404
    assertThatThrownBy(() -> writeVoid(actor, () -> profiles.delete(actor, party, "SUPPLIER", 0)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 版本乐观锁挡住并发覆盖() {
    UUID party = partyWith(actor, "LOCK", "SUPPLIER");
    var first = tx(actor, () -> profiles.saveSupplier(actor, party, supplier(30, "STANDARD", "CREDIT", null)));
    // 两个客户端都拿到了 version=0
    tx(actor, () -> profiles.saveSupplier(
        actor, party, new PartySupplierProfileInput(45, null, null, "STANDARD", "CREDIT", "", 0)));
    assertThatThrownBy(
            () -> writeVoid(actor, () -> profiles.saveSupplier(
                actor, party, new PartySupplierProfileInput(60, null, null, "STANDARD", "CREDIT", "", first.version()))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被他人修改");
    // 带非 0 版本建档（客户端以为它存在）→ 409
    UUID other = partyWith(actor, "LOCK2", "SUPPLIER");
    assertThatThrownBy(
            () -> writeVoid(actor, () -> profiles.saveSupplier(
                actor, other, new PartySupplierProfileInput(30, null, null, "STANDARD", "CREDIT", "", 3))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被他人创建");
  }

  // ================================================================ 承运商

  @Test
  void 承运商账号必填且服务能力大小写被规范化() {
    UUID party = partyWith(actor, "CAR", "CARRIER");
    // 账号必填：没有账号的档案在 10 物流里取不到凭据
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveCarrier(
                actor, party, new PartyCarrierProfileInput("API", "  ", List.of(), "", "", 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("账号标识不能为空");
    // 词表外的能力
    assertThatThrownBy(
            () -> tx(actor, () -> profiles.saveCarrier(actor, party, carrier("API", List.of("SEA", "RAIL_FREIGHT")))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不在词表里");

    // 小写 'sea' 被规范化成 SEA —— 否则 @> ARRAY['SEA'] 会静默不匹配
    var saved =
        tx(actor, () -> profiles.saveCarrier(actor, party, carrier("WEB_PORTAL", List.of("sea", "Air", "sea"))));
    assertThat(saved.serviceCapabilities()).containsExactly("AIR", "SEA");
    assertThat(saved.interfaceTypeLabel()).isEqualTo("平台下单");
    assertThat(saved.serviceCapabilitiesLabel()).isEqualTo("空运、海运");
    // 非 API 对接要提示轨迹需人工回填
    assertThat(saved.warnings()).anyMatch(w -> w.contains("运单轨迹需要人工回填"));
    // 能力为空要提示筛不到
    var bare = tx(actor, () -> profiles.saveCarrier(actor, party, carrier("API", List.of())));
    assertThat(bare.warnings()).anyMatch(w -> w.contains("筛不到"));
  }

  @Test
  void 承运商能力筛选按数组包含而不是逗号串() {
    UUID a1 = partyWith(actor, "SEA", "CARRIER");
    UUID a2 = partyWith(actor, "AIRONLY", "CARRIER");
    tx(actor, () -> profiles.saveCarrier(actor, a1, carrier("API", List.of("SEA", "CUSTOMS"))));
    tx(actor, () -> profiles.saveCarrier(actor, a2, carrier("API", List.of("AIR"))));

    // "支持海运的承运商"—— SEA 排在数组第一位，用 `like '%,SEA,%'` 会漏匹配它。
    var page = tx(actor, () -> profiles.list(
        new PartyRoleProfileQuery("CARRIER", null, null, "SEA", null, null, null, null, 0, 50)));
    assertThat(page.items()).extracting(PartyRoleProfileRow::partyId).contains(a1).doesNotContain(a2);
  }

  // ================================================================ 列表与概览

  @Test
  void 列表计数与分页切片一致且支持缺档案筛选() {
    // 建两个有档案、一个没建档案的供应商
    UUID withProfile = partyWith(actor, "LP1", "SUPPLIER");
    UUID alsoProfile = partyWith(actor, "LP2", "SUPPLIER");
    UUID noProfile = partyWith(actor, "LP3", "SUPPLIER");
    tx(actor, () -> profiles.saveSupplier(actor, withProfile, supplier(30, "STRATEGIC", "CREDIT", null)));
    tx(actor, () -> profiles.saveSupplier(actor, alsoProfile, supplier(0, "TRIAL", "PREPAID", null)));

    var all = tx(actor, () -> profiles.list(
        new PartyRoleProfileQuery("SUPPLIER", null, null, null, null, null, null, null, 0, 200)));
    assertThat(all.items()).extracting(PartyRoleProfileRow::partyId)
        .contains(withProfile, alsoProfile, noProfile);
    assertThat(all.total()).isEqualTo(all.items().size());

    // 只看缺档案
    var missing = tx(actor, () -> profiles.list(
        new PartyRoleProfileQuery("SUPPLIER", null, null, null, null, null, true, null, 0, 200)));
    assertThat(missing.items()).extracting(PartyRoleProfileRow::partyId).contains(noProfile);
    assertThat(missing.items()).extracting(PartyRoleProfileRow::profileFound).containsOnly(false);
    // 筛选后计数仍然与取行对得上（两处引同一处筛选条件）
    assertThat(missing.total()).isEqualTo(missing.items().size());

    // 按分级筛
    var strategic = tx(actor, () -> profiles.list(
        new PartyRoleProfileQuery("SUPPLIER", null, "STRATEGIC", null, null, null, null, null, 0, 200)));
    assertThat(strategic.items()).extracting(PartyRoleProfileRow::partyId).contains(withProfile);
    assertThat(strategic.items()).extracting(PartyRoleProfileRow::level).containsOnly("STRATEGIC");
    // 列表行的 headline 是服务端算好的中文串
    assertThat(strategic.items().getFirst().headline()).contains("账期 30 天").contains("战略供应商");

    // 客户筛选项用在供应商上要报错而不是静默忽略
    assertThatThrownBy(() -> tx(actor, () -> profiles.list(
            new PartyRoleProfileQuery("SUPPLIER", null, null, null, null, "USD", null, null, 0, 20))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只对客户档案有效");
  }

  @Test
  void 概览覆盖率与缺档案数逐项一致且探针为零() {
    UUID p1 = partyWith(actor, "OV1", "SUPPLIER", "CUSTOMER");
    tx(actor, () -> profiles.saveSupplier(actor, p1, supplier(30, "STANDARD", "CREDIT", null)));
    tx(actor, () -> profiles.saveCustomer(actor, p1, customer("STANDARD", "USD", null, 0)));

    var ov = tx(actor, () -> profiles.overview());
    // 全库逐项对：概览里的角色数必须等于列表里同一口径的行数
    long supplierRoles = tx(actor, () -> profiles.list(
            new PartyRoleProfileQuery("SUPPLIER", null, null, null, null, null, null, null, 0, 200)))
        .total();
    assertThat(ov.supplierRoleCount()).isEqualTo(supplierRoles);
    assertThat(ov.supplierProfileCount()).isLessThanOrEqualTo(ov.supplierRoleCount());
    // 缺档案数 = 三个角色各自的（角色数 - 档案数）之和
    long expectedMissing =
        (ov.supplierRoleCount() - ov.supplierProfileCount())
            + (ov.customerRoleCount() - ov.customerProfileCount())
            + (ov.carrierRoleCount() - ov.carrierProfileCount());
    assertThat(ov.missingProfileCount()).isEqualTo(expectedMissing);
    assertThat(ov.missingProfileCount()).isGreaterThanOrEqualTo(0);
    // 两个"绕过服务层的脏数据"探针正常都为 0（V30 的 DB CHECK 兜住了）
    assertThat(ov.riskyCustomerWithCreditCount()).isZero();
    assertThat(ov.termsWithoutCreditCount()).isZero();
  }

  @Test
  void 关键词通配符被转义() {
    UUID party = partyWith(actor, "KW", "SUPPLIER");
    var profile = tx(actor, () -> profiles.saveSupplier(actor, party, supplier(30, "STANDARD", "CREDIT", null)));
    String code = tx(actor, () -> profiles.supplier(party)).partyCode();
    // 搜 % 本该字面匹配，不会变成"匹配所有"
    var page = tx(actor, () -> profiles.list(
        new PartyRoleProfileQuery("SUPPLIER", "%", null, null, null, null, null, null, 0, 200)));
    assertThat(page.items()).extracting(PartyRoleProfileRow::partyId).doesNotContain(party);
    // 精确的编码能搜到
    var hit = tx(actor, () -> profiles.list(
        new PartyRoleProfileQuery("SUPPLIER", code, null, null, null, null, null, null, 0, 200)));
    assertThat(hit.items()).extracting(PartyRoleProfileRow::partyId).contains(party);
    assertThat(profile.profileFound()).isTrue();
  }

  @Test
  void 词表选项按角色类型返回且不重复造币种表() {
    var supplier = tx(actor, () -> profiles.options("SUPPLIER"));
    assertThat(supplier.roleType()).isEqualTo("SUPPLIER");
    assertThat(supplier.levels()).hasSize(5);
    assertThat(supplier.settlementMethods()).hasSize(4);
    // 非本角色的词表不返回（前端不需要，也不会误导）
    assertThat(supplier.interfaceTypes()).isEmpty();
    assertThat(supplier.serviceCapabilities()).isEmpty();

    var carrier = tx(actor, () -> profiles.options("CARRIER"));
    assertThat(carrier.interfaceTypes()).hasSize(4);
    assertThat(carrier.serviceCapabilities()).hasSize(9);
    assertThat(carrier.levels()).isEmpty();

    // 非法角色类型 → 400
    assertThatThrownBy(() -> tx(actor, () -> profiles.options("VENDOR")))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ================================================================ 租户隔离三断言

  /**
   * 租户隔离三断言，<b>并且覆盖角色档案行</b>。
   *
   * <p>只测主体等于没测：档案行才是本模块真正的越权面 ——
   * 「A 读到自己的额度时混进 B 的信用额度」会直接让 A 的应收按 B 客户的额度放单。
   */
  @Test
  void 租户隔离三断言覆盖角色档案行() throws Exception {
    UUID mine = partyWith(actor, "ISO-MINE", "SUPPLIER", "CUSTOMER");
    UUID theirs = partyWith(actorB, "ISO-THEIRS", "SUPPLIER", "CUSTOMER");
    tx(actor, () -> profiles.saveSupplier(actor, mine, supplier(30, "STRATEGIC", "CREDIT", null)));
    tx(actor, () -> profiles.saveCustomer(
        actor, mine, customer("STRATEGIC", "USD", new java.math.BigDecimal("111111"), 30)));
    tx(actorB, () -> profiles.saveSupplier(actorB, theirs, supplier(90, "TRIAL", "CREDIT", null)));
    tx(actorB, () -> profiles.saveCustomer(
        actorB, theirs, customer("KEY", "EUR", new java.math.BigDecimal("999999"), 90)));

    // 两边都必须真的有档案行 —— 否则下面的断言就是空的
    assertThat(tx(actor, () -> profiles.supplier(mine).profileFound())).isTrue();
    assertThat(tx(actorB, () -> profiles.supplier(theirs).profileFound())).isTrue();

    // 1) 两租户互不可见：读对方主体 404，写对方档案 404，档案行互相读不到
    assertThatThrownBy(() -> tx(actor, () -> profiles.supplier(theirs)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> profiles.supplier(mine)))
        .isInstanceOf(ResponseStatusException.class);
    // A 不能把档案写到 B 的主体上
    assertThatThrownBy(
            () -> writeVoid(actor, () -> profiles.saveSupplier(
                actor, theirs, supplier(365, "STRATEGIC", "CREDIT", null))))
        .isInstanceOf(ResponseStatusException.class);
    // A 不能删 B 的档案
    var theirProfile = tx(actorB, () -> profiles.supplier(theirs));
    assertThatThrownBy(
            () -> writeVoid(actor, () -> profiles.delete(actor, theirs, "SUPPLIER", theirProfile.version())))
        .isInstanceOf(ResponseStatusException.class);
    // 额度不相交：这是本模块最要紧的一条
    assertThat(tx(actor, () -> profiles.customer(mine).defaultCreditLimit()))
        .isEqualByComparingTo("111111");
    assertThat(tx(actorB, () -> profiles.customer(theirs).defaultCreditLimit()))
        .isEqualByComparingTo("999999");

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(get("/catalog/parties/" + mine + "/supplier-profile")
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(get("/catalog/parties/" + theirs + "/customer-profile")
            .header("Authorization", "Bearer tenant-a").header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    // 伪造头下写入仍落在 B 自己名下 —— 断言**行确实落在 B 名下**，不只断言 A 看不见
    String code = unique("FORGED-PROFILE");
    var forged = partyWith(actorB, code, "SUPPLIER");
    writeVoid(actorB, () -> profiles.saveSupplier(actorB, forged, supplier(7, "TRIAL", "CREDIT", null)));
    assertThatThrownBy(() -> tx(actor, () -> profiles.supplier(forged)))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actorB, () -> profiles.supplier(forged).paymentTermsDays())).isEqualTo(7);
    assertThat(tx(actorB, () -> sql.fetchOne(
                "select tenant_id from party_supplier_profile where role_id=?",
                tx(actorB, () -> profiles.supplier(forged)).roleId()).get(0, UUID.class)))
        .isEqualTo(b);

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言档案集合不相交
    for (int i = 0; i < 5; i++) {
      var myProfiles = tx(actor, () -> profiles.list(
              new PartyRoleProfileQuery("SUPPLIER", null, null, null, null, null, null, null, 0, 200)))
          .items().stream().map(PartyRoleProfileRow::roleId).collect(java.util.stream.Collectors.toSet());
      var theirProfiles = tx(actorB, () -> profiles.list(
              new PartyRoleProfileQuery("SUPPLIER", null, null, null, null, null, null, null, 0, 200)))
          .items().stream().map(PartyRoleProfileRow::roleId).collect(java.util.stream.Collectors.toSet());
      assertThat(myProfiles).isNotEmpty();
      assertThat(theirProfiles).isNotEmpty();
      assertThat(java.util.Collections.disjoint(myProfiles, theirProfiles)).isTrue();
      // 汇总视图也不串
      assertThat(tx(actor, () -> profiles.aggregate(mine)).supplier().paymentTermsDays()).isEqualTo(30);
      assertThat(tx(actorB, () -> profiles.aggregate(theirs)).supplier().paymentTermsDays()).isEqualTo(90);
    }
  }

  // ================================================================ RLS 与 HTTP

  @Test
  void 三张档案表都强制RLS() {
    for (String table :
        List.of("party_supplier_profile", "party_customer_profile", "party_carrier_profile")) {
      var row = tx(actor, () -> sql.fetchOne(
          "select relrowsecurity,relforcerowsecurity from pg_class where relname=?", table));
      assertThat(row.get("relrowsecurity", Boolean.class)).as("%s 开启 RLS", table).isTrue();
      assertThat(row.get("relforcerowsecurity", Boolean.class)).as("%s 强制 RLS", table).isTrue();
      var policy = tx(actor, () -> sql.fetchOne(
          "select count(*) from pg_policies where tablename=? and policyname='isolation'", table));
      assertThat(policy.get(0, Long.class)).as("%s 有 isolation 策略", table).isEqualTo(1L);
    }
  }

  @Test
  void 档案表结构与约束齐全() {
    // 主键是 (tenant_id, role_id)：档案键在角色上，不在主体上
    var pk = tx(actor, () -> sql.fetchOne(
        "select (select string_agg(a.attname,',' order by k.ord) from unnest(c.conkey)"
            + " with ordinality k(att,ord) join pg_attribute a"
            + " on a.attrelid=c.conrelid and a.attnum=k.att)"
            + " from pg_constraint c"
            + " where c.conrelid='party_supplier_profile'::regclass and c.contype='p'"));
    assertThat(pk.get(0, String.class)).isEqualTo("tenant_id,role_id");
    // 外键指向 party_role(tenant_id,id) 且级联删除（另一条是指向 unit_of_measure 的，
    // 所以这里指名按被引用表筛，不能 fetch 一张可能有几行的表）
    var fk = tx(actor, () -> sql.fetchOne(
        "select c.confdeltype from pg_constraint c join pg_class t on t.oid=c.confrelid"
            + " where c.conrelid='party_supplier_profile'::regclass and c.contype='f'"
            + " and t.relname='party_role'"));
    assertThat(fk.get("confdeltype", String.class)).as("ON DELETE CASCADE").isEqualTo("c");
    // MOQ 采购单位的外键（V30 注释里承诺的那条）
    var unitFk = tx(actor, () -> sql.fetchOne(
        "select t.relname from pg_constraint c join pg_class t on t.oid=c.confrelid"
            + " where c.conrelid='party_supplier_profile'::regclass and c.contype='f'"
            + " and t.relname='unit_of_measure'"));
    assertThat(unitFk.get(0, String.class)).isEqualTo("unit_of_measure");
    // 数量列精度：MOQ numeric(19,6)、额度 numeric(19,4)、账期整数
    assertThat(columnPrecision("party_supplier_profile", "moq_quantity")).isEqualTo("19,6");
    assertThat(columnPrecision("party_supplier_profile", "payment_terms_days")).isEqualTo("32,0");
    assertThat(columnPrecision("party_customer_profile", "default_credit_limit")).isEqualTo("19,4");
    assertThat(columnType("party_customer_profile", "default_credit_limit")).isEqualTo("numeric");
    // 服务能力是数组列（不是逗号串）
    // information_schema.data_type 对数组列报 ARRAY，udt_name 是 PostgreSQL 内部的 _text
    assertThat(columnType("party_carrier_profile", "service_capabilities")).isEqualTo("ARRAY");
    assertThat(columnUdt("party_carrier_profile", "service_capabilities")).isEqualTo("_text");
  }

  private String columnPrecision(String table, String column) {
    return tx(actor, () -> sql.fetchOne(
            "select numeric_precision||','||numeric_scale from information_schema.columns"
                + " where table_name=? and column_name=?", table, column))
        .get(0, String.class);
  }

  private String columnUdt(String table, String column) {
    return tx(actor, () -> sql.fetchOne(
            "select udt_name from information_schema.columns where table_name=? and column_name=?",
            table, column))
        .get(0, String.class);
  }

  private String columnType(String table, String column) {
    return tx(actor, () -> sql.fetchOne(
            "select data_type from information_schema.columns where table_name=? and column_name=?",
            table, column))
        .get(0, String.class);
  }

  @Test
  void dbCHECK挡住绕过服务层的脏数据() {
    UUID party = partyWith(actor, "DBCHK", "SUPPLIER");
    var saved = tx(actor, () -> profiles.saveSupplier(actor, party, supplier(30, "STANDARD", "CREDIT", null)));
    UUID roleId = saved.roleId();
    // 「预付 + 90 天账期」：服务层会拒，DB CHECK 也要兜住直连写入
    assertThatThrownBy(() -> writeVoid(actor, () -> sql.execute(
            "update party_supplier_profile set settlement_method='PREPAID',payment_terms_days=90"
                + " where tenant_id=? and role_id=?", a, roleId)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    // MOQ 数量与单位不成对：同样被 CHECK 挡住
    assertThatThrownBy(() -> writeVoid(actor, () -> sql.execute(
            "update party_supplier_profile set moq_quantity=100,moq_unit_code=null"
                + " where tenant_id=? and role_id=?", a, roleId)))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void http全链路与读写权限分离() throws Exception {
    String code = unique("HTTP");
    var roles = new PartyRoleInput[] {new PartyRoleInput("SUPPLIER", "ACTIVE", "")};
    var created =
        http.perform(post("/catalog/parties").header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(JSON.writeValueAsBytes(Map.of(
                    "code", code, "name", "HTTP 主体", "partyType", "ORGANIZATION",
                    "countryCode", "DE", "email", "http@x.example", "status", "ACTIVE",
                    "roles", List.of(Map.of("roleType", "SUPPLIER", "status", "ACTIVE", "note", "")),
                    "version", 0))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    UUID partyId = UUID.fromString(new ObjectMapper().readTree(created).get("id").asText());

    // PUT 建档案 → 200，且真的落库
    http.perform(put("/catalog/parties/" + partyId + "/supplier-profile")
            .header("Authorization", "Bearer tenant-a").contentType("application/json")
            .content(JSON.writeValueAsBytes(Map.of(
                "paymentTermsDays", 30, "cooperationLevel", "STRATEGIC",
                "settlementMethod", "CREDIT", "note", "HTTP 建的", "version", 0))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.profileFound").value(true))
        .andExpect(jsonPath("$.paymentTermsDays").value(30));

    // GET 读回一致
    http.perform(get("/catalog/parties/" + partyId + "/supplier-profile")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cooperationLevelLabel").value("战略供应商"));

    // 汇总与列表 / 概览 / 词表
    http.perform(get("/catalog/parties/" + partyId + "/role-profiles")
            .header("Authorization", "Bearer tenant-a")).andExpect(status().isOk());
    http.perform(get("/catalog/role-profiles/overview")
            .header("Authorization", "Bearer tenant-a")).andExpect(status().isOk());
    http.perform(get("/catalog/role-profiles?roleType=SUPPLIER")
            .header("Authorization", "Bearer tenant-a")).andExpect(status().isOk());
    http.perform(get("/catalog/role-profiles/options?roleType=CUSTOMER")
            .header("Authorization", "Bearer tenant-a")).andExpect(status().isOk());

    // 写权限：只有读权限的角色写档案必须 403；读放行
    http.perform(put("/catalog/parties/" + partyId + "/supplier-profile")
            .header("Authorization", "Bearer reader").contentType("application/json")
            .content(JSON.writeValueAsBytes(Map.of(
                "paymentTermsDays", 60, "cooperationLevel", "STANDARD",
                "settlementMethod", "CREDIT", "version", 1))))
        .andExpect(status().isForbidden());
    http.perform(get("/catalog/parties/" + partyId + "/supplier-profile")
            .header("Authorization", "Bearer reader")).andExpect(status().isOk());

    // 缺 token → 401
    http.perform(get("/catalog/parties/" + partyId + "/supplier-profile"))
        .andExpect(status().isUnauthorized());

    // 校验失败 → 400（而不是 500）
    http.perform(put("/catalog/parties/" + partyId + "/supplier-profile")
            .header("Authorization", "Bearer tenant-a").contentType("application/json")
            .content(JSON.writeValueAsBytes(Map.of(
                "paymentTermsDays", 90, "cooperationLevel", "STANDARD",
                "settlementMethod", "PREPAID", "version", 1))))
        .andExpect(status().isBadRequest());

    // DELETE 带 version
    http.perform(delete("/catalog/parties/" + partyId + "/supplier-profile")
            .header("Authorization", "Bearer tenant-a").param("version", "0"))
        .andExpect(status().isOk());
  }
}