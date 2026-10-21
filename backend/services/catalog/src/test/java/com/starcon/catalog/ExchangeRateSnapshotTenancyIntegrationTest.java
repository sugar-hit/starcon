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
 * 5.2.2.2 汇率快照：**租户隔离三断言**（AGENTS.md 强制要求，缺一不可）。
 *
 * <p>三条断言：
 *
 * <ol>
 *   <li><b>两租户互不可见</b>：A 的快照 B 读不到，B 的快照 A 读不到，台账不混，直连 SQL 也读不到。
 *   <li><b>伪造 tenant header 无效</b>：{@code X-Tenant-Id} 不决定租户；
 *       租户只来自已认证成员关系（identity 的 {@code /internal/access}）。
 *   <li><b>连接池归还后不串租户</b>：连接池固定为 1 条连接，反复交替两个租户各 5 轮，
 *       每一轮两个集合都必须不相交。
 * </ol>
 *
 * <p><b>为什么这个模块必须单独测</b>：快照决定每一次换算与利润。A 读到 B 的汇率快照，
 * 会让 A 的历史利润整体算错 —— 而这种错误在界面上完全看不出来。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明「归还之后不会串租户」。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExchangeRateSnapshotTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final LocalDate DAY = LocalDate.now().minusDays(3);

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
    tx(actor, () -> sql.execute("delete from exchange_rate"));
    tx(actorB, () -> sql.execute("delete from exchange_rate"));
    tx(actor, () -> sql.execute("delete from exchange_rate_rounding_rule"));
    tx(actorB, () -> sql.execute("delete from exchange_rate_rounding_rule"));
    source(actor, "MANUAL");
    source(actorB, "MANUAL");
    write(actor, () -> snapshots.initializeRoundingRules(actor));
    write(actorB, () -> snapshots.initializeRoundingRules(actorB));
  }

  // ================================================================ 夹具

  void source(AccessService.Access who, String code) {
    tx(
        who,
        () -> {
          sql.execute(
              "insert into exchange_rate_source(id,tenant_id,code,name,kind,enabled,priority,endpoint,note,created_by)"
                  + " values(?::uuid,?::uuid,?,?,'MANUAL',true,100,null,'',?::uuid)"
                  + " on conflict (tenant_id,code) do nothing",
              UUID.randomUUID(),
              who.tenantId(),
              code,
              "手工录入",
              who.userId());
          return null;
        });
  }

  /** 在某个租户下录一条汇率并冻结一条快照，返回快照 id。 */
  UUID freezeOne(AccessService.Access who, String amount, String documentNo) {
    var rate =
        write(
                who,
                () ->
                    rates.create(
                        who,
                        new RateInput(
                            "USD",
                            "CNY",
                            new BigDecimal("7.1234"),
                            DAY,
                            "MANUAL",
                            "ref-" + UUID.randomUUID(),
                            "")))
            .id();
    var documentId = UUID.randomUUID();
    return write(
            who,
            () ->
                snapshots.create(
                    who,
                    new SnapshotInput(
                        rate,
                        null,
                        null,
                        null,
                        "USD",
                        new BigDecimal(amount),
                        "PRICE_LIST",
                        documentId,
                        documentNo,
                        "L1",
                        null,
                        "")))
        .id();
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

  // ================================================================ 三断言

  /**
   * 租户隔离三断言。
   *
   * <p>只测主体等于没测：快照决定每一次换算与利润，A 读到 B 的快照会让 A 的历史利润整体算错。
   */
  @Test
  void 租户隔离三断言() throws Exception {
    var mySnapshot = freezeOne(actor, "100.00", "PL-A");
    var theirSnapshot = freezeOne(actorB, "500.00", "PL-B");

    // 两边都必须真的有数据 —— 否则下面的断言就是空的。
    assertThat(tx(actor, () -> snapshots.snapshot(mySnapshot)).targetAmount())
        .isEqualByComparingTo("712.34");
    assertThat(tx(actorB, () -> snapshots.snapshot(theirSnapshot)).targetAmount())
        .isEqualByComparingTo("3561.70");

    // ---------------------------------------------------- 1) 两租户互不可见
    assertThatThrownBy(() -> tx(actor, () -> snapshots.snapshot(theirSnapshot)))
        .as("A 读不到 B 的快照")
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> snapshots.snapshot(mySnapshot)))
        .as("B 读不到 A 的快照")
        .isInstanceOf(ResponseStatusException.class);

    // 台账不混：两个租户的台账里只有自己的行。
    var myRows = tx(actor, () -> snapshots.list(new SnapshotListQuery(null, null, null, null, null, null, null, 0, 200)));
    var theirRows = tx(actorB, () -> snapshots.list(new SnapshotListQuery(null, null, null, null, null, null, null, 0, 200)));
    assertThat(myRows.items()).isNotEmpty();
    assertThat(theirRows.items()).isNotEmpty();
    assertThat(myRows.items().stream().map(SnapshotView::id)).doesNotContain(theirSnapshot);
    assertThat(theirRows.items().stream().map(SnapshotView::id)).doesNotContain(mySnapshot);

    // 直连 SQL 也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    assertThat(rows(actor, "select * from exchange_rate_snapshot where tenant_id=?", b)).isEmpty();
    assertThat(rows(actorB, "select * from exchange_rate_snapshot where tenant_id=?", a)).isEmpty();

    // 舍入规则同样互不可见。
    assertThat(tx(actor, () -> snapshots.roundingRules())).isNotEmpty();
    var theirRule =
        write(actorB, () -> snapshots.createRoundingRule(
            actorB,
            new RoundingRuleInput("B_ONLY", "B 租户独有规则", "EUR", "HALF_UP", 3, true, 50, "")));
    assertThat(tx(actor, () -> snapshots.roundingRules()).stream().map(RoundingRuleView::code))
        .doesNotContain("B_ONLY");
    assertThat(tx(actorB, () -> snapshots.roundingRules()).stream().map(RoundingRuleView::code))
        .contains("B_ONLY");
    assertThat(theirRule.code()).isEqualTo("B_ONLY");

    // ---------------------------------------------------- 2) 伪造 tenant header 无效
    // 伪造头下的读：A 的 Authorization + B 的 X-Tenant-Id ⇒ 仍然只看得见 A 的数据。
    http.perform(
            get("/catalog/exchange-rate-snapshots/" + theirSnapshot)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/exchange-rate-snapshots/" + mySnapshot)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // 伪造头下的写入仍落在**调用者自己**名下 —— 断言行确实落在 A 名下，不只断言 B 看不见。
    var rateId =
        tx(actor, () -> rates.create(
                actor,
                new RateInput("EUR", "CNY", new BigDecimal("7.8"), DAY, "MANUAL", "伪造头下的汇率", ""))
            .id());
    http.perform(
            post("/catalog/exchange-rate-snapshots")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .contentType("application/json")
                .content(
                    JSON.writeValueAsString(
                        new SnapshotInput(
                            rateId,
                            null,
                            null,
                            null,
                            "EUR",
                            new BigDecimal("100"),
                            "PRICE_LIST",
                            UUID.randomUUID(),
                            "PL-FORGED",
                            "L1",
                            null,
                            ""))))
        .andExpect(status().isOk());
    var forged = rows(actor, "select * from exchange_rate_snapshot where document_no='PL-FORGED'");
    assertThat(forged).hasSize(1);
    assertThat(forged.get(0).get("tenant_id", UUID.class)).isEqualTo(a);

    // ---------------------------------------------------- 3) 连接池归还后不串租户
    for (int i = 0; i < 5; i++) {
      // 比较的是**行**（快照 id）而不是币种：两个租户都用 USD→CNY 是正常的，
      // 不相交的是「谁看得见哪些行」。
      var mySet =
          tx(
                  actor,
                  () ->
                      snapshots.list(new SnapshotListQuery(null, null, null, null, null, null, null, 0, 200))
                          .items()
                          .stream()
                          .map(SnapshotView::id)
                          .collect(java.util.stream.Collectors.toSet()));
      var theirSet =
          tx(
                  actorB,
                  () ->
                      snapshots.list(new SnapshotListQuery(null, null, null, null, null, null, null, 0, 200))
                          .items()
                          .stream()
                          .map(SnapshotView::id)
                          .collect(java.util.stream.Collectors.toSet()));
      assertThat(mySet).isNotEmpty();
      assertThat(theirSet).isNotEmpty();
      assertThat(java.util.Collections.disjoint(mySet, theirSet)).isTrue();
      // 明细也不串：A 读 B 的快照必须失败，而不是「读到了 B 的快照」。
      assertThatThrownBy(() -> tx(actor, () -> snapshots.snapshot(theirSnapshot)))
          .as("A 读 B 的快照必须失败而不是串过去")
          .isInstanceOf(ResponseStatusException.class);
    }
  }
}
