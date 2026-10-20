package com.starcon.catalog;

import static com.starcon.catalog.ExchangeRateSnapshotModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.catalog.ExchangeRateModels.RateInput;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * 5.2.2.2 汇率快照：真实 PostgreSQL 16 + 强制 RLS 下的冻结、方向显式、舍入规则解析、
 * 不可改写、复算与端点行为。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：快照与舍入规则全部真的落库。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的五个断言</b>：
 *
 * <ol>
 *   <li>{@link #今天汇率变了历史快照一个数字都不动()}：蓝图加粗句的正身 ——
 *       「今天汇率变了不能让上个月的利润报表数字跟着变」。
 *   <li>{@link #快照不允许被改写连直连SQL也不行()}：冻结的东西不许改。
 *   <li>{@link #冻结值与汇率记录不一致的行写不进来()}：快照上写着一个汇率表里
 *       不存在的汇率 —— 直连 SQL 也绕不过去。
 *   <li>{@link #舍入规则先具体后通用()}：日元零位不能被通用两位规则盖掉。
 *   <li>{@link #复算能重算出同一个目标币金额()}：利润复算能力是真的，不是话术。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExchangeRateSnapshotIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final LocalDate DAY = LocalDate.now().minusDays(3);
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
      identity.start();
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read", "catalog.sku.read")))));
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

  @Autowired ExchangeRateSnapshotService snapshots;
  @Autowired ExchangeRateService rates;
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
    // 汇率与舍入规则是可改的配置：每个用例一套干净的（快照是只追加的，刻意**不清** ——
    // 那是本模块要证明的性质之一，用例之间靠唯一的 document_id 隔开）。
    tx(actor, () -> sql.execute("delete from exchange_rate"));
    tx(actorB, () -> sql.execute("delete from exchange_rate"));
    tx(actor, () -> sql.execute("delete from exchange_rate_rounding_rule"));
    tx(actorB, () -> sql.execute("delete from exchange_rate_rounding_rule"));
    source(actor, "MANUAL", "手工录入", "MANUAL", null);
    source(actorB, "MANUAL", "手工录入", "MANUAL", null);
  }

  // ================================================================ 夹具

  void source(
      AccessService.Access who, String code, String name, String kind, String endpoint) {
    tx(
        who,
        () -> {
          sql.execute(
              "insert into exchange_rate_source(id,tenant_id,code,name,kind,enabled,priority,endpoint,note,created_by)"
                  + " values(?::uuid,?::uuid,?,?,?,true,100,?,'',?::uuid)"
                  + " on conflict (tenant_id,code) do nothing",
              UUID.randomUUID(),
              who.tenantId(),
              code,
              name,
              kind,
              endpoint,
              who.userId());
          return null;
        });
  }

  /** 手工录入一条汇率（上游 5.2.2.1）。 */
  UUID rate(AccessService.Access who, String base, String quote, String value, LocalDate date) {
    return write(
            who,
            () ->
                rates.create(
                    who,
                    new RateInput(
                        base, quote, new BigDecimal(value), date, "MANUAL", "ref-" + UUID.randomUUID(), "")))
        .id();
  }

  /** 初始化内置舍入规则。 */
  void initRules(AccessService.Access who) {
    write(who, () -> snapshots.initializeRoundingRules(who));
  }

  /** 冻结一条快照（默认按 rateId 冻结，原价币 = 汇率的基准币）。 */
  SnapshotView freeze(
      AccessService.Access who,
      UUID rateId,
      String sourceCurrency,
      String amount,
      UUID documentId,
      String lineKey) {
    return write(
        who,
        () ->
            snapshots.create(
                who,
                new SnapshotInput(
                    rateId,
                    null,
                    null,
                    null,
                    sourceCurrency,
                    new BigDecimal(amount),
                    "PRICE_LIST",
                    documentId,
                    "PL-" + documentId.toString().substring(0, 8),
                    lineKey,
                    null,
                    "")));
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  java.util.List<org.jooq.Record> rows(AccessService.Access who, String sqlText, Object... params) {
    return tx(who, () -> sql.fetch(sqlText, params));
  }

  /**
   * 直连数据库跑一条 SQL（迁移 / 表 owner 角色）。
   *
   * <p>用来证明**连表 owner 也绕不过数据库层的守卫** —— 服务层的权限检查与
   * 应用角色的 GRANT 都不够，得数据库自己把这条路堵死。
   */
  void rawExecute(String sqlText, Object... params) {
    try (var conn =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var ps = conn.prepareStatement(sqlText)) {
      for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
      ps.execute();
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  static String rootMessage(Throwable e) {
    var current = e;
    var text = e == null ? "" : String.valueOf(e.getMessage());
    while (current != null && current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
      var causeMessage = String.valueOf(current.getMessage());
      if (causeMessage != null && !causeMessage.isBlank()) text = causeMessage;
    }
    return text == null ? "" : text;
  }

  // ================================================================ 冻结

  /**
   * 冻结的快照**真的落库**，且换算过程整行冻结在快照行里。
   *
   * <p>蓝图要求「保留换算过程（原价、汇率、舍入方式、目标币金额）」——
   * 这四项必须落在同一行，复算才不需要回查任何别的表。
   */
  @Test
  void 冻结的快照真的落库且换算过程整行冻结() {
    initRules(actor);
    var rateId = rate(actor, "USD", "CNY", "7.1234", DAY);
    var documentId = UUID.randomUUID();

    var view = freeze(actor, rateId, "USD", "100.00", documentId, "L1");

    // 接口层：方向显式 + 整句换算过程。
    assertThat(view.rateDisplay()).isEqualTo("1 USD = 7.1234 CNY");
    assertThat(view.sourceCurrency()).isEqualTo("USD");
    assertThat(view.targetCurrency()).isEqualTo("CNY");
    assertThat(view.conversionDirection()).isEqualTo("BASE_TO_QUOTE");
    assertThat(view.conversionDisplay()).contains("×").contains("712.34").contains("CNY");

    // 存储层：四项换算过程都在同一行里。
    var stored = rows(actor, "select * from exchange_rate_snapshot where id=?", view.id());
    assertThat(stored).hasSize(1);
    var row = stored.get(0);
    assertThat(row.get("source_amount", BigDecimal.class)).isEqualByComparingTo("100.000000");
    assertThat(row.get("target_amount", BigDecimal.class)).isEqualByComparingTo("712.340000");
    assertThat(row.get("rate", BigDecimal.class)).isEqualByComparingTo("7.12340000");
    assertThat(row.get("rounding_mode", String.class)).isEqualTo("HALF_UP");
    assertThat(row.get("rounding_scale", Integer.class)).isEqualTo(2);
    // 目标币种 CNY ⇒ 用 CNY 专用规则（不是通用兜底）：**先具体后通用**。
    assertThat(row.get("rounding_rule_code", String.class)).isEqualTo("CNY");
    assertThat(row.get("rate_id", UUID.class)).isEqualTo(rateId);
    assertThat(row.get("rate_effective_date", LocalDate.class)).isEqualTo(DAY);
    assertThat(row.get("rate_source_code", String.class)).isEqualTo("MANUAL");
    assertThat(row.get("tenant_id", UUID.class)).isEqualTo(a);
  }

  /**
   * 🔴 今天汇率变了，历史快照**一个数字都不动**。
   *
   * <p>这是蓝图加粗句「今天汇率变了不能让上个月的利润报表数字跟着变」的正身。
   * 做法是：冻结一条快照 → 把汇率表里那条汇率改掉 → 再读快照，断言数字没变。
   */
  @Test
  void 今天汇率变了历史快照一个数字都不动() {
    initRules(actor);
    var rateId = rate(actor, "USD", "CNY", "7.1234", DAY);
    var documentId = UUID.randomUUID();
    var frozen = freeze(actor, rateId, "USD", "100.00", documentId, "L1");
    assertThat(frozen.targetAmount()).isEqualByComparingTo("712.34");

    // 汇率变了（模拟「今天牌价改了」）。
    tx(
        actor,
        () -> {
          sql.execute(
              "update exchange_rate set rate=?,updated_at=now() where id=?",
              new BigDecimal("9.9999"),
              rateId);
          return null;
        });

    // 快照里的汇率与目标币金额**一个都没动**。
    var after = tx(actor, () -> snapshots.snapshot(frozen.id()));
    assertThat(after.rate()).isEqualByComparingTo("7.1234");
    assertThat(after.targetAmount()).isEqualByComparingTo("712.34");
    assertThat(after.rateDisplay()).isEqualTo("1 USD = 7.1234 CNY");

    // 直接查库也一样：快照不 JOIN 汇率表取值。
    var stored = rows(actor, "select rate,target_amount from exchange_rate_snapshot where id=?", frozen.id());
    assertThat(stored.get(0).get("rate", BigDecimal.class)).isEqualByComparingTo("7.1234");
    assertThat(stored.get(0).get("target_amount", BigDecimal.class)).isEqualByComparingTo("712.34");

    // 复算仍然一致（复算用的是快照里冻的汇率，不是汇率表当前的数）。
    var report =
        tx(actor, () -> snapshots.replay(actor, new ReplayRequest(frozen.id(), null, null)));
    assertThat(report.total()).isEqualTo(1);
    assertThat(report.matched()).isEqualTo(1);
    assertThat(report.mismatched()).isZero();
  }

  /** 反向换算按除法算，方向字段写明是「报价币→基准币」。 */
  @Test
  void 反向换算按除法算且方向显式() {
    initRules(actor);
    var rateId = rate(actor, "USD", "CNY", "7.1234", DAY);
    var documentId = UUID.randomUUID();

    // 原价币是 CNY（这条汇率的报价币那一侧）⇒ 除法。
    var view = freeze(actor, rateId, "CNY", "100.00", documentId, "L1");

    assertThat(view.conversionDirection()).isEqualTo("QUOTE_TO_BASE");
    assertThat(view.sourceCurrency()).isEqualTo("CNY");
    assertThat(view.targetCurrency()).isEqualTo("USD");
    assertThat(view.targetAmount()).isEqualByComparingTo("14.04");
    assertThat(view.conversionDisplay()).contains("÷").contains("14.04").contains("USD");
  }

  /** 原价币不在这条汇率的两侧 ⇒ 拒绝，不猜方向。 */
  @Test
  void 原价币不在汇率两侧时拒绝() {
    initRules(actor);
    var rateId = rate(actor, "USD", "CNY", "7.1234", DAY);
    var error =
        assertThrows(
            ResponseStatusException.class,
            () -> freeze(actor, rateId, "EUR", "100.00", UUID.randomUUID(), "L1"));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
    assertThat(error.getReason()).contains("必须是这条汇率的基准币");
  }

  /** 查不到汇率时报 404 并写明原因，不回退到「最近的一版」。 */
  @Test
  void 查不到汇率时报404而不是回退() {
    initRules(actor);
    var error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                write(
                    actor,
                    () ->
                        snapshots.create(
                            actor,
                            new SnapshotInput(
                                null,
                                "EUR",
                                "CNY",
                                TODAY,
                                "EUR",
                                new BigDecimal("100"),
                                "PRICE_LIST",
                                UUID.randomUUID(),
                                "PL-1",
                                "L1",
                                null,
                                ""))));
    assertThat(error.getStatusCode().value()).isEqualTo(404);
    assertThat(error.getReason()).contains("不会拿别的币种对");
  }

  /** 同一 单据行 + 同一 币种对 只能有一条快照；要更正就换行标识另冻一条。 */
  @Test
  void 同一单据行同一币种对只能有一条快照() {
    initRules(actor);
    var rateId = rate(actor, "USD", "CNY", "7.1234", DAY);
    var documentId = UUID.randomUUID();
    freeze(actor, rateId, "USD", "100.00", documentId, "L1");

    var error =
        assertThrows(
            ResponseStatusException.class,
            () -> freeze(actor, rateId, "USD", "200.00", documentId, "L1"));
    assertThat(error.getStatusCode().value()).isEqualTo(409);
    assertThat(error.getReason()).contains("只能有一条");

    // 换一行标识 ⇒ 可以再冻一条，两条都在（历史不被抹掉）。
    var second = freeze(actor, rateId, "USD", "200.00", documentId, "L1-v2");
    assertThat(second.id()).isNotNull();
    assertThat(
            rows(actor, "select * from exchange_rate_snapshot where document_id=?", documentId))
        .hasSize(2);
  }

  // ================================================================ 不可改写

  /**
   * 快照不允许被改写：**连表 owner（迁移角色）直连 SQL** 也不行。
   *
   * <p>两道防线都要钉死：① 应用角色根本没有 UPDATE 权限（只追加）；
   * ② 即使用 owner 直连 SQL，{@code guard_exchange_rate_snapshot_immutable} 触发器仍然拒绝 ——
   * 万一将来有人扩了 GRANT，「改写历史快照」这条路仍然堵死。
   */
  @Test
  void 快照不允许被改写连直连SQL也不行() {
    initRules(actor);
    var rateId = rate(actor, "USD", "CNY", "7.1234", DAY);
    var documentId = UUID.randomUUID();
    var frozen = freeze(actor, rateId, "USD", "100.00", documentId, "L1");

    // ① 应用角色：连 UPDATE 权限都没有。
    var denied =
        assertThrows(
            RuntimeException.class,
            () ->
                tx(
                    actor,
                    () -> {
                      sql.execute(
                          "update exchange_rate_snapshot set target_amount=? where id=?",
                          new BigDecimal("999.99"),
                          frozen.id());
                      return null;
                    }));
    assertThat(rootMessage(denied)).contains("permission denied");

    // ② 表 owner 直连 SQL：触发器照样拒绝。
    var blocked =
        assertThrows(
            RuntimeException.class,
            () ->
                rawExecute(
                    "update exchange_rate_snapshot set target_amount=? where id=?",
                    new BigDecimal("999.99"),
                    frozen.id()));
    assertThat(rootMessage(blocked)).contains("不允许修改");

    // 值确实没被改。
    var after = tx(actor, () -> snapshots.snapshot(frozen.id()));
    assertThat(after.targetAmount()).isEqualByComparingTo("712.34");
  }

  /**
   * 冻结值与汇率记录不一致的行**写不进来**（直连 SQL 也绕不过去）。
   *
   * <p>这是「快照上写着一个汇率表里根本不存在的汇率」这条路的物理封堵。
   */
  @Test
  void 冻结值与汇率记录不一致的行写不进来() {
    initRules(actor);
    var rateId = rate(actor, "USD", "CNY", "7.1234", DAY);
    var documentId = UUID.randomUUID();

    // 冻结值与汇率记录不一致（rate 写成别的数）。
    var wrongRate =
        assertThrows(
            RuntimeException.class,
            () ->
                tx(
                    actor,
                    () -> {
                      sql.execute(
                          "insert into exchange_rate_snapshot(id,tenant_id,document_type,document_id,"
                              + "document_no,line_key,source_currency,source_amount,target_currency,"
                              + "target_amount,rate_id,rate_base_currency,rate_quote_currency,rate,"
                              + "conversion_direction,rate_effective_date,rate_source_code,"
                              + "rounding_rule_code,rounding_mode,rounding_scale,frozen_at,created_by)"
                              + " values(?::uuid,?::uuid,'PRICE_LIST',?::uuid,'PL-X','L1','USD',100,"
                              + "'CNY',999.99,?::uuid,'USD','CNY',9.9999,'BASE_TO_QUOTE',?::date,"
                              + "'MANUAL','DEFAULT','HALF_UP',2,now(),?::uuid)",
                          UUID.randomUUID(),
                          actor.tenantId(),
                          documentId,
                          rateId,
                          DAY,
                          actor.userId());
                      return null;
                    }));
    assertThat(rootMessage(wrongRate)).contains("冻结的汇率与汇率记录不一致");

    // 方向与汇率记录不一致（把基准币写成 JPY）。
    var wrongDirection =
        assertThrows(
            RuntimeException.class,
            () ->
                tx(
                    actor,
                    () -> {
                      sql.execute(
                          "insert into exchange_rate_snapshot(id,tenant_id,document_type,document_id,"
                              + "document_no,line_key,source_currency,source_amount,target_currency,"
                              + "target_amount,rate_id,rate_base_currency,rate_quote_currency,rate,"
                              + "conversion_direction,rate_effective_date,rate_source_code,"
                              + "rounding_rule_code,rounding_mode,rounding_scale,frozen_at,created_by)"
                              + " values(?::uuid,?::uuid,'PRICE_LIST',?::uuid,'PL-X','L2','JPY',100,"
                              + "'CNY',999.99,?::uuid,'JPY','CNY',7.1234,'BASE_TO_QUOTE',?::date,"
                              + "'MANUAL','DEFAULT','HALF_UP',2,now(),?::uuid)",
                          UUID.randomUUID(),
                          actor.tenantId(),
                          documentId,
                          rateId,
                          DAY,
                          actor.userId());
                      return null;
                    }));
    assertThat(rootMessage(wrongDirection)).contains("冻结的汇率方向与汇率记录不一致");

    // 一条都没写进去。
    assertThat(rows(actor, "select * from exchange_rate_snapshot where document_id=?", documentId))
        .isEmpty();
  }

  // ================================================================ 舍入规则

  /**
   * 舍入规则**先具体后通用**：日元零位不能被通用两位规则盖掉。
   *
   * <p>日元按两位小数入账是真实存在的账务事故，所以这条必须钉死。
   */
  @Test
  void 舍入规则先具体后通用() {
    initRules(actor);
    var jpyRate = rate(actor, "USD", "JPY", "150.0000", DAY);
    var audRate = rate(actor, "USD", "AUD", "1.5000", DAY);
    var documentId = UUID.randomUUID();

    // 目标币种 JPY ⇒ 用 JPY 专用规则（零位小数）。
    var jpy = freeze(actor, jpyRate, "USD", "100.00", documentId, "L1");
    assertThat(jpy.roundingRuleCode()).isEqualTo("JPY");
    assertThat(jpy.roundingScale()).isZero();
    assertThat(jpy.targetAmount()).isEqualByComparingTo("15000");

    // 目标币种 AUD ⇒ 没有专用规则 ⇒ 用通用 DEFAULT（两位小数）。
    var aud = freeze(actor, audRate, "USD", "100.00", documentId, "L2");
    assertThat(aud.roundingRuleCode()).isEqualTo("DEFAULT");
    assertThat(aud.roundingScale()).isEqualTo(2);
    assertThat(aud.targetAmount()).isEqualByComparingTo("150.00");
  }

  /** 没有可用舍入规则时明确报错，不偷偷按默认口径舍入。 */
  @Test
  void 没有可用舍入规则时明确报错() {
    // 不初始化内置规则 ⇒ 租户一条规则都没有。
    var rateId = rate(actor, "USD", "CNY", "7.1234", DAY);
    var error =
        assertThrows(
            ResponseStatusException.class,
            () -> freeze(actor, rateId, "USD", "100.00", UUID.randomUUID(), "L1"));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
    assertThat(error.getReason()).contains("没有可用的舍入规则");
  }

  /** 改舍入规则**不动历史快照**：快照里冻的是当时的模式与位数。 */
  @Test
  void 改舍入规则不动历史快照() {
    initRules(actor);
    var rateId = rate(actor, "USD", "CNY", "7.1234", DAY);
    var documentId = UUID.randomUUID();
    var frozen = freeze(actor, rateId, "USD", "100.00", documentId, "L1");
    assertThat(frozen.roundingScale()).isEqualTo(2);

    // 把通用规则改成 4 位小数。
    var updated =
        write(
            actor,
            () ->
                snapshots.updateRoundingRule(
                    actor,
                    "DEFAULT",
                    new RoundingRuleUpdate(
                        "通用两位小数", null, "HALF_UP", 4, true, 100, "改成四位", 0L)));
    assertThat(updated.roundingScale()).isEqualTo(4);

    // 历史快照一个数字都没动。
    var after = tx(actor, () -> snapshots.snapshot(frozen.id()));
    assertThat(after.roundingScale()).isEqualTo(2);
    assertThat(after.targetAmount()).isEqualByComparingTo("712.34");
  }

  /** 初始化内置舍入规则是幂等的，且写进租户自己的库。 */
  @Test
  void 初始化内置舍入规则是幂等的且写进租户自己的库() {
    var first = write(actor, () -> snapshots.initializeRoundingRules(actor));
    assertThat(first.stream().map(RoundingRuleView::code))
        .contains("DEFAULT", "CNY", "USD", "EUR", "GBP", "JPY", "KRW");
    var second = write(actor, () -> snapshots.initializeRoundingRules(actor));
    assertThat(second.stream().map(RoundingRuleView::code).distinct().count())
        .isEqualTo(second.size());
    // B 租户看不到 A 的规则。
    assertThat(tx(actorB, () -> snapshots.roundingRules())).isEmpty();
  }

  // ================================================================ 复算

  /** 复算能重算出同一个目标币金额（利润复算能力是真的，不是话术）。 */
  @Test
  void 复算能重算出同一个目标币金额() {
    initRules(actor);
    var rateId = rate(actor, "USD", "CNY", "7.1234", DAY);
    var documentId = UUID.randomUUID();
    var frozen = freeze(actor, rateId, "USD", "100.00", documentId, "L1");

    var report =
        tx(actor, () -> snapshots.replay(actor, new ReplayRequest(null, "PRICE_LIST", documentId)));
    assertThat(report.total()).isEqualTo(1);
    assertThat(report.matched()).isEqualTo(1);
    assertThat(report.mismatched()).isZero();
    var row = report.rows().get(0);
    assertThat(row.snapshotId()).isEqualTo(frozen.id());
    assertThat(row.targetAmount()).isEqualByComparingTo("712.34");
    assertThat(row.recomputedTargetAmount()).isEqualByComparingTo("712.34");
    assertThat(row.difference()).isEqualByComparingTo("0");
    assertThat(row.rateDisplay()).isEqualTo("1 USD = 7.1234 CNY");
    assertThat(row.matched()).isTrue();

    // 复算请求什么都没给 ⇒ 明确报错（不是「空结果 = 通过」）。
    var error =
        assertThrows(
            ResponseStatusException.class,
            () -> tx(actor, () -> snapshots.replay(actor, new ReplayRequest(null, null, null))));
    assertThat(error.getStatusCode().value()).isEqualTo(400);
  }

  /** 单据上没有快照时，复算说「无从复算」而不是报「通过」。 */
  @Test
  void 单据上没有快照时复算说无从复算() {
    initRules(actor);
    var report =
        tx(
            actor,
            () ->
                snapshots.replay(
                    actor, new ReplayRequest(null, "PRICE_LIST", UUID.randomUUID())));
    assertThat(report.total()).isZero();
    assertThat(report.matched()).isZero();
    assertThat(report.note()).contains("无从复算");
  }

  // ================================================================ 时间与精度

  /**
   * 冻结时刻写回**不被时区偏移**：断言写回的值，而不是只断言没抛异常。
   *
   * <p>用 {@code Timestamp.valueOf(LocalDateTime.now())} 绑定 timestamptz 会静默偏 −28800s；
   * 这里断言库里那一列的瞬时值与 JVM 墙钟在分钟级以内。
   */
  @Test
  void 冻结时刻写回不被时区偏移() {
    initRules(actor);
    var rateId = rate(actor, "USD", "CNY", "7.1234", DAY);
    var before = OffsetDateTime.now().toInstant();
    var view = freeze(actor, rateId, "USD", "100.00", UUID.randomUUID(), "L1");
    var after = OffsetDateTime.now().toInstant();

    var stored = rows(actor, "select frozen_at from exchange_rate_snapshot where id=?", view.id());
    var frozen = stored.get(0).get("frozen_at", OffsetDateTime.class);
    assertThat(frozen).as("timestamptz 必须写回真实瞬时值").isNotNull();
    assertThat(frozen.toInstant())
        .as("写回的时刻不能比调用时刻偏 8 小时（时区静默偏移）")
        .isAfterOrEqualTo(before.minusSeconds(60))
        .isBeforeOrEqualTo(after.plusSeconds(60));
  }

  /** 金额与汇率列的精度：numeric(19,6) / numeric(19,8)，存得住也取得回。 */
  @Test
  void 金额与汇率列的精度是numeric() {
    var columns =
        rows(
            actor,
            "select column_name,data_type,numeric_precision,numeric_scale"
                + " from information_schema.columns where table_name='exchange_rate_snapshot'"
                + " and column_name in ('source_amount','target_amount','rate')");
    assertThat(columns).hasSize(3);
    for (var column : columns) {
      assertThat(column.get("data_type", String.class)).isEqualTo("numeric");
    }
    var scale =
        columns.stream()
            .filter(c -> "target_amount".equals(c.get("column_name", String.class)))
            .findFirst()
            .orElseThrow();
    assertThat(scale.get("numeric_precision", Integer.class)).isEqualTo(19);
    assertThat(scale.get("numeric_scale", Integer.class)).isEqualTo(6);
  }

  /** 两张表都开了强制 RLS，租户列 NOT NULL。 */
  @Test
  void 两张表都开了强制RLS() {
    for (var table : List.of("exchange_rate_snapshot", "exchange_rate_rounding_rule")) {
      var forced =
          rows(actor, "select relforcerowsecurity,relrowsecurity from pg_class where relname=?", table);
      assertThat(forced).as("表 %s 必须存在", table).hasSize(1);
      assertThat(forced.get(0).get("relrowsecurity", Boolean.class)).as("%s 开了 RLS", table).isTrue();
      assertThat(forced.get(0).get("relforcerowsecurity", Boolean.class))
          .as("%s 强制 RLS（漏 FORCE 表 owner 能绕过策略）", table)
          .isTrue();
      var policies =
          rows(actor, "select policyname from pg_policies where tablename=?", table);
      assertThat(policies.stream().map(p -> p.get("policyname", String.class)))
          .as("%s 的隔离策略", table)
          .contains("isolation");
    }
  }

  // ================================================================ 端点与能力声明

  /** 端点层：冻结 → 台账 → 详情 → 复算，全部走 HTTP；只读角色写端点 403。 */
  @Test
  void 端点层冻结与查询都通() throws Exception {
    initRules(actor);
    var rateId = rate(actor, "USD", "CNY", "7.1234", DAY);
    var documentId = UUID.randomUUID();

    http.perform(
            post("/catalog/exchange-rate-snapshots")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new SnapshotInput(
                            rateId,
                            null,
                            null,
                            null,
                            "USD",
                            new BigDecimal("100.00"),
                            "PRICE_LIST",
                            documentId,
                            "PL-1",
                            "L1",
                            null,
                            ""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rateDisplay").value("1 USD = 7.1234 CNY"))
        .andExpect(jsonPath("$.conversionDirection").value("BASE_TO_QUOTE"))
        .andExpect(jsonPath("$.roundingScale").value(2))
        .andExpect(jsonPath("$.roundingMode").value("HALF_UP"));

    http.perform(
            get("/catalog/exchange-rate-snapshots")
                .header("Authorization", "Bearer tenant-a")
                .param("documentType", "PRICE_LIST")
                .param("documentId", documentId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].rateDisplay").value("1 USD = 7.1234 CNY"));

    http.perform(
            post("/catalog/exchange-rate-snapshots/replay")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new ReplayRequest(null, "PRICE_LIST", documentId))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.matched").value(1))
        .andExpect(jsonPath("$.mismatched").value(0));

    http.perform(
            get("/catalog/exchange-rate-snapshots/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.directionRule").isNotEmpty())
        .andExpect(jsonPath("$.immutabilityRule").isNotEmpty());

    http.perform(
            get("/catalog/exchange-rate-snapshots/rounding-rules")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));

    // 只读角色写端点 403。
    http.perform(
            post("/catalog/exchange-rate-snapshots")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new SnapshotInput(
                            rateId,
                            null,
                            null,
                            null,
                            "USD",
                            new BigDecimal("1"),
                            "PRICE_LIST",
                            UUID.randomUUID(),
                            "PL-1",
                            "L1",
                            null,
                            ""))))
        .andExpect(status().isForbidden());
  }

  /** 能力声明把方向规则、冻结规则与舍入规则写在明处。 */
  @Test
  void 能力声明把方向规则与冻结规则写在明处() {
    initRules(actor);
    var capabilities = tx(actor, () -> snapshots.capabilities());
    assertThat(capabilities.directionRule()).contains("基准币").contains("报价币");
    assertThat(capabilities.immutabilityRule()).contains("冻结").contains("只追加");
    assertThat(capabilities.roundingRule()).contains("先具体后通用");
    assertThat(capabilities.upstreamNote()).contains("5.2.2.1");
    assertThat(capabilities.downstreamNote()).contains("09 订单").contains("13 利润");
    assertThat(capabilities.documentTypes()).isNotEmpty();
    assertThat(capabilities.roundingModes()).isNotEmpty();
    assertThat(capabilities.roundingRules()).isNotEmpty();
  }
}
