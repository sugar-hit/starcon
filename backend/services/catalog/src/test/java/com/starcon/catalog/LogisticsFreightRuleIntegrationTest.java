package com.starcon.catalog;

import static com.starcon.catalog.LogisticsFreightModels.*;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * 5.2.3.2 物流与头程费用：真实 PostgreSQL 16 + 强制 RLS 下的
 * 「规则不可改写」、运费差异的算法、租户隔离三断言、时间戳写回。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：渠道计费规则、阶梯、附加费、头程费用结构与
 * 实际运费全部真的落库。连接池固定为 1 条连接：只有复用同一条连接，
 * 才能真正证明归还后不会串租户。
 *
 * <p><b>零跨服务依赖</b>：本模块不调任何别的服务（渠道标识只是逻辑引用码 + 名称快照），
 * 所以测试里只有一个 identity 替身。
 *
 * <p><b>本文件最重要的五个断言</b>：
 *
 * <ol>
 *   <li>{@link #已生效的规则内容不可改写()}：改规则 = 新增一条新版本 + 停用旧版本，
 *       DB 层的触发器直接挡住内容改写。
 *   <li>{@link #改规则是新增版本且历史测算仍按旧版本算()}：不可变优先没有牺牲历史可解释性。
 *   <li>{@link #阶梯落不进任何一档时基础运费算不出来而不是零()}：「没有规则 ≠ 运费是 0」。
 *   <li>{@link #没有实际运费时差异算不出来而不是零()}：差异恒等于 0 冒充「没有差异」是要消灭的现象。
 *   <li>{@link #租户隔离三断言覆盖规则阶梯与实际运费()}：物流费率直接决定别人的成本与报价。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogisticsFreightRuleIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
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

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "attribute", "research", "compliance", "assets")
        .flatMap(
            p ->
                java.util.stream.Stream.of("read", "create", "update", "delete")
                    .map(op -> "catalog." + p + "." + op))
        .toList();
  }

  static {
    pg.start();
    try (var conn =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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
      // 只有商品读权限：写端点必须 403。
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read", "catalog.sku.read")))));
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

  @Autowired LogisticsFreightService freight;
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
  void setUp() throws Exception {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    // 每个用例一套干净夹具：本模块的规则表按「同一作用域不得有生效区间重叠」把关，
    // 上一条用例留下的规则会让下一条用例的建规则被拒 —— 那是正确的业务行为，不是测试污染。
    // TRUNCATE 以超级用户连接执行（RLS / FORCE RLS 对超管不生效），只用于夹具重建。
    try (var conn =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = conn.createStatement()) {
      s.execute(
          "truncate logistics_freight_rule, logistics_freight_rule_tier,"
              + " logistics_freight_surcharge, logistics_head_freight_structure,"
              + " logistics_head_freight_component, logistics_freight_actual cascade");
    }
  }

  // ================================================================ 夹具

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  static FreightTierInput tier(
      int seq, String min, String max, String mode, String flat, String firstLimit,
      String firstPrice, String additionalPrice) {
    return new FreightTierInput(
        seq,
        new BigDecimal(min),
        max == null ? null : new BigDecimal(max),
        mode,
        flat == null ? null : new BigDecimal(flat),
        firstLimit == null ? null : new BigDecimal(firstLimit),
        firstPrice == null ? null : new BigDecimal(firstPrice),
        additionalPrice == null ? null : new BigDecimal(additionalPrice));
  }

  /** 首重 1kg × 12.00，续重每 kg × 5.00（[0,30) 一档）。 */
  static FreightTierInput courierTier() {
    return tier(1, "0", "30", "FIRST_PLUS_ADDITIONAL", null, "1", "12.00", "5.00");
  }

  static FreightRuleInput rule(
      String channel, String carrier, String method, String basis, String currency,
      OffsetDateTime from, List<FreightTierInput> tiers) {
    return new FreightRuleInput(
        channel, channel + " 渠道", carrier, carrier == null ? "" : carrier + " 承运商",
        method, method + " 运输", basis, currency, null, null, from, null, "夹具", tiers);
  }

  static FreightSurchargeInput surcharge(
      String channel, String method, String type, String calcBasis, String rate,
      String fixed, String currency, OffsetDateTime from) {
    return new FreightSurchargeInput(
        channel, channel + " 渠道", null, "", method, method + " 运输", type, calcBasis,
        rate == null ? null : new BigDecimal(rate),
        fixed == null ? null : new BigDecimal(fixed),
        currency, from, null, "夹具");
  }

  // ================================================================ 落库与不可变

  @Test
  void 规则与阶梯真的落库且档位内容不可改写() throws Exception {
    var from = OffsetDateTime.now().minusDays(2).withNano(0);
    var saved =
        write(
            actor,
            () ->
                freight.createRule(
                    actor,
                    rule("YUNDA", null, "EXPRESS", "BY_WEIGHT", "CNY", from, List.of(courierTier()))));
    UUID ruleId = UUID.fromString(saved.id());

    // 真的落库了（不是只在内存里）。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from logistics_freight_rule where tenant_id = ? and id = ?",
                                a, ruleId)
                            .get(0, Long.class))
                .longValue())
        .isEqualTo(1L);
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from logistics_freight_rule_tier where tenant_id = ? and rule_id = ?",
                                a, ruleId)
                            .get(0, Long.class))
                .longValue())
        .isEqualTo(1L);

    // 🔴 已生效规则的**内容**不可改写（DB 层挡下）。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        sql.execute(
                            "update logistics_freight_rule set currency = ? where tenant_id = ? and id = ?",
                            "USD", a, ruleId)))
        .as("改写已生效规则的币种必须被 DB 层拒绝")
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("不可改写");
    // 币种没变。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select currency from logistics_freight_rule where tenant_id = ? and id = ?",
                                a, ruleId)
                            .get(0, String.class)))
        .isEqualTo("CNY");

    // 🔴 阶梯也不可改写（从属事实）。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        sql.execute(
                            "update logistics_freight_rule_tier set additional_price = ? where tenant_id = ? and rule_id = ?",
                            new BigDecimal("9.99"), a, ruleId)))
        .as("改写档位必须被 DB 层拒绝")
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void 改规则是新增版本且历史测算仍按旧版本算() throws Exception {
    var from = OffsetDateTime.now().minusDays(3).withNano(0);
    var old =
        write(
            actor,
            () ->
                freight.createRule(
                    actor,
                    rule("YUNDA", null, "EXPRESS", "BY_WEIGHT", "CNY", from, List.of(courierTier()))));
    UUID oldId = UUID.fromString(old.id());

    // 旧的生效期内：3.5kg ⇒ 首段 12.00 + 续段 12.50 = 24.50
    var before =
        tx(
            actor,
            () ->
                freight.estimate(
                    new LogisticsFreightService.EstimateQuery(
                        "YUNDA", null, "EXPRESS", null,
                        new BigDecimal("3.5"), null, null, null, null,
                        from.plusDays(1))));
    assertThat(before.totalAmount()).isEqualTo("24.5000");
    assertThat(before.complete()).isTrue();
    // 改规则 = 新增一条版本 + 旧版本被取代。
    var changeAt = from.plusDays(2);
    var next =
        write(
            actor,
            () ->
                freight.supersedeRule(
                    actor,
                    oldId,
                    rule(
                        "YUNDA", null, "EXPRESS", "BY_WEIGHT", "CNY", changeAt,
                        List.of(tier(1, "0", "30", "FIRST_PLUS_ADDITIONAL", null, "1", "20.00", "8.00")))));
    UUID newId = UUID.fromString(next.id());
    assertThat(newId).isNotEqualTo(oldId);
    assertThat(next.versionNo()).isEqualTo(2);

    // 旧版本：已被取代 + 指向后继版本 + 生效区间已关闭。
    var oldStatus =
        tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select status, superseded_by_id, effective_to from logistics_freight_rule"
                                + " where tenant_id = ? and id = ?",
                            a, oldId)
                        .get("status", String.class));
    assertThat(oldStatus).isEqualTo("SUPERSEDED");
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select superseded_by_id from logistics_freight_rule where tenant_id = ? and id = ?",
                                a, oldId)
                            .get("superseded_by_id", UUID.class)))
        .isEqualTo(newId);

    // 历史测算仍按旧版本算（旧区间内仍是 24.50）。
    var afterOld =
        tx(
            actor,
            () ->
                freight.estimate(
                    new LogisticsFreightService.EstimateQuery(
                        "YUNDA", null, "EXPRESS", null,
                        new BigDecimal("3.5"), null, null, null, null,
                        from.plusDays(1))));
    assertThat(afterOld.totalAmount()).isEqualTo("24.5000");

    // 新区间按新版本算：首段 20.00 + 续段 2.5 × 8.00 = 40.00
    var afterNew =
        tx(
            actor,
            () ->
                freight.estimate(
                    new LogisticsFreightService.EstimateQuery(
                        "YUNDA", null, "EXPRESS", null,
                        new BigDecimal("3.5"), null, null, null, null,
                        changeAt.plusDays(1))));
    assertThat(afterNew.totalAmount()).isEqualTo("40.0000");
  }

  @Test
  void 停用必须写理由且停用后不再参与测算() throws Exception {
    var from = OffsetDateTime.now().minusDays(2).withNano(0);
    var saved =
        write(
            actor,
            () ->
                freight.createRule(
                    actor,
                    rule("YUNDA", null, "EXPRESS", "BY_WEIGHT", "CNY", from, List.of(courierTier()))));
    UUID ruleId = UUID.fromString(saved.id());
    assertThatThrownBy(() -> write(actor, () -> freight.disableRule(actor, ruleId, "  ")))
        .isInstanceOf(ResponseStatusException.class);
    write(actor, () -> freight.disableRule(actor, ruleId, "渠道换了合作方"));

    var estimate =
        tx(
            actor,
            () ->
                freight.estimate(
                    new LogisticsFreightService.EstimateQuery(
                        "YUNDA", null, "EXPRESS", null,
                        new BigDecimal("3.5"), null, null, null, null, OffsetDateTime.now())));
    assertThat(estimate.complete()).isFalse();
    assertThat(estimate.totalAmount()).isNull();
  }

  @Test
  void 同一作用域生效区间重叠被拒绝() throws Exception {
    var from = OffsetDateTime.now().minusDays(2).withNano(0);
    write(
        actor,
        () ->
            freight.createRule(
                actor,
                rule("YUNDA", null, "EXPRESS", "BY_WEIGHT", "CNY", from, List.of(courierTier()))));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        freight.createRule(
                            actor,
                            rule(
                                "YUNDA", null, "EXPRESS", "BY_WEIGHT", "CNY",
                                from.plusDays(1), List.of(courierTier())))))
        .as("同一作用域下生效区间重叠的两条规则必须被 DB 层拒绝")
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("重叠");
  }

  // ================================================================ 测算

  @Test
  void 阶梯落不进任何一档时基础运费算不出来而不是零() throws Exception {
    var from = OffsetDateTime.now().minusDays(2).withNano(0);
    write(
        actor,
        () ->
            freight.createRule(
                actor,
                rule(
                    "YUNDA", null, "EXPRESS", "BY_WEIGHT", "CNY", from,
                    List.of(tier(1, "5", "30", "FIRST_PLUS_ADDITIONAL", null, "6", "12.00", "5.00")))));
    var estimate =
        tx(
            actor,
            () ->
                freight.estimate(
                    new LogisticsFreightService.EstimateQuery(
                        "YUNDA", null, "EXPRESS", null,
                        new BigDecimal("2"), null, null, null, null, OffsetDateTime.now())));
    assertThat(estimate.complete()).isFalse();
    assertThat(estimate.totalAmount()).isNull();
    assertThat(estimate.baseAmount()).isNull();
    var baseStep =
        estimate.steps().stream().filter(s -> "base".equals(s.step())).findFirst().orElseThrow();
    assertThat(baseStep.applicable()).isFalse();
    assertThat(baseStep.amount()).isNull();
  }

  @Test
  void 没有生效规则时运费算不出来而不是零() throws Exception {
    var estimate =
        tx(
            actor,
            () ->
                freight.estimate(
                    new LogisticsFreightService.EstimateQuery(
                        "YUNDA", null, "EXPRESS", null,
                        new BigDecimal("2"), null, null, null, null, OffsetDateTime.now())));
    assertThat(estimate.complete()).isFalse();
    assertThat(estimate.totalAmount()).isNull();
    assertThat(estimate.note()).contains("算不出来");
  }

  @Test
  void 附加费逐步拆解且合计与各步一致() throws Exception {
    var from = OffsetDateTime.now().minusDays(2).withNano(0);
    write(
        actor,
        () ->
            freight.createRule(
                actor,
                rule("YUNDA", null, "EXPRESS", "BY_WEIGHT", "CNY", from, List.of(courierTier()))));
    write(
        actor,
        () ->
            freight.createSurcharge(
                actor,
                surcharge(
                    "YUNDA", "EXPRESS", "FUEL", "PERCENT_OF_BASE_FREIGHT", "0.15", null, "CNY",
                    from)));

    var estimate =
        tx(
            actor,
            () ->
                freight.estimate(
                    new LogisticsFreightService.EstimateQuery(
                        "YUNDA", null, "EXPRESS", null,
                        new BigDecimal("3.5"), null, null, null, null, OffsetDateTime.now())));
    assertThat(estimate.complete()).isTrue();
    // 基础 24.5000 + 燃油 3.6750 = 28.1750
    assertThat(estimate.baseAmount()).isEqualTo("24.5000");
    assertThat(estimate.surchargeAmount()).isEqualTo("3.6750");
    assertThat(estimate.totalAmount()).isEqualTo("28.1750");
    // 逐步拆解：每一步都要有算式说明（不是裸数字）。
    assertThat(estimate.steps()).hasSizeGreaterThanOrEqualTo(5);
    var fuelStep =
        estimate.steps().stream()
            .filter(s -> "surcharge:FUEL".equals(s.step()))
            .findFirst()
            .orElseThrow();
    assertThat(fuelStep.amount()).isEqualTo("3.6750");
    assertThat(fuelStep.basis()).contains("0.15");
    var chargeableStep =
        estimate.steps().stream().filter(s -> "chargeable".equals(s.step())).findFirst().orElseThrow();
    assertThat(chargeableStep.basis()).contains("3.5");
  }

  @Test
  void 同一渠道有多个计费口径时必须显式指定否则算不出来() throws Exception {
    var from = OffsetDateTime.now().minusDays(2).withNano(0);
    write(
        actor,
        () ->
            freight.createRule(
                actor,
                rule(
                    "YUNDA", null, "EXPRESS", "BY_WEIGHT", "CNY", from,
                    List.of(courierTier()))));
    write(
        actor,
        () ->
            freight.createRule(
                actor,
                rule(
                    "YUNDA", null, "EXPRESS", "BY_PIECE", "CNY", from,
                    List.of(tier(1, "0", null, "FLAT_PER_TIER", "8.00", null, null, null)))));

    var ambiguous =
        tx(
            actor,
            () ->
                freight.estimate(
                    new LogisticsFreightService.EstimateQuery(
                        "YUNDA", null, "EXPRESS", null,
                        new BigDecimal("3.5"), null, null, null, new BigDecimal("3"),
                        OffsetDateTime.now())));
    assertThat(ambiguous.complete()).isFalse();
    assertThat(ambiguous.totalAmount()).isNull();

    // 显式指定口径就能算出来。
    var explicit =
        tx(
            actor,
            () ->
                freight.estimate(
                    new LogisticsFreightService.EstimateQuery(
                        "YUNDA", null, "EXPRESS", "BY_PIECE",
                        null, null, null, null, new BigDecimal("3"),
                        OffsetDateTime.now())));
    assertThat(explicit.complete()).isTrue();
    assertThat(explicit.totalAmount()).isEqualTo("8.0000");
  }

  @Test
  void 头程费用结构逐步拆解且混币时合计算不出来() throws Exception {
    var from = OffsetDateTime.now().minusDays(2).withNano(0);
    var saved =
        write(
            actor,
            () ->
                freight.createHeadStructure(
                    actor,
                    new HeadFreightStructureInput(
                        "SEA", "海运", "CN-SZ", "深圳", "US-LAX", "洛杉矶", "CNY",
                        from, null, "夹具",
                        List.of(
                            new HeadFreightComponentInput(
                                1, "LINE_HAUL", "海运费", "PER_CBM", new BigDecimal("120.00"), "CNY"),
                            new HeadFreightComponentInput(
                                2, "TRUCKING", "拖车费", "PER_CONTAINER", new BigDecimal("900.00"), "CNY")))));
    assertThat(saved.id()).isNotBlank();

    var estimate =
        tx(
            actor,
            () ->
                freight.headEstimate(
                    new LogisticsFreightService.HeadEstimateQuery(
                        "SEA", "CN-SZ", "US-LAX",
                        new BigDecimal("1"), new BigDecimal("12"), new BigDecimal("2"),
                        new BigDecimal("300"), OffsetDateTime.now())));
    assertThat(estimate.complete()).isTrue();
    // 120 × 12 = 1440；900 × 2 = 1800 ⇒ 3240
    assertThat(estimate.totalAmount()).isEqualTo("3240.0000");
    assertThat(estimate.steps()).hasSize(3);

    // 混币 ⇒ 合计算不出来（跨币种绝不相加）。
    write(
        actor,
        () ->
            freight.createHeadStructure(
                actor,
                new HeadFreightStructureInput(
                    "AIR", "空运", "CN-SZ", "深圳", "US-LAX", "洛杉矶", "CNY",
                    from, null, "夹具",
                    List.of(
                        new HeadFreightComponentInput(
                            1, "LINE_HAUL", "空运费", "PER_KG", new BigDecimal("3.00"), "CNY"),
                        new HeadFreightComponentInput(
                            2, "INSURANCE", "保险费", "PER_SHIPMENT", new BigDecimal("80.00"), "USD")))));
    var mixed =
        tx(
            actor,
            () ->
                freight.headEstimate(
                    new LogisticsFreightService.HeadEstimateQuery(
                        "AIR", "CN-SZ", "US-LAX",
                        new BigDecimal("1"), new BigDecimal("12"), new BigDecimal("2"),
                        new BigDecimal("300"), OffsetDateTime.now())));
    assertThat(mixed.complete()).isFalse();
    assertThat(mixed.totalAmount()).isNull();
    assertThat(mixed.note()).contains("跨币种");
  }

  // ================================================================ 实际运费与差异

  @Test
  void 没有实际运费时差异算不出来而不是零() throws Exception {
    var from = OffsetDateTime.now().minusDays(2).withNano(0);
    write(
        actor,
        () ->
            freight.createRule(
                actor,
                rule("YUNDA", null, "EXPRESS", "BY_WEIGHT", "CNY", from, List.of(courierTier()))));
    var variance =
        tx(
            actor,
            () ->
                freight.variance(
                    new LogisticsFreightService.VarianceQuery(
                        null, null, null, LocalDate.now().minusDays(5), LocalDate.now())));
    assertThat(variance.available()).isFalse();
    assertThat(variance.reason()).contains("算不出来");
    assertThat(variance.groups()).isEmpty();
  }

  @Test
  void 实际运费与规则测算的差额算得出来且差额由服务层现算() throws Exception {
    var from = OffsetDateTime.now().minusDays(2).withNano(0);
    write(
        actor,
        () ->
            freight.createRule(
                actor,
                rule("YUNDA", null, "EXPRESS", "BY_WEIGHT", "CNY", from, List.of(courierTier()))));
    write(
        actor,
        () ->
            freight.recordActual(
                actor,
                new FreightActualInput(
                    "YUNDA", "YUNDA 渠道", null, "", "EXPRESS", "EXPRESS 运输", "BY_WEIGHT",
                    new BigDecimal("30.00"), null, null, "CNY",
                    new BigDecimal("3.5"), new BigDecimal("1"),
                    LocalDate.now(), "YUNDA-20261021", "")));

    var variance =
        tx(
            actor,
            () ->
                freight.variance(
                    new LogisticsFreightService.VarianceQuery(
                        null, null, null, LocalDate.now().minusDays(5), LocalDate.now())));
    assertThat(variance.available()).isTrue();
    assertThat(variance.groups()).hasSize(1);
    var group = variance.groups().get(0);
    assertThat(group.actualAmount()).isEqualTo("30.0000");
    assertThat(group.estimatedAmount()).isEqualTo("24.5000");
    assertThat(group.varianceAmount()).isEqualTo("5.5000");
    assertThat(group.comparableCount()).isEqualTo(1L);
  }

  @Test
  void 实际运费是只追加的事实改金额会被数据库层拒绝() throws Exception {
    var saved =
        write(
            actor,
            () ->
                freight.recordActual(
                    actor,
                    new FreightActualInput(
                        "YUNDA", "YUNDA 渠道", null, "", "EXPRESS", "EXPRESS 运输", "BY_WEIGHT",
                        new BigDecimal("30.00"), null, null, "CNY",
                        new BigDecimal("3.5"), new BigDecimal("1"),
                        LocalDate.now(), "", "")));
    UUID id = UUID.fromString(saved.id());
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        sql.execute(
                            "update logistics_freight_actual set amount = ? where tenant_id = ? and id = ?",
                            new BigDecimal("1.00"), a, id)))
        .as("改写实际运费金额必须被 DB 层拒绝")
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("只追加");
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select amount from logistics_freight_actual where tenant_id = ? and id = ?",
                                a, id)
                            .get(0, BigDecimal.class)))
        .isEqualByComparingTo("30.00");
  }

  @Test
  void 作废必须写理由且作废后不再进入差异() throws Exception {
    var from = OffsetDateTime.now().minusDays(2).withNano(0);
    write(
        actor,
        () ->
            freight.createRule(
                actor,
                rule("YUNDA", null, "EXPRESS", "BY_WEIGHT", "CNY", from, List.of(courierTier()))));
    var saved =
        write(
            actor,
            () ->
                freight.recordActual(
                    actor,
                    new FreightActualInput(
                        "YUNDA", "YUNDA 渠道", null, "", "EXPRESS", "EXPRESS 运输", "BY_WEIGHT",
                        new BigDecimal("30.00"), null, null, "CNY",
                        new BigDecimal("3.5"), new BigDecimal("1"),
                        LocalDate.now(), "", "")));
    UUID id = UUID.fromString(saved.id());
    assertThatThrownBy(() -> write(actor, () -> freight.voidActual(actor, id, "  ")))
        .isInstanceOf(ResponseStatusException.class);
    write(actor, () -> freight.voidActual(actor, id, "重复登记"));

    var variance =
        tx(
            actor,
            () ->
                freight.variance(
                    new LogisticsFreightService.VarianceQuery(
                        null, null, null, LocalDate.now().minusDays(5), LocalDate.now())));
    assertThat(variance.available()).isFalse();
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 租户隔离三断言覆盖规则阶梯与实际运费() throws Exception {
    var from = OffsetDateTime.now().minusDays(2).withNano(0);
    var myRule =
        write(
            actor,
            () ->
                freight.createRule(
                    actor,
                    rule("YUNDA", null, "EXPRESS", "BY_WEIGHT", "CNY", from, List.of(courierTier()))));
    var theirRule =
        write(
            actorB,
            () ->
                freight.createRule(
                    actorB,
                    rule("SF", null, "EXPRESS", "BY_WEIGHT", "CNY", from, List.of(courierTier()))));
    write(
        actor,
        () ->
            freight.recordActual(
                actor,
                new FreightActualInput(
                    "YUNDA", "YUNDA 渠道", null, "", "EXPRESS", "EXPRESS 运输", "BY_WEIGHT",
                    new BigDecimal("30.00"), null, null, "CNY",
                    new BigDecimal("3.5"), new BigDecimal("1"),
                    LocalDate.now(), "A 的账单", "")));

    // 两边都必须真的有数据 —— 否则下面的断言就是空的。
    assertThat(tx(actor, () -> freight.listRules(new LogisticsFreightService.RuleQuery(null, null, null, null, null, null, 0, 200)).items()))
        .isNotEmpty();
    assertThat(tx(actorB, () -> freight.listRules(new LogisticsFreightService.RuleQuery(null, null, null, null, null, null, 0, 200)).items()))
        .isNotEmpty();

    // 1) 两租户互不可见
    assertThat(tx(actor, () -> freight.listRules(new LogisticsFreightService.RuleQuery(null, "SF", null, null, null, null, 0, 200)).items()))
        .isEmpty();
    assertThat(tx(actorB, () -> freight.listRules(new LogisticsFreightService.RuleQuery(null, "YUNDA", null, null, null, null, 0, 200)).items()))
        .isEmpty();
    assertThat(tx(actor, () -> freight.listActuals(new LogisticsFreightService.ActualQuery(null, null, null, null, null, 0, 200)).items()))
        .isNotEmpty();
    assertThat(tx(actorB, () -> freight.listActuals(new LogisticsFreightService.ActualQuery(null, null, null, null, null, 0, 200)).items()))
        .isEmpty();
    // 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from logistics_freight_rule where tenant_id = ? and id = ?",
                                b, UUID.fromString(theirRule.id()))
                            .get(0, Long.class))
                .longValue())
        .isZero();

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(
            get("/catalog/logistics-freight/rules/" + theirRule.id())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/logistics-freight/rules/" + myRule.id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 伪造头下的写入仍落在**调用者自己**名下 —— 断言行确实落在 A 名下，不只断言 B 看不见。
    http.perform(
            post("/catalog/logistics-freight/rules")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        rule("JD", null, "EXPRESS", "BY_WEIGHT", "CNY", from, List.of(courierTier())))))
        .andExpect(status().isOk());
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select tenant_id from logistics_freight_rule where channel_code = ? and shipping_method_code = ?",
                                "JD", "EXPRESS")
                            .get(0, UUID.class)))
        .isEqualTo(a);

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言两个集合不相交
    for (int i = 0; i < 5; i++) {
      var mySet =
          tx(
                  actor,
                  () ->
                      freight.listRules(new LogisticsFreightService.RuleQuery(null, null, null, null, null, null, 0, 200))
                          .items()
                          .stream()
                          .map(FreightRuleRow::id)
                          .collect(java.util.stream.Collectors.toSet()));
      var theirSet =
          tx(
                  actorB,
                  () ->
                      freight.listRules(new LogisticsFreightService.RuleQuery(null, null, null, null, null, null, 0, 200))
                          .items()
                          .stream()
                          .map(FreightRuleRow::id)
                          .collect(java.util.stream.Collectors.toSet()));
      assertThat(mySet).isNotEmpty();
      assertThat(theirSet).isNotEmpty();
      assertThat(java.util.Collections.disjoint(mySet, theirSet)).isTrue();
      assertThat(tx(actor, () -> freight.listActuals(new LogisticsFreightService.ActualQuery(null, null, null, null, null, 0, 200)).items()))
          .isNotEmpty();
      assertThat(tx(actorB, () -> freight.listActuals(new LogisticsFreightService.ActualQuery(null, null, null, null, null, 0, 200)).items()))
          .isEmpty();
    }
  }

  // ================================================================ 时间戳

  @Test
  void 生效时间按时刻写回不被时区偏移() throws Exception {
    // 东八区的一个具体时刻，微秒精度。
    var instant = OffsetDateTime.of(2026, 3, 1, 8, 30, 0, 123456000, ZoneOffset.ofHours(8));
    var saved =
        write(
            actor,
            () ->
                freight.createRule(
                    actor,
                    rule("YUNDA", null, "EXPRESS", "BY_WEIGHT", "CNY", instant, List.of(courierTier()))));
    var stored =
        tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select effective_from from logistics_freight_rule where tenant_id = ? and id = ?",
                            a, UUID.fromString(saved.id()))
                        .get(0, OffsetDateTime.class));
    assertThat(stored).isNotNull();
    assertThat(stored.toInstant())
        .as("写回的时刻必须与传入的时刻是同一个瞬间（不能偏 −28800s）")
        .isEqualTo(instant.toInstant());
    // 明细接口给出的也是同一个瞬间。
    var detail = tx(actor, () -> freight.ruleDetail(UUID.fromString(saved.id())));
    assertThat(OffsetDateTime.parse(detail.rule().effectiveFrom()).toInstant())
        .isEqualTo(instant.toInstant());
  }

  // ================================================================ 权限

  @Test
  void 写端点需要写权限() throws Exception {
    http.perform(
            post("/catalog/logistics-freight/rules")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        rule(
                            "YUNDA", null, "EXPRESS", "BY_WEIGHT", "CNY", OffsetDateTime.now(),
                            List.of(courierTier())))))
        .andExpect(status().isForbidden());
  }
}
