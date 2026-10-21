package com.starcon.catalog;

import static com.starcon.catalog.PlatformFeeModels.*;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
 * 5.2.3.1 平台费用：真实 PostgreSQL 16 + 强制 RLS 下的
 * 「规则与实际费用是两个独立事实」、费用差异的算法、租户隔离三断言、
 * 时间戳写回，以及上游目录不可达时**不拿空清单冒充「这个租户没有平台」**。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：规则、阶梯与实际费用全部真的落库。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>跨服务依赖的处理</b>：平台 / 站点目录来自 channel 服务（另一个数据库）。
 * 生产路径是真实的 {@code RestClient} HTTP 调用，本测试用一个 {@link HttpServer} 起一个
 * <b>确定性的替身</b>来满足它 —— 替身只存在于测试代码里，且被断言「确实收到了 catalog 透传的
 * {@code Authorization} 头」，所以它证明的是<b>生产代码真的走 HTTP 且透传了租户上下文</b>，
 * 而不是把替身逻辑塞进了生产代码。
 *
 * <p><b>本文件最重要的五个断言</b>：
 *
 * <ol>
 *   <li>{@link #规则与实际费用是两个独立事实且差额算得出来()}：蓝图加粗句的技术落点。
 *       规则算出的 3.72 与平台实收的 3.72 / 4.50 是**两份独立的事实**，
 *       差额由服务层现算 —— 合并成一个字段的话这个断言根本写不出来。
 *   <li>{@link #没有实际费用时差异算不出来而不是零()}：差异恒等于 0 冒充「没有差异」，
 *       是蓝图加粗句明确要消灭的现象。
 *   <li>{@link #阶梯落不进任何一档时费用项是算不出来而不是零()}：同上，费用项层面。
 *   <li>{@link #租户隔离三断言覆盖规则阶梯与实际费用()}：平台费率直接决定别人的利润与报价。
 *   <li>{@link #生效时间按时刻写回不被时区偏移()}：timestamptz 走 {@code ?::timestamptz} +
 *       {@code OffsetDateTime} 绑定；断言**写回的值**，不是只断言没抛异常。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformFeeIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer channel;
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 渠道替身最近一次收到的 Authorization 头：用来断言租户上下文真的透传出去了。 */
  static final AtomicReference<String> lastBearer = new AtomicReference<>();

  /** 置位后替身一律回 503：模拟「渠道服务不可用」。 */
  static final AtomicBoolean channelDown = new AtomicBoolean(false);

  /** 平台目录替身返回的平台 / 站点清单（与 channel 的全局码表同形）。 */
  static final List<Map<String, Object>> PLATFORMS = new ArrayList<>();

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
      startChannelStub();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /**
   * 渠道替身：一个真实的 HTTP 服务器，形状与 channel 的 {@code GET /channel/platforms} 逐字一致。
   *
   * <p>它刻意<b>不</b>校验 Authorization（真实 channel 会向 identity 校验），
   * 因为本测试要断言的正是「catalog 把这个头透传出去了」。
   */
  static void startChannelStub() throws Exception {
    channel = HttpServer.create(new InetSocketAddress(0), 0);
    channel.createContext(
        "/channel/platforms",
        e -> {
          lastBearer.set(e.getRequestHeaders().getFirst("Authorization"));
          if (channelDown.get()) {
            e.sendResponseHeaders(503, -1);
            e.close();
            return;
          }
          byte[] out = JSON.writeValueAsBytes(PLATFORMS);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, out.length);
          e.getResponseBody().write(out);
          e.close();
        });
    channel.start();
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
    r.add("CHANNEL_URL", () -> "http://localhost:" + channel.getAddress().getPort());
    // 单连接：只有复用同一条连接，才能真正证明归还之后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired PlatformFeeService fees;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    channel.stop(0);
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setUp() throws Exception {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    channelDown.set(false);
    lastBearer.set(null);
    PLATFORMS.clear();
    PLATFORMS.add(platform("AMAZON", "亚马逊", site("US", "美国站", "USD"), site("DE", "德国站", "EUR")));
    PLATFORMS.add(platform("SHOPIFY", "Shopify", site("GLOBAL", "全球站", "USD")));
    // 每个用例一套干净夹具：本模块的规则表按「同一作用域不得有生效区间重叠的规则」把关，
    // 上一条用例留下的规则会让下一条用例的建规则被 409 —— 那是正确的业务行为，不是测试污染。
    // TRUNCATE 以超级用户连接执行（RLS / FORCE RLS 对超管不生效），只用于夹具重建。
    try (var conn =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = conn.createStatement()) {
      s.execute(
          "truncate platform_fee_rule, platform_fee_rule_tier, platform_fee_actual,"
              + " product_category, product_category_tree cascade");
    }
  }

  // ================================================================ 夹具

  static Map<String, Object> platform(String code, String name, Map<String, Object>... sites) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", UUID.nameUUIDFromBytes(code.getBytes()).toString());
    row.put("code", code);
    row.put("name", name);
    row.put("integration", "API");
    row.put("supportStatus", "SUPPORTED");
    row.put("capabilities", List.of());
    row.put("marketplaces", List.of(sites));
    return row;
  }

  static Map<String, Object> site(String code, String name, String currency) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", UUID.nameUUIDFromBytes(code.getBytes()).toString());
    row.put("code", code);
    row.put("name", name);
    row.put("countryCode", code);
    row.put("defaultCurrency", currency);
    row.put("defaultTimezone", "UTC");
    row.put("defaultLocale", "en-US");
    row.put("currencyScale", 2);
    row.put("vatRequired", false);
    return row;
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  /** 建一棵分类树 + 一个类目，返回类目 id。 */
  UUID category(AccessService.Access who, String treeCode, String treeKind, String platformCode, String code) {
    return write(
        who,
        () -> {
          var treeId = UUID.randomUUID();
          sql.execute(
              "insert into product_category_tree(id,tenant_id,code,name,tree_kind,platform_code,enabled)"
                  + " values(?,?,?,?,?,?,true)",
              treeId,
              who.tenantId(),
              treeCode,
              treeKind + " 树",
              treeKind,
              treeKind.equals("PLATFORM") ? platformCode : null);
          var categoryId = UUID.randomUUID();
          sql.execute(
              "insert into product_category(id,tenant_id,tree_id,code,name,parent_id,position,enabled,path_ids)"
                  + " values(?,?,?,?,?,null,0,true,?)",
              categoryId,
              who.tenantId(),
              treeId,
              code,
              code,
              new UUID[] {categoryId});
          return categoryId;
        });
  }

  FeeRuleInput rule(
      String platform,
      String site,
      UUID internalCategory,
      String feeType,
      String basis,
      BigDecimal rate,
      BigDecimal fixed,
      String currency,
      OffsetDateTime from) {
    return new FeeRuleInput(
        platform,
        site,
        internalCategory,
        null,
        feeType,
        basis,
        rate,
        fixed,
        currency,
        null,
        null,
        null,
        null,
        from,
        null,
        true,
        "测试规则",
        null);
  }

  // ================================================================ 核心断言

  /**
   * 蓝图加粗句的技术落点：规则与实际费用是**两个独立的事实**，差额算得出来。
   *
   * <p>如果把两者合并成一个字段（「费用金额」列），下面这条断言根本写不出来 ——
   * 而「算不出差额」正是蓝图加粗句要消灭的现象。
   */
  @Test
  void 规则与实际费用是两个独立事实且差额算得出来() throws Exception {
    var from = OffsetDateTime.now().minusDays(1).withNano(0);
    // 规则：AMAZON / US / 佣金 15%。
    var saved =
        write(
            actor,
            () ->
                fees.save(
                    actor,
                    null,
                    rule("AMAZON", "US", null, "COMMISSION", "PERCENTAGE_OF_SALE",
                        new BigDecimal("0.15"), null, null, from),
                    "Bearer tenant-a"));
    assertThat(saved.id()).isNotBlank();

    // 实际费用：平台实收 4.50，而规则算出来是 24.80 × 15% = 3.72。
    var actual =
        write(
            actor,
            () ->
                fees.recordActual(
                    actor,
                    new FeeActualInput(
                        "AMAZON", "US", null, null, "COMMISSION",
                        new BigDecimal("4.50"), "USD",
                        new BigDecimal("24.80"), new BigDecimal("1"),
                        LocalDate.now(), "AMZ-2026-0001", "平台账单观察值"),
                    "Bearer tenant-a"));
    assertThat(actual.amount()).isEqualTo("4.50");

    // 测算：规则口径 = 3.72。
    var estimate =
        tx(
            actor,
            () ->
                fees.estimate(
                    new PlatformFeeService.EstimateQuery(
                        "AMAZON", "US", null, null,
                        new BigDecimal("24.80"), new BigDecimal("1"), "USD", OffsetDateTime.now())));
    var commission =
        estimate.items().stream()
            .filter(i -> "COMMISSION".equals(i.feeType()))
            .findFirst()
            .orElseThrow();
    assertThat(commission.amount()).isEqualTo("3.7200");
    assertThat(commission.applicable()).isTrue();

    // 差异：实际 4.50 − 测算 3.72 = 0.78。
    var variance =
        tx(
            actor,
            () ->
                fees.variance(
                    new PlatformFeeService.VarianceQuery(
                        "AMAZON", "US", null, LocalDate.now().minusDays(1), LocalDate.now())));
    assertThat(variance.available()).isTrue();
    assertThat(variance.groups()).hasSize(1);
    var group = variance.groups().get(0);
    assertThat(group.actualAmount()).isEqualTo("4.5000");
    assertThat(group.estimatedAmount()).isEqualTo("3.7200");
    assertThat(group.varianceAmount()).isEqualTo("0.7800");
    assertThat(group.comparableCount()).isEqualTo(1L);

    // 两个事实真的是**两张表里的两行**，不是一个字段。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from platform_fee_rule where tenant_id = ?", a)
                            .get(0, Long.class))
                .longValue())
        .isEqualTo(1L);
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from platform_fee_actual where tenant_id = ?", a)
                            .get(0, Long.class))
                .longValue())
        .isEqualTo(1L);
  }

  /**
   * 🔴 没有实际费用时，差异**算不出来**，不是 0。
   *
   * <p>蓝图加粗句：「两者差额要在利润报表中显性展示为『费用差异』，否则测算永远不准却不知道为什么」。
   * 显示「差异 0.00」等于宣称「平台完全按规则收了钱」—— 而真实情况是「没有可比的实际费用」。
   */
  @Test
  void 没有实际费用时差异算不出来而不是零() throws Exception {
    var variance =
        tx(
            actor,
            () ->
                fees.variance(
                    new PlatformFeeService.VarianceQuery(
                        "AMAZON", "US", null, LocalDate.now().minusDays(7), LocalDate.now())));
    assertThat(variance.available()).isFalse();
    assertThat(variance.reason()).contains("算不出来").contains("不是「差异为 0」");
    assertThat(variance.groups()).isEmpty();
  }

  /**
   * 🔴 阶梯落不进任何一档时，费用项是**算不出来**，不是 0。
   */
  @Test
  void 阶梯落不进任何一档时费用项是算不出来而不是零() throws Exception {
    var from = OffsetDateTime.now().minusDays(1).withNano(0);
    var saved =
        write(
            actor,
            () ->
                fees.save(
                    actor,
                    null,
                    rule("AMAZON", "US", null, "COMMISSION", "TIERED_PERCENTAGE",
                        null, null, null, from),
                    "Bearer tenant-a"));
    var ruleId = UUID.fromString(saved.id());
    write(
        actor,
        () ->
            fees.replaceTiers(
                actor,
                ruleId,
                List.of(new FeeTierInput(1, new BigDecimal("0"), new BigDecimal("100"), new BigDecimal("0.10")))));

    // 150 落在所有档位之外 ⇒ 规则不适用。
    var estimate =
        tx(
            actor,
            () ->
                fees.estimate(
                    new PlatformFeeService.EstimateQuery(
                        "AMAZON", "US", null, null,
                        new BigDecimal("150"), new BigDecimal("1"), "USD", OffsetDateTime.now())));
    var commission =
        estimate.items().stream()
            .filter(i -> "COMMISSION".equals(i.feeType()))
            .findFirst()
            .orElseThrow();
    assertThat(commission.applicable()).isFalse();
    assertThat(commission.amount()).isNull();
    assertThat(commission.note()).contains("不适用");
    // 合计不完整 ⇒ 合计是 null（**不是** 0）。
    assertThat(estimate.complete()).isFalse();
    assertThat(estimate.totalAmount()).isNull();

    // 落在档内时才有值。
    var inside =
        tx(
            actor,
            () ->
                fees.estimate(
                    new PlatformFeeService.EstimateQuery(
                        "AMAZON", "US", null, null,
                        new BigDecimal("80"), new BigDecimal("1"), "USD", OffsetDateTime.now())));
    var hit =
        inside.items().stream()
            .filter(i -> "COMMISSION".equals(i.feeType()))
            .findFirst()
            .orElseThrow();
    assertThat(hit.amount()).isEqualTo("8.0000");
  }

  /**
   * 订阅费分摊是真的「周期总额 ÷ 分摊基数」除出来的，不是拍好的一个数。
   */
  @Test
  void 订阅费分摊是周期总额除以分摊基数() throws Exception {
    var from = OffsetDateTime.now().minusDays(1).withNano(0);
    var input =
        new FeeRuleInput(
            "AMAZON", "US", null, null, "SUBSCRIPTION", "FIXED_PER_ORDER",
            null, null, null,
            new BigDecimal("39.99"), "USD", "PER_ORDER", new BigDecimal("100"),
            from, null, true, "月度订阅分摊", null);
    write(actor, () -> fees.save(actor, null, input, "Bearer tenant-a"));

    var estimate =
        tx(
            actor,
            () ->
                fees.estimate(
                    new PlatformFeeService.EstimateQuery(
                        "AMAZON", "US", null, null,
                        new BigDecimal("20"), new BigDecimal("1"), "USD", OffsetDateTime.now())));
    var subscription =
        estimate.items().stream()
            .filter(i -> "SUBSCRIPTION".equals(i.feeType()))
            .findFirst()
            .orElseThrow();
    // 39.99 ÷ 100 = 0.3999
    assertThat(subscription.amount()).isEqualTo("0.3999");
    assertThat(subscription.currency()).isEqualTo("USD");
    assertThat(subscription.basis()).contains("39.99").contains("100");
  }

  /**
   * 🔴 租户隔离三断言，覆盖规则、阶梯与实际费用。
   *
   * <p>平台费率直接决定别人的利润与报价（能看出竞争者在某个平台给多少佣金），
   * A 读到 B 的费率会直接让 A 的报价算错。
   */
  @Test
  void 租户隔离三断言覆盖规则阶梯与实际费用() throws Exception {
    var from = OffsetDateTime.now().minusDays(1).withNano(0);
    var myRule =
        write(
            actor,
            () ->
                fees.save(
                    actor,
                    null,
                    rule("AMAZON", "US", null, "COMMISSION", "PERCENTAGE_OF_SALE",
                        new BigDecimal("0.15"), null, null, from),
                    "Bearer tenant-a"));
    var theirRule =
        write(
            actorB,
            () ->
                fees.save(
                    actorB,
                    null,
                    rule("SHOPIFY", "GLOBAL", null, "COMMISSION", "PERCENTAGE_OF_SALE",
                        new BigDecimal("0.29"), null, null, from),
                    "Bearer tenant-b"));
    write(
        actor,
        () ->
            fees.recordActual(
                actor,
                new FeeActualInput(
                    "AMAZON", "US", null, null, "COMMISSION",
                    new BigDecimal("4.50"), "USD",
                    new BigDecimal("24.80"), new BigDecimal("1"),
                    LocalDate.now(), "A 的账单", ""),
                "Bearer tenant-a"));

    // 两边都必须真的有数据 —— 否则下面的断言就是空的。
    assertThat(tx(actor, () -> fees.list(new PlatformFeeService.RuleQuery(null, "AMAZON", null, null, null, 0, 200)).items()))
        .isNotEmpty();
    assertThat(tx(actorB, () -> fees.list(new PlatformFeeService.RuleQuery(null, "SHOPIFY", null, null, null, 0, 200)).items()))
        .isNotEmpty();

    // 1) 两租户互不可见
    assertThat(tx(actor, () -> fees.list(new PlatformFeeService.RuleQuery(null, "SHOPIFY", null, null, null, 0, 200)).items()))
        .isEmpty();
    assertThat(tx(actorB, () -> fees.list(new PlatformFeeService.RuleQuery(null, "AMAZON", null, null, null, 0, 200)).items()))
        .isEmpty();
    assertThat(tx(actor, () -> fees.listActuals(new PlatformFeeService.ActualQuery("AMAZON", null, null, null, null, 0, 200)).items()))
        .isNotEmpty();
    assertThat(tx(actorB, () -> fees.listActuals(new PlatformFeeService.ActualQuery("AMAZON", null, null, null, null, 0, 200)).items()))
        .isEmpty();
    // 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from platform_fee_rule where tenant_id = ? and id = ?",
                                b, UUID.fromString(theirRule.id()))
                            .get(0, Long.class))
                .longValue())
        .isZero();

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(
            get("/catalog/platform-fees/rules/" + theirRule.id())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/platform-fees/rules/" + myRule.id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 伪造头下的写入仍落在**调用者自己**名下 —— 断言行确实落在 A 名下，不只断言 B 看不见。
    http.perform(
            post("/catalog/platform-fees/rules")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .contentType("application/json")
                .content(JSON.writeValueAsString(
                    rule("AMAZON", "US", null, "TRANSACTION", "FIXED_PER_ORDER",
                        null, new BigDecimal("0.35"), "USD", from))))
        .andExpect(status().isOk());
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select tenant_id, fee_type from platform_fee_rule where platform_code = ? and charge_basis = ?",
                                "AMAZON", "FIXED_PER_ORDER")
                            .get(0, UUID.class)))
        .isEqualTo(a);

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言两个集合不相交
    for (int i = 0; i < 5; i++) {
      var mySet =
          tx(
                  actor,
                  () ->
                      fees.list(new PlatformFeeService.RuleQuery(null, null, null, null, null, 0, 200))
                          .items()
                          .stream()
                          .map(FeeRuleRow::id)
                          .collect(java.util.stream.Collectors.toSet()));
      var theirSet =
          tx(
                  actorB,
                  () ->
                      fees.list(new PlatformFeeService.RuleQuery(null, null, null, null, null, 0, 200))
                          .items()
                          .stream()
                          .map(FeeRuleRow::id)
                          .collect(java.util.stream.Collectors.toSet()));
      assertThat(mySet).isNotEmpty();
      assertThat(theirSet).isNotEmpty();
      assertThat(java.util.Collections.disjoint(mySet, theirSet)).isTrue();
      assertThat(tx(actor, () -> fees.listActuals(new PlatformFeeService.ActualQuery(null, null, null, null, null, 0, 200)).items()))
          .isNotEmpty();
      assertThat(tx(actorB, () -> fees.listActuals(new PlatformFeeService.ActualQuery(null, null, null, null, null, 0, 200)).items()))
          .isEmpty();
    }
  }

  /**
   * 🔴 实际费用是**只追加**的事实：除作废四件套外，其余列一律不可改写。
   *
   * <p>「改一条实际费用」= 让历史事实可以回溯修改 ⇒ 差异的历史就没有意义了。
   */
  @Test
  void 实际费用是只追加的事实改金额会被数据库层拒绝() throws Exception {
    var actual =
        write(
            actor,
            () ->
                fees.recordActual(
                    actor,
                    new FeeActualInput(
                        "AMAZON", "US", null, null, "PENALTY",
                        new BigDecimal("5.00"), "USD",
                        new BigDecimal("100"), new BigDecimal("1"),
                        LocalDate.now(), "", "违规罚款"),
                    "Bearer tenant-a"));
    var id = UUID.fromString(actual.id());
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        sql.execute(
                            "update platform_fee_actual set amount = ? where tenant_id = ? and id = ?",
                            new BigDecimal("1.00"), a, id)))
        .as("改写实际费用金额必须被 DB 层拒绝")
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("只追加");
    // 金额没变。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select amount from platform_fee_actual where tenant_id = ? and id = ?",
                                a, id)
                            .get(0, BigDecimal.class)))
        .isEqualByComparingTo("5.00");
  }

  /** 作废必须留理由；作废后该笔不再进入费用差异。 */
  @Test
  void 作废必须留理由且作废后不再进入差异() throws Exception {
    var from = OffsetDateTime.now().minusDays(1).withNano(0);
    write(
        actor,
        () ->
            fees.save(
                actor,
                null,
                rule("AMAZON", "US", null, "PENALTY", "FIXED_PER_ORDER",
                    null, new BigDecimal("5.00"), "USD", from),
                "Bearer tenant-a"));
    var actual =
        write(
            actor,
            () ->
                fees.recordActual(
                    actor,
                    new FeeActualInput(
                        "AMAZON", "US", null, null, "PENALTY",
                        new BigDecimal("5.00"), "USD",
                        new BigDecimal("100"), new BigDecimal("1"),
                        LocalDate.now(), "", ""),
                    "Bearer tenant-a"));
    assertThatThrownBy(
            () -> write(actor, () -> fees.voidActual(actor, UUID.fromString(actual.id()), "  ")))
        .isInstanceOf(ResponseStatusException.class);
    write(
        actor,
        () -> fees.voidActual(actor, UUID.fromString(actual.id()), "录错了，重复登记"));

    var variance =
        tx(
            actor,
            () ->
                fees.variance(
                    new PlatformFeeService.VarianceQuery(
                        "AMAZON", "US", null, LocalDate.now().minusDays(1), LocalDate.now())));
    assertThat(variance.available()).isFalse();
  }

  /**
   * 🔴 生效时间按时刻写回，不被时区偏移（−28800s 那类静默漂移）。
   *
   * <p>断言**写回的值**，不是只断言没抛异常。
   */
  @Test
  void 生效时间按时刻写回不被时区偏移() throws Exception {
    // 东八区的一个具体时刻，微秒精度。
    var instant = OffsetDateTime.of(2026, 3, 1, 8, 30, 0, 123456000, ZoneOffset.ofHours(8));
    var saved =
        write(
            actor,
            () ->
                fees.save(
                    actor,
                    null,
                    rule("AMAZON", "US", null, "COMMISSION", "PERCENTAGE_OF_SALE",
                        new BigDecimal("0.15"), null, null, instant),
                    "Bearer tenant-a"));
    var stored =
        tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select effective_from from platform_fee_rule where tenant_id = ? and id = ?",
                            a, UUID.fromString(saved.id()))
                        .get(0, OffsetDateTime.class));
    assertThat(stored).isNotNull();
    assertThat(stored.toInstant())
        .as("写回的时刻必须与传入的时刻是同一个瞬间（不能偏 −28800s）")
        .isEqualTo(instant.toInstant());
    // 明细接口给出的也是同一个瞬间。
    var detail = tx(actor, () -> fees.detail(UUID.fromString(saved.id())));
    assertThat(OffsetDateTime.parse(detail.rule().effectiveFrom()).toInstant())
        .isEqualTo(instant.toInstant());
  }

  /**
   * 🔴 上游目录读不到时返回 **503**，不拿空清单冒充「这个租户没有平台」。
   */
  @Test
  void 上游目录读不到时返回503而不是空清单() throws Exception {
    channelDown.set(true);
    assertThatThrownBy(() -> tx(actor, () -> fees.directory("Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(503));
    // 建规则时同样要读上游（平台码必须真实存在）⇒ 同样 503。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        fees.save(
                            actor,
                            null,
                            rule("AMAZON", "US", null, "COMMISSION", "PERCENTAGE_OF_SALE",
                                new BigDecimal("0.15"), null, null, OffsetDateTime.now()),
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(503));
  }

  /** 上游可用时 Authorization 头被原样透传（租户由 channel 从成员关系解析）。 */
  @Test
  void 上游可用时透传调用方的Authorization头() throws Exception {
    tx(actor, () -> fees.directory("Bearer tenant-a"));
    assertThat(lastBearer.get()).isEqualTo("Bearer tenant-a");
  }

  /** 不存在的平台 / 站点必须被拒绝（唯一真相在渠道服务的全局码表里）。 */
  @Test
  void 不存在的平台与站点被拒绝() throws Exception {
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        fees.save(
                            actor,
                            null,
                            rule("NO_SUCH_PLATFORM", null, null, "COMMISSION", "PERCENTAGE_OF_SALE",
                                new BigDecimal("0.15"), null, null, OffsetDateTime.now()),
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不在渠道服务的平台清单里");
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        fees.save(
                            actor,
                            null,
                            rule("AMAZON", "NO_SUCH_SITE", null, "COMMISSION", "PERCENTAGE_OF_SALE",
                                new BigDecimal("0.15"), null, null, OffsetDateTime.now()),
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不在平台");
  }

  /** 同一作用域下生效区间重叠的两条规则必须被拒绝（否则「用哪一条」没有答案）。 */
  @Test
  void 生效区间重叠被拒绝() throws Exception {
    var from = OffsetDateTime.now().minusDays(2).withNano(0);
    write(
        actor,
        () ->
            fees.save(
                actor,
                null,
                rule("AMAZON", "US", null, "COMMISSION", "PERCENTAGE_OF_SALE",
                    new BigDecimal("0.15"), null, null, from),
                "Bearer tenant-a"));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        fees.save(
                            actor,
                            null,
                            rule("AMAZON", "US", null, "COMMISSION", "PERCENTAGE_OF_SALE",
                                new BigDecimal("0.12"), null, null, from.plusDays(1)),
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("重叠");
  }

  /** 类目维度：内部分类的规则只命中该类目及其子类目。 */
  @Test
  void 类目维度只命中该类目及其子类目() throws Exception {
    var categoryId = category(actor, "INT", "INTERNAL", null, "TOYS");
    var from = OffsetDateTime.now().minusDays(1).withNano(0);
    write(
        actor,
        () ->
            fees.save(
                actor,
                null,
                rule("AMAZON", "US", categoryId, "COMMISSION", "PERCENTAGE_OF_SALE",
                    new BigDecimal("0.20"), null, null, from),
                "Bearer tenant-a"));

    // 命中该类目。
    var hit =
        tx(
            actor,
            () ->
                fees.estimate(
                    new PlatformFeeService.EstimateQuery(
                        "AMAZON", "US", categoryId, null,
                        new BigDecimal("100"), new BigDecimal("1"), "USD", OffsetDateTime.now())));
    var commission =
        hit.items().stream()
            .filter(i -> "COMMISSION".equals(i.feeType()))
            .findFirst()
            .orElseThrow();
    assertThat(commission.amount()).isEqualTo("20.0000");

    // 不命中：换一个类目 ⇒ 没有生效规则（不是 0）。
    var other = category(actor, "INT2", "INTERNAL", null, "KITCHEN");
    var miss =
        tx(
            actor,
            () ->
                fees.estimate(
                    new PlatformFeeService.EstimateQuery(
                        "AMAZON", "US", other, null,
                        new BigDecimal("100"), new BigDecimal("1"), "USD", OffsetDateTime.now())));
    var missCommission =
        miss.items().stream()
            .filter(i -> "COMMISSION".equals(i.feeType()))
            .findFirst()
            .orElseThrow();
    assertThat(missCommission.applicable()).isFalse();
    assertThat(missCommission.amount()).isNull();
  }

  /** 平台类目必须挂在 PLATFORM 树且树归属与规则声明的平台一致（DB 触发器 + 服务层双闸）。 */
  @Test
  void 平台类目归属被守卫() throws Exception {
    var internal = category(actor, "INT", "INTERNAL", null, "TOYS");
    var from = OffsetDateTime.now().minusDays(1).withNano(0);
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        fees.save(
                            actor,
                            null,
                            new FeeRuleInput(
                                "AMAZON", "US", null, internal, "COMMISSION", "PERCENTAGE_OF_SALE",
                                new BigDecimal("0.15"), null, null, null, null, null, null,
                                from, null, true, "", null),
                            "Bearer tenant-a")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("平台类目");
  }

  /** 读端点必须要求权限：只有读权限的角色写不了。 */
  @Test
  void 写端点需要写权限() throws Exception {
    http.perform(
            post("/catalog/platform-fees/rules")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        rule("AMAZON", "US", null, "COMMISSION", "PERCENTAGE_OF_SALE",
                            new BigDecimal("0.15"), null, null, OffsetDateTime.now()))))
        .andExpect(status().isForbidden());
  }
}
