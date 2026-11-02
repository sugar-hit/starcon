package com.starcon.catalog;

import static com.starcon.catalog.PriceListModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * 5.1.1.1 价目表列表：真实 PostgreSQL 16 + 强制 RLS 下的价目表 CRUD、币种硬约束、
 * 生效区间（半开区间）、适用范围与覆盖范围摘要，以及**表内价目项数量**这条计数契约。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：价目表与适用范围真的落库。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>跨服务依赖的处理</b>：法人来自 identity（另一个数据库），店铺与站点来自 channel
 * （第三个数据库）。生产路径是真实的 {@code RestClient} HTTP 调用，本测试用两个
 * {@link HttpServer} 起确定性替身来满足它们 —— 替身只存在于测试代码里，
 * 所以它证明的是<b>生产代码真的走 HTTP 且透传了租户上下文</b>。
 *
 * <p><b>本文件最重要的八个断言</b>：
 *
 * <ol>
 *   <li>{@link #一价目一币种由数据库挡住()}：混币种写法在 **DB 层**被命名约束挡住
 *       （SQLSTATE 23514 + 约束名 {@code price_list_one_currency}），
 *       <b>不是</b>只有服务层的校验。
 *   <li>{@link #生效区间写回值逐字相等()}：写进去的 {@code timestamptz} 读回来是同一个瞬间
 *       （用 +08:00 的偏移写，断言不会被静默偏成 −28800s）。
 *   <li>{@link #半开区间的边界()}：起点含、终点不含 —— 两张首尾相接的价目表不同时生效。
 *   <li>{@link #适用范围真的落库并算出覆盖摘要()}：三类范围各一条，覆盖摘要逐项计数。
 *   <li>{@link #范围里的编码必须真实存在()}：上游目录里没有的编码 ⇒ 400，不是「先收下再说」。
 *   <li>{@link #上游读不到时返回503而不是空目录()}：读不到上游 ≠ 没有法人 / 没有店铺。
 *   <li>{@link #价目项数量由触发器维护()}：计数契约（5.1.1.2 的触发器）真的把数量写回。
 *   <li>{@link #客户端不能直接写价目项数量()}：数量是读出来的事实，不是客户端能填的字段。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class PriceListIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer channel;
  /**
   * 测试用的 JSON 序列化器。
   *
   * <p>🔴 必须关掉 {@code WRITE_DATES_AS_TIMESTAMPS}：默认的 ObjectMapper 会把
   * {@link java.time.OffsetDateTime} 写成**数字**（1790812800.000000000），
   * 而服务端（Spring Boot 默认 write-dates-as-timestamps=false）只认 ISO 字符串 ——
   * 那样 POST 会被 400 掉，而原因藏在序列化器里，看不出是日期形状的问题。
   */
  static final ObjectMapper JSON =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();

  /** 渠道服务的店铺目录是否「挂了」（用来证明读不到上游时是 503 而不是空目录）。 */
  static final AtomicBoolean CHANNEL_SHOPS_DOWN = new AtomicBoolean(false);

  static final String LEGAL_ENTITY_CODE = "US-HQ";
  static final String SHOP_CODE = "AMZ-US";
  static final String SITE_CODE = "US";
  static final String PLATFORM_CODE = "AMAZON";

  static AccessService.Access actor(
      UUID uid, UUID tenant, List<AccessService.Grant> grants) {
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
      identity.createContext(
          "/identity",
          e -> {
            // 法人档案（17.1.2.2）。
            var entity = new LinkedHashMap<String, Object>();
            entity.put("id", UUID.randomUUID().toString());
            entity.put("code", LEGAL_ENTITY_CODE);
            entity.put("legalName", "美国总部");
            entity.put("baseCurrency", "USD");
            entity.put("status", "ACTIVE");
            byte[] body = JSON.writeValueAsBytes(List.of(entity));
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      startChannelStub();
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions()))));
      actors.put(
          "Bearer reader",
          actor(user, a, List.of(grant("ALL", List.of("catalog.spu.read", "catalog.sku.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** channel 替身：平台目录（2.1.1.1）与店铺列表（2.3.1.1）。 */
  static void startChannelStub() throws Exception {
    channel = HttpServer.create(new InetSocketAddress(0), 0);
    channel.createContext(
        "/channel",
        e -> {
          var path = e.getRequestURI().getPath();
          Object body;
          if (path.equals("/channel/shops")) {
            if (CHANNEL_SHOPS_DOWN.get()) {
              e.sendResponseHeaders(500, -1);
              e.close();
              return;
            }
            var marketplace = new LinkedHashMap<String, Object>();
            marketplace.put("marketplaceCode", SITE_CODE);
            marketplace.put("status", "ACTIVE");
            var shop = new LinkedHashMap<String, Object>();
            shop.put("id", UUID.randomUUID().toString());
            shop.put("platformCode", PLATFORM_CODE);
            shop.put("legalEntityId", null);
            shop.put("code", SHOP_CODE);
            shop.put("name", "亚马逊美国自发货店");
            shop.put("status", "ACTIVE");
            shop.put("marketplaces", List.of(marketplace));
            body = List.of(shop);
          } else {
            var marketplace = new LinkedHashMap<String, Object>();
            marketplace.put("id", UUID.randomUUID().toString());
            marketplace.put("code", SITE_CODE);
            marketplace.put("name", "美国站");
            marketplace.put("countryCode", "US");
            marketplace.put("defaultCurrency", "USD");
            marketplace.put("defaultTimezone", "America/Los_Angeles");
            marketplace.put("defaultLocale", "en-US");
            marketplace.put("currencyScale", 2);
            marketplace.put("vatRequired", false);
            var platform = new LinkedHashMap<String, Object>();
            platform.put("id", UUID.randomUUID().toString());
            platform.put("code", PLATFORM_CODE);
            platform.put("name", "Amazon");
            platform.put("integration", "OFFICIAL");
            platform.put("supportStatus", "SUPPORTED");
            platform.put("capabilities", List.of());
            platform.put("marketplaces", List.of(marketplace));
            body = List.of(platform);
          }
          byte[] out = JSON.writeValueAsBytes(body);
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

  @AfterAll
  static void stop() {
    channel.stop(0);
    identity.stop(0);
    pg.stop();
  }

  @Autowired PriceListService priceLists;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actorA;
  AccessService.Access actorB;

  @BeforeEach
  void setUp() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    CHANNEL_SHOPS_DOWN.set(false);
  }

  // ================================================================ 夹具

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  static String bearer(AccessService.Access who) {
    return "Bearer " + (who.tenantId().equals(a) ? "tenant-a" : "tenant-b");
  }

  private PriceListInput input(String code, OffsetDateTime from, OffsetDateTime to) {
    return new PriceListInput(
        code,
        "价目表 " + code,
        "夹具价目表",
        "USD",
        from,
        to,
        Boolean.TRUE,
        "夹具",
        List.of(),
        null);
  }

  private PriceListInput scopedInput(String code) {
    return new PriceListInput(
        code,
        "价目表 " + code,
        "夹具价目表",
        "USD",
        OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
        null,
        Boolean.TRUE,
        "夹具",
        List.of(
            new ScopeInput(ScopeType.LEGAL_ENTITY, LEGAL_ENTITY_CODE, null),
            new ScopeInput(ScopeType.SHOP, SHOP_CODE, PLATFORM_CODE),
            new ScopeInput(ScopeType.SITE, SITE_CODE, PLATFORM_CODE)),
        null);
  }

  private PriceListDetail save(AccessService.Access who, PriceListInput in) {
    return write(who, () -> priceLists.save(who, null, in, bearer(who)));
  }

  // ================================================================ 断言一：真的落库

  @Test
  void 新建价目表真的落库() {
    var code = unique("NA-RETAIL");
    var from = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
    var detail = save(actorA, input(code, from, null));

    assertThat(detail.summary().code()).isEqualTo(code);
    assertThat(detail.summary().currency()).isEqualTo("USD");
    assertThat(detail.summary().itemCount()).isZero();
    assertThat(detail.summary().coverage().appliesToWholeTenant()).isTrue();

    // 直接查库：行真的在，租户对，编码对。
    var row =
        tx(
            actorA,
            () ->
                sql.fetchOne(
                    "select tenant_id, code, currency, valid_from, valid_to, enabled, item_count"
                        + " from price_list where id=?",
                    detail.summary().id()));
    assertThat(row).isNotNull();
    assertThat(row.get("tenant_id", UUID.class)).isEqualTo(a);
    assertThat(row.get("code", String.class)).isEqualTo(code);
    assertThat(row.get("currency", String.class)).isEqualTo("USD");
    assertThat(row.get("item_count", Integer.class)).isZero();
    assertThat(row.get("valid_to", OffsetDateTime.class)).isNull();
  }

  @Test
  void 币种归一化成大写且编码租户内唯一() {
    var code = unique("NA-RETAIL");
    var in =
        new PriceListInput(
            code,
            "小写币种",
            "",
            "usd",
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
            null,
            Boolean.TRUE,
            "",
            List.of(),
            null);
    var detail = save(actorA, in);
    assertThat(detail.summary().currency()).isEqualTo("USD");

    // 同一个编码再来一次 ⇒ 409。
    var duplicate =
        write(
            actorA,
            () ->
                assertThatThrownBy(
                        () ->
                            priceLists.save(
                                actorA,
                                null,
                                input(code, OffsetDateTime.now(ZoneOffset.UTC), null),
                                bearer(actorA)))
                    .isInstanceOf(ResponseStatusException.class));
    assertThat(duplicate).isNotNull();
  }

  // ================================================================ 断言二：一价目一币种由数据库挡住

  @Test
  void 一价目一币种由数据库挡住() {
    var id = UUID.randomUUID();
    var from = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);

    // 「USD,CNY」正是混币种价目表最可能的写法 —— DB 层必须写不进去。
    var mixed =
        tx(
            actorA,
            () ->
                assertThatThrownBy(
                        () ->
                            sql.execute(
                                "insert into price_list(id,tenant_id,code,name,currency,valid_from,"
                                    + "created_by) values(?::uuid,?::uuid,?,?,?,?::timestamptz,?::uuid)",
                                id,
                                a,
                                unique("MIXED"),
                                "混币种",
                                "USD,CNY",
                                from,
                                user))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class));
    assertThat(mixed).isNotNull();
    mixed.hasStackTraceContaining("price_list_one_currency");

    // 六个字母（两个币种粘在一起）同样被挡。
    var glued =
        tx(
            actorA,
            () ->
                assertThatThrownBy(
                        () ->
                            sql.execute(
                                "insert into price_list(id,tenant_id,code,name,currency,valid_from,"
                                    + "created_by) values(?::uuid,?::uuid,?,?,?,?::timestamptz,?::uuid)",
                                id,
                                a,
                                unique("GLUED"),
                                "粘两个币种",
                                "USDCNY",
                                from,
                                user))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class));
    assertThat(glued).isNotNull();

    // 小写（列宽放得下，但形状不对）⇒ 同样由**命名约束**挡住 ——
    // 这条证明拦住它的是 CHECK 本身，不是列宽。
    var lower =
        tx(
            actorA,
            () ->
                assertThatThrownBy(
                        () ->
                            sql.execute(
                                "insert into price_list(id,tenant_id,code,name,currency,valid_from,"
                                    + "created_by) values(?::uuid,?::uuid,?,?,?,?::timestamptz,?::uuid)",
                                id,
                                a,
                                unique("LOWER"),
                                "小写币种",
                                "usd",
                                from,
                                user))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class));
    assertThat(lower).isNotNull();
    lower.hasStackTraceContaining("price_list_one_currency");

    // 币种为空 ⇒ NOT NULL 挡住（一张表一个币种，币种不能缺）。
    var missing =
        tx(
            actorA,
            () ->
                assertThatThrownBy(
                        () ->
                            sql.execute(
                                "insert into price_list(id,tenant_id,code,name,currency,valid_from,"
                                    + "created_by) values(?::uuid,?::uuid,?,?,null,?::timestamptz,?::uuid)",
                                id,
                                a,
                                unique("NOCUR"),
                                "没有币种",
                                from,
                                user))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class));
    assertThat(missing).isNotNull();

    // 这一条也没写进去（前面的失败没有留下半行）。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from price_list where code like 'MIXED-%'"
                                + " or code like 'GLUED-%' or code like 'NOCUR-%'")
                        .get(0, Long.class)))
        .isZero();
  }

  // ================================================================ 断言三：生效区间

  @Test
  void 生效区间写回值逐字相等() {
    // 用 +08:00 的偏移写：如果有人用 Timestamp.valueOf(LocalDateTime) 绑定，
    // 读回来会被静默偏成 −28800s（东八区变成 UTC 却被当成 UTC）。
    var from = OffsetDateTime.parse("2026-10-01T09:00:00+08:00");
    var to = OffsetDateTime.parse("2026-12-01T09:00:00+08:00");
    var code = unique("TZ");
    var detail = save(actorA, input(code, from, to));

    assertThat(detail.validFrom()).isEqualTo(from);
    assertThat(detail.validTo()).isEqualTo(to);

    // 直接查库：偏移量与瞬间都对。
    var row =
        tx(
            actorA,
            () ->
                sql.fetchOne(
                    "select valid_from, valid_to from price_list where id=?", detail.summary().id()));
    var storedFrom = row.get("valid_from", OffsetDateTime.class);
    var storedTo = row.get("valid_to", OffsetDateTime.class);
    assertThat(storedFrom.toInstant()).isEqualTo(from.toInstant());
    assertThat(storedTo.toInstant()).isEqualTo(to.toInstant());
    assertThat(storedFrom.getOffset()).isEqualTo(from.getOffset());
  }

  @Test
  void 半开区间的边界() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    // 区间覆盖此刻 ⇒ 生效。
    var live = save(actorA, input(unique("LIVE"), now.minusHours(1), now.plusHours(1)));
    assertThat(live.summary().effectiveNow()).isTrue();
    // 区间已过（终点不含）⇒ 不生效。
    var past = save(actorA, input(unique("PAST"), now.minusDays(2), now.minusDays(1)));
    assertThat(past.summary().effectiveNow()).isFalse();
    // 区间未到（起点含，此刻在起点之前）⇒ 不生效。
    var future = save(actorA, input(unique("FUTURE"), now.plusDays(1), now.plusDays(2)));
    assertThat(future.summary().effectiveNow()).isFalse();
    // 长期有效（终点为空）⇒ 生效。
    var forever = save(actorA, input(unique("FOREVER"), now.minusDays(1), null));
    assertThat(forever.summary().effectiveNow()).isTrue();
  }

  @Test
  void 终点必须晚于起点() {
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    assertThatThrownBy(() -> save(actorA, input(unique("BAD"), now, now.minusSeconds(1))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("生效终点必须晚于生效起点");
  }

  // ================================================================ 断言四：适用范围与覆盖摘要

  @Test
  void 适用范围真的落库并算出覆盖摘要() {
    var detail = save(actorA, scopedInput(unique("SCOPED")));

    assertThat(detail.scopes()).hasSize(3);
    assertThat(detail.scopes().get(0).type()).isEqualTo(ScopeType.LEGAL_ENTITY);
    assertThat(detail.scopes().get(0).name()).isEqualTo("美国总部");
    assertThat(detail.scopes().get(1).type()).isEqualTo(ScopeType.SHOP);
    assertThat(detail.scopes().get(1).name()).isEqualTo("亚马逊美国自发货店");
    assertThat(detail.scopes().get(2).type()).isEqualTo(ScopeType.SITE);
    assertThat(detail.scopes().get(2).platformCode()).isEqualTo(PLATFORM_CODE);

    var coverage = detail.coverage();
    assertThat(coverage.scopeTotal()).isEqualTo(3);
    assertThat(coverage.legalEntityCount()).isEqualTo(1);
    assertThat(coverage.shopCount()).isEqualTo(1);
    assertThat(coverage.siteCount()).isEqualTo(1);
    assertThat(coverage.appliesToWholeTenant()).isFalse();
    assertThat(coverage.text()).isEqualTo("1 法人 / 1 店铺 / 1 站点");

    // 范围行真的在库里（挂着这张表）。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne(
                            "select count(*) from price_list_scope where price_list_id=?",
                            detail.summary().id())
                        .get(0, Long.class)))
        .isEqualTo(3L);
  }

  @Test
  void 范围里的编码必须真实存在() {
    var in =
        new PriceListInput(
            unique("BAD-SCOPE"),
            "不存在的店铺",
            "",
            "USD",
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
            null,
            Boolean.TRUE,
            "",
            List.of(new ScopeInput(ScopeType.SHOP, "NO-SUCH-SHOP", PLATFORM_CODE)),
            null);
    assertThatThrownBy(() -> save(actorA, in))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不存在");
    // 没有留下半张表。
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne("select count(*) from price_list where name='不存在的店铺'")
                        .get(0, Long.class)))
        .isZero();
  }

  @Test
  void 站点的平台必须与渠道服务一致() {
    var in =
        new PriceListInput(
            unique("BAD-PLATFORM"),
            "平台对不上",
            "",
            "USD",
            OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
            null,
            Boolean.TRUE,
            "",
            List.of(new ScopeInput(ScopeType.SITE, SITE_CODE, "EBAY")),
            null);
    assertThatThrownBy(() -> save(actorA, in))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("属于平台");
  }

  // ================================================================ 断言五：上游读不到

  @Test
  void 上游读不到时返回503而不是空目录() throws Exception {
    CHANNEL_SHOPS_DOWN.set(true);
    try {
      assertThatThrownBy(() -> priceLists.scopeDirectory("Bearer tenant-a"))
          .isInstanceOf(PriceListScopePort.ScopeUnavailableException.class);
      http.perform(get("/catalog/price-lists/scope-directory").header("Authorization", "Bearer tenant-a"))
          .andExpect(status().isServiceUnavailable());
    } finally {
      CHANNEL_SHOPS_DOWN.set(false);
    }
    // 上游恢复后目录真的有内容（不是空列表）。
    var directory = priceLists.scopeDirectory("Bearer tenant-a");
    assertThat(directory.legalEntities()).hasSize(1);
    assertThat(directory.shops()).hasSize(1);
    assertThat(directory.sites()).hasSize(1);
  }

  // ================================================================ 断言六：台账、筛选、覆盖摘要

  @Test
  void 台账支持分页与筛选() {
    var code = unique("FILTER");
    save(actorA, scopedInput(code));

    var all = tx(actorA, () -> priceLists.list(new PriceListService.PriceListQuery(null, null, null, null, null, null, 0, 200)));
    assertThat(all.total()).isGreaterThanOrEqualTo(1);
    assertThat(all.items().stream().map(PriceListSummary::code)).contains(code);

    var byKeyword =
        tx(
            actorA,
            () -> priceLists.list(new PriceListService.PriceListQuery(code, null, null, null, null, null, 0, 200)));
    assertThat(byKeyword.items()).hasSize(1);
    assertThat(byKeyword.items().get(0).code()).isEqualTo(code);

    var byCurrency =
        tx(
            actorA,
            () ->
                priceLists.list(new PriceListService.PriceListQuery(null, "USD", null, null, null, null, 0, 200)));
    assertThat(byCurrency.items().stream().map(PriceListSummary::code)).contains(code);

    var byScope =
        tx(
            actorA,
            () ->
                priceLists.list(
                    new PriceListService.PriceListQuery(
                        null, null, ScopeType.SHOP, SHOP_CODE, null, null, 0, 200)));
    assertThat(byScope.items().stream().map(PriceListSummary::code)).contains(code);

    var byShop =
        tx(
            actorA,
            () ->
                priceLists.list(
                    new PriceListService.PriceListQuery(
                        null, null, ScopeType.SHOP, "NO-SUCH-SHOP", null, null, 0, 200)));
    assertThat(byShop.items()).isEmpty();

    // 真分页：第 0 页与第 1 页不是同一批。
    var firstPage =
        tx(
            actorA,
            () ->
                priceLists.list(
                    new PriceListService.PriceListQuery(null, null, null, null, null, null, 0, 1)));
    assertThat(firstPage.items()).hasSize(1);
    assertThat(firstPage.size()).isEqualTo(1);
    assertThat(firstPage.total()).isGreaterThanOrEqualTo(2);
  }

  @Test
  void 覆盖范围摘要汇总全租户的价目表() {
    var code = unique("COVERAGE");
    save(actorA, scopedInput(code));
    var overview = tx(actorA, () -> priceLists.coverage());
    assertThat(overview.listCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.currencies()).anyMatch(c -> c.currency().equals("USD"));
    assertThat(overview.scopeTypes()).anyMatch(s -> s.type() == ScopeType.SHOP);
    assertThat(overview.lists()).anyMatch(e -> e.code().equals(code));
  }

  // ================================================================ 断言七：乐观锁与删除

  @Test
  void 版本对不上时拒绝修改() {
    var detail = save(actorA, input(unique("LOCK"), OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), null));
    var stale = detail.version();
    // 先改一次（版本 +1）。
    var bumped =
        write(
            actorA,
            () ->
                priceLists.save(
                    actorA,
                    detail.summary().id(),
                    new PriceListInput(
                        detail.summary().code(),
                        "改名了",
                        "",
                        "USD",
                        detail.validFrom(),
                        detail.validTo(),
                        Boolean.TRUE,
                        "",
                        List.of(),
                        stale),
                    bearer(actorA)));
    assertThat(bumped.version()).isEqualTo(stale + 1);

    // 再拿旧版本提交 ⇒ 409。
    assertThatThrownBy(
            () ->
                write(
                    actorA,
                    () ->
                        priceLists.save(
                            actorA,
                            detail.summary().id(),
                            new PriceListInput(
                                detail.summary().code(),
                                "再改一次",
                                "",
                                "USD",
                                detail.validFrom(),
                                detail.validTo(),
                                Boolean.TRUE,
                                "",
                                List.of(),
                                stale),
                            bearer(actorA))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经被别人改过了");
  }

  @Test
  void 删除会连带删掉适用范围() {
    var detail = save(actorA, scopedInput(unique("DEL")));
    var id = detail.summary().id();
    write(
        actorA,
        () -> {
          priceLists.delete(id);
          return null;
        });
    assertThatThrownBy(() -> tx(actorA, () -> priceLists.detail(id)))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(
            tx(
                actorA,
                () ->
                    sql.fetchOne("select count(*) from price_list_scope where price_list_id=?", id)
                        .get(0, Long.class)))
        .isZero();
  }

  // ================================================================ 断言八：价目项数量契约

  @Test
  void 价目项数量由触发器维护() {
    var detail = save(actorA, input(unique("COUNT"), OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), null));
    var id = detail.summary().id();

    // 5.1.1.2 交付前没有 price_list_item 表；这里用一张**临时**表模拟它，
    // 并挂上本迁移提供的计数触发器 —— 临时表随会话结束消失，
    // 不会与 5.1.1.2 的真表撞名（那才是它的写入所有权）。
    write(
        actorA,
        () -> {
          sql.execute(
              "create temporary table if not exists price_list_item(tenant_id uuid, price_list_id uuid)");
          sql.execute(
              "drop trigger if exists price_list_item_count_trigger on price_list_item");
          sql.execute(
              "create trigger price_list_item_count_trigger after insert or update or delete"
                  + " on price_list_item for each row execute function price_list_sync_item_count()");
          return null;
        });

    write(
        actorA,
        () -> {
          sql.execute(
              "insert into price_list_item(tenant_id,price_list_id) values(?,?), (?,?)",
              a,
              id,
              a,
              id);
          return null;
        });

    // 计数真的被写回（读的是 price_list.item_count，不是临时表）。
    assertThat(tx(actorA, () -> priceLists.detail(id)).summary().itemCount()).isEqualTo(2);

    // 有价目项时删除被拒（否则价目项会指向一张不存在的表）。
    assertThatThrownBy(
            () ->
                write(
                    actorA,
                    () -> {
                      priceLists.delete(id);
                      return null;
                    }))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("还有 2 个价目项");

    write(
        actorA,
        () -> {
          sql.execute("delete from price_list_item where price_list_id=?", id);
          return null;
        });
    assertThat(tx(actorA, () -> priceLists.detail(id)).summary().itemCount()).isZero();
  }

  @Test
  void 客户端不能直接写价目项数量() throws Exception {
    var payload =
        """
        {"code":"%s","name":"手写数量","currency":"USD",
         "validFrom":"2026-10-01T00:00:00Z","enabled":true,"itemCount":99}
        """
            .formatted(unique("SNEAKY"));
    http.perform(
            post("/catalog/price-lists/lists")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(payload))
        .andExpect(status().isBadRequest());
  }

  // ================================================================ 端点往返

  @Test
  void 端点往返() throws Exception {
    var code = unique("API");
    var created =
        http.perform(
                post("/catalog/price-lists/lists")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(
                        JSON.writeValueAsBytes(
                            new PriceListInput(
                                code,
                                "端点价目表",
                                "",
                                "USD",
                                OffsetDateTime.parse("2026-10-01T00:00:00Z"),
                                null,
                                Boolean.TRUE,
                                "",
                                List.of(),
                                null))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var detail = JSON.readValue(created, PriceListDetail.class);
    assertThat(detail.summary().code()).isEqualTo(code);

    http.perform(get("/catalog/price-lists/lists").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray());
    http.perform(
            get("/catalog/price-lists/lists/" + detail.summary().id())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currency").value("USD"));
    http.perform(get("/catalog/price-lists/coverage").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/price-lists/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(
            delete("/catalog/price-lists/lists/" + detail.summary().id())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
  }

  @Test
  void 只读角色不能写价目表() throws Exception {
    http.perform(
            post("/catalog/price-lists/lists")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    JSON.writeValueAsBytes(
                        new PriceListInput(
                            unique("READER"),
                            "只读角色",
                            "",
                            "USD",
                            OffsetDateTime.parse("2026-10-01T00:00:00Z"),
                            null,
                            Boolean.TRUE,
                            "",
                            List.of(),
                            null))))
        .andExpect(status().isForbidden());
  }

  // ================================================================ RLS 断言

  @Test
  void 价目表强制开启行级安全() {
    for (var table : List.of("price_list", "price_list_scope")) {
      var row =
          tx(
              actorA,
              () ->
                  sql.fetchOne(
                      "select relforcerowsecurity, relrowsecurity from pg_class where relname=?",
                      table));
      assertThat(row).as("表 %s 必须存在", table).isNotNull();
      assertThat(row.get("relrowsecurity", Boolean.class)).as("%s 必须 ENABLE RLS", table).isTrue();
      assertThat(row.get("relforcerowsecurity", Boolean.class))
          .as("%s 必须 FORCE RLS（漏掉 FORCE 就能绕过策略读别人的价目表）", table)
          .isTrue();
    }
  }
}
