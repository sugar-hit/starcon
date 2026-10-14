package com.starcon.channel;

import static com.starcon.channel.WarehouseConnectionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 10.4.2.1 海外仓连接。
 *
 * <p>本测试类钉两件事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、 伪造 tenant header 无效、连接池归还后不串租户
 *   <li><b>蓝图加粗约束</b>：外部仓库存是观测值不是自有账本，分开存储且<b>不能覆盖</b>。 这一条不靠服务层自觉，而是同时用三种方式证明：
 *       <ul>
 *         <li>同步后观测表有数据（功能真的跑了，不是空转）
 *         <li>观测行<b>不可 UPDATE / DELETE</b>——绕过服务层直接写 SQL 也被拒（DB 触发器）
 *         <li>「当前值」是 DISTINCT ON 查出来的，不是某一行被覆盖的结果
 *       </ul>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class WarehouseConnectionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 复用 identity 已注册的 channel 权限码：本模块不新增权限码，identity 服务零改动。 */
  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of(
                              "channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

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
        s.execute(
            "do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
                + " then create role starcon_app nologin; end if; end $$");
        s.execute(
            "do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
                + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
      actors.put("Bearer tenant-a", actor(user, a, grants));
      actors.put("Bearer tenant-b", actor(user, b, grants));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired WarehouseConnectionService warehouses;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  @BeforeEach
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  UUID connection(AccessService.Access who) {
    return as(
        who,
        "channel.connection.create",
        () ->
            warehouses.createConnection(
                new WarehouseConnectionInput(
                    providerId(who),
                    "vault://overseas-warehouse/" + UUID.randomUUID(),
                    "acct-" + UUID.randomUUID().toString().substring(0, 8),
                    "US East Hub",
                    "reported-feed")));
  }

  UUID providerId(AccessService.Access who) {
    return as(
        who,
        "channel.connection.read",
        () ->
            warehouses.providers().stream()
                .filter(p -> p.code().equals("GENERIC_3PL"))
                .findFirst()
                .orElseThrow()
                .id());
  }

  UUID account(AccessService.Access who, UUID connectionId) {
    return as(
        who,
        "channel.connection.create",
        () ->
            warehouses.createAccount(
                connectionId,
                new WarehouseAccountInput(
                    "WH-" + UUID.randomUUID().toString().substring(0, 8),
                    "US East Hub",
                    "US",
                    "America/New_York",
                    WarehouseAccountKind.OVERSEAS)));
  }

  void inbound(
      AccessService.Access who, UUID accountId, String sku, String qty, String reportedAt) {
    as(
        who,
        "channel.sync.create",
        () ->
            warehouses.recordInboundStockLine(
                accountId,
                sku,
                new BigDecimal(qty),
                "PCS",
                "REPORTED_FEED",
                java.time.OffsetDateTime.parse(reportedAt)));
  }

  // ==================================================================
  // 蓝图加粗约束：外部仓库存是观测值，不是自有账本，不能覆盖
  // ==================================================================

  @Test
  @DisplayName("10.4.2.1 观测值是快照：同步只 INSERT，改不动、删不掉，最新值是查出来的")
  void observationsAreAppendOnlySnapshots() {
    UUID conn = connection(actorA);
    UUID acct = account(actorA, conn);
    inbound(actorA, acct, "SKU-001", "10", "2026-10-01T10:00:00Z");
    inbound(actorA, acct, "SKU-002", "5.5", "2026-10-01T10:00:00Z");

    UUID run = as(actorA, "channel.sync.trigger", () -> warehouses.triggerSync(acct, "MANUAL"));

    // 1) 同步真的执行并写了观测数据，不是空转
    List<ExternalStockObservationView> observed =
        as(actorA, "channel.sync.read", () -> warehouses.observations(acct, 100));
    assertThat(observed).hasSize(2);
    assertThat(observed)
        .allSatisfy(
            o -> {
              assertThat(o.source()).isEqualTo("REPORTED_FEED");
              assertThat(o.observedAt()).isNotNull();
            });
    // 数量 HALF_UP 到 6 位，禁浮点
    assertThat(observed)
        .filteredOn(o -> o.externalSku().equals("SKU-002"))
        .singleElement()
        .satisfies(o -> assertThat(o.quantity()).isEqualByComparingTo("5.500000"));

    // 2) 同步记录落库且计数正确
    List<WarehouseSyncRunView> runs =
        as(actorA, "channel.sync.read", () -> warehouses.syncRuns(conn, 10));
    assertThat(runs)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.id()).isEqualTo(run);
              assertThat(r.status()).isEqualTo("SUCCEEDED");
              assertThat(r.fetchedCount()).isEqualTo(2);
              assertThat(r.observedCount()).isEqualTo(2);
            });

    // 3) 🔴 不可覆盖：绕过服务层直接写 SQL 做 UPDATE，DB 触发器必须拒绝。
    //    注意这里用的是**应用账号 channel_app**（不是迁移账号），所以走的是真实权限面。
    as(
        actorA,
        "channel.sync.read",
        () -> {
          Throwable e =
              catchThrowable(
                  () -> sqlUpdate("update external_stock_observation set quantity=999999"));
          // 权限面已经先拒（只有 INSERT/SELECT），所以这里看到的是权限错误而不是触发器错误
          assertThat(rootMessage(e)).containsAnyOf("permission denied", "no privileges", "观测值不是账本");
          return null;
        });

    // 3b) 即使拿到迁移账号（等价于绕过服务层直连数据库），触发器照样拒绝
    assertThat(
            catchThrowable(
                () -> adminUpdate("update external_stock_observation set quantity=999999")))
        .isNotNull();
    assertThat(
            catchThrowable(
                () ->
                    adminUpdate(
                        "delete from external_stock_observation where external_sku='SKU-001'")))
        .isNotNull();

    // 5) 观测值一个都没变——证明「覆盖」这件事在数据上不可能发生
    List<ExternalStockObservationView> after =
        as(actorA, "channel.sync.read", () -> warehouses.observations(acct, 100));
    assertThat(after).hasSameSizeAs(observed);
    assertThat(after)
        .filteredOn(o -> o.externalSku().equals("SKU-001"))
        .singleElement()
        .satisfies(o -> assertThat(o.quantity()).isEqualByComparingTo("10.000000"));

    // 6) 「最新观测」是 DISTINCT ON 查出来的：再观测一次得到新快照，旧快照仍在
    inbound(actorA, acct, "SKU-001", "7", "2026-10-02T10:00:00Z");
    as(actorA, "channel.sync.trigger", () -> warehouses.triggerSync(acct, "MANUAL"));

    // 4 行而不是 3 行：一次同步观测的是该账户**此刻的全部**报送行，不是「变化的那几行」。
    // 第二批把 SKU-001 改成 7 之后重新观测，SKU-002（仍是 5.5）也被盖上同一个观测时点——
    // 这正是观测快照该有的样子：每个时点留下的是一张完整画面，而不是增量补丁。
    assertThat(as(actorA, "channel.sync.read", () -> warehouses.observations(acct, 100)))
        .hasSize(4);
    List<ExternalStockLatestView> latest =
        as(actorA, "channel.sync.read", () -> warehouses.latestObservations(acct));
    assertThat(latest)
        .filteredOn(o -> o.externalSku().equals("SKU-001"))
        .singleElement()
        .satisfies(
            o -> {
              assertThat(o.quantity()).isEqualByComparingTo("7.000000");
              assertThat(o.observedAt()).isEqualTo(java.time.Instant.parse("2026-10-02T10:00:00Z"));
            });
  }

  @Test
  @DisplayName("10.4.2.1 观测表对应用账号只有 INSERT/SELECT 权限（权限层也钉住不覆盖）")
  void observationTableGrantsAreAppendOnly() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only")) {
      for (String sql :
          List.of(
              "update external_stock_observation set quantity=1",
              "delete from external_stock_observation")) {
        Throwable e = catchThrowable(() -> c.createStatement().execute(sql));
        assertThat(rootMessage(e)).contains("permission denied");
      }
    }
  }

  @Test
  @DisplayName("10.4.2.1 每条观测都必须可追溯到来源与时点（NOT NULL 是硬约束）")
  void observationsAlwaysCarryProvenance() {
    UUID conn = connection(actorA);
    UUID acct = account(actorA, conn);
    inbound(actorA, acct, "SKU-001", "3", "2026-10-03T08:00:00Z");
    as(actorA, "channel.sync.trigger", () -> warehouses.triggerSync(acct, "MANUAL"));

    as(
        actorA,
        "channel.sync.read",
        () -> {
          Throwable e =
              catchThrowable(
                  () ->
                      sqlUpdate(
                          "insert into external_stock_observation(id,tenant_id,connection_id,"
                              + " account_id,external_sku,quantity,source,observed_at)"
                              + " values (gen_random_uuid(),"
                              + " current_setting('app.current_tenant',true)::uuid,'"
                              + conn
                              + "','"
                              + acct
                              + "','SKU-NOSOURCE',1,'',now()::timestamptz)"));
          // source 为空串会被 CHECK 拒（追不回来的数字对不了账）
          assertThat(rootMessage(e)).isNotBlank();
          return null;
        });
  }

  @Test
  @DisplayName("10.4.2.1 服务商改正过的数必须观测到最新值（不得被同批旧行静默吞掉）")
  void correctedInboundValueWins() {
    UUID conn = connection(actorA);
    UUID acct = account(actorA, conn);
    // 同一 SKU 先后两批报送：10 → 7。拉取必须取到**最新**那条。
    inbound(actorA, acct, "SKU-CORRECT", "10", "2026-10-01T10:00:00Z");
    inbound(actorA, acct, "SKU-CORRECT", "7", "2026-10-02T10:00:00Z");

    as(actorA, "channel.sync.trigger", () -> warehouses.triggerSync(acct, "MANUAL"));

    assertThat(as(actorA, "channel.sync.read", () -> warehouses.latestObservations(acct)))
        .singleElement()
        .satisfies(o -> assertThat(o.quantity()).isEqualByComparingTo("7.000000"));
  }

  @Test
  @DisplayName("10.4.2.1 同一批报送重复触发不产生第二条运行（批次级幂等）")
  void sameBatchIsIdempotent() {
    UUID conn = connection(actorA);
    UUID acct = account(actorA, conn);
    inbound(actorA, acct, "SKU-IDEM", "5", "2026-10-01T10:00:00Z");

    as(actorA, "channel.sync.trigger", () -> warehouses.triggerSync(acct, "MANUAL"));
    // 同一批再触发 → 明确拒绝，而不是并排再插一条运行
    assertThatThrownBy(
            () -> as(actorA, "channel.sync.trigger", () -> warehouses.triggerSync(acct, "MANUAL")))
        .hasMessageContaining("已同步过");

    // 新一批报送 → 可以再跑（这正是「按批次幂等」而不是「一个账户只能同步一次」）
    inbound(actorA, acct, "SKU-IDEM", "6", "2026-10-03T10:00:00Z");
    as(actorA, "channel.sync.trigger", () -> warehouses.triggerSync(acct, "MANUAL"));
    assertThat(as(actorA, "channel.sync.read", () -> warehouses.syncRuns(conn, 10))).hasSize(2);
  }

  // ==================================================================
  // 多租户三断言
  // ==================================================================

  @Test
  @DisplayName("10.4.2.1 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    UUID connA = connection(actorA);
    UUID acctA = account(actorA, connA);
    inbound(actorA, acctA, "SKU-A", "42", "2026-10-01T10:00:00Z");
    as(actorA, "channel.sync.trigger", () -> warehouses.triggerSync(acctA, "MANUAL"));

    // 租户 B 看不到 A 的连接、仓账户、观测
    assertThat(as(actorB, "channel.connection.read", () -> warehouses.connections()))
        .noneMatch(c -> c.id().equals(connA));
    assertThat(as(actorB, "channel.connection.read", () -> warehouses.accounts(null)))
        .noneMatch(x -> x.connectionId().equals(connA));
    assertThatThrownBy(
            () -> as(actorB, "channel.connection.read", () -> warehouses.connection(connA)))
        .hasMessageContaining("连接不存在");

    // B 自己建一套，两边数据各自独立：A 只看得到自己的连接
    UUID connB = connection(actorB);
    assertThat(as(actorB, "channel.connection.read", () -> warehouses.connections()))
        .extracting(WarehouseConnectionView::id)
        .containsExactly(connB);
    assertThat(as(actorA, "channel.connection.read", () -> warehouses.connections()))
        .extracting(WarehouseConnectionView::id)
        .contains(connA)
        .doesNotContain(connB);
  }

  @Test
  @DisplayName("10.4.2.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID connA = connection(actorA);

    // 用 A 的合法会话，但请求头里塞 B 的租户标识 —— 结果仍必须是 A 的视图
    UUID connB = connection(actorB);
    http.perform(
            get("/channel/warehouse-providers/connections")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id", org.hamcrest.Matchers.hasItem(connA.toString())))
        .andExpect(
            jsonPath(
                "$[*].id",
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(connB.toString()))));

    // 无会话一律 401：租户不可能由客户端头决定
    http.perform(
            get("/channel/warehouse-providers/connections").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("10.4.2.1 断言三：连接池归还后不串租户")
  void pooledConnectionsDoNotBleedTenants() {
    UUID connA = connection(actorA);
    UUID connB = connection(actorB);
    UUID acctA = account(actorA, connA);
    inbound(actorA, acctA, "SKU-A", "11", "2026-10-01T10:00:00Z");
    as(actorA, "channel.sync.trigger", () -> warehouses.triggerSync(acctA, "MANUAL"));

    // 同一个池上交替跑 A/B/A/B：A 永远只看到自己的行
    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "channel.sync.read", () -> warehouses.observations(acctA, 50)))
          .allSatisfy(o -> assertThat(o.accountId()).isEqualTo(acctA));
      assertThat(as(actorB, "channel.connection.read", () -> warehouses.connections()))
          .noneMatch(c -> c.id().equals(connA));
      assertThat(as(actorB, "channel.connection.read", () -> warehouses.connections()))
          .extracting(WarehouseConnectionView::id)
          .contains(connB);
    }
  }

  @Test
  @DisplayName("10.4.2.1 端点可真实操作：建连接 → 建仓账户 → 登记报送 → 触发同步 → 读观测")
  void endpointsDriveTheWholeFlow() throws Exception {
    http.perform(get("/channel/warehouse-providers").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[?(@.code=='GENERIC_3PL')].code")
                .value(org.hamcrest.Matchers.hasItem("GENERIC_3PL")));

    UUID conn = connection(actorA);
    UUID acct = account(actorA, conn);

    http.perform(
            post("/channel/warehouse-providers/accounts/{id}/inbound-stock-lines", acct)
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"externalSku\":\"SKU-E2E\",\"quantity\":\"12.25\",\"unit\":\"PCS\","
                        + "\"source\":\"REPORTED_FEED\",\"reportedAt\":\"2026-10-04T12:00:00Z\"}"))
        .andExpect(status().isOk());

    http.perform(
            post("/channel/warehouse-providers/accounts/{id}/sync", acct)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    http.perform(
            get("/channel/warehouse-providers/accounts/{id}/observations/latest", acct)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].externalSku").value("SKU-E2E"))
        .andExpect(jsonPath("$[0].quantity").value(org.hamcrest.Matchers.closeTo(12.25, 0.0001)))
        .andExpect(jsonPath("$[0].source").value("REPORTED_FEED"));

    http.perform(
            get("/channel/warehouse-providers/sync-runs")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("SUCCEEDED"));

    // 伪造会话拿不到数据
    http.perform(get("/channel/warehouse-providers/accounts/{id}/observations", acct))
        .andExpect(status().isUnauthorized());
  }

  // ---- 小工具 ----

  /** 以应用账号（RLS + 权限面生效）执行 DML，验证 DB 层的真实拒绝行为。 */
  private void sqlUpdate(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      // 先绑定租户上下文，否则 RLS 会先把它挡掉，测不到触发器/CHECK 本身
      s.execute("select set_config('app.current_tenant','" + a + "',false)");
      s.execute(statement);
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以迁移账号（超级权限）执行 DML —— 等价于「绕过服务层与权限面直连数据库」。 触发器必须在这条路上依然拦住覆盖行为，否则不变量就只是靠约定。 */
  private void adminUpdate(String statement) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + a + "',false)");
      s.execute(statement);
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private static String rootMessage(Throwable t) {
    Throwable cur = t;
    while (cur.getCause() != null) cur = cur.getCause();
    return cur.getMessage() == null ? "" : cur.getMessage();
  }
}
