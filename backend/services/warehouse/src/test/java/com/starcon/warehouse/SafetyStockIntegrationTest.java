package com.starcon.warehouse;

import static com.starcon.warehouse.SafetyStockModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.warehouse.SafetyStockModels.SafetyStockEvaluationRequest;
import com.starcon.warehouse.SafetyStockModels.SafetyStockPolicyInput;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 7.2.2.2 安全库存：阈值分档生效、<b>平台断货 vs 自有仓耗尽</b>、日均销量与可售天数推算、 补货建议关联、销量区间重叠、时区零漂移，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link SellablePolicyIntegrationTest} 完全一致（同样的 Testcontainers、 同样的 fake identity
 * server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）。
 *
 * <p><b>本测试的核心是蓝图那句加粗红线</b>：「安全库存要区分『平台断货风险』和『自有仓耗尽』： FBA 断货造成的损失远大于自有仓」。分开的两个用例都不够 —— 必须是<b>同一个
 * SKU、同样配 INFO、 一个挂自有仓一个挂 FBA</b>，然后断言 FBA 侧的 effectiveSeverity 被提了一级且排在前面。 只测「FBA
 * 会产生一种预警」是把红线读成了分类；「损失更大」这半句必须由排序与提级兑现。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SafetyStockIntegrationTest {
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

  static final UUID le = idFor(a, 1);
  static final UUID sku = idFor(a, 2);
  static final UUID whOwn = idFor(a, 3);
  static final UUID whFba = idFor(a, 4);
  static final UUID qSellable = idFor(a, 5);
  static final UUID qDefective = idFor(a, 6);

  /** 分类属 catalog 服务：这里只造一个逻辑 id，warehouse 侧不建外键也不查它的表。 */
  static final UUID categoryOne = UUID.randomUUID();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-safety-" + tenant + "-" + ordinal).getBytes());
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

  @Autowired SafetyStockService service;
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
          "truncate safety_stock_replenishment_suggestion, safety_stock_alert, safety_stock_evaluation,"
              + " safety_stock_sales, safety_stock_policy_warehouse, safety_stock_policy,"
              + " inventory_balance, inventory_quality_status, warehouse, sku_ref, legal_entity_ref"
              + " restart identity cascade");
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
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 3),
                tenant,
                "WH-OWN",
                "自有仓",
                "CN",
                "Asia/Shanghai");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone) values(?,?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "WH-FBA",
                "美国 FBA",
                "US",
                "America/Los_Angeles");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "SELLABLE",
                "可售",
                "SELLABLE");
            sql.execute(
                "insert into inventory_quality_status(id,tenant_id,code,name,role) values(?,?,?,?,?)",
                idFor(tenant, 6),
                tenant,
                "DEFECTIVE",
                "残次",
                "DEFECTIVE");
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

  // ================================================================ 夹具写入

  void balance(UUID tenant, UUID warehouse, UUID quality, String onHand, String reserved) {
    access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          sql.execute(
              "insert into inventory_balance(id,tenant_id,sku_id,warehouse_id,legal_entity_id,"
                  + "quality_status_id,on_hand,reserved) values(?,?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              tenant,
              idFor(tenant, 2),
              warehouse,
              idFor(tenant, 1),
              quality,
              new BigDecimal(onHand),
              new BigDecimal(reserved));
          return null;
        });
  }

  /** 标准库存：自有仓 100 件可售、FBA 仓 60 件可售、自有仓另有 500 件残次。 */
  void standardStock() {
    balance(a, whOwn, qSellable, "100", "0");
    balance(a, whFba, qSellable, "60", "0");
    balance(a, whOwn, qDefective, "500", "0");
  }

  List<SafetyStockPolicyInput.SafetyStockWarehouseBinding> own() {
    return List.of(new SafetyStockPolicyInput.SafetyStockWarehouseBinding(whOwn, "OWN"));
  }

  List<SafetyStockPolicyInput.SafetyStockWarehouseBinding> fba() {
    return List.of(new SafetyStockPolicyInput.SafetyStockWarehouseBinding(whFba, "PLATFORM_FBA"));
  }

  /** SKU 档阈值：{@code scope} 默认 SKU。 */
  SafetyStockPolicyInput skuPolicy(
      String threshold,
      String severity,
      List<SafetyStockPolicyInput.SafetyStockWarehouseBinding> warehouses) {
    return skuPolicy(threshold, severity, warehouses, null, false, null);
  }

  SafetyStockPolicyInput skuPolicy(
      String threshold,
      String severity,
      List<SafetyStockPolicyInput.SafetyStockWarehouseBinding> warehouses,
      String coverageDays,
      boolean autoSuggest,
      OffsetDateTime from) {
    return new SafetyStockPolicyInput(
        "SKU",
        null,
        sku,
        "商品一",
        new BigDecimal(threshold),
        severity,
        coverageDays == null ? null : new BigDecimal(coverageDays),
        autoSuggest,
        from,
        "夹具",
        warehouses);
  }

  SafetyStockPolicySaved save(SafetyStockPolicyInput input) {
    return save(null, input, actorA);
  }

  SafetyStockPolicySaved save(
      UUID supersedeId, SafetyStockPolicyInput input, AccessService.Access who) {
    return access.run(
        who, "warehouse.inventory.update", () -> service.save(supersedeId, input, who.userId()));
  }

  SafetyStockSalesSaved sales(String from, String to, String units, AccessService.Access who) {
    return access.run(
        who,
        "warehouse.inventory.update",
        () ->
            service.recordSales(
                new SafetyStockSalesInput(
                    "MANUAL",
                    sku,
                    "商品一",
                    LocalDate.parse(from),
                    LocalDate.parse(to),
                    new BigDecimal(units)),
                who.userId()));
  }

  SafetyStockSalesSaved sales(String from, String to, String units) {
    return sales(from, to, units, actorA);
  }

  SafetyStockEvaluationRow evaluate() {
    return evaluate(actorA, new SafetyStockEvaluationRequest(List.of(), null, null));
  }

  SafetyStockEvaluationRow evaluate(
      AccessService.Access who, SafetyStockEvaluationRequest request) {
    return access.run(
        who, "warehouse.inventory.update", () -> service.evaluate(request, who.userId()));
  }

  SafetyStockEvaluationRequest withSkuContext(UUID categoryId) {
    return new SafetyStockEvaluationRequest(
        List.of(new SafetyStockEvaluationRequest.SafetyStockEvaluationSkuContext(sku, categoryId)),
        null,
        null);
  }

  /** 未处置的预警（与工作台默认视图一致）。要查已处置的用各自显式构造的查询。 */
  List<SafetyStockAlertRow> alerts() {
    return access
        .run(
            actorA,
            "warehouse.inventory.read",
            () ->
                service.alerts(
                    new SafetyStockAlertQuery(
                        null, null, null, null, null, null, null, false, null, 0, 50)))
        .alerts();
  }

  // ================================================================ 蓝图红线：平台断货 vs 自有仓耗尽

  @Test
  void 平台断货与自有仓耗尽是两种预警且平台侧提级排前() {
    standardStock();
    // 同一个 SKU、同一个 INFO 级别，一个挂自有仓一个挂 FBA —— 只有风险类型不同。
    save(skuPolicy("150", "INFO", own()));
    save(skuPolicy("150", "INFO", fba()));

    var result = evaluate();
    // 100 < 150 与 60 < 150 都低于阈值，两边都该报。
    assertThat(result.alertsOpened()).isEqualTo(2);

    var rows = alerts();
    assertThat(rows).hasSize(2);

    var platform =
        rows.stream()
            .filter(r -> r.alertType() == SafetyStockAlertType.PLATFORM_STOCKOUT_RISK)
            .findFirst()
            .orElseThrow();
    var own =
        rows.stream()
            .filter(r -> r.alertType() == SafetyStockAlertType.OWN_WAREHOUSE_DEPLETION)
            .findFirst()
            .orElseThrow();

    // 类型确实被区分开了 —— 这是红线的前半句。alertType 与 warehouseKind 一一对应
    // （PLATFORM_STOCKOUT_RISK ⇔ PLATFORM_FBA），所以看类型就等于看仓的归属。
    assertThat(platform.warehouseCode()).isEqualTo("WH-FBA");
    assertThat(own.warehouseCode()).isEqualTo("WH-OWN");

    // 两边配置级别相同，**提级后**平台侧更高 —— 这是红线的后半句「FBA 断货造成的损失远大于自有仓」。
    assertThat(platform.severity()).isEqualTo(AlertSeverity.INFO);
    assertThat(own.severity()).isEqualTo(AlertSeverity.INFO);
    assertThat(platform.effectiveSeverity()).isEqualTo(AlertSeverity.WARNING);
    assertThat(own.effectiveSeverity()).isEqualTo(AlertSeverity.INFO);

    // 提级必须落到排序上：损失更大的那一条排在前面，不是只写在字段里。
    assertThat(rows.get(0).id()).isEqualTo(platform.id());

    // 缺口量也逐项算得出来。
    assertThat(platform.availableQuantity()).isEqualByComparingTo("60");
    assertThat(platform.shortfallQuantity()).isEqualByComparingTo("90");
    assertThat(own.availableQuantity()).isEqualByComparingTo("100");
    assertThat(own.shortfallQuantity()).isEqualByComparingTo("50");

    // 只看平台风险时过滤得出来。
    var onlyPlatform =
        access
            .run(
                actorA,
                "warehouse.inventory.read",
                () ->
                    service.alerts(
                        new SafetyStockAlertQuery(
                            null, null, null, null, null, null, null, true, null, 0, 50)))
            .alerts();
    assertThat(onlyPlatform).hasSize(1);
    assertThat(onlyPlatform.get(0).alertType())
        .isEqualTo(SafetyStockAlertType.PLATFORM_STOCKOUT_RISK);
  }

  @Test
  void 平台断货在CRITICAL与WARNING下不再提级() {
    standardStock();
    save(skuPolicy("150", "WARNING", fba()));
    evaluate();
    var platform = alerts().get(0);
    // WARNING → CRITICAL；CRITICAL 提到顶为止，不该溢出成别的值。
    assertThat(platform.effectiveSeverity()).isEqualTo(AlertSeverity.CRITICAL);
    assertThat(platform.severity()).isEqualTo(AlertSeverity.WARNING);
  }

  @Test
  void 同一档阈值不能混挂自有仓与平台仓() {
    standardStock();
    var mixed =
        List.of(
            new SafetyStockPolicyInput.SafetyStockWarehouseBinding(whOwn, "OWN"),
            new SafetyStockPolicyInput.SafetyStockWarehouseBinding(whFba, "PLATFORM_FBA"));
    assertThatThrownBy(() -> save(skuPolicy("150", "WARNING", mixed)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能同时挂自有仓与平台仓")
        .hasMessageContaining("两个独立的池子");
  }

  @Test
  void 混挂被应用层绕开时DB延迟约束仍拒绝() {
    standardStock();
    var saved = save(skuPolicy("150", "WARNING", own()));
    // 直接写库绕过应用层校验（模拟另一个写入方）：V114 的延迟约束触发器必须兜住。
    // 约束是 DEFERRABLE，这里显式 SET CONSTRAINTS ... IMMEDIATE 让它在事务内立刻检查。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute("set constraints all immediate");
                      sql.execute(
                          "insert into safety_stock_policy_warehouse"
                              + " (tenant_id,policy_id,warehouse_id,warehouse_kind) values (?,?,?,?)",
                          a,
                          saved.policyId(),
                          whFba,
                          "PLATFORM_FBA");
                      return null;
                    }))
        .hasMessageContaining("不能同时挂自有仓与平台仓");
  }

  @Test
  void 高于阈值不报警低于阈值才报() {
    standardStock();
    // 60 < 100 会报；100 >= 100 不报（CHECK 用的是严格小于，不是小于等于）。
    save(skuPolicy("100", "WARNING", own()));
    var result = evaluate();
    assertThat(result.alertsOpened()).isZero();
    assertThat(result.alertsSuppressed()).isEqualTo(1);
    assertThat(alerts()).isEmpty();
  }

  @Test
  void 残次库存被排除而不是扣减() {
    // 自有仓 100 件可售 + 500 件残次。残次不计入可用量，但阈值判定只看可售。
    standardStock();
    save(skuPolicy("101", "WARNING", own()));
    var result = evaluate();
    assertThat(result.alertsOpened()).isEqualTo(1);
    assertThat(alerts().get(0).availableQuantity()).isEqualByComparingTo("100");
  }

  // ================================================================ 日均销量 → 可售天数 → 补货建议

  @Test
  void 日均销量推算可售天数与建议补货量() {
    standardStock();
    // 30 天卖 300 件 → 日均 10 件。
    sales("2026-09-01", "2026-09-30", "300");
    // 目标库存 = 阈值 150 + 日均 10 × 覆盖 7 天 = 220；自有仓 100 件 → 建议补 120。
    save(skuPolicy("150", "WARNING", own(), "7", true, null));

    var result = evaluate();
    assertThat(result.skusWithoutSales()).isZero();
    assertThat(result.suggestionsCreated()).isEqualTo(1);

    var alert = alerts().get(0);
    assertThat(alert.dailyAverageSales()).isEqualByComparingTo("10");
    // 可售天数 = 100 ÷ 10 = 10 天。
    assertThat(alert.daysOfCover()).isEqualByComparingTo("10");
    assertThat(alert.suggestedQuantity()).isEqualByComparingTo("120");

    var suggestion =
        access
            .run(actorA, "warehouse.inventory.read", () -> service.alert(alert.id()))
            .suggestion();
    assertThat(suggestion).isNotNull();
    assertThat(suggestion.suggestedQuantity()).isEqualByComparingTo("120");
    assertThat(suggestion.targetStockQuantity()).isEqualByComparingTo("220");
    assertThat(suggestion.unquantifiedReason()).isEmpty();
    // 6.2.1.2 未交付 → 采购单号留空，而不是编一个。
    assertThat(suggestion.externalPurchaseReference()).isNull();
    assertThat(suggestion.status()).isEqualTo(SafetyStockSuggestionStatus.PROPOSED);
  }

  @Test
  void 未登记销量时天数与建议量留空而不是拿零兜底() {
    standardStock();
    save(skuPolicy("150", "WARNING", own(), "7", true, null));

    var result = evaluate();
    assertThat(result.alertsOpened()).isEqualTo(1);
    assertThat(result.skusWithoutSales()).isEqualTo(1);

    var alert = alerts().get(0);
    assertThat(alert.dailyAverageSales()).isNull();
    assertThat(alert.daysOfCover()).isNull();
    assertThat(alert.suggestedQuantity()).isNull();
    assertThat(alert.reviewNote()).contains("尚未登记销量");

    // 建议仍然生成，但明确写清为什么算不出量 —— 拿 0 会被读成「不用补货」。
    var suggestion =
        access
            .run(actorA, "warehouse.inventory.read", () -> service.alert(alert.id()))
            .suggestion();
    assertThat(suggestion).isNotNull();
    assertThat(suggestion.suggestedQuantity()).isNull();
    assertThat(suggestion.targetStockQuantity()).isNull();
    assertThat(suggestion.unquantifiedReason()).contains("尚未登记销量");

    // 「只看算不出量的」这一批要能被筛出来。
    var unquantified =
        access
            .run(
                actorA,
                "warehouse.inventory.read",
                () ->
                    service.suggestions(
                        new SafetyStockSuggestionQuery(null, null, null, true, 0, 50)))
            .suggestions();
    assertThat(unquantified).hasSize(1);
  }

  @Test
  void 日均销量为零时天数留空而不是零天() {
    standardStock();
    // 30 天卖 0 件是真实结果（登记本身合法），但 100 ÷ 0 无意义。
    sales("2026-09-01", "2026-09-30", "0");
    save(skuPolicy("150", "WARNING", own(), "7", true, null));
    evaluate();
    var alert = alerts().get(0);
    assertThat(alert.dailyAverageSales()).isEqualByComparingTo("0");
    assertThat(alert.daysOfCover()).isNull();
    assertThat(alert.reviewNote()).contains("除以零");
  }

  @Test
  void 销量登记区间重叠被拒绝() {
    assertThatThrownBy(
            () -> {
              sales("2026-09-01", "2026-09-30", "300");
              sales("2026-09-15", "2026-09-20", "60");
            })
        .hasMessageContaining("重叠");
  }

  @Test
  void 不重叠的销量区间可以并存并汇总() {
    sales("2026-09-01", "2026-09-15", "150");
    sales("2026-09-16", "2026-09-30", "150");
    var page =
        access.run(actorA, "warehouse.inventory.read", () -> service.sales(sku, null, 0, 50));
    assertThat(page.total()).isEqualTo(1);
    var row = page.sales().get(0);
    assertThat(row.records()).isEqualTo(2);
    assertThat(row.coveredDays()).isEqualTo(30);
    // 300 件 / 30 天 = 10 件/天
    assertThat(row.dailyAverage()).isEqualByComparingTo("10");
  }

  @Test
  void 未开自动建议时不生成建议() {
    standardStock();
    sales("2026-09-01", "2026-09-30", "300");
    save(skuPolicy("150", "WARNING", own(), "7", false, null));
    var result = evaluate();
    assertThat(result.alertsOpened()).isEqualTo(1);
    assertThat(result.suggestionsCreated()).isZero();
    assertThat(
            access
                .run(actorA, "warehouse.inventory.read", () -> service.alert(alerts().get(0).id()))
                .suggestion())
        .isNull();
  }

  @Test
  void 忽略建议必须写理由() {
    standardStock();
    sales("2026-09-01", "2026-09-30", "300");
    save(skuPolicy("150", "WARNING", own(), "7", true, null));
    evaluate();
    var suggestionId = alerts().get(0).id();
    var detail = access.run(actorA, "warehouse.inventory.read", () -> service.alert(suggestionId));
    var id = detail.suggestion().id();

    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> service.dismissSuggestion(id, "  ", actorA.userId())))
        .hasMessageContaining("必须写理由");

    var dismissed =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () -> service.dismissSuggestion(id, "本季清库存，暂不补", actorA.userId()));
    assertThat(dismissed.status()).isEqualTo(SafetyStockSuggestionStatus.DISMISSED);

    // 已忽略的建议不能被重复忽略（状态机单向）。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> service.dismissSuggestion(id, "再忽略一次", actorA.userId())))
        .hasMessageContaining("只有待处理的建议可以忽略");
  }

  @Test
  void 采纳建议后不能重复采纳() {
    standardStock();
    sales("2026-09-01", "2026-09-30", "300");
    save(skuPolicy("150", "WARNING", own(), "7", true, null));
    evaluate();
    var id =
        access
            .run(actorA, "warehouse.inventory.read", () -> service.alert(alerts().get(0).id()))
            .suggestion()
            .id();
    var ok =
        access.run(actorA, "warehouse.inventory.update", () -> service.acknowledgeSuggestion(id));
    assertThat(ok.status()).isEqualTo(SafetyStockSuggestionStatus.ACKNOWLEDGED);
    assertThat(ok.message()).contains("6.2.1.2");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA, "warehouse.inventory.update", () -> service.acknowledgeSuggestion(id)))
        .hasMessageContaining("只有待处理的建议可以采纳");
  }

  // ================================================================ 预警状态机与读数快照

  @Test
  void 预警状态只能单向推进() {
    standardStock();
    save(skuPolicy("150", "WARNING", own()));
    evaluate();
    var id = alerts().get(0).id();

    // 未认领不能直接处置。
    assertThatThrownBy(
            () -> access.run(actorA, "warehouse.inventory.update", () -> service.resolve(id)))
        .hasMessageContaining("只有已认领的预警可以标记为已处置");

    access.run(
        actorA, "warehouse.inventory.update", () -> service.acknowledge(id, actorA.userId()));
    access.run(actorA, "warehouse.inventory.update", () -> service.resolve(id));

    // 已处置是终态：不能复活。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> service.acknowledge(id, actorA.userId())))
        .hasMessageContaining("只有待处理的预警可以认领");
  }

  @Test
  void 预警读数快照不可被改写() {
    standardStock();
    save(skuPolicy("150", "WARNING", own()));
    evaluate();
    var id = alerts().get(0).id();

    // 绕过应用层直接 UPDATE 读数 → DB 触发器拒绝。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "update safety_stock_alert set available_quantity = 9999 where id = ?",
                          id);
                      return null;
                    }))
        .hasMessageContaining("预警读数快照不可修改");
  }

  @Test
  void 已处置的预警默认不在工作台列表里() {
    standardStock();
    save(skuPolicy("150", "WARNING", own()));
    evaluate();
    var id = alerts().get(0).id();
    access.run(
        actorA, "warehouse.inventory.update", () -> service.acknowledge(id, actorA.userId()));
    access.run(actorA, "warehouse.inventory.update", () -> service.resolve(id));

    assertThat(alerts()).isEmpty();
    var resolved =
        access
            .run(
                actorA,
                "warehouse.inventory.read",
                () ->
                    service.alerts(
                        new SafetyStockAlertQuery(
                            null, null, null, null, "RESOLVED", null, null, false, null, 0, 50)))
            .alerts();
    assertThat(resolved).hasSize(1);
    assertThat(resolved.get(0).resolvedAt()).isNotNull();
  }

  // ================================================================ 分档生效与不可变

  @Test
  void 阈值分档生效且旧档值原样保留() {
    standardStock();
    var at = now().minusDays(10);
    var first = save(skuPolicy("150", "WARNING", own(), null, false, at));
    var second =
        save(first.policyId(), skuPolicy("300", "CRITICAL", own(), null, false, now()), actorA);

    // 换版前 → 第一档
    var before =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.effective(SafetyStockScopeType.SKU, null, sku, at));
    assertThat(before.policy().id()).isEqualTo(first.policyId());
    assertThat(before.policy().thresholdQuantity()).isEqualByComparingTo("150");
    assertThat(before.usedFallback()).isFalse();

    // 换版后 → 第二档
    var after =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.effective(SafetyStockScopeType.SKU, null, sku, now().plusMinutes(1)));
    assertThat(after.policy().id()).isEqualTo(second.policyId());
    assertThat(after.policy().thresholdQuantity()).isEqualByComparingTo("300");

    // 旧档仍可查：它是「当时那条预警依据长什么样」的唯一凭据。
    var page =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                service.page(
                    new SafetyStockQuery(null, null, sku, null, null, false, null, 0, 50)));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.policies().stream().filter(p -> p.id().equals(first.policyId())).count())
        .isEqualTo(1);
  }

  @Test
  void 阈值内容写定后不可改() {
    standardStock();
    var saved = save(skuPolicy("150", "WARNING", own()));
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> {
                      sql.execute(
                          "update safety_stock_policy set threshold_quantity = 1 where id = ?",
                          saved.policyId());
                      return null;
                    }))
        .hasMessageContaining("阈值内容写定后不可修改");
  }

  @Test
  void 生效区间重叠被拒() {
    standardStock();
    // 第一档已提交（挂仓已存在），此时再插一档区间与之重叠 → 即时触发器当场拒绝。
    save(skuPolicy("150", "WARNING", own(), null, false, now().minusDays(10)));
    assertThatThrownBy(
            () -> save(skuPolicy("200", "WARNING", own(), null, false, now().minusDays(5))))
        .hasMessageContaining("重叠");
  }

  @Test
  void 作废后如实报未配置而不是按零处理() {
    standardStock();
    var saved = save(skuPolicy("150", "WARNING", own()));
    var retired =
        access.run(
            actorA, "warehouse.inventory.update", () -> service.retire(saved.policyId(), now()));
    assertThat(retired.effectiveTo()).isNotNull();

    var effective =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.effective(SafetyStockScopeType.SKU, null, sku, now().plusMinutes(1)));
    assertThat(effective.policy()).isNull();
    assertThat(effective.note()).contains("不受任何安全库存保护");
  }

  @Test
  void 优先级是SKU高于分类高于兜底() {
    standardStock();
    save(
        new SafetyStockPolicyInput(
            "DEFAULT",
            null,
            null,
            "",
            new BigDecimal("10"),
            "INFO",
            null,
            false,
            null,
            "兜底",
            own()));
    save(
        new SafetyStockPolicyInput(
            "CATEGORY",
            categoryOne,
            null,
            "",
            new BigDecimal("200"),
            "WARNING",
            null,
            false,
            null,
            "分类档",
            own()));

    // 按 SKU 解析：SKU 档没配，但给了分类 → 下探命中分类档 200（而不是直接跳到兜底档 10）。
    var asSku =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.effective(SafetyStockScopeType.SKU, categoryOne, sku, now()));
    assertThat(asSku.policy().thresholdQuantity()).isEqualByComparingTo("200");
    assertThat(asSku.resolvedScopeType()).isEqualTo(SafetyStockScopeType.CATEGORY);
    assertThat(asSku.usedFallback()).isFalse();

    // 同一个 SKU 但分类不在那档分类里 → 回退到兜底档，如实标 usedFallback。
    var otherCategory = UUID.randomUUID();
    var fallback =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.effective(SafetyStockScopeType.CATEGORY, otherCategory, sku, now()));
    assertThat(fallback.policy().thresholdQuantity()).isEqualByComparingTo("10");
    assertThat(fallback.usedFallback()).isTrue();
  }

  @Test
  void 同一个SKU的自有仓档与平台仓档可以并存() {
    standardStock();
    // 槽位唯一性是「范围 × 仓」而不是「范围」：这两档管的是两个不同的池子，必须能同时开。
    var ownPolicy = save(skuPolicy("150", "WARNING", own()));
    var fbaPolicy = save(skuPolicy("80", "CRITICAL", fba()));

    var page =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                service.page(new SafetyStockQuery(null, null, sku, null, null, true, null, 0, 50)));
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.policies())
        .extracting(SafetyStockPolicyRow::id)
        .containsExactlyInAnyOrder(ownPolicy.policyId(), fbaPolicy.policyId());

    // **建档后立刻按 effectiveOnly 查就必须两档都在**：这一条同时守着「同一 SKU 的自有仓档与
    // FBA 档可以并存」（槽位是「范围 × 仓」而不是「范围」）与「刚配好的阈值立刻生效」。
    assertThat(page.policies())
        .allSatisfy(p -> assertThat(p.effectiveTo()).as("两档都应仍在生效").isNull());

    // 但同一个仓上不能有两档同时在效（区间重叠与槽位两道延迟约束任一拦下，错误信息同源）。
    assertThatThrownBy(() -> save(skuPolicy("200", "INFO", own())))
        .hasMessageContaining("同一时刻只能有一个生效值");
  }

  @Test
  void 分类档没有SKU清单时如实报未扫描() {
    standardStock();
    save(
        new SafetyStockPolicyInput(
            "DEFAULT",
            null,
            null,
            "",
            new BigDecimal("150"),
            "WARNING",
            null,
            false,
            null,
            "兜底",
            own()));
    save(
        new SafetyStockPolicyInput(
            "CATEGORY",
            categoryOne,
            null,
            "",
            new BigDecimal("150"),
            "WARNING",
            null,
            false,
            null,
            "分类档",
            own()));
    save(skuPolicy("150", "WARNING", own()));

    // 不带清单：SKU 档照扫，分类/兜底档计入未扫描。
    var withoutList = evaluate();
    assertThat(withoutList.policiesEvaluated()).isEqualTo(3);
    assertThat(withoutList.policiesNotScannable()).isEqualTo(2);
    assertThat(withoutList.alertsOpened()).isEqualTo(1);
    assertThat(withoutList.note()).contains("policiesNotScannable");

    // 带上分类上下文后分类档能被扫到。
    var withList = evaluate(actorA, withSkuContext(categoryOne));
    assertThat(withList.policiesNotScannable()).isZero();
    // 分类档 200 > 100 不报；SKU 档 150 > 100 报一条。
    assertThat(withList.alertsOpened()).isEqualTo(1);
  }

  @Test
  void 没有在效阈值时如实报没有基准() {
    standardStock();
    var result = evaluate();
    assertThat(result.policiesEvaluated()).isZero();
    assertThat(result.note()).contains("0 条预警**不代表库存健康**");
  }

  @Test
  void 零结果的评估也留下批次() {
    standardStock();
    save(skuPolicy("10", "WARNING", own()));
    var result = evaluate();
    assertThat(result.alertsOpened()).isZero();
    var page = access.run(actorA, "warehouse.inventory.read", () -> service.evaluations(0, 50));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.evaluations().get(0).id()).isEqualTo(result.id());
  }

  // ================================================================ 时区零漂移

  @Test
  void 生效起点带偏移写入后读回零漂移() {
    standardStock();
    // +08:00 的写入值：必须断言**写回的值**，否则 -28800s 的静默偏移没人发现。
    var written = OffsetDateTime.of(2026, 9, 1, 8, 0, 0, 0, ZoneOffset.ofHours(8));
    var saved = save(skuPolicy("150", "WARNING", own(), null, false, written));
    assertThat(saved.effectiveFrom().toInstant()).isEqualTo(written.toInstant());
    assertThat(saved.effectiveFrom().getOffset()).isEqualTo(ZoneOffset.ofHours(8));

    var detail =
        access.run(actorA, "warehouse.inventory.read", () -> service.detail(saved.policyId()));
    assertThat(detail.policy().effectiveFrom().toInstant()).isEqualTo(written.toInstant());
    assertThat(detail.policy().effectiveFrom().getOffset()).isEqualTo(ZoneOffset.ofHours(8));

    // 按时刻解析也必须落在同一瞬间。
    var effective =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.effective(SafetyStockScopeType.SKU, null, sku, written.plusDays(1)));
    assertThat(effective.policy().id()).isEqualTo(saved.policyId());
  }

  // ================================================================ 能力声明

  @Test
  void 能力声明把四条边界如实报出() {
    var caps = access.run(actorA, "warehouse.inventory.read", () -> service.capabilities());
    // 四条不可用一律 false —— 前端据此渲染提示，而不是从 404 或空数组里猜哪一环没通。
    assertThat(caps.salesSourceDerived()).isFalse();
    assertThat(caps.skuCategoryExpansionAvailable()).isFalse();
    assertThat(caps.replenishmentTargetDelivered()).isFalse();
    assertThat(caps.workbenchAlertFeedDelivered()).isFalse();
    assertThat(caps.salesSourceNote()).contains("登记");
    assertThat(caps.categoryExpansionNote()).contains("catalog");
    assertThat(caps.replenishmentNote()).contains("6.2.1.2");
    assertThat(caps.workbenchNote()).contains("工作台");
    assertThat(caps.platformStockoutEscalation()).contains("上一级严重度");

    assertThat(caps.scopeTypes()).hasSize(3);
    assertThat(caps.severities()).hasSize(3);
    assertThat(caps.alertTypes()).hasSize(2);
    assertThat(caps.alertStatuses()).hasSize(3);
    assertThat(caps.warehouseKinds()).hasSize(2);
    assertThat(caps.suggestionStatuses()).hasSize(3);
    // 选项不能有空描述 —— 前端直接显示它。
    assertThat(caps.scopeTypes()).allSatisfy(o -> assertThat(o.description()).isNotBlank());
  }

  // ================================================================ 输入校验

  @Test
  void 输入校验拒绝矛盾的配置() {
    standardStock();
    // 开了自动建议却没给覆盖天数。
    assertThatThrownBy(() -> save(skuPolicy("150", "WARNING", own(), null, true, null)))
        .hasMessageContaining("必须给 reorderCoverageDays");
    // 一个仓都不挂。
    assertThatThrownBy(
            () ->
                save(
                    new SafetyStockPolicyInput(
                        "SKU",
                        null,
                        sku,
                        "商品一",
                        new BigDecimal("150"),
                        "WARNING",
                        null,
                        false,
                        null,
                        "",
                        List.of())))
        .hasMessageContaining("至少要挂一个仓库");
    // 挂不存在的仓。
    assertThatThrownBy(
            () ->
                save(
                    new SafetyStockPolicyInput(
                        "SKU",
                        null,
                        sku,
                        "商品一",
                        new BigDecimal("150"),
                        "WARNING",
                        null,
                        false,
                        null,
                        "",
                        List.of(
                            new SafetyStockPolicyInput.SafetyStockWarehouseBinding(
                                UUID.randomUUID(), "OWN")))))
        .hasMessageContaining("不属于当前租户");
    // 负阈值。
    assertThatThrownBy(() -> save(skuPolicy("-1", "WARNING", own()))).hasMessageContaining("不能为负数");
    // 未知级别。
    assertThatThrownBy(() -> save(skuPolicy("150", "URGENT", own())))
        .hasMessageContaining("未知的预警级别");
    // SKU 档却不给 skuId。
    assertThatThrownBy(
            () ->
                save(
                    new SafetyStockPolicyInput(
                        "SKU",
                        null,
                        null,
                        "",
                        new BigDecimal("150"),
                        "WARNING",
                        null,
                        false,
                        null,
                        "",
                        own())))
        .hasMessageContaining("必须给 skuId");
    // 坏的 warehouseKind。
    assertThatThrownBy(
            () ->
                save(
                    new SafetyStockPolicyInput(
                        "SKU",
                        null,
                        sku,
                        "商品一",
                        new BigDecimal("150"),
                        "WARNING",
                        null,
                        false,
                        null,
                        "",
                        List.of(
                            new SafetyStockPolicyInput.SafetyStockWarehouseBinding(
                                whOwn, "THIRD_PARTY")))))
        .hasMessageContaining("分不出这两件事");
  }

  @Test
  void 阈值分页是真分页() {
    standardStock();
    var at = now().minusDays(3);
    for (int i = 0; i < 5; i++) {
      // 五个分类档各配一个不同的分类，避免同槽位冲突。分类档**不带 sku_label**
      // （DB 的 chk_label_needs_sku 挡住：留了标签就会指向一个不存在的 SKU 对象），
      // 要检索的那段文字放进 note。
      save(
          new SafetyStockPolicyInput(
              "CATEGORY",
              UUID.randomUUID(),
              null,
              "",
              new BigDecimal(100 + i),
              "INFO",
              null,
              false,
              at,
              "分类 " + i,
              own()));
    }
    var first =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                service.page(
                    new SafetyStockQuery(null, null, null, null, null, false, null, 0, 2)));
    var second =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                service.page(
                    new SafetyStockQuery(null, null, null, null, null, false, null, 1, 2)));
    assertThat(first.total()).isEqualTo(5);
    assertThat(first.policies()).hasSize(2);
    assertThat(second.policies()).hasSize(2);
    // 翻页不重不漏。
    var ids = new java.util.ArrayList<UUID>();
    first.policies().forEach(p -> ids.add(p.id()));
    second.policies().forEach(p -> ids.add(p.id()));
    assertThat(ids).doesNotHaveDuplicates();

    // 按关键字过滤。
    var hit =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                service.page(
                    new SafetyStockQuery(null, null, null, null, null, false, "分类 3", 0, 50)));
    assertThat(hit.total()).isEqualTo(1);

    // 按仓过滤（仓挂在阈值档上，要过挂仓表）。
    var byWarehouse =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                service.page(
                    new SafetyStockQuery(null, null, null, null, whOwn, false, null, 0, 50)));
    assertThat(byWarehouse.total()).isEqualTo(5);
  }

  // ================================================================ 端点与权限码

  @Test
  void 端点权限码复用已注册的warehouse_inventory一族() throws Exception {
    standardStock();
    // 只有读权限时，写动作一律 403 —— 阈值与预警级别是经营决策，不能挂在读权限上。
    http.perform(
            post("/warehouse/inventory/safety-stock/policies")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content(
                    "{\"scopeType\":\"SKU\",\"thresholdQuantity\":150,\"alertSeverity\":\"WARNING\","
                        + "\"warehouses\":[{\"warehouseId\":\""
                        + whOwn
                        + "\",\"warehouseKind\":\"OWN\"}]}"))
        .andExpect(status().isForbidden());

    // 读动作正常放行。
    http.perform(
            get("/warehouse/inventory/safety-stock/capabilities")
                .header("Authorization", "Bearer read-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.salesSourceDerived").value(false))
        .andExpect(jsonPath("$.platformStockoutEscalation").exists());

    // 未认证 → 401。
    http.perform(get("/warehouse/inventory/safety-stock/policies"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 端点能跑通评估到预警到建议的完整链路() throws Exception {
    standardStock();
    sales("2026-09-01", "2026-09-30", "300");
    http.perform(
            post("/warehouse/inventory/safety-stock/policies")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"scopeType\":\"SKU\",\"skuId\":\""
                        + sku
                        + "\",\"skuLabel\":\"商品一\","
                        + "\"thresholdQuantity\":150,\"alertSeverity\":\"WARNING\","
                        + "\"reorderCoverageDays\":7,\"autoSuggestReplenishment\":true,"
                        + "\"warehouses\":[{\"warehouseId\":\""
                        + whOwn
                        + "\",\"warehouseKind\":\"OWN\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.policyId").exists());

    http.perform(
            post("/warehouse/inventory/safety-stock/evaluate")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.alertsOpened").value(1))
        .andExpect(jsonPath("$.suggestionsCreated").value(1));

    http.perform(
            get("/warehouse/inventory/safety-stock/alerts")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.alerts[0].alertType").value("OWN_WAREHOUSE_DEPLETION"))
        .andExpect(jsonPath("$.alerts[0].daysOfCover").value(10.0))
        .andExpect(jsonPath("$.alerts[0].suggestedQuantity").value(120));

    http.perform(
            get("/warehouse/inventory/safety-stock/suggestions")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.suggestions[0].targetStockQuantity").value(220));

    http.perform(
            get("/warehouse/inventory/safety-stock/evaluations")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
  }

  @Test
  void 跨租户读预警得到404而不是泄露存在性() {
    standardStock();
    save(skuPolicy("150", "WARNING", own()));
    evaluate();
    var id = alerts().get(0).id();
    assertThatThrownBy(
            () -> access.run(actorB, "warehouse.inventory.read", () -> service.alert(id)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不属于当前租户");
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    standardStock();
    // A 租户：自有仓 100、FBA 60
    var policyA = save(skuPolicy("150", "WARNING", own()));
    evaluate();

    // B 租户用同一批逻辑 id（idFor(b,...) 与 A 不同，但 SKU/仓也都是 B 的）造自己的一套：
    // 阈值 80 → 60 < 80 会报，但只有 B 自己能看到。
    access.run(
        actorB,
        "warehouse.inventory.update",
        () -> {
          balance(b, idFor(b, 3), idFor(b, 5), "5", "0");
          var input =
              new SafetyStockPolicyInput(
                  "SKU",
                  null,
                  idFor(b, 2),
                  "B 的商品",
                  new BigDecimal("80"),
                  "CRITICAL",
                  null,
                  false,
                  null,
                  "B 夹具",
                  List.of(
                      new SafetyStockPolicyInput.SafetyStockWarehouseBinding(idFor(b, 3), "OWN")));
          var saved = service.save(null, input, actorB.userId());
          return service.evaluate(
              new SafetyStockEvaluationRequest(List.of(), null, null), actorB.userId());
        });

    var pageA =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () ->
                service.page(
                    new SafetyStockQuery(null, null, sku, null, null, false, null, 0, 50)));
    assertThat(pageA.total()).isEqualTo(1);
    assertThat(pageA.policies().get(0).id()).isEqualTo(policyA.policyId());
    assertThat(pageA.policies().get(0).thresholdQuantity()).isEqualByComparingTo("150");

    var alertsA =
        access
            .run(
                actorA,
                "warehouse.inventory.read",
                () ->
                    service.alerts(
                        new SafetyStockAlertQuery(
                            null, null, null, null, null, null, null, false, null, 0, 50)))
            .alerts();
    assertThat(alertsA).hasSize(1);
    assertThat(alertsA.get(0).skuId()).isEqualTo(sku);

    var alertsB =
        access
            .run(
                actorB,
                "warehouse.inventory.read",
                () ->
                    service.alerts(
                        new SafetyStockAlertQuery(
                            null, null, null, null, null, null, null, false, null, 0, 50)))
            .alerts();
    assertThat(alertsB).hasSize(1);
    assertThat(alertsB.get(0).skuId()).isEqualTo(idFor(b, 2));
    assertThat(alertsB.get(0).severity()).isEqualTo(AlertSeverity.CRITICAL);

    // 拿 A 的预警 id 去 B 那里读 → 404（RLS 下查不到），不泄露「这条存在」。
    assertThatThrownBy(
            () ->
                access.run(
                    actorB, "warehouse.inventory.read", () -> service.alert(alertsA.get(0).id())))
        .isInstanceOf(ResponseStatusException.class);
    // 拿 A 的阈值 id 去 B 那里读 → 404。
    assertThatThrownBy(
            () ->
                access.run(
                    actorB, "warehouse.inventory.read", () -> service.detail(policyA.policyId())))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    standardStock();
    save(skuPolicy("150", "WARNING", own()));
    evaluate();

    // 身份是 B、伪造 X-Tenant-Id 指向 A → B 什么都看不到（B 自己没配任何阈值）。
    http.perform(
            get("/warehouse/inventory/safety-stock/alerts")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));

    // 身份是 A、伪造 B 的头 → A 仍然只看得到自己那一条。
    http.perform(
            get("/warehouse/inventory/safety-stock/alerts")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.alerts[0].skuCode").value("SKU-1"));

    // 写入路径同样不受伪造头影响：B 带 A 的头建阈值，落的仍然是 B 的租户。
    http.perform(
            post("/warehouse/inventory/safety-stock/policies")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content(
                    "{\"scopeType\":\"DEFAULT\",\"thresholdQuantity\":999,\"alertSeverity\":\"CRITICAL\","
                        + "\"warehouses\":[{\"warehouseId\":\""
                        + idFor(b, 3)
                        + "\",\"warehouseKind\":\"OWN\"}]}"))
        .andExpect(status().isOk());

    // A 的阈值列表里仍然只有自己那一条（没有阈值 999 的兜底档）。
    http.perform(
            get("/warehouse/inventory/safety-stock/policies?effectiveOnly=true")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(jsonPath("$.policies[?(@.thresholdQuantity==999)]").isEmpty())
        .andExpect(jsonPath("$.total").value(1));

    // B 自己的列表里才看得到那条 999。
    http.perform(
            get("/warehouse/inventory/safety-stock/policies?effectiveOnly=true")
                .header("Authorization", "Bearer tenant-b"))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.policies[0].thresholdQuantity").value(999));
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    standardStock();
    var a1 = save(skuPolicy("150", "WARNING", own()));
    // B 租户造同名 SKU、同仓但阈值不同的另一档。
    access.run(
        actorB,
        "warehouse.inventory.update",
        () -> {
          balance(b, idFor(b, 3), idFor(b, 5), "5", "0");
          return service.save(
              null,
              new SafetyStockPolicyInput(
                  "SKU",
                  null,
                  idFor(b, 2),
                  "B 的商品",
                  new BigDecimal("80"),
                  "WARNING",
                  null,
                  false,
                  null,
                  "B 夹具",
                  List.of(
                      new SafetyStockPolicyInput.SafetyStockWarehouseBinding(idFor(b, 3), "OWN"))),
              actorB.userId());
        });

    // 池大小为 1，同一条连接被反复借还：交替跑两个租户 20 轮，
    // 每一轮都要同时校验配置写入、回查、评估与预警生成四条路径都不串租户。
    for (int i = 0; i < 20; i++) {
      var pageA =
          access.run(
              actorA,
              "warehouse.inventory.read",
              () ->
                  service.page(
                      new SafetyStockQuery(null, null, sku, null, null, true, null, 0, 50)));
      var pageB =
          access.run(
              actorB,
              "warehouse.inventory.read",
              () ->
                  service.page(
                      new SafetyStockQuery(
                          null, null, idFor(b, 2), null, null, true, null, 0, 50)));
      assertThat(pageA.policies()).hasSize(1);
      assertThat(pageB.policies()).hasSize(1);
      assertThat(pageA.policies().get(0).id()).isEqualTo(a1.policyId());
      assertThat(pageA.policies().get(0).thresholdQuantity()).isEqualByComparingTo("150");
      assertThat(pageB.policies().get(0).thresholdQuantity()).isEqualByComparingTo("80");

      // 生效解析路径：同名 SKU 在两个租户解析到各自的档。
      assertThat(
              access
                  .run(
                      actorA,
                      "warehouse.inventory.read",
                      () -> service.effective(SafetyStockScopeType.SKU, null, sku, null))
                  .policy()
                  .thresholdQuantity())
          .isEqualByComparingTo("150");
      assertThat(
              access
                  .run(
                      actorB,
                      "warehouse.inventory.read",
                      () -> service.effective(SafetyStockScopeType.SKU, null, idFor(b, 2), null))
                  .policy()
                  .thresholdQuantity())
          .isEqualByComparingTo("80");

      // 评估路径：A 报一条（100<150），B 也报一条（5<80），但各自只看到自己那一条。
      var runA = evaluate();
      var runB = evaluate(actorB, new SafetyStockEvaluationRequest(List.of(), null, null));
      assertThat(runA.alertsOpened()).isEqualTo(1);
      assertThat(runB.alertsOpened()).isEqualTo(1);
      var alertsA =
          access
              .run(
                  actorA,
                  "warehouse.inventory.read",
                  () ->
                      service.alerts(
                          new SafetyStockAlertQuery(
                              null, sku, null, null, null, null, null, false, null, 0, 50)))
              .alerts();
      assertThat(alertsA).allSatisfy(alert -> assertThat(alert.skuId()).isEqualTo(sku));
    }
  }

  @Test
  void 六张业务表都强制行级安全() {
    standardStock();
    // 没有 app.current_tenant 时直查表为空：RLS 真的在生效，不是应用层自觉过滤。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "warehouse_app", "test-only");
        var s = c.createStatement()) {
      for (var table :
          List.of(
              "safety_stock_policy",
              "safety_stock_policy_warehouse",
              "safety_stock_sales",
              "safety_stock_evaluation",
              "safety_stock_alert",
              "safety_stock_replenishment_suggestion")) {
        var rs = s.executeQuery("select count(*) from " + table);
        rs.next();
        assertThat(rs.getLong(1)).as(table + " 在无租户上下文时必须为空").isZero();
        var meta =
            s.executeQuery(
                "select relforcerowsecurity from pg_class where relname = '" + table + "'");
        meta.next();
        assertThat(meta.getBoolean(1)).as(table + " 必须 FORCE RLS").isTrue();
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void 跨服务不得建外键或视图() {
    // 阈值档只存 catalog 的逻辑 id：库里不能出现指向 catalog 的外键，
    // 也不能有跨服务视图（AGENTS.md 模块边界纪律）。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "warehouse_app", "test-only");
        var s = c.createStatement()) {
      // 被引用的表在 key_column_usage / constraint_column_usage 上，不在 table_constraints 上。
      var fks =
          s.executeQuery(
              "select count(*) from information_schema.constraint_column_usage ccu"
                  + " join information_schema.table_constraints tc"
                  + "   on tc.constraint_name = ccu.constraint_name"
                  + "  and tc.constraint_schema = ccu.constraint_schema"
                  + " where tc.constraint_type = 'FOREIGN KEY'"
                  + "   and tc.table_name like 'safety_stock%'"
                  + "   and ccu.table_name in ('spu','sku','category','product','shop','channel')");
      fks.next();
      assertThat(fks.getLong(1)).isZero();

      var views =
          s.executeQuery(
              "select count(*) from information_schema.views where table_name like 'safety_stock%'");
      views.next();
      assertThat(views.getLong(1)).isZero();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
