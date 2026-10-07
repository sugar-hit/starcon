package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.PhysicalSafetyModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.7.2.1 物理与安全属性：物理参数 + 安全属性读写、体积重与计费重派生、
 * 按安全属性筛选、渠道可用性判定、装箱体积核算，以及<b>租户隔离三断言</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：{@code sku_package} 与
 * {@code sku_safety_attribute} 全部真的落库，Flyway 真的跑 V28。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的断言</b>：
 *
 * <ol>
 *   <li>{@link #安全属性未采集一律判不可发()}：规则 R2 的核心 —— 默认放行等于
 *       给了一条「不填就能绕过申报」的路。
 *   <li>{@link #已采集且都不带时渠道才放行()}：且明确要求 {@code safetyCollected=true}。
 *   <li>{@link #体积重按调用方给的除数计算()}：两个除数给出两个不同答案（规则 R3）。
 *   <li>{@link #未采集与都不带在列表与档案上必须显示成不同状态()}：V28 规则 B2。
 *   <li>{@link #租户隔离三断言()}：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户。
 *   <li>{@link #新表强制rls且数量列精度正确()}：RLS 与 NUMERIC 精度在库层核对。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PhysicalSafetyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper JSON = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

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
    try (var conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = conn.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
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
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      // 只读角色：只有 compliance.read，没有 update —— 用来证明写操作按权限收敛。
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.compliance.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "assets", "attribute", "compliance")
        .flatMap(
            p ->
                java.util.stream.Stream.of("read", "create", "update", "delete")
                    .map(op -> "catalog." + p + "." + op))
        .toList();
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
    // 单连接：只有复用同一条连接，「归还后不串租户」才是真的被证明过。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @Autowired PhysicalSafetyService physical;
  @Autowired CatalogService catalog;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setup() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.compliance.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.compliance.update", work);
  }

  String unique(String hint) {
    return hint + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  UUID skuOf(AccessService.Access who, String code) {
    UUID spuId =
        write(who, () -> catalog.saveSpu(
            who, null, new SpuInput(code, "SPU " + code, "", "Brand", "Category", "DRAFT", 0)));
    return write(who, () -> catalog.saveSku(
        who, spuId, null, new SkuInput(code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), 0)));
  }

  /** 存一份完整的物理 + 安全档案。 */
  PhysicalSafetySaved save(
      AccessService.Access who, UUID skuId, String l, String w, String h,
      String net, String gross, String packaging, boolean collected,
      boolean battery, boolean liquid, boolean dg, boolean magnetic) {
    return write(who, () -> physical.save(who, skuId, input(l, w, h, net, gross, packaging,
        collected, battery, liquid, dg, magnetic, 0, 0)));
  }

  PhysicalSafetyInput input(
      String l, String w, String h, String net, String gross, String packaging,
      boolean collected, boolean battery, boolean liquid, boolean dg, boolean magnetic,
      long safetyVersion, long packagingVersion) {
    return new PhysicalSafetyInput(
        decimal(l), decimal(w), decimal(h), decimal(net), decimal(gross), packaging,
        collected, battery, liquid, dg, magnetic, "", safetyVersion, packagingVersion);
  }

  static BigDecimal decimal(String value) {
    return value == null ? null : new BigDecimal(value);
  }

  // ============================================================ 物理参数读写

  @Test
  void 物理参数与安全属性一次提交写入两张表() {
    UUID sku = skuOf(actor, unique("PS"));
    var saved = save(actor, sku, "200", "150", "100", "500", "800", "纸箱",
        true, true, false, false, false);

    // sku_package（V6 的表，本模块不另建表抄一份）
    var pkg = tx(actor, () -> sql.fetchOne(
        "select length_mm,width_mm,height_mm,net_weight_g,gross_weight_g,packaging_type"
            + " from sku_package where tenant_id=? and sku_id=?", a, sku));
    assertThat(pkg.get("length_mm", BigDecimal.class)).isEqualByComparingTo(decimal("200"));
    assertThat(pkg.get("width_mm", BigDecimal.class)).isEqualByComparingTo(decimal("150"));
    assertThat(pkg.get("height_mm", BigDecimal.class)).isEqualByComparingTo(decimal("100"));
    assertThat(pkg.get("net_weight_g", BigDecimal.class)).isEqualByComparingTo(decimal("500"));
    assertThat(pkg.get("gross_weight_g", BigDecimal.class)).isEqualByComparingTo(decimal("800"));
    assertThat(pkg.get("packaging_type", String.class)).isEqualTo("纸箱");

    // sku_safety_attribute（V28 新表）
    var safety = tx(actor, () -> sql.fetchOne(
        "select contains_battery,contains_liquid,dangerous_goods,is_magnetic"
            + " from sku_safety_attribute where tenant_id=? and sku_id=?", a, sku));
    assertThat(safety.get("contains_battery", Boolean.class)).isTrue();
    assertThat(safety.get("contains_liquid", Boolean.class)).isFalse();
    assertThat(safety.get("dangerous_goods", Boolean.class)).isFalse();
    assertThat(safety.get("is_magnetic", Boolean.class)).isFalse();

    // 回传的档案与库里一致（界面不必为了看结果再拉一次）。
    assertThat(saved.profile().hasVolume()).isTrue();
    assertThat(saved.profile().volumeCm3()).isEqualByComparingTo(decimal("3000"));
    assertThat(saved.profile().activeFlags()).containsExactly("contains_battery");
    assertThat(saved.packagingChanged()).isTrue();
  }

  @Test
  void 毛重小于净重被拒且不写任何一行() {
    UUID sku = skuOf(actor, unique("PS"));
    assertThatThrownBy(() -> save(actor, sku, "200", "150", "100", "900", "100", "纸箱",
        true, false, false, false, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("毛重不能小于净重");
    // 整次提交是原子的：物理参数与安全属性都不该留下半截。
    assertThat(tx(actor, () -> sql.fetch("select * from sku_package where sku_id=?", sku))).isEmpty();
    assertThat(tx(actor, () -> sql.fetch("select * from sku_safety_attribute where sku_id=?", sku)))
        .isEmpty();
  }

  @Test
  void 勾了安全标记却没确认已采集被拒() {
    UUID sku = skuOf(actor, unique("PS"));
    // 自相矛盾的提交：渠道会看到「带电」，运营却以为自己没填。
    assertThatThrownBy(() -> save(actor, sku, "200", "150", "100", "500", "800", "纸箱",
        false, true, false, false, false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已采集安全属性");
  }

  @Test
  void 未采集时写空行等于删除安全属性而物理参数保留() {
    UUID sku = skuOf(actor, unique("PS"));
    save(actor, sku, "200", "150", "100", "500", "800", "纸箱", true, true, false, false, false);
    // 取消「已采集」：安全属性回到未采集，物理参数不动。
    var saved = save(actor, sku, "200", "150", "100", "500", "800", "纸箱",
        false, false, false, false, false);
    assertThat(saved.profile().safetyCollected()).isFalse();
    assertThat(saved.profile().volumeCm3()).isEqualByComparingTo(decimal("3000"));
    assertThat(tx(actor, () -> sql.fetch("select * from sku_safety_attribute where sku_id=?", sku)))
        .isEmpty();
  }

  @Test
  void 乐观锁版本不符撞409() {
    UUID sku = skuOf(actor, unique("PS"));
    save(actor, sku, "200", "150", "100", "500", "800", "纸箱", true, false, false, false, false);
    // 用过期的 safetyVersion 再写一次。
    assertThatThrownBy(() -> write(actor, () -> physical.save(actor, sku,
        input("200", "150", "100", "500", "800", "纸箱", true, true, false, false, false, 99, 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被他人修改");
  }

  // ============================================================ 派生计算

  @Test
  void 体积重按调用方给的除数计算() {
    UUID sku = skuOf(actor, unique("PS"));
    save(actor, sku, "200", "150", "100", "500", "800", "纸箱", true, false, false, false, false);
    // 3000 cm³ ÷ 6000 = 0.5 kg；÷ 5000 = 0.6 kg。规则 R3：除数是调用方给的。
    assertThat(tx(actor, () -> physical.profile(sku, decimal("6000"))).volumetricWeightKg())
        .isEqualByComparingTo(decimal("0.5"));
    assertThat(tx(actor, () -> physical.profile(sku, decimal("5000"))).volumetricWeightKg())
        .isEqualByComparingTo(decimal("0.6"));
    // 不给除数就不给体积重 —— 本模块不替 10 物流选除数。
    var noDivisor = tx(actor, () -> physical.profile(sku, null));
    assertThat(noDivisor.volumetricWeightKg()).isNull();
    assertThat(noDivisor.volumeCm3()).isEqualByComparingTo(decimal("3000"));
  }

  @Test
  void 计费重取实重与体积重的较大者() {
    UUID bulky = skuOf(actor, unique("PS"));
    // 泡货：1m³ 的空箱子只有 0.2kg 实重，但体积重 166.67kg。
    save(actor, bulky, "1000", "1000", "1000", "100", "200", "纸箱", true, false, false, false, false);
    var bulkyView = tx(actor, () -> physical.profile(bulky, decimal("6000")));
    // 1,000×1,000×1,000 mm = 1,000,000 cm³ ÷ 6000 = 166.666667 kg
    assertThat(bulkyView.volumetricWeightKg()).isEqualByComparingTo(decimal("166.666667"));
    // 体积重远大于实重 0.2kg → 计费重取体积重
    assertThat(bulkyView.chargeableWeightKg()).isEqualByComparingTo(decimal("166.666667"));

    UUID heavy = skuOf(actor, unique("PS"));
    // 重货：小盒子但很重，体积重只有 0.5kg，实重 8kg。
    save(actor, heavy, "200", "150", "100", "7000", "8000", "纸箱", true, false, false, false, false);
    var heavyView = tx(actor, () -> physical.profile(heavy, decimal("6000")));
    assertThat(heavyView.volumetricWeightKg()).isEqualByComparingTo(decimal("0.5"));
    // 实重更大 → 计费重取实重
    assertThat(heavyView.chargeableWeightKg()).isEqualByComparingTo(decimal("8"));
  }

  @Test
  void 缺一维不给体积而不是给零() {
    UUID sku = skuOf(actor, unique("PS"));
    // 只填长宽，缺高。
    save(actor, sku, "200", "150", null, "500", "800", "纸箱", true, false, false, false, false);
    var view = tx(actor, () -> physical.profile(sku, decimal("6000")));
    assertThat(view.hasVolume()).isFalse();
    assertThat(view.volumeCm3()).isNull();
    assertThat(view.volumetricWeightKg()).isNull();
  }

  // ============================================================ 安全属性语义（规则 B2 / R2）

  @Test
  void 未采集与都不带在档案上必须显示成不同状态() {
    UUID notCollected = skuOf(actor, unique("PS"));
    UUID plain = skuOf(actor, unique("PS"));
    var untouched = tx(actor, () -> physical.profile(notCollected, decimal("6000")));
    var confirmed = save(actor, plain, null, null, null, null, null, "",
        true, false, false, false, false).profile();

    assertThat(untouched.safetyCollected()).isFalse();
    assertThat(confirmed.safetyCollected()).isTrue();
    // 关键：已采集且都不带时，四个标记确实是 false —— 这是「确认无需申报」的状态。
    assertThat(confirmed.containsBattery()).isFalse();
    assertThat(confirmed.dangerousGoods()).isFalse();
    assertThat(confirmed.requiredDeclarations()).isEmpty();
    // 而未采集的 SKU 不能因为「查不到 true」就被当成「都不带」。
    assertThat(untouched.requiredDeclarations()).isEmpty();
    assertThat(untouched.safetyCollected()).isFalse();
  }

  @Test
  void 安全属性未采集一律判不可发() {
    UUID sku = skuOf(actor, unique("PS"));
    // 只填物理参数，**不**确认安全属性 —— 这一行就是「还没人采集过」。
    save(actor, sku, "200", "150", "100", "500", "800", "纸箱", false, false, false, false, false);
    assertThat(tx(actor, () -> physical.profile(sku, null)).safetyCollected()).isFalse();
    // 渠道什么都不限制，但安全属性没采集 → 仍然拒发。
    var result = tx(actor, () -> physical.verdict(sku, policy("ANY", true, true, true, true, false)));
    assertThat(result.shippable()).isFalse();
    assertThat(result.blockers()).contains("SAFETY_NOT_COLLECTED");
    // 附注要讲清「按不知道处理」，页面不靠猜。
    assertThat(result.note()).contains("还没采集");
  }

  @Test
  void 已采集且都不带时渠道才放行() {
    UUID sku = skuOf(actor, unique("PS"));
    save(actor, sku, "200", "150", "100", "500", "800", "纸箱", true, false, false, false, false);
    var result = tx(actor, () -> physical.verdict(sku, policy("EXPRESS", true, true, true, true, false)));
    assertThat(result.shippable()).isTrue();
    assertThat(result.blockers()).isEmpty();
    assertThat(result.declarations()).isEmpty();
  }

  @Test
  void 带电货物遇到不收带电的渠道被拦下() {
    UUID sku = skuOf(actor, unique("PS"));
    save(actor, sku, "200", "150", "100", "500", "800", "纸箱", true, true, false, false, false);
    var blocked = tx(actor, () -> physical.verdict(sku, policy("ROAD", false, true, true, true, false)));
    assertThat(blocked.shippable()).isFalse();
    assertThat(blocked.blockers()).containsExactly("BATTERY_NOT_ALLOWED");
    assertThat(blocked.blockerLabels()).allMatch(label -> !label.isBlank());

    // 收带电的渠道放行，但仍要给电池申报 —— 拦截与申报是两件事。
    var allowed = tx(actor, () -> physical.verdict(sku, policy("AIR-DG", true, true, true, true, false)));
    assertThat(allowed.shippable()).isTrue();
    assertThat(allowed.declarations()).containsExactly("BATTERY");
  }

  @Test
  void 渠道判定带上体积重与计费重() {
    UUID sku = skuOf(actor, unique("PS"));
    save(actor, sku, "200", "150", "100", "500", "800", "纸箱", true, false, false, false, false);
    var result = tx(actor, () -> physical.verdict(sku, policy("AIR", true, true, true, true, true)));
    assertThat(result.shippable()).isTrue();
    assertThat(result.volumeCm3()).isEqualByComparingTo(decimal("3000"));
    assertThat(result.volumetricWeightKg()).isEqualByComparingTo(decimal("0.5"));
    // 实重 0.8kg > 体积重 0.5kg → 计费重取实重
    assertThat(result.chargeableWeightKg()).isEqualByComparingTo(decimal("0.8"));
    assertThat(result.note()).contains("6000");
  }

  @Test
  void 渠道要求物理参数时缺一维被拦下() {
    UUID sku = skuOf(actor, unique("PS"));
    save(actor, sku, "200", "150", null, "500", "800", "纸箱", true, false, false, false, false);
    var result = tx(actor, () -> physical.verdict(sku, policy("AIR", true, true, true, true, true)));
    assertThat(result.shippable()).isFalse();
    assertThat(result.blockers()).contains("PHYSICAL_PARAMETERS_MISSING");
  }

  ChannelVerdictInput policy(
      String code, boolean battery, boolean liquid, boolean dg, boolean magnetic, boolean requireParams) {
    return new ChannelVerdictInput(code, battery, liquid, dg, magnetic, requireParams, decimal("6000"));
  }

  // ============================================================ 筛选

  @Test
  void 按安全属性筛出危险品与带电商品() {
    String prefix = unique("FILTER");
    UUID dangerous = skuOf(actor, prefix + "DG");
    UUID battery = skuOf(actor, prefix + "BAT");
    UUID plain = skuOf(actor, prefix + "PLAIN");
    save(actor, dangerous, null, null, null, null, null, "", true, false, false, true, false);
    save(actor, battery, null, null, null, null, null, "", true, true, false, false, false);
    save(actor, plain, null, null, null, null, null, "", true, false, false, false, false);

    var dangerRows = tx(actor, () -> physical.search(
        new PhysicalSafetyQuery(prefix, null, null, true, null, null, null, null, 0, 50)));
    assertThat(dangerRows.items()).extracting(PhysicalSafetyRow::skuCode)
        .contains(codeOf(dangerous));
    assertThat(dangerRows.items()).extracting(PhysicalSafetyRow::skuCode)
        .doesNotContain(codeOf(battery), codeOf(plain));

    // 筛「不是危险品」也必须能答 —— 运营会问这个问题。
    var notDangerous = tx(actor, () -> physical.search(
        new PhysicalSafetyQuery(prefix, null, null, false, null, null, null, null, 0, 50)));
    assertThat(notDangerous.items()).extracting(PhysicalSafetyRow::skuCode)
        .contains(codeOf(battery), codeOf(plain));
    assertThat(notDangerous.items()).extracting(PhysicalSafetyRow::skuCode)
        .doesNotContain(codeOf(dangerous));
  }

  @Test
  void 筛未采集与筛需要申报() {
    String prefix = unique("UNCOLLECTED");
    UUID untouched = skuOf(actor, prefix + "NEW");
    UUID liquid = skuOf(actor, prefix + "LIQ");
    save(actor, liquid, null, null, null, null, null, "", true, false, true, false, false);

    var uncollected = tx(actor, () -> physical.search(
        new PhysicalSafetyQuery(prefix, null, null, null, null, false, null, null, 0, 50)));
    assertThat(uncollected.items()).extracting(PhysicalSafetyRow::skuCode)
        .contains(codeOf(untouched));
    assertThat(uncollected.items()).extracting(PhysicalSafetyRow::skuCode)
        .doesNotContain(codeOf(liquid));

    var needsDeclaration = tx(actor, () -> physical.search(
        new PhysicalSafetyQuery(prefix, null, null, null, null, null, true, null, 0, 50)));
    assertThat(needsDeclaration.items()).extracting(PhysicalSafetyRow::skuCode)
        .contains(codeOf(liquid));
    assertThat(needsDeclaration.items()).extracting(PhysicalSafetyRow::requiredDeclarations)
        .allSatisfy(declarations -> assertThat(declarations).isNotEmpty());
  }

  @Test
  void 列表分页与总数一致() {
    String prefix = unique("PAGE");
    for (int i = 0; i < 3; i++) {
      UUID sku = skuOf(actor, prefix + i);
      save(actor, sku, "200", "150", "100", "500", "800", "纸箱", true, false, false, false, false);
    }
    var page0 = tx(actor, () -> physical.search(
        new PhysicalSafetyQuery(prefix, null, null, null, null, null, null, null, 0, 2)));
    var page1 = tx(actor, () -> physical.search(
        new PhysicalSafetyQuery(prefix, null, null, null, null, null, null, null, 1, 2)));
    assertThat(page0.items()).hasSize(2);
    assertThat(page0.total()).isEqualTo(3);
    assertThat(page0.totalPages()).isEqualTo(2);
    assertThat(page1.items()).hasSize(1);
    // 两页不能重复。
    assertThat(page0.items()).doesNotContainAnyElementsOf(page1.items());
  }

  String codeOf(UUID sku) {
    return tx(actor, () -> sql.fetchOne("select code from sku where id=?", sku).get("code", String.class));
  }

  // ============================================================ 装箱体积核算

  @Test
  void 装箱核算按体积与限重里更紧的那个给件数() {
    UUID sku = skuOf(actor, unique("CARTON"));
    // 单件 3000 cm³、毛重 600g。每箱容积 10000 cm³ → 体积上 3 件；限重 10000g → 16 件。
    // 取更紧的 3 件。
    save(actor, sku, "200", "150", "100", "500", "600", "纸箱", true, false, false, false, false);
    var result = tx(actor, () -> physical.cartonCalc(new CartonCalcInput(
        List.of(new CartonLineInput(sku, decimal("7"))), decimal("10000"), decimal("10000"))));
    var line = result.lines().get(0);
    assertThat(line.unitsPerCartonByVolume()).isEqualTo(3);
    assertThat(line.unitsPerCartonByWeight()).isEqualTo(16);
    assertThat(line.unitsPerCarton()).isEqualTo(3);
    assertThat(line.limitedByWeight()).isFalse();
    // 7 件、每箱 3 件 → 3 箱（向上取整，不能是 2 箱装不下第 7 件）
    assertThat(line.cartonsNeeded()).isEqualTo(3);
    assertThat(result.totalCartons()).isEqualTo(3);
    assertThat(line.totalVolumeCm3()).isEqualByComparingTo(decimal("21000"));
  }

  @Test
  void 限重更紧时按限重算并标明重量受限() {
    UUID sku = skuOf(actor, unique("CARTON"));
    save(actor, sku, "100", "100", "100", "2800", "3000", "纸箱", true, false, false, false, false);
    var result = tx(actor, () -> physical.cartonCalc(new CartonCalcInput(
        List.of(new CartonLineInput(sku, decimal("4"))), decimal("10000"), decimal("8000"))));
    var line = result.lines().get(0);
    assertThat(line.unitsPerCartonByVolume()).isEqualTo(10);
    assertThat(line.unitsPerCartonByWeight()).isEqualTo(2);
    assertThat(line.unitsPerCarton()).isEqualTo(2);
    assertThat(line.limitedByWeight()).isTrue();
    assertThat(line.cartonsNeeded()).isEqualTo(2);
  }

  @Test
  void 同一sku多行合并后再算箱数() {
    UUID sku = skuOf(actor, unique("CARTON"));
    save(actor, sku, "200", "150", "100", "500", "600", "纸箱", true, false, false, false, false);
    // 分成 4 + 4 两行：合并后是 8 件、每箱 3 件 → 3 箱。
    // 不合并的话会算成 2 + 2 = 4 箱，而真实的拣货是把这 8 件装进同一批箱子。
    var result = tx(actor, () -> physical.cartonCalc(new CartonCalcInput(
        List.of(new CartonLineInput(sku, decimal("4")), new CartonLineInput(sku, decimal("4"))),
        decimal("10000"), decimal("100000"))));
    assertThat(result.lines()).hasSize(1);
    assertThat(result.lines().get(0).quantity()).isEqualByComparingTo(decimal("8"));
    assertThat(result.totalCartons()).isEqualTo(3);
  }

  @Test
  void 缺体积的件在核算里显式标明而不是当作零() {
    UUID sku = skuOf(actor, unique("CARTON"));
    save(actor, sku, null, null, null, "1000", "2000", "", true, false, false, false, false);
    var result = tx(actor, () -> physical.cartonCalc(new CartonCalcInput(
        List.of(new CartonLineInput(sku, decimal("10"))), decimal("10000"), decimal("10000"))));
    var line = result.lines().get(0);
    assertThat(line.unitVolumeCm3()).isNull();
    assertThat(line.totalVolumeCm3()).isNull();
    assertThat(line.unitsPerCartonByWeight()).isEqualTo(5);
    assertThat(line.note()).contains("缺长宽高");
  }

  @Test
  void 箱规不合法时被拒() {
    UUID sku = skuOf(actor, unique("CARTON"));
    assertThatThrownBy(() -> tx(actor, () -> physical.cartonCalc(new CartonCalcInput(
        List.of(new CartonLineInput(sku, decimal("1"))), BigDecimal.ZERO, decimal("1000")))))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> physical.cartonCalc(new CartonCalcInput(
        List.of(), decimal("1000"), decimal("1000")))))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ============================================================ 租户隔离三断言

  @Test
  void 租户隔离三断言() throws Exception {
    String myPrefix = unique("MINE");
    UUID mySku = skuOf(actor, myPrefix);
    save(actor, mySku, "200", "150", "100", "500", "800", "纸箱", true, true, false, false, false);

    String theirPrefix = unique("THEIRS");
    UUID theirSku = skuOf(actorB, theirPrefix);
    save(actorB, theirSku, "300", "300", "300", "900", "1200", "木箱", true, false, false, true, false);

    // 1) 两租户互不可见：读对方的档案直接 404。
    assertThatThrownBy(() -> tx(actor, () -> physical.profile(theirSku, decimal("6000"))))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> physical.profile(mySku, decimal("6000"))))
        .isInstanceOf(ResponseStatusException.class);
    // 写对方的档案同样拿不到目标。
    assertThatThrownBy(() -> write(actor, () -> physical.save(actor, theirSku,
        input("1", "1", "1", "1", "1", "", true, false, false, false, false, 0, 0))))
        .isInstanceOf(ResponseStatusException.class);
    // 对方的物理参数不能被本租户改掉。
    assertThat(tx(actorB, () -> sql.fetchOne(
        "select length_mm from sku_package where sku_id=?", theirSku))
        .get("length_mm", BigDecimal.class)).isEqualByComparingTo(decimal("300"));
    // 装箱核算跨租户取行同样 404。
    assertThatThrownBy(() -> tx(actor, () -> physical.cartonCalc(new CartonCalcInput(
        List.of(new CartonLineInput(theirSku, decimal("1"))), decimal("1000"), decimal("1000")))))
        .isInstanceOf(ResponseStatusException.class);
    // 渠道判定跨租户取档案同样 404。
    assertThatThrownBy(() -> tx(actor, () -> physical.verdict(theirSku,
        policy("AIR", true, true, true, true, false))))
        .isInstanceOf(ResponseStatusException.class);

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系。
    http.perform(get("/catalog/physical-safety/skus/" + mySku)
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 即便带上除数也一样 —— 参数不能绕过隔离。
    http.perform(get("/catalog/physical-safety/skus/" + mySku)
            .param("divisorCm3PerKg", "6000")
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 筛选接口同样不认伪造的头。
    http.perform(get("/catalog/physical-safety/profiles")
            .param("keyword", myPrefix)
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isOk())
        .andExpect(result -> {
          var body = JSON.readTree(result.getResponse().getContentAsString());
          assertThat(body.get("total").asLong()).isZero();
        });

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言档案与列表都不串。
    for (int i = 0; i < 5; i++) {
      var mine = tx(actor, () -> physical.profile(mySku, decimal("6000")));
      var theirs = tx(actorB, () -> physical.profile(theirSku, decimal("6000")));
      assertThat(mine.skuId()).isEqualTo(mySku);
      assertThat(theirs.skuId()).isEqualTo(theirSku);
      assertThat(mine.dangerousGoods()).isFalse();
      assertThat(theirs.dangerousGoods()).isTrue();
      assertThat(mine.packagingType()).isEqualTo("纸箱");
      assertThat(theirs.packagingType()).isEqualTo("木箱");
      assertThat(mine.volumeCm3()).isEqualByComparingTo(decimal("3000"));
      assertThat(theirs.volumeCm3()).isEqualByComparingTo(decimal("27000"));

      var myRows = tx(actor, () -> physical.search(
          new PhysicalSafetyQuery(null, null, null, null, null, null, null, null, 0, 100)))
          .items();
      var theirRows = tx(actorB, () -> physical.search(
          new PhysicalSafetyQuery(null, null, null, null, null, null, null, null, 0, 100)))
          .items();
      var myIds = myRows.stream().map(PhysicalSafetyRow::skuId).toList();
      var theirIds = theirRows.stream().map(PhysicalSafetyRow::skuId).toList();
      assertThat(myIds).contains(mySku).doesNotContain(theirSku);
      assertThat(theirIds).contains(theirSku).doesNotContain(mySku);
      assertThat(java.util.Collections.disjoint(myIds, theirIds)).isTrue();
    }
  }

  // ============================================================ 库层约束

  @Test
  void 新表强制rls() {
    var table = tx(actor, () -> sql.fetchOne(
        "select relrowsecurity,relforcerowsecurity from pg_class"
            + " where relname='sku_safety_attribute' and relkind='r'"));
    assertThat(Boolean.TRUE.equals(table.get("relrowsecurity", Boolean.class))).isTrue();
    assertThat(Boolean.TRUE.equals(table.get("relforcerowsecurity", Boolean.class))).isTrue();
  }

  @Test
  void 物理参数列保持numeric精度不被本模块改坏() {
    // 本模块复用 sku_package 而不是另建表：V6 的 NUMERIC(19,6) 精度必须原样保留。
    var columns = tx(actor, () -> sql.fetch(
        "select column_name,numeric_precision p,numeric_scale s from information_schema.columns"
            + " where table_name='sku_package' and column_name in"
            + " ('length_mm','width_mm','height_mm','net_weight_g','gross_weight_g')"));
    assertThat(columns).hasSize(5);
    assertThat(columns).allMatch(r ->
        r.get("p", Integer.class) == 19 && r.get("s", Integer.class) == 6);
  }

  @Test
  void 写入后经http读回的是真实落库的值() throws Exception {
    UUID sku = skuOf(actor, unique("HTTP"));
    save(actor, sku, "200", "150", "100", "500", "800", "纸箱", true, false, true, false, false);
    http.perform(get("/catalog/physical-safety/skus/" + sku).param("divisorCm3PerKg", "6000")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(result -> {
          var body = JSON.readTree(result.getResponse().getContentAsString());
          assertThat(body.get("skuId").asText()).isEqualTo(sku.toString());
          assertThat(body.get("lengthMm").decimalValue()).isEqualByComparingTo(decimal("200"));
          assertThat(body.get("volumeCm3").decimalValue()).isEqualByComparingTo(decimal("3000"));
          assertThat(body.get("volumetricWeightKg").decimalValue()).isEqualByComparingTo(decimal("0.5"));
          assertThat(body.get("chargeableWeightKg").decimalValue()).isEqualByComparingTo(decimal("0.8"));
          assertThat(body.get("safetyCollected").asBoolean()).isTrue();
          assertThat(body.get("containsLiquid").asBoolean()).isTrue();
          assertThat(body.get("dangerousGoods").asBoolean()).isFalse();
          assertThat(body.get("requiredDeclarations").toString()).contains("LIQUID");
        });
  }

  @Test
  void 写操作按权限收敛() throws Exception {
    UUID sku = skuOf(actor, unique("PERM"));
    // reader 只有 compliance.read，没有 update。
    http.perform(put("/catalog/physical-safety/skus/" + sku)
            .header("Authorization", "Bearer reader")
            .contentType("application/json")
            .content(
                JSON.writeValueAsString(
                    input("200", "150", "100", "500", "800", "纸箱", true, false, false, false, false, 0, 0))))
        .andExpect(status().isForbidden());
  }

  @Test
  void 未认证返回未授权() throws Exception {
    UUID sku = skuOf(actor, unique("AUTH"));
    http.perform(get("/catalog/physical-safety/skus/" + sku).header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());
  }
}
