package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.PriceAnomalyModels.*;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * 5.5.2.1 异常告警：真实 PostgreSQL 16 + 强制 RLS 下的四类异常判定、租户隔离三断言、
 * 跨币种不可比、时间戳写回，以及「不拿 0 冒充没有」。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：观测台账、规则、异常事件、预警通知全部真的落库。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的六个断言</b>：
 *
 * <ol>
 *   <li>{@link #平台价与内部价一次对不上不报连续超过门槛才报()}：蓝图要的是「**长期**不一致」，
 *       不是「一次就对不上」。持续天数必须真的由连续命中段算出来。
 *   <li>{@link #售价低于成本线用的是5_2_1_1的成本事实()}：成本线必须来自 5.2.1.1 写下的
 *       成本事实，判定写进 SQL —— 前端拿浮点比是另一回事。
 *   <li>{@link #平台自动降价要能区分平台降的与我们自己改的()}：把自己的调价说成平台干的，
 *       结论最后查无实据。
 *   <li>{@link #多站点价格倒挂按站点两两比对得出()}：蓝图加粗句「德国站定价低于美国站」
 *       的技术落点 —— 站点对进存储形状，不是全平台平均价差。
 *   <li>{@link #跨币种站点对没有汇率时不可比而不是编汇率()}：没有汇率表（5.2.2.1/5.2.2.2 未交付）
 *       ⇒ 德国站（EUR）与美国站（USD）**不可比**，如实报出而不是乘个编出来的汇率。
 *   <li>{@link #租户隔离三断言覆盖观测事件与通知()}：价格是最敏感的数据之一，
 *       A 读到 B 的站点价会直接让 A 的定价判断跑偏。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriceAnomalyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();

  /** 检测窗口：观测落在这段区间里。 */
  static final LocalDate DAY_OLD = LocalDate.now().minusDays(30);
  static final LocalDate DAY_MID = LocalDate.now().minusDays(15);
  static final LocalDate DAY_NEW = LocalDate.now().minusDays(2);
  static final LocalDate TODAY = LocalDate.now();

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

  @Autowired PriceAnomalyService anomalies;
  @Autowired CatalogService catalog;
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
  void setUp() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    // 🔴 判定规则是**租户级**的：测试之间共享同一个租户，
    //   上一条用例改过的阈值会漏到下一条用例里（表现为「有的用例莫名其妙不报」）。
    //   所以每个用例开始前把两个租户的规则清干净，回到内置默认值。
    clearRules(actor);
    clearRules(actorB);
  }

  void clearRules(AccessService.Access who) {
    write(
        who,
        () -> {
          sql.execute(
              "delete from price_anomaly_rule where tenant_id = "
                  + "nullif(current_setting('app.current_tenant',true),'')::uuid");
          return null;
        });
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

  // ================================================================ 夹具

  UUID sku(AccessService.Access who, String prefix) {
    String code = unique(prefix);
    UUID spuId =
        write(
            who,
            () ->
                catalog.saveSpu(
                    who,
                    null,
                    new SpuInput(
                        code + "-SPU", "SPU " + code, "", "Brand", "Category", "DRAFT", 0)));
    return write(
        who,
        () ->
            catalog.saveSku(
                who, spuId, null, new SkuInput(code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), 0)));
  }

  /**
   * 造一条 **5.2.1.1 成本构成** 的事实（V215 的两张表）。
   *
   * <p>🔴 本模块的「低于成本线」判定读的就是这两张表 —— 所以夹具必须按 5.2.1.1 的
   * **真实形状**写：profile 指向当前 revision，revision 带当时的合计与完整性标记。
   * 成本线直接写死 10.0000 USD，这样「判定用的到底是不是 5.2.1.1 的成本事实」是可断言的。
   */
  void costLine(AccessService.Access who, UUID skuId, String currency, String total) {
    write(
        who,
        () -> {
          var profileId = UUID.randomUUID();
          var revisionId = UUID.randomUUID();
          var effective = DAY_OLD.atStartOfDay().atOffset(ZoneOffset.UTC);
          sql.execute(
              "insert into sku_cost_profile(id,tenant_id,sku_id,cost_method,currency,packaging_cost,"
                  + "effective_from,version,created_by,created_at,updated_at)"
                  + " values(?::uuid,?::uuid,?::uuid,'MOVING_AVERAGE',?,null,?::timestamptz,0,?::uuid,now(),now())",
              profileId,
              who.tenantId(),
              skuId,
              currency,
              effective,
              who.userId());
          sql.execute(
              "insert into sku_cost_revision(id,tenant_id,sku_id,currency,revision_no,cost_method,"
                  + "purchase_cost,first_mile_allocation,packaging_cost,duty_cost,other_direct_cost,"
                  + "total_unit_cost,snapshot_complete,missing_components,effective_from,change_reason,"
                  + "created_by,created_at)"
                  + " values(?::uuid,?::uuid,?::uuid,?,1,'MOVING_AVERAGE',?,0,null,0,0,?,true,'',"
                  + "?::timestamptz,'5.2.1.1 成本构成夹具',?::uuid,now())",
              revisionId,
              who.tenantId(),
              skuId,
              currency,
              new BigDecimal(total),
              new BigDecimal(total),
              effective,
              who.userId());
          sql.execute(
              "update sku_cost_profile set current_revision_id=?::uuid where tenant_id="
                  + "nullif(current_setting('app.current_tenant',true),'')::uuid and id=?::uuid",
              revisionId,
              profileId);
          return null;
        });
  }

  /** 登记一条价格观测。 */
  ObservationSaved observe(
      AccessService.Access who,
      UUID skuId,
      String platform,
      String site,
      String currency,
      String internalPrice,
      String platformPrice,
      String changeSource,
      String source,
      OffsetDateTime observedAt) {
    return write(
        who,
        () ->
            anomalies.registerObservation(
                who,
                new ObservationInput(
                    skuId,
                    platform,
                    site,
                    currency,
                    internalPrice == null ? null : new BigDecimal(internalPrice),
                    platformPrice == null ? null : new BigDecimal(platformPrice),
                    changeSource,
                    source,
                    null,
                    null,
                    null,
                    null,
                    observedAt,
                    "夹具观测")));
  }

  /** 登记一条带比价口径（汇率）的观测。 */
  ObservationSaved observeWithFx(
      AccessService.Access who,
      UUID skuId,
      String platform,
      String site,
      String currency,
      String platformPrice,
      String comparisonCurrency,
      String fxRate,
      OffsetDateTime observedAt) {
    return write(
        who,
        () ->
            anomalies.registerObservation(
                who,
                new ObservationInput(
                    skuId,
                    platform,
                    site,
                    currency,
                    null,
                    new BigDecimal(platformPrice),
                    "PLATFORM_FEEDBACK",
                    "PLATFORM_FEEDBACK",
                    comparisonCurrency,
                    new BigDecimal(fxRate),
                    TODAY,
                    "夹具汇率",
                    observedAt,
                    "带比价口径的观测")));
  }

  EventListPage events(AccessService.Access who) {
    return tx(
        who,
        () ->
            anomalies.listEvents(
                new EventQuery(
                    null, null, null, null, null, null, null, null, 0, 200)));
  }

  /**
   * 只看某个 SKU 的事件。
   *
   * <p>🔴 用例之间共享同一个租户，事件会累积 —— 所以断言必须**按 SKU 收窄**，
   * 否则「这个用例报了 1 条」会被别的用例留下的行污染成 2 条（用例顺序一变就假红）。
   */
  List<EventRow> eventsOf(AccessService.Access who, UUID skuId) {
    return events(who).items().stream().filter(e -> e.skuId().equals(skuId)).toList();
  }

  // ================================================================ 测试

  @Test
  void 能力声明点明四个输入的真实来源与下游缺口() {
    var capabilities = tx(actor, () -> anomalies.capabilities());

    assertThat(capabilities.inputs()).hasSize(4);
    // 平台价：本仓没有自动来源（channel 的 listing 明确「无价格列」）。
    var platformPrice =
        capabilities.inputs().stream()
            .filter(i -> i.code().equals("PLATFORM_PRICE"))
            .findFirst()
            .orElseThrow();
    assertThat(platformPrice.source()).isEqualTo("PRICE_OBSERVATION_LEDGER");
    assertThat(platformPrice.note() + platformPrice.impact()).contains("无价格列");
    // 成本线：来自 5.2.1.1。
    var cost =
        capabilities.inputs().stream()
            .filter(i -> i.code().equals("COST_LINE"))
            .findFirst()
            .orElseThrow();
    assertThat(cost.ownerModule()).contains("5.2.1.1");
    assertThat(cost.ownerModule() + cost.basis() + cost.impact()).contains("sku_cost_revision");
    // 站点价差规则：配置。
    var rule =
        capabilities.inputs().stream()
            .filter(i -> i.code().equals("SITE_GAP_RULE"))
            .findFirst()
            .orElseThrow();
    assertThat(rule.source()).isEqualTo("CATALOG_ANOMALY_RULE");

    // 四类异常都在，且都声明了判定口径。
    assertThat(capabilities.anomalyTypes())
        .extracting(AnomalyCapability::code)
        .containsExactlyInAnyOrder(
            "PLATFORM_INTERNAL_DIVERGENCE",
            "BELOW_COST",
            "PLATFORM_AUTO_DISCOUNT",
            "CROSS_SITE_INVERSION");
    // 下游 1 工作台预警未交付 ⇒ 如实点名。
    assertThat(capabilities.downstreamNote()).contains("1.3.1.1").contains("未交付");
  }

  @Test
  void 登记观测必须给价且比价币种与汇率同生共死() {
    UUID skuId = sku(actor, "OBS");
    var at = DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC);

    // 两个价都不给 ⇒ 没有任何信息量。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        anomalies.registerObservation(
                            actor,
                            new ObservationInput(
                                skuId, "AMZN", "US", "USD", null, null, "UNKNOWN",
                                "MANUAL_ATTESTATION", null, null, null, null, at, ""))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("至少要给一个");

    // 只给汇率不给比价币种 ⇒ 比价口径残缺。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        anomalies.registerObservation(
                            actor,
                            new ObservationInput(
                                skuId, "AMZN", "US", "USD", null, new BigDecimal("10"),
                                "UNKNOWN", "MANUAL_ATTESTATION", null, new BigDecimal("1.1"),
                                TODAY, "汇率", at, ""))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须同时给或同时不给");

    // 给了汇率却不说哪天、哪来的 ⇒ 事后无法复算。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        anomalies.registerObservation(
                            actor,
                            new ObservationInput(
                                skuId, "AMZN", "US", "USD", null, new BigDecimal("10"),
                                "UNKNOWN", "MANUAL_ATTESTATION", "USD", new BigDecimal("1.1"),
                                null, null, at, ""))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("哪天的汇率");

    // 正常登记成功。
    var saved =
        observe(actor, skuId, "AMZN", "US", "USD", "10.00", "10.00", "UNKNOWN", "MANUAL_ATTESTATION", at);
    assertThat(saved.id()).isNotNull();

    // 同一 SKU、同一站点、同一时刻重复登记 ⇒ 冲突（观测是只追加的，不覆盖历史）。
    assertThatThrownBy(
            () ->
                observe(
                    actor, skuId, "AMZN", "US", "USD", "9.00", "9.00", "UNKNOWN",
                    "MANUAL_ATTESTATION", at))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 观测必须挂在真实存在的SKU上而不是让数据库外键来管() {
    // 🔴 本迁移**零外键**（避免打破既有测试的 TRUNCATE）⇒ 引用校验由触发器做。
    var ghost = UUID.randomUUID();
    var at = DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC);
    assertThatThrownBy(
            () ->
                observe(
                    actor, ghost, "AMZN", "US", "USD", "10.00", "10.00", "UNKNOWN",
                    "MANUAL_ATTESTATION", at))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("SKU 不存在");

    // 绕过服务层直接写 SQL：触发器同样要挡住（否则就是「DB 层没有这道闸」）。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () -> {
                      sql.execute(
                          "insert into price_anomaly_observation(id,tenant_id,sku_id,platform_code,"
                              + "site_code,currency,internal_price,platform_price,price_change_source,"
                              + "source,observed_at,note,created_by,created_at)"
                              + " values(?::uuid,?::uuid,?::uuid,'AMZN','US','USD',10,10,'UNKNOWN',"
                              + "'MANUAL_ATTESTATION',?::timestamptz,'绕过服务层',?::uuid,now())",
                          UUID.randomUUID(),
                          actor.tenantId(),
                          ghost,
                          at,
                          actor.userId());
                      return null;
                    }))
        .hasMessageContaining("existing SKU");
  }

  @Test
  void 平台价与内部价一次对不上不报连续超过门槛才报() {
    UUID skuId = sku(actor, "DIV");
    var old = DAY_OLD.atStartOfDay().atOffset(ZoneOffset.UTC);
    var nextDay = DAY_OLD.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

    // 只偏离了一天（两个观测点、跨度 1 天）⇒ 默认门槛（7 天）下**不报**。
    observe(actor, skuId, "AMZN", "US", "USD", "10.00", "20.00", "UNKNOWN", "MANUAL_ATTESTATION", old);
    observe(actor, skuId, "AMZN", "US", "USD", "10.00", "20.00", "UNKNOWN", "MANUAL_ATTESTATION", nextDay);
    var first = write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));
    assertThat(first.eventsOpened()).isZero();
    assertThat(
            eventsOf(actor, skuId).stream()
                .filter(e -> e.anomalyType().equals("PLATFORM_INTERNAL_DIVERGENCE"))
                .count())
        .isZero();

    // 把门槛降到 0 天 ⇒ 同一份事实现在就命中（证明「不报」的原因是门槛而不是判定失效）。
    write(
        actor,
        () ->
            anomalies.saveRule(
                actor,
                new AnomalyRuleInput(
                    "PLATFORM_INTERNAL_DIVERGENCE", "临时门槛", true, new BigDecimal("0.05"), 0,
                    "MEDIUM", "测试用")));
    var second = write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));
    assertThat(second.eventsOpened()).isPositive();
    var divergence =
        eventsOf(actor, skuId).stream()
            .filter(e -> e.anomalyType().equals("PLATFORM_INTERNAL_DIVERGENCE"))
            .findFirst()
            .orElseThrow();
    // 持续天数 = 连续命中段的跨度（1 天）。
    assertThat(divergence.durationDays()).isEqualTo(1);
    assertThat(divergence.gapRatio()).isNotNull();
    assertThat(divergence.firstSeenAt()).isNotNull();
    assertThat(divergence.lastSeenAt()).isNotNull();

    // 恢复默认门槛（7 天）并补一次仍然偏离的观测 ⇒ 连续段拉到 28 天 ⇒ 同一条事件被更新，
    // **不**新开第二条（事件是状态，不是快照）。
    write(
        actor,
        () ->
            anomalies.saveRule(
                actor,
                new AnomalyRuleInput(
                    "PLATFORM_INTERNAL_DIVERGENCE", "默认门槛", true, new BigDecimal("0.05"), 7,
                    "MEDIUM", "测试用")));
    observe(
        actor, skuId, "AMZN", "US", "USD", "10.00", "20.00", "UNKNOWN", "MANUAL_ATTESTATION",
        DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC));
    var third = write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));
    assertThat(third.eventsOpened()).isZero();
    assertThat(third.eventsUpdated()).isEqualTo(1);
    assertThat(
            eventsOf(actor, skuId).stream()
                .filter(e -> e.anomalyType().equals("PLATFORM_INTERNAL_DIVERGENCE"))
                .count())
        .isEqualTo(1);
    assertThat(
            eventsOf(actor, skuId).stream()
                .filter(e -> e.anomalyType().equals("PLATFORM_INTERNAL_DIVERGENCE"))
                .findFirst()
                .orElseThrow()
                .durationDays())
        .isEqualTo(28);
  }

  @Test
  void 平台价与内部价恢复正常后事件自动消解() {
    UUID skuId = sku(actor, "RES");
    var old = DAY_OLD.atStartOfDay().atOffset(ZoneOffset.UTC);
    var mid = DAY_MID.atStartOfDay().atOffset(ZoneOffset.UTC);
    var fresh = DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC);

    observe(actor, skuId, "AMZN", "US", "USD", "10.00", "20.00", "UNKNOWN", "MANUAL_ATTESTATION", old);
    observe(actor, skuId, "AMZN", "US", "USD", "10.00", "20.00", "UNKNOWN", "MANUAL_ATTESTATION", mid);
    write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));
    assertThat(
            eventsOf(actor, skuId).stream()
                .filter(e -> e.anomalyType().equals("PLATFORM_INTERNAL_DIVERGENCE"))
                .count())
        .isEqualTo(1);

    // 恢复正常：再加一条一致的观测，且它比前面两条**更新**。
    observe(actor, skuId, "AMZN", "US", "USD", "10.00", "10.00", "UNKNOWN", "MANUAL_ATTESTATION", fresh);
    write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));

    var divergence =
        eventsOf(actor, skuId).stream()
            .filter(e -> e.anomalyType().equals("PLATFORM_INTERNAL_DIVERGENCE"))
            .findFirst()
            .orElseThrow();
    // 🔴 最新一次观测已经正常 ⇒ 没有连续段以它结尾 ⇒ 当前不成立 ⇒ 自动消解。
    assertThat(divergence.status()).isEqualTo("RESOLVED");
  }

  @Test
  void 售价低于成本线用的是5_2_1_1的成本事实() {
    UUID skuId = sku(actor, "COST");
    // 5.2.1.1 的成本事实：单位成本 10.0000 USD。
    costLine(actor, skuId, "USD", "10.0000");
    var at = DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC);

    // 平台价 8.00 < 成本线 10.00 ⇒ 命中。
    observe(actor, skuId, "AMZN", "US", "USD", "12.00", "8.00", "UNKNOWN", "MANUAL_ATTESTATION", at);
    write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));

    var belowCost =
        eventsOf(actor, skuId).stream()
            .filter(e -> e.anomalyType().equals("BELOW_COST"))
            .findFirst()
            .orElseThrow();
    // 🔴 事件上的成本线必须**逐字**是 5.2.1.1 写下的那个数，不是任何拍脑袋的线。
    assertThat(belowCost.costLine()).isEqualByComparingTo("10.0000");
    assertThat(belowCost.costCurrency()).isEqualTo("USD");
    assertThat(belowCost.costComplete()).isTrue();
    assertThat(belowCost.subjectPrice()).isEqualByComparingTo("8.0000");
    assertThat(belowCost.gapAmount()).isEqualByComparingTo("-2.0000");
    // 成本快照时刻要带在事件上（事后能回答「当时按哪一版成本判的」）。
    assertThat(belowCost.costSnapshotAt()).isNotNull();

    // 没有成本构成的 SKU ⇒ 这一维度**不参与判定**（读不到 ≠ 成本为 0）。
    UUID noCost = sku(actor, "NOCOST");
    observe(actor, noCost, "AMZN", "US", "USD", "12.00", "1.00", "UNKNOWN", "MANUAL_ATTESTATION", at);
    write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));
    assertThat(
            eventsOf(actor, noCost).stream()
                .filter(e -> e.anomalyType().equals("BELOW_COST"))
                .count())
        .isZero();
  }

  @Test
  void 平台自动降价要能区分平台降的与我们自己改的() {
    UUID skuId = sku(actor, "DISC");
    var old = DAY_OLD.atStartOfDay().atOffset(ZoneOffset.UTC);
    var mid = DAY_MID.atStartOfDay().atOffset(ZoneOffset.UTC);

    // 平台自动降的：10.00 → 8.00（降 20%）。
    observe(actor, skuId, "AMZN", "US", "USD", null, "10.00", "UNKNOWN", "PLATFORM_FEEDBACK", old);
    observe(actor, skuId, "AMZN", "US", "USD", null, "8.00", "AUTOMATIC_PLATFORM", "PLATFORM_FEEDBACK", mid);

    // 我们自己改的：另一个站点，同样降价，但变动来源是我们自己。
    observe(actor, skuId, "AMZN", "DE", "EUR", null, "10.00", "UNKNOWN", "PLATFORM_FEEDBACK", old);
    observe(actor, skuId, "AMZN", "DE", "EUR", null, "8.00", "MANUAL_INTERNAL", "PRICE_LIST", mid);

    var run = write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));
    assertThat(run.eventsOpened()).isPositive();

    var discounts =
        eventsOf(actor, skuId).stream()
            .filter(e -> e.anomalyType().equals("PLATFORM_AUTO_DISCOUNT"))
            .toList();
    // 🔴 只有平台自动降的那一条算数；我们自己改的那一条**不算**。
    assertThat(discounts).hasSize(1);
    assertThat(discounts.get(0).siteCode()).isEqualTo("US");
    assertThat(discounts.get(0).referencePrice()).isEqualByComparingTo("10.00");
    assertThat(discounts.get(0).subjectPrice()).isEqualByComparingTo("8.00");
    assertThat(discounts.get(0).gapRatio()).isNotNull();

    // 检测小结里如实报出「我们自己改的降价次数」—— 证明两者确实被区分开了。
    var discountSummary =
        run.typeSummary().stream()
            .filter(s -> s.anomalyType().equals("PLATFORM_AUTO_DISCOUNT"))
            .findFirst()
            .orElseThrow();
    assertThat(discountSummary.note()).contains("我们自己改的降价").contains("1 次");
  }

  @Test
  void 多站点价格倒挂按站点两两比对得出() {
    UUID skuId = sku(actor, "SITE");
    var at = DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC);

    // 德国站 19.00 EUR（1 EUR = 1.10 USD ⇒ 折算 20.90 USD）、美国站 25.00 USD
    // ⇒ 德国站便宜 16.4% > 10% 门槛 ⇒ 倒挂。
    observeWithFx(actor, skuId, "AMZN", "DE", "EUR", "19.00", "USD", "1.10", at);
    observe(actor, skuId, "AMZN", "US", "USD", null, "25.00", "PLATFORM_FEEDBACK", "PLATFORM_FEEDBACK", at);

    write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));

    var inversions =
        eventsOf(actor, skuId).stream()
            .filter(e -> e.anomalyType().equals("CROSS_SITE_INVERSION"))
            .toList();
    // 🔴 站点对进**存储形状**：低的一边与贵的一边都在事件上。
    assertThat(inversions).hasSize(1);
    var inversion = inversions.get(0);
    assertThat(inversion.siteCode()).isEqualTo("DE");
    assertThat(inversion.relatedSiteCode()).isEqualTo("US");
    assertThat(inversion.subjectPrice()).isEqualByComparingTo("19.00");
    assertThat(inversion.referencePrice()).isEqualByComparingTo("25.00");
    assertThat(inversion.gapRatio()).isNotNull();
  }

  @Test
  void 站点倒挂是站点两两比对而不是全平台平均价差() {
    UUID skuId = sku(actor, "PAIR");
    var at = DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC);

    // 三个站点：US 30.00、DE 20.00、FR 25.00。
    // 「相对全平台平均价」（25.00）的算法：DE 低于平均 20%、FR 正好等于平均 ⇒ **只报 DE**。
    // 「站点两两比对」的算法：DE 比 US 低 33% ⇒ 报；DE 比 FR 低 20% ⇒ 报；FR 比 US 低 17% ⇒ 报。
    observe(actor, skuId, "AMZN", "US", "USD", null, "30.00", "PLATFORM_FEEDBACK", "PLATFORM_FEEDBACK", at);
    observe(actor, skuId, "AMZN", "DE", "USD", null, "20.00", "PLATFORM_FEEDBACK", "PLATFORM_FEEDBACK", at);
    observe(actor, skuId, "AMZN", "FR", "USD", null, "25.00", "PLATFORM_FEEDBACK", "PLATFORM_FEEDBACK", at);

    write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));

    var inversions =
        eventsOf(actor, skuId).stream()
            .filter(e -> e.anomalyType().equals("CROSS_SITE_INVERSION"))
            .toList();
    // 三个站点 ⇒ 三个站点对 ⇒ 三个倒挂事件（每个站点对一条）。
    assertThat(inversions).hasSize(3);
    // 每个事件都带着「谁比谁便宜」。
    assertThat(inversions)
        .allSatisfy(
            e -> {
              assertThat(e.siteCode()).isNotEqualTo(e.relatedSiteCode());
              assertThat(e.subjectPrice()).isLessThan(e.referencePrice());
            });
  }

  @Test
  void 跨币种站点对没有汇率时不可比而不是编汇率() {
    UUID skuId = sku(actor, "FX");
    var at = DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC);

    // 德国站 EUR 19.00、美国站 USD 25.00 —— 没有比价口径 ⇒ **不可比**。
    observe(actor, skuId, "AMZN", "DE", "EUR", null, "19.00", "PLATFORM_FEEDBACK", "PLATFORM_FEEDBACK", at);
    observe(actor, skuId, "AMZN", "US", "USD", null, "25.00", "PLATFORM_FEEDBACK", "PLATFORM_FEEDBACK", at);

    var run = write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));

    // 🔴 不判倒挂（没有汇率就没有可比的价格），也不静默丢弃 —— 如实报出「不可比」。
    assertThat(
            eventsOf(actor, skuId).stream()
                .filter(e -> e.anomalyType().equals("CROSS_SITE_INVERSION"))
                .count())
        .isZero();
    assertThat(run.uncomparablePairs()).isNotEmpty();
    var pair = run.uncomparablePairs().get(0);
    assertThat(pair.reason()).contains("没有汇率表").contains("不编汇率");
    assertThat(pair.currency()).isEqualTo("EUR");
    assertThat(pair.relatedCurrency()).isEqualTo("USD");
  }

  @Test
  void 重复检测不堆重复事件而是把持续天数往前推() {
    UUID skuId = sku(actor, "IDEM");
    var old = DAY_OLD.atStartOfDay().atOffset(ZoneOffset.UTC);
    var mid = DAY_MID.atStartOfDay().atOffset(ZoneOffset.UTC);
    var fresh = DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC);

    observe(actor, skuId, "AMZN", "US", "USD", "10.00", "20.00", "UNKNOWN", "MANUAL_ATTESTATION", old);
    observe(actor, skuId, "AMZN", "US", "USD", "10.00", "20.00", "UNKNOWN", "MANUAL_ATTESTATION", mid);
    observe(actor, skuId, "AMZN", "US", "USD", "10.00", "20.00", "UNKNOWN", "MANUAL_ATTESTATION", fresh);

    var first = write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));
    assertThat(
            eventsOf(actor, skuId).stream()
                .filter(e -> e.anomalyType().equals("PLATFORM_INTERNAL_DIVERGENCE"))
                .count())
        .isEqualTo(1);
    assertThat(first.notificationsRaised()).isPositive();

    // 第二次检测：同一条事件，不新开、不新发通知。
    // 🔴 断言落在**本 SKU 的事件**上而不是全局计数器上：同一个租户里别的用例也留下了事件，
    //   全局计数会被污染，而「这条异常有没有被重复建出来」才是要证明的事。
    var second = write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));
    assertThat(second.eventsOpened()).isZero();
    assertThat(
            eventsOf(actor, skuId).stream()
                .filter(e -> e.anomalyType().equals("PLATFORM_INTERNAL_DIVERGENCE"))
                .count())
        .isEqualTo(1);
    assertThat(second.notificationsRaised()).isZero();

    var divergence =
        eventsOf(actor, skuId).stream()
            .filter(e -> e.anomalyType().equals("PLATFORM_INTERNAL_DIVERGENCE"))
            .findFirst()
            .orElseThrow();
    assertThat(divergence.durationDays()).isEqualTo(28);
    // 🔴 通知按**事件**收窄：同一个租户里别的用例也会留下通知。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from price_anomaly_notification n"
                                    + " join price_anomaly_event e on e.tenant_id = n.tenant_id"
                                    + " and e.id = n.event_id where n.tenant_id ="
                                    + " nullif(current_setting('app.current_tenant',true),'')::uuid"
                                    + " and e.sku_id = ?::uuid",
                                skuId)
                            .get(0, Long.class))
                .longValue())
        .isEqualTo(1);
  }

  @Test
  void 事件状态可以确认与消解且终态不再变更() {
    UUID skuId = sku(actor, "FLOW");
    var at = DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC);
    observe(actor, skuId, "AMZN", "US", "USD", "10.00", "8.00", "UNKNOWN", "MANUAL_ATTESTATION", at);
    costLine(actor, skuId, "USD", "10.0000");
    write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));

    var event =
        eventsOf(actor, skuId).stream()
            .filter(e -> e.anomalyType().equals("BELOW_COST"))
            .findFirst()
            .orElseThrow();
    assertThat(event.status()).isEqualTo("OPEN");

    var acknowledged = write(actor, () -> anomalies.acknowledge(actor, event.id()));
    assertThat(acknowledged.status()).isEqualTo("ACKNOWLEDGED");

    var resolved = write(actor, () -> anomalies.resolve(actor, event.id()));
    assertThat(resolved.status()).isEqualTo("RESOLVED");

    // 终态再变更 ⇒ 409（不是 404：它确实存在）。
    assertThatThrownBy(() -> write(actor, () -> anomalies.acknowledge(actor, event.id())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("终态");

    // 不存在的事件 ⇒ 404。
    assertThatThrownBy(() -> write(actor, () -> anomalies.acknowledge(actor, UUID.randomUUID())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在");
  }

  @Test
  void 规则阈值可配且能覆盖内置默认值() {
    var defaults = tx(actor, () -> anomalies.effectiveRules());
    assertThat(defaults).hasSize(4);
    assertThat(defaults).allSatisfy(r -> assertThat(r.overridden()).isFalse());
    var divergenceDefault =
        defaults.stream()
            .filter(r -> r.anomalyType().equals("PLATFORM_INTERNAL_DIVERGENCE"))
            .findFirst()
            .orElseThrow();
    assertThat(divergenceDefault.minDurationDays()).isEqualTo(7);

    var saved =
        write(
            actor,
            () ->
                anomalies.saveRule(
                    actor,
                    new AnomalyRuleInput(
                        "PLATFORM_INTERNAL_DIVERGENCE", "自定义门槛", false, new BigDecimal("0.20"), 3,
                        "HIGH", "运维改的")));
    assertThat(saved.overridden()).isTrue();
    assertThat(saved.enabled()).isFalse();
    assertThat(saved.minDurationDays()).isEqualTo(3);
    assertThat(saved.severity()).isEqualTo("HIGH");

    // 阈值越界 ⇒ 400。
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        anomalies.saveRule(
                            actor,
                            new AnomalyRuleInput(
                                "BELOW_COST", "越界", true, BigDecimal.ONE, 1, "HIGH", ""))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("[0,1)");
  }

  @Test
  void 观测写回时刻零漂移() {
    UUID skuId = sku(actor, "TIME");
    // 带时区的时刻：写回必须逐字相等（Timestamp.valueOf 会静默偏 −28800s）。
    var observed = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).withOffsetSameInstant(ZoneOffset.UTC);
    var saved =
        observe(actor, skuId, "AMZN", "US", "USD", "10.00", "10.00", "UNKNOWN", "MANUAL_ATTESTATION", observed);
    var written =
        tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select observed_at from price_anomaly_observation where id = ?::uuid",
                            saved.id())
                        .get(0, OffsetDateTime.class));
    assertThat(written).isEqualTo(observed);
  }

  @Test
  void 租户隔离三断言覆盖观测事件与通知() throws Exception {
    UUID mySku = sku(actor, "ISO-A");
    UUID theirSku = sku(actorB, "ISO-B");
    var at = DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC);

    // 两条观测（跨度 15 天）⇒ 命中「平台价与内部价长期不一致」⇒ 两边各有一条事件。
    // （只有一条观测不算「长期」—— 一次对不上不报，这是蓝图的要求。）
    var mine = observe(actor, mySku, "AMZN", "US", "USD", "10.00", "8.00", "UNKNOWN", "MANUAL_ATTESTATION", at);
    observe(
        actor, mySku, "AMZN", "US", "USD", "10.00", "8.00", "UNKNOWN", "MANUAL_ATTESTATION",
        DAY_OLD.plusDays(15).atStartOfDay().atOffset(ZoneOffset.UTC));
    var theirs = observe(actorB, theirSku, "AMZN", "US", "USD", "10.00", "8.00", "UNKNOWN", "MANUAL_ATTESTATION", at);
    observe(
        actorB, theirSku, "AMZN", "US", "USD", "10.00", "8.00", "UNKNOWN", "MANUAL_ATTESTATION",
        DAY_OLD.plusDays(15).atStartOfDay().atOffset(ZoneOffset.UTC));

    // 两边各自跑一次检测，各出一条事件。
    write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));
    write(actorB, () -> anomalies.detect(actorB, DAY_OLD, TODAY));

    // 1) 两租户互不可见
    assertThat(
            events(actor).items().stream().map(EventRow::skuId).collect(java.util.stream.Collectors.toSet()))
        .contains(mySku)
        .doesNotContain(theirSku);
    assertThat(
            events(actorB).items().stream().map(EventRow::skuId).collect(java.util.stream.Collectors.toSet()))
        .contains(theirSku)
        .doesNotContain(mySku);
    // 观测台账同样互不可见。
    var myObservations =
        tx(
            actor,
            () ->
                anomalies.listObservations(
                    new ObservationQuery(null, null, null, null, null, null, null, 0, 200)));
    assertThat(myObservations.items()).extracting(ObservationRow::skuId).contains(mySku).doesNotContain(theirSku);
    // 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from price_anomaly_observation where tenant_id=? and id=?",
                                b,
                                theirs.id())
                            .get(0, Long.class))
                .longValue())
        .isZero();

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(
            get("/catalog/price-anomaly-alerts/events")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());
    http.perform(
            post("/catalog/price-anomaly-alerts/detect")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isOk());
    // 伪造头下的写入仍落在**调用者自己**名下 —— 断言行确实落在 B 名下。
    // （用 B 自己的 SKU：拿 A 的 SKU 去登记必然 404，那是「看不到 A 的 SKU」的正确表现，
    //   与伪造头无关 —— 那样断言就测不到伪造头了。）
    http.perform(
            post("/catalog/price-anomaly-alerts/observations")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new ObservationInput(
                            theirSku, "AMZN", "DE", "EUR", new BigDecimal("10"), new BigDecimal("8"),
                            "UNKNOWN", "MANUAL_ATTESTATION", null, null, null, null,
                            DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC), "伪造头下的登记"))))
        .andExpect(status().isOk());
    assertThat(
            tx(
                    actorB,
                    () ->
                        sql.fetchOne(
                                "select count(*) from price_anomaly_observation where sku_id=?"
                                    + " and site_code='DE'",
                                theirSku)
                            .get(0, Long.class))
                .longValue())
        .isEqualTo(1);
    // 🔴 伪造的 X-Tenant-Id 没有把这一行写进 A 的名下：A 看不到它。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from price_anomaly_observation where sku_id=?"
                                    + " and site_code='DE'",
                                theirSku)
                            .get(0, Long.class))
                .longValue())
        .isZero();
    // 拿 A 的 SKU 去 B 的身份下登记 ⇒ 404（B 看不到 A 的 SKU）。
    http.perform(
            post("/catalog/price-anomaly-alerts/observations")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new ObservationInput(
                            mySku, "AMZN", "FR", "EUR", new BigDecimal("10"), new BigDecimal("8"),
                            "UNKNOWN", "MANUAL_ATTESTATION", null, null, null, null,
                            DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC), "拿 A 的 SKU 去登记"))))
        .andExpect(status().isNotFound());

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言两个集合不相交
    for (int i = 0; i < 5; i++) {
      var mySet =
          events(actor).items().stream().map(EventRow::skuId).collect(java.util.stream.Collectors.toSet());
      var theirSet =
          events(actorB).items().stream().map(EventRow::skuId).collect(java.util.stream.Collectors.toSet());
      assertThat(mySet).isNotEmpty();
      assertThat(theirSet).isNotEmpty();
      assertThat(java.util.Collections.disjoint(mySet, theirSet)).isTrue();
    }
  }

  @Test
  void 预警通知只落在本服务内且未投递() {
    UUID skuId = sku(actor, "NOTIF");
    var at = DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC);
    costLine(actor, skuId, "USD", "10.0000");
    observe(actor, skuId, "AMZN", "US", "USD", "12.00", "8.00", "UNKNOWN", "MANUAL_ATTESTATION", at);
    write(actor, () -> anomalies.detect(actor, DAY_OLD, TODAY));

    var notifications =
        tx(
            actor,
            () ->
                anomalies.listNotifications(
                    new EventQuery(null, null, null, null, null, null, null, null, 0, 200)));
    assertThat(notifications.items()).isNotEmpty();
    var notification = notifications.items().get(0);
    // 🔴 下游 1 工作台预警未交付 ⇒ ownerModule 点名 1.3.1.1、delivered 恒为 false。
    assertThat(notification.ownerModule()).isEqualTo("1.3.1.1");
    assertThat(notification.delivered()).isFalse();
    assertThat(notification.deliveredAt()).isNull();
    assertThat(notification.channel()).isEqualTo("INTERNAL");
    assertThat(notification.body()).contains("1.3.1.1");
  }

  @Test
  void 写端点需要写权限() throws Exception {
    UUID skuId = sku(actor, "PERM");
    var at = DAY_NEW.atStartOfDay().atOffset(ZoneOffset.UTC);
    http.perform(
            post("/catalog/price-anomaly-alerts/observations")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new ObservationInput(
                            skuId, "AMZN", "US", "USD", new BigDecimal("10"), new BigDecimal("10"),
                            "UNKNOWN", "MANUAL_ATTESTATION", null, null, null, null, at, ""))))
        .andExpect(status().isForbidden());
  }
}
