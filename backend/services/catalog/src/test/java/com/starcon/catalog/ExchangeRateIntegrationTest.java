package com.starcon.catalog;

import static com.starcon.catalog.ExchangeRateModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 5.2.2.1 汇率查询：真实 PostgreSQL 16 + 强制 RLS 下的方向显式、唯一性、反向推导、
 * 租户隔离三断言、外部拉取的真实请求与失败错误态。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：汇率事实、来源配置、拉取日志全部真的落库。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的六个断言</b>：
 *
 * <ol>
 *   <li>{@link #手工录入的汇率真的落库且方向显式()}：方向必须落成
 *       {@code base_currency} / {@code quote_currency} 两个**字段**，而���是注释。
 *   <li>{@link #反向汇率由正向推导而不是手工再录一条()}：反向只允许推导；
 *       手工再录一条方向相反的必须被拒（那是两个互相矛盾的事实）。
 *   <li>{@link #同一币种对同一生效日只能有一条生效记录()}：「今天的汇率」不许有多个答案。
 *   <li>{@link #租户隔离三断言()}：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。
 *   <li>{@link #外部拉取真的发请求并把汇率入库()}：真发 HTTP 真入库，且**不带用户凭据**。
 *   <li>{@link #外部拉取失败时有明确错误态而不是静默返回空()}：失败要看得见。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExchangeRateIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer external;
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();

  /** 外部源最近一次收到的查询参数（用来断言「真的发了请求」且参数正确）。 */
  static final AtomicReference<String> lastQuery = new AtomicReference<>();

  /** 外部源最近一次收到的 Authorization 头：必须是 null（不把用户凭据送给第三方）。 */
  static final AtomicReference<String> lastExternalAuth = new AtomicReference<>();

  /** 置位后外部源一律回 500：模拟「外部汇率源挂了」。 */
  static final AtomicBoolean externalDown = new AtomicBoolean();

  /** 置位后 identity 的法人端点一律回 503：模拟「身份服务不可用」。 */
  static final AtomicBoolean identityDown = new AtomicBoolean();

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
      // identity 的法人档案端点（17.1.2.2 本位币）：本模块跨服务只读它。
      identity.createContext(
          "/identity/legal-entities",
          e -> {
            if (identityDown.get()) {
              e.sendResponseHeaders(503, -1);
              e.close();
              return;
            }
            var bearer = e.getRequestHeaders().getFirst("Authorization");
            var tenant = "Bearer tenant-b".equals(bearer) ? b : a;
            var body =
                JSON.writeValueAsBytes(
                    List.of(
                        Map.of(
                            "id", UUID.randomUUID(),
                            "code", "LE-" + (tenant.equals(a) ? "A" : "B"),
                            "legalName", "测试法人",
                            "baseCurrency", "CNY",
                            "status", "ACTIVE"),
                        Map.of(
                            "id", UUID.randomUUID(),
                            "code", "LE-" + (tenant.equals(a) ? "A" : "B") + "-2",
                            "legalName", "测试法人二",
                            "baseCurrency", "USD",
                            "status", "ACTIVE")));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.start();

      // 外部汇率源：一个真实的 HTTP 服务器（第三方汇率接口的替身）。
      external = HttpServer.create(new InetSocketAddress(0), 0);
      external.createContext(
          "/rates",
          e -> {
            lastQuery.set(e.getRequestURI().getQuery());
            lastExternalAuth.set(e.getRequestHeaders().getFirst("Authorization"));
            if (externalDown.get()) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            var body =
                JSON.writeValueAsBytes(
                    Map.of(
                        "rate", "7.12345678",
                        "effectiveDate", DAY.toString(),
                        "sourceRef", "STUB-REF-1"));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      external.start();

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

  @Autowired ExchangeRateService rates;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    external.stop(0);
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setUp() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    externalDown.set(false);
    identityDown.set(false);
    lastQuery.set(null);
    lastExternalAuth.set(null);
    // 每个用例一套干净的汇率与拉取日志：同一 币种对 + 同一生效日 只能有一条，
    // 上一个用例留下的行会让下一个用例撞唯一约束（那是**本模块的正确行为**）。
    clean(actor);
    clean(actorB);
    // 每个用例一套干净的来源与汇率（按来源码 + 币种对 + 日期唯一，日期用唯一值避免互相干扰）。
    source(actor, "MANUAL", "手工录入", "MANUAL", null);
    source(actorB, "MANUAL", "手工录入", "MANUAL", null);
  }

  /**
   * 清掉该租户的汇率。
   *
   * <p><b>拉取日志不清</b>：它是 append-only 的（DB 层只给 SELECT/INSERT 授权），
   * 刻意不提供删除 —— 日志是可追溯的凭据。所以下面的断言按状态筛，不按位置取。
   */
  void clean(AccessService.Access who) {
    tx(
        who,
        () -> {
          sql.execute("delete from exchange_rate");
          return null;
        });
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  // ================================================================ 夹具

  /** 登记一个汇率来源。 */
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

  /** 手工录入一条汇率。 */
  RateView record(
      AccessService.Access who, String base, String quote, String value, LocalDate date, String source) {
    return write(
        who,
        () ->
            rates.create(
                who,
                new RateInput(base, quote, new BigDecimal(value), date, source, "ref-" + SEQ.get(), "")));
  }

  /** 直接查库（绕过服务层），证明真的落进了 PostgreSQL。 */
  java.util.List<org.jooq.Record> rows(AccessService.Access who, String sqlText, Object... params) {
    return tx(who, () -> sql.fetch(sqlText, params));
  }

  // ================================================================ 方向显式

  /**
   * 手工录入的汇率真的落库，且方向落成**两个字段**（不是注释、不是裸数字）。
   */
  @Test
  void 手工录入的汇率真的落库且方向显式() {
    var view = record(actor, "USD", "CNY", "7.1234", DAY, "MANUAL");

    // 接口层：显示句必须带方向。
    assertThat(view.display()).isEqualTo("1 USD = 7.1234 CNY");
    assertThat(view.baseCurrency()).isEqualTo("USD");
    assertThat(view.quoteCurrency()).isEqualTo("CNY");

    // 存储层：两个方向字段都在，且行数恰好一条。
    var stored = rows(actor, "select * from exchange_rate where id=?", view.id());
    assertThat(stored).hasSize(1);
    assertThat(stored.get(0).get("base_currency", String.class)).isEqualTo("USD");
    assertThat(stored.get(0).get("quote_currency", String.class)).isEqualTo("CNY");
    assertThat(stored.get(0).get("rate", BigDecimal.class)).isEqualByComparingTo("7.1234");
    assertThat(stored.get(0).get("effective_date", LocalDate.class)).isEqualTo(DAY);
    assertThat(stored.get(0).get("source_code", String.class)).isEqualTo("MANUAL");
    assertThat(stored.get(0).get("entry_mode", String.class)).isEqualTo("MANUAL");
    // tenant_id NOT NULL。
    assertThat(stored.get(0).get("tenant_id", UUID.class)).isEqualTo(a);
  }

  /**
   * 反向汇率**由正向推导**，不许手工再录一条方向相反的。
   *
   * <p>蓝图加粗句的正面与反面都要断言：正着录能得到反向值；反着录会被拒。
   */
  @Test
  void 反向汇率由正向推导而不是手工再录一条() {
    var forward = record(actor, "USD", "CNY", "7.1234", DAY, "MANUAL");

    // 反向值是推导出来的，并且带标注。
    assertThat(forward.inverseDerived()).isTrue();
    assertThat(forward.inverseRate()).isEqualByComparingTo("0.14038240");
    assertThat(forward.inverseDisplay()).contains("1 CNY = 0.1403824 USD").contains("推导");

    // 库里**没有**多出一条方向相反的行。
    assertThat(rows(actor, "select * from exchange_rate")).hasSize(1);

    // 手工再录一条方向相反的 ⇒ 409，且理由写明「两个互相矛盾的事实」。
    var error =
        assertThrows(
            ResponseStatusException.class,
            () -> record(actor, "CNY", "USD", "0.1404", DAY, "MANUAL"));
    assertThat(error.getStatusCode().value()).isEqualTo(409);
    assertThat(error.getReason()).contains("方向相反").contains("矛盾");
    assertThat(rows(actor, "select * from exchange_rate")).hasSize(1);

    // 反向查询查不到已存的行（它是推导值，不是事实）⇒ 404，不回退。
    var missing =
        assertThrows(
            ResponseStatusException.class, () -> tx(actor, () -> rates.latest("CNY", "USD", TODAY)));
    assertThat(missing.getStatusCode().value()).isEqualTo(404);
  }

  /** 同一 币种对 + 同一生效日 只能有一条生效记录。 */
  @Test
  void 同一币种对同一生效日只能有一条生效记录() {
    record(actor, "USD", "CNY", "7.1234", DAY, "MANUAL");
    var error =
        assertThrows(
            ResponseStatusException.class, () -> record(actor, "USD", "CNY", "7.2000", DAY, "MANUAL"));
    assertThat(error.getStatusCode().value()).isEqualTo(409);
    assertThat(error.getReason()).contains("只能有一条");
    assertThat(rows(actor, "select * from exchange_rate")).hasSize(1);

    // 不同生效日可以各有一条。
    record(actor, "USD", "CNY", "7.2000", TODAY, "MANUAL");
    assertThat(rows(actor, "select * from exchange_rate")).hasSize(2);
  }

  /** 最新汇率：取 asOf 当天或之前最近的一条；查不到就 404，不回退到别的币种对。 */
  @Test
  void 最新汇率取指定日期之前最近的一条且查不到时报404() {
    record(actor, "USD", "CNY", "7.1000", DAY, "MANUAL");
    record(actor, "USD", "CNY", "7.2000", TODAY, "MANUAL");

    var latest = tx(actor, () -> rates.latest("USD", "CNY", TODAY));
    assertThat(latest.display()).isEqualTo("1 USD = 7.2 CNY");
    assertThat(latest.effectiveDate()).isEqualTo(TODAY);

    // asOf 早于两条 ⇒ 取到更早的那条。
    var asOf = tx(actor, () -> rates.latest("USD", "CNY", DAY));
    assertThat(asOf.display()).isEqualTo("1 USD = 7.1 CNY");

    // 查不到 ⇒ 404，且理由写明「不会拿别的汇率顶替」。
    var missing =
        assertThrows(
            ResponseStatusException.class, () -> tx(actor, () -> rates.latest("EUR", "CNY", TODAY)));
    assertThat(missing.getStatusCode().value()).isEqualTo(404);
    assertThat(missing.getReason()).contains("不会拿别的币种对");
  }

  // ================================================================ 租户隔离三断言

  /**
   * 租户隔离三断言，<b>并且覆盖汇率事实行与来源配置行</b>。
   *
   * <p>只测主体等于没测：汇率决定了每一次换算与利润，A 读到 B 的汇率会让 A 的利润整体算错。
   */
  @Test
  void 租户隔离三断言() throws Exception {
    var myRate = record(actor, "USD", "CNY", "7.1234", DAY, "MANUAL");
    var theirRate = record(actorB, "USD", "CNY", "9.9999", DAY, "MANUAL");
    source(actorB, "FOREIGN_ONLY_B", "B 租户独有来源", "EXTERNAL", null);

    // 两边都必须真的有数据 —— 否则下面的断言就是空的。
    assertThat(tx(actor, () -> rates.rate(myRate.id())).display()).isEqualTo("1 USD = 7.1234 CNY");
    assertThat(tx(actorB, () -> rates.rate(theirRate.id())).display()).isEqualTo("1 USD = 9.9999 CNY");

    // 1) 两租户互不可见
    assertThatThrownBy(() -> tx(actor, () -> rates.rate(theirRate.id())))
        .as("A 读不到 B 的汇率")
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> rates.rate(myRate.id())))
        .as("B 读不到 A 的汇率")
        .isInstanceOf(ResponseStatusException.class);
    // A 的台账里混不进 B 的行。
    var myRows = tx(actor, () -> rates.list(new RateListQuery(null, null, null, null, null, null, 0, 200)));
    assertThat(myRows.items()).isNotEmpty();
    assertThat(myRows.items().stream().map(RateView::id)).doesNotContain(theirRate.id());
    assertThat(myRows.items().stream().map(RateView::id)).contains(myRate.id());
    // 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    assertThat(
            rows(actor, "select * from exchange_rate where tenant_id=?", b).size())
        .isZero();
    // 来源配置同样互不可见。
    assertThat(tx(actor, () -> rates.sources()).stream().map(RateSourceView::code))
        .doesNotContain("FOREIGN_ONLY_B");
    assertThat(tx(actorB, () -> rates.sources()).stream().map(RateSourceView::code))
        .contains("FOREIGN_ONLY_B");

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(
            get("/catalog/exchange-rates/" + theirRate.id())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/exchange-rates/" + myRate.id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 伪造头下的写入仍落在**调用者自己**名下 —— 断言行确实落在 A 名下，不只断言 B 看不见。
    http.perform(
            post("/catalog/exchange-rates")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new RateInput(
                            "EUR",
                            "CNY",
                            new BigDecimal("7.8000"),
                            DAY,
                            "MANUAL",
                            "伪造头下的录入",
                            ""))))
        .andExpect(status().isOk());
    var forged = rows(actor, "select * from exchange_rate where base_currency='EUR'");
    assertThat(forged).hasSize(1);
    assertThat(forged.get(0).get("tenant_id", UUID.class)).isEqualTo(a);

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言两个集合不相交
    for (int i = 0; i < 5; i++) {
      // 比较的是**行**（汇率记录 id）而不是币种码：两个租户都登记 USD→CNY 是正常的，
      // 不相交的是「谁看得见哪些行」。
      var mySet =
          tx(
                  actor,
                  () ->
                      rates.list(new RateListQuery(null, null, null, null, null, null, 0, 200))
                          .items()
                          .stream()
                          .map(RateView::id)
                          .collect(java.util.stream.Collectors.toSet()));
      var theirSet =
          tx(
                  actorB,
                  () ->
                      rates.list(new RateListQuery(null, null, null, null, null, null, 0, 200))
                          .items()
                          .stream()
                          .map(RateView::id)
                          .collect(java.util.stream.Collectors.toSet()));
      assertThat(mySet).isNotEmpty();
      assertThat(theirSet).isNotEmpty();
      assertThat(java.util.Collections.disjoint(mySet, theirSet)).isTrue();
      // 明细也不串：A 读 B 的汇率必须失败，而不是「读到了 B 的汇率」。
      assertThatThrownBy(() -> tx(actor, () -> rates.rate(theirRate.id())))
          .as("A 读 B 的汇率必须失败而不是串过去")
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  // ================================================================ 来源配置

  /** 来源可配置：停用后挡住录入；被汇率引用时不能删。 */
  @Test
  void 来源可配置停用后挡住录入且被引用时不能删() {
    var created =
        write(
            actor,
            () ->
                rates.createSource(
                    actor,
                    new RateSourceInput("SHOPEE", "平台结算", "MANUAL", true, 50, null, "")));
    assertThat(created.code()).isEqualTo("SHOPEE");
    assertThat(created.rateCount()).isZero();

    // 改名与改优先级（乐观锁）。
    var updated =
        write(
            actor,
            () ->
                rates.updateSource(
                    actor,
                    "SHOPEE",
                    new RateSourceUpdate("平台结算汇率", "MANUAL", true, 10, null, "改过", 0L)));
    assertThat(updated.name()).isEqualTo("平台结算汇率");
    assertThat(updated.priority()).isEqualTo(10);

    // 外部来源可以**先登记、后填地址**：缺地址时拉取端点明确拒绝，不发必然失败的请求。
    var externalSource =
        write(
            actor,
            () ->
                rates.createSource(
                    actor,
                    new RateSourceInput("NO_ENDPOINT", "没地址", "EXTERNAL", true, 10, null, "")));
    assertThat(externalSource.code()).isEqualTo("NO_ENDPOINT");
    assertThat(externalSource.endpoint()).isNull();
    var pullBlocked =
        write(actor, () -> rates.fetch(actor, new FetchRequest("NO_ENDPOINT", "USD", "CNY", DAY)));
    assertThat(pullBlocked.status()).isEqualTo("FAILED");
    assertThat(pullBlocked.failureStatus()).isEqualTo(400);
    assertThat(pullBlocked.errorMessage()).contains("没有配置外部地址");

    // 手工录入来源配地址是死配置 ⇒ 拒绝。
    var bad =
        assertThrows(
            ResponseStatusException.class,
            () ->
                write(
                    actor,
                    () ->
                        rates.createSource(
                            actor,
                            new RateSourceInput(
                                "MANUAL_WITH_URL", "手工却带地址", "MANUAL", true, 10,
                                "https://example.invalid/rates", ""))));
    assertThat(bad.getStatusCode().value()).isEqualTo(400);
    assertThat(bad.getReason()).contains("不应配地址");

    // 停用后挡住录入。
    var disabled =
        write(
            actor,
            () ->
                rates.updateSource(
                    actor,
                    "SHOPEE",
                    new RateSourceUpdate("平台结算汇率", "MANUAL", false, 10, null, "停用", 1L)));
    assertThat(disabled.enabled()).isFalse();
    var blocked =
        assertThrows(
            ResponseStatusException.class, () -> record(actor, "USD", "CNY", "7.1", DAY, "SHOPEE"));
    assertThat(blocked.getStatusCode().value()).isEqualTo(400);
    assertThat(blocked.getReason()).contains("已停用");

    // 被引用后不能删（DB 触发器）：删掉会让历史汇率指向一个查不到的定义。
    source(actor, "MANUAL", "手工录入", "MANUAL", null);
    record(actor, "USD", "CNY", "7.1", DAY, "MANUAL");
    var deleteBlocked =
        assertThrows(
            RuntimeException.class,
            () ->
                tx(
                    actor,
                    () -> {
                      sql.execute("delete from exchange_rate_source where code='MANUAL'");
                      return null;
                    }));
    assertThat(rootMessage(deleteBlocked)).contains("不能删除");
  }

  /** 初始化内置来源：幂等，且写进租户自己的库。 */
  @Test
  void 初始化内置来源是幂等的且写进租户自己的库() {
    var first = write(actor, () -> rates.initializeSources(actor));
    assertThat(first.stream().map(RateSourceView::code))
        .contains("BANK_OF_CHINA", "PBOC", "PLATFORM_SETTLEMENT", "MANUAL");
    // 再来一次不重复、不覆盖。
    var second = write(actor, () -> rates.initializeSources(actor));
    assertThat(second.stream().map(RateSourceView::code).distinct().count())
        .isEqualTo(second.size());
    assertThat(rows(actor, "select * from exchange_rate_source where code='PBOC'")).hasSize(1);
    // B 租户看不到 A 的来源。
    assertThat(rows(actorB, "select * from exchange_rate_source where code='PBOC'")).isEmpty();
  }

  // ================================================================ 外部拉取

  /**
   * 外部拉取**真的发请求**、真的入库，而且**不带用户的凭据**。
   */
  @Test
  void 外部拉取真的发请求并把汇率入库() {
    source(actor, "BANK_OF_CHINA", "中行牌价", "EXTERNAL", externalBase() + "/rates");
    var result =
        write(
            actor,
            () ->
                rates.fetch(
                    actor, new FetchRequest("BANK_OF_CHINA", "USD", "CNY", DAY)));

    assertThat(result.status()).isEqualTo("SUCCEEDED");
    assertThat(result.rowsFetched()).isEqualTo(1);
    assertThat(result.rowsSaved()).isEqualTo(1);
    assertThat(result.rates()).hasSize(1);
    assertThat(result.rates().get(0).display()).isEqualTo("1 USD = 7.12345678 CNY");
    assertThat(result.rates().get(0).entryMode()).isEqualTo("EXTERNAL");

    // 请求真的发出去了，且参数正确。
    assertThat(lastQuery.get()).contains("base=USD").contains("quote=CNY").contains("date=" + DAY);
    // 🔴 外部源是第三方：不能把用户的 Authorization 头送出去。
    assertThat(lastExternalAuth.get()).as("外部源不得收到用户凭据").isNull();

    // 真的落库了。
    var stored = rows(actor, "select * from exchange_rate where entry_mode='EXTERNAL'");
    assertThat(stored).hasSize(1);
    assertThat(stored.get(0).get("rate", BigDecimal.class)).isEqualByComparingTo("7.12345678");
    assertThat(stored.get(0).get("source_ref", String.class)).isEqualTo("STUB-REF-1");

    // 拉取日志落了一行 SUCCEEDED（日志是 append-only，按状态筛而不是按位置取）。
    var logs = tx(actor, () -> rates.fetchLog(0, 100));
    var succeeded =
        logs.stream().filter(l -> "SUCCEEDED".equals(l.status())).findFirst().orElse(null);
    assertThat(succeeded).as("拉取日志里必须有成功记录").isNotNull();
    assertThat(succeeded.rowsSaved()).isEqualTo(1);
    assertThat(succeeded.rowsFetched()).isEqualTo(1);
  }

  /**
   * 外部拉取失败时**有明确错误态**，并落**持久**的失败日志 —— 不静默返回空。
   *
   * <p>失败路径不抛异常（抛异常会回滚事务，把失败日志一起回滚掉），
   * 所以端点层把 FAILED 映射成 503 / 400 的响应体。
   */
  @Test
  void 外部拉取失败时有明确错误态而不是静默返回空() throws Exception {
    source(actor, "BANK_OF_CHINA", "中行牌价", "EXTERNAL", externalBase() + "/rates");
    externalDown.set(true);

    var result =
        write(
            actor,
            () -> rates.fetch(actor, new FetchRequest("BANK_OF_CHINA", "USD", "CNY", DAY)));
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.failureStatus()).isEqualTo(503);
    assertThat(result.errorMessage()).contains("500");
    assertThat(result.rates()).isEmpty();

    // 失败日志：有原因，不是空字符串（这一行必须在失败之后仍然留在库里）。
    var logs = tx(actor, () -> rates.fetchLog(0, 100));
    var failed =
        logs.stream().filter(l -> "FAILED".equals(l.status())).findFirst().orElse(null);
    assertThat(failed).as("拉取日志里必须有失败记录").isNotNull();
    assertThat(failed.errorMessage()).isNotBlank();
    assertThat(failed.errorMessage()).contains("500");
    // 一条汇率都没写进去。
    assertThat(rows(actor, "select * from exchange_rate")).isEmpty();

    // 端点层：503 + 响应体带原因。
    http.perform(
            post("/catalog/exchange-rates/fetch")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new FetchRequest("BANK_OF_CHINA", "USD", "CNY", DAY))))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.errorMessage").isNotEmpty());
  }

  /** 没配地址的来源拉取时明确报错，不发一个必然失败的请求。 */
  @Test
  void 没配地址的来源拉取时明确报错() {
    source(actor, "PBOC", "央行中间价", "EXTERNAL", null);
    var result = write(actor, () -> rates.fetch(actor, new FetchRequest("PBOC", "USD", "CNY", DAY)));
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.failureStatus()).isEqualTo(400);
    assertThat(result.errorMessage()).contains("没有配置外部地址");
    // 一次请求都没发出去。
    assertThat(lastQuery.get()).isNull();
    // 失败也留痕。
    var logs = tx(actor, () -> rates.fetchLog(0, 100));
    assertThat(logs.stream().filter(l -> "FAILED".equals(l.status())).count()).isPositive();
  }

  // ================================================================ 本位币

  /** 本位币面板读 identity 的法人档案；读不到时 503，不用空集合冒充「没有本位币」。 */
  @Test
  void 本位币面板来自identity且读不到时报503() {
    // 先录一条 USD→CNY 汇率：面板是「各币种对各本位币的汇率」，没有汇率就没什么可显示。
    record(actor, "USD", "CNY", "7.1234", DAY, "MANUAL");
    var panel = tx(actor, () -> rates.functionalCurrencyPanel("Bearer tenant-a"));
    assertThat(panel.rows()).isNotEmpty();
    var cnyRow =
        panel.rows().stream()
            .filter(r -> "CNY".equals(r.baseCurrency()))
            .findFirst()
            .orElse(null);
    assertThat(cnyRow).as("本位币 CNY 那一行必须在面板里").isNotNull();
    // 面板里的每条汇率都带方向。
    assertThat(cnyRow.rates()).isNotEmpty();
    assertThat(cnyRow.rates().get(0).display()).isEqualTo("1 USD = 7.1234 CNY");

    identityDown.set(true);
    var error =
        assertThrows(
            ResponseStatusException.class,
            () -> tx(actor, () -> rates.functionalCurrencyPanel("Bearer tenant-a")));
    assertThat(error.getStatusCode().value()).isEqualTo(503);
    assertThat(error.getReason()).contains("读不到本位币");
  }

  // ================================================================ 能力声明与时间

  /** 能力声明必须把方向规则、唯一性规则、反向规则与未交付的下游写在明处。 */
  @Test
  void 能力声明明示方向规则与未交付的下游() {
    var capabilities = tx(actor, () -> rates.capabilities());
    assertThat(capabilities.directionRule()).contains("基准币种").contains("报价币种");
    assertThat(capabilities.uniquenessRule()).contains("只能有一条");
    assertThat(capabilities.inverseRule()).contains("推导");
    assertThat(capabilities.downstreamNote()).contains("5.2.2.2").contains("尚未交付");
    assertThat(capabilities.sources()).isNotEmpty();
  }

  /**
   * 生效时刻写回**不被时区偏移**：断言写回的值，而不是只断言没抛异常。
   *
   * <p>用 {@code Timestamp.valueOf(LocalDateTime.now())} 绑定 timestamptz 会静默偏 −28800s；
   * 这里断言库里那一列的瞬时值与 JVM 墙钟在分钟级以内。
   */
  @Test
  void 生效时刻写回不被时区偏移() {
    var before = OffsetDateTime.now().toInstant();
    var view = record(actor, "USD", "CNY", "7.1234", DAY, "MANUAL");
    var after = OffsetDateTime.now().toInstant();

    var stored =
        rows(actor, "select acquired_at,effective_date from exchange_rate where id=?", view.id());
    assertThat(stored).hasSize(1);
    var acquired = stored.get(0).get("acquired_at", OffsetDateTime.class);
    assertThat(acquired).as("timestamptz 必须写回真实瞬时值").isNotNull();
    var epoch = acquired.toInstant();
    assertThat(epoch)
        .as("写回的时刻不能比调用时刻偏 8 小时（时区静默偏移）")
        .isAfterOrEqualTo(before.minusSeconds(60))
        .isBeforeOrEqualTo(after.plusSeconds(60));
    // 生效日是 date 列，不受时区影响。
    assertThat(stored.get(0).get("effective_date", LocalDate.class)).isEqualTo(DAY);
  }

  // ================================================================ 端点

  /** 端点层：录入 → 台账 → 详情 → 最新，全部走 HTTP。 */
  @Test
  void 端点层录入与查询都通() throws Exception {
    http.perform(
            post("/catalog/exchange-rates")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new RateInput(
                            "USD", "CNY", new BigDecimal("7.1234"), DAY, "MANUAL", "端点录入", ""))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.display").value("1 USD = 7.1234 CNY"))
        .andExpect(jsonPath("$.baseCurrency").value("USD"))
        .andExpect(jsonPath("$.quoteCurrency").value("CNY"))
        .andExpect(jsonPath("$.inverseDerived").value(true));

    http.perform(
            get("/catalog/exchange-rates")
                .header("Authorization", "Bearer tenant-a")
                .param("baseCurrency", "USD")
                .param("quoteCurrency", "CNY"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].display").value("1 USD = 7.1234 CNY"));

    http.perform(
            get("/catalog/exchange-rates/latest")
                .header("Authorization", "Bearer tenant-a")
                .param("baseCurrency", "USD")
                .param("quoteCurrency", "CNY"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.display").value("1 USD = 7.1234 CNY"));

    http.perform(get("/catalog/exchange-rates/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.directionRule").isNotEmpty());

    // 只读角色写端点 403。
    http.perform(
            post("/catalog/exchange-rates")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new RateInput(
                            "EUR", "CNY", new BigDecimal("7.8"), DAY, "MANUAL", "", ""))))
        .andExpect(status().isForbidden());
  }

  // ================================================================ 工具

  static String externalBase() {
    return "http://localhost:" + external.getAddress().getPort();
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
}
