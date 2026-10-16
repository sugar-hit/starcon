package com.starcon.channel;

import static com.starcon.channel.CarrierModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 10.1.1.1 承运商列表。
 *
 * <p>本测试类钉五组事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、
 *       伪造 tenant header 无效、连接池归还后不串租户
 *   <li><b>🔴 承运商身份不在此处重复建档</b>（蓝图加粗句）：本模块的写端点
 *       不接受任何主体身份字段，绕过服务层直接改快照也会被 DB 拒
 *   <li><b>🔴 凭据只存引用</b>：真实 token 在写库那一刻被拒；库里只剩引用与掩码
 *   <li><b>🔴 可用性是可判定的依据</b>：不是管理员手填的下拉框，谎报状态被
 *       DB 触发器拒；上游不可达时 503 而不是空列表
 *   <li><b>真分页真筛选</b>，汇总不受分页影响；覆盖国家 / 业务类型是真实多值关系
 * </ol>
 *
 * <p><b>不出现任何真实凭据</b>：测试里只有形如 {@code kv://starcon/test/carrier/sf}
 * 的引用与 {@code sf_live_****3f2a} 的掩码。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CarrierIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer catalog;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** 上游夹具按 bearer 分租户：真实 catalog 侧也有自己的 RLS，B 的会话读不到 A 的承运商。 */
  static final Map<String, List<Map<String, Object>>> carriersByBearer = new ConcurrentHashMap<>();
  static final Map<String, Map<String, Object>> profileByBearer = new ConcurrentHashMap<>();
  static final List<String> catalogBears = new ArrayList<>();

  /** 让上游「不可达」，用来验证本模块 503 而不是返回空列表。 */
  static final AtomicBoolean catalogDown = new AtomicBoolean(false);

  /** 复用 identity 已注册的 channel 权限码：本模块不新增权限码，identity 零改动。 */
  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
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
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
      actors.put("Bearer tenant-a", actor(user, a, grants));
      actors.put("Bearer tenant-b", actor(user, b, grants));
      identity.start();

      // ---- 上游 catalog 的**真实 API 形状**（不是内存里造对象）----
      // 端口 CarrierProfileQueryPort 真的发 HTTP 过来，所以这里必须在 HTTP 层
      // 断言它转发了调用方的 bearer —— 那是租户隔离「两道」的第一道。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/parties",
          e -> {
            String bear = e.getRequestHeaders().getFirst("Authorization");
            if (catalogDown.get()) {
              e.sendResponseHeaders(503, -1);
              e.close();
              return;
            }
            if (bear != null) {
              catalogBears.add(bear);
            }
            Object payload;
            if (e.getRequestURI().getPath().endsWith("/carrier-profile")) {
              String partyId = e.getRequestURI().getPath().split("/")[3];
              Map<String, Object> mine =
                  profileByBearer.getOrDefault(String.valueOf(bear), Map.<String, Object>of());
              Object raw = mine.get(partyId);
              if (raw == null) {
                e.sendResponseHeaders(404, -1);
                e.close();
                return;
              }
              @SuppressWarnings("unchecked")
              Map<String, Object> profile = (Map<String, Object>) raw;
              payload = profile;
            } else {
              List<Map<String, Object>> mine =
                  carriersByBearer.getOrDefault(String.valueOf(bear), List.of());
              Map<String, Object> page = new LinkedHashMap<>();
              page.put("items", mine);
              page.put("total", mine.size());
              page.put("page", 0);
              page.put("size", mine.size());
              payload = page;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(payload);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.start();
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
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
  }

  @Autowired CarrierService carriers;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  @BeforeEach
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    carriersByBearer.clear();
    profileByBearer.clear();
    catalogBears.clear();
    catalogDown.set(false);
    // 本类的断言大量用绝对计数，不清就会把上一个用例的残留算进来。
    truncateAll();
  }

  void truncateAll() {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // ⚠️ cascade 是必需的，不是顺手加的：本表是**承运商档案的父表**，而下游模块
      //   （10.1.1.2 渠道配置 V136 的 logistics_channel_config）按「渠道归属承运商」
      //   对它建了复合外键。TRUNCATE 一张被引用的表时 PostgreSQL 要求把**全部**引用表
      //   一并列进同一条语句，否则报 23503「cannot truncate a table referenced in a
      //   foreign key constraint」—— 症状是本类每个用例在 setUp 里就炸，与本模块
      //   的任何断言无关。
      //   刻意用 cascade 而不是把下游表名逐个写进来：后者每多一个引用本表的下游模块
      //   就得回来改这里一次，漏改的表现是「别的模块的测试红了」而不是「这里漏了一条」，
      //   根因很难查。cascade 覆盖**任何**引用表，本意（三张承运商表 + 其依赖）一字未改。
      s.execute(
          "truncate carrier_country_coverage, carrier_business_scope,"
              + " carrier_logistics_profile cascade");
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    catalog.stop(0);
    pg.stop();
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 夹具：上游 catalog 的一个承运商
  // ==================================================================

  /** 往上游假服务放一个挂了 CARRIER 角色、且建了承运商角色档案的往来单位。 */
  void upstreamCarrier(
      String bearer, String code, String name, boolean usable, String interfaceType) {
    UUID partyId = UUID.randomUUID();
    UUID roleId = UUID.randomUUID();
    Map<String, Object> party = new LinkedHashMap<>();
    party.put("id", partyId.toString());
    party.put("code", code);
    party.put("name", name);
    party.put("status", usable ? "ACTIVE" : "BLACKLISTED");
    party.put("roles", List.of("CARRIER"));
    party.put("usable", usable);
    party.put("usableBlockCode", usable ? "" : "PARTY_BLACKLISTED");
    carriersByBearer.computeIfAbsent(bearer, k -> new ArrayList<>()).add(party);

    Map<String, Object> profile = new LinkedHashMap<>();
    profile.put("partyId", partyId.toString());
    profile.put("roleId", roleId.toString());
    profile.put("roleStatus", "ACTIVE");
    profile.put("profileFound", true);
    profile.put("interfaceType", interfaceType);
    profile.put("accountRef", "ACC-" + code);
    profile.put("serviceCapabilities", List.of("EXPRESS", "DOOR_TO_DOOR"));
    profileByBearer.computeIfAbsent(bearer, k -> new ConcurrentHashMap<>())
        .put(partyId.toString(), profile);
  }

  SyncCarriersResult sync(AccessService.Access who, String bearer) {
    return as(who, "channel.operation.create", () -> carriers.syncCarriers(0, 100, bearer));
  }

  CarrierPage list(AccessService.Access who) {
    return as(who, "channel.operation.read",
        () -> carriers.carriers(null, null, null, null, null, null, "NAME_ASC", 0, 50));
  }

  CarrierRow byName(AccessService.Access who, String name) {
    return list(who).items().stream()
        .filter(r -> r.partyName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("找不到承运商 " + name));
  }

  /** 登记一份**齐备**的物流侧档案（凭据只给引用 + 掩码，不含任何真实凭据）。 */
  CarrierRow registerFull(
      AccessService.Access who, String name, String category, String tier,
      List<String> countries, List<String> business, long version) {
    return as(who, "channel.operation.update",
        () -> carriers.save(
            byName(who, name).id(),
            new SaveCarrierRequest(
                category, tier,
                "kv://starcon/test/carriers/" + name.toLowerCase(),
                "sf_live_****3f2a", countries, business, "集成测试登记", version)));
  }

  // ==================================================================
  // 端到端
  // ==================================================================

  @Test
  @DisplayName("10.1.1.1 端到端：同步承运商身份 → 登记物流侧档案 → 可用性从 NOT_REGISTERED 走到 AVAILABLE")
  void endToEnd() {
    upstreamCarrier("Bearer tenant-a", "SF", "顺丰速运", true, "API");
    upstreamCarrier("Bearer tenant-a", "DHL", "DHL Express", true, "API");

    SyncCarriersResult synced = sync(actorA, "Bearer tenant-a");
    assertThat(synced.seen()).isEqualTo(2);
    assertThat(synced.created()).isEqualTo(2);

    // 同步只带身份快照，物流侧档案是空的 ⇒ 如实 NOT_REGISTERED
    CarrierRow fresh = byName(actorA, "顺丰速运");
    assertThat(fresh.readiness()).isEqualTo("NOT_REGISTERED");
    assertThat(fresh.availability()).isEqualTo("NOT_REGISTERED");
    assertThat(fresh.carrierCategory()).isNull();
    assertThat(fresh.serviceTier()).isNull();
    assertThat(fresh.coverageCountries()).isEmpty();
    // 身份字段来自上游快照
    assertThat(fresh.partyCode()).isEqualTo("SF");
    assertThat(fresh.interfaceType()).isEqualTo("API");

    // 登记到一半：覆盖国家有了，业务类型没有
    CarrierRow half =
        as(actorA, "channel.operation.update",
            () -> carriers.save(
                fresh.id(),
                new SaveCarrierRequest(
                    "EXPRESS_COURIER", "EXPRESS", "", "", List.of("DE", "US"),
                    List.<String>of(), "半成品", 0)));
    assertThat(half.readiness()).isEqualTo("NO_CREDENTIAL");
    assertThat(half.coverageCountries()).containsExactly("DE", "US");

    // 补齐凭据引用与业务类型 ⇒ READY ⇒ AVAILABLE
    CarrierRow full = registerFull(actorA, "顺丰速运", "EXPRESS_COURIER", "EXPRESS",
        List.of("DE", "US"), List.of("OWN_FULFILLMENT", "FBA_INBOUND"), half.version());
    assertThat(full.readiness()).isEqualTo("READY");
    assertThat(full.availability()).isEqualTo("AVAILABLE");
    assertThat(full.businessTypes()).containsExactly("FBA_INBOUND", "OWN_FULFILLMENT");
    // 凭据只存引用与掩码
    assertThat(full.secretRef()).startsWith("kv://");
    assertThat(full.secretMasked()).contains("*");

    // 另一个承运商没登记，仍是 NOT_REGISTERED（列表如实反映）
    assertThat(byName(actorA, "DHL Express").availability()).isEqualTo("NOT_REGISTERED");
  }

  @Test
  @DisplayName("10.1.1.1 跨服务只经授权 API：端口转发的是调用方自己的 bearer")
  void upstreamCallForwardsCallerBearer() {
    upstreamCarrier("Bearer tenant-a", "SF", "顺丰速运", true, "API");
    sync(actorA, "Bearer tenant-a");
    // 同步会打两次上游（列表 + 逐个角色档案），两次都必须带调用方自己的 bearer，
    // 而不是本服务替对方决定租户。
    assertThat(catalogBears).isNotEmpty().containsOnly("Bearer tenant-a");
    assertThat(catalogBears).doesNotContain("Bearer tenant-b");
  }

  @Test
  @DisplayName("10.1.1.1 🔴 凭据只存密钥管理器引用：真实 token 在写库那一刻被拒")
  void rawCredentialIsRejected() {
    upstreamCarrier("Bearer tenant-a", "SF", "顺丰速运", true, "API");
    sync(actorA, "Bearer tenant-a");
    UUID id = byName(actorA, "顺丰速运").id();

    // 真实 token（没有引用前缀）被 DB 的 CHECK 拒
    assertThatThrownBy(
            () -> as(actorA, "channel.operation.update",
                () -> carriers.save(id, new SaveCarrierRequest(
                    "EXPRESS_COURIER", "EXPRESS", "sk_live_51H8xQ2eZvKYlo2Cabcdef",
                    "sk_live_****cdef", List.of("DE"), List.of("OWN_FULFILLMENT"), "", 0))))
        .hasMessageContaining("violates check constraint");

    // 有引用但没掩码 ⇒ 界面上会显示成「没配凭据」而其实配了，也被拒
    assertThatThrownBy(
            () -> as(actorA, "channel.operation.update",
                () -> carriers.save(id, new SaveCarrierRequest(
                    "EXPRESS_COURIER", "EXPRESS", "kv://starcon/test/carriers/sf",
                    "plaintext", List.of("DE"), List.of("OWN_FULFILLMENT"), "", 0))))
        .hasMessageContaining("violates check constraint");

    // 库里一个真实凭据都没有
    assertThat(rowsInDb())
        .allSatisfy(
            row -> {
              assertThat(String.valueOf(row.get("secret_ref"))).doesNotContain("sk_live_51H8");
              assertThat(String.valueOf(row.get("secret_masked"))).doesNotContain("abcdef");
            });
  }

  @Test
  @DisplayName("10.1.1.1 🔴 可用性不是手填的下拉框：绕过服务层谎报 READY 被 DB 拒")
  void readinessCannotBeForged() throws Exception {
    upstreamCarrier("Bearer tenant-a", "SF", "顺丰速运", true, "API");
    sync(actorA, "Bearer tenant-a");
    UUID id = byName(actorA, "顺丰速运").id();

    // 超级账号（绕过 RLS）直接写 SQL 把状态改成 READY：仍然被触发器拒
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("set app.current_tenant = '" + a + "'");
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update carrier_logistics_profile set readiness='READY' where id='"
                          + id
                          + "'"))
          .hasMessageContaining("不接受手工指定");
    }
    assertThat(byName(actorA, "顺丰速运").readiness()).isEqualTo("NOT_REGISTERED");
  }

  @Test
  @DisplayName("10.1.1.1 🔴 承运商身份归 catalog：绕过服务层改快照被拒")
  void upstreamSnapshotCannotBeEditedLocally() throws Exception {
    upstreamCarrier("Bearer tenant-a", "SF", "顺丰速运", true, "API");
    sync(actorA, "Bearer tenant-a");
    UUID id = byName(actorA, "顺丰速运").id();

    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("set app.current_tenant = '" + a + "'");
      // 改接口类型（可用性判定的输入）而不推进 synced_at ⇒ 被拒
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update carrier_logistics_profile set interface_type='EMAIL' where id='"
                          + id
                          + "'"))
          .hasMessageContaining("只能由同步按上游读数刷新");
      // 改主体编码 ⇒ 被拒（编码是下游单据对齐主体的锚点）
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update carrier_logistics_profile set party_code='SF2' where id='"
                          + id
                          + "'"))
          .hasMessageContaining("主体编码建后不可改");
    }
  }

  @Test
  @DisplayName("10.1.1.1 🔴 上游不可达一律 503，绝不返回空承运商列表")
  void upstreamUnavailableIsFiveOhThree() {
    upstreamCarrier("Bearer tenant-a", "SF", "顺丰速运", true, "API");
    catalogDown.set(true);

    assertThatThrownBy(() -> sync(actorA, "Bearer tenant-a"))
        .hasMessageContaining("catalog 服务返回 503");

    // 一次都没落库（不能拿半截数据糊弄）
    assertThat(rowsInDb()).isEmpty();
  }

  // ==================================================================
  // 多值维度 / 筛选 / 分页
  // ==================================================================

  @Test
  @DisplayName("10.1.1.1 覆盖国家与支持业务类型是真多值关系，能按它们筛选")
  void multiValueDimensionsAreFilterable() {
    upstreamCarrier("Bearer tenant-a", "SF", "顺丰速运", true, "API");
    upstreamCarrier("Bearer tenant-a", "DHL", "DHL Express", true, "API");
    sync(actorA, "Bearer tenant-a");
    registerFull(actorA, "顺丰速运", "EXPRESS_COURIER", "EXPRESS",
        List.of("DE", "US", "JP"), List.of("OWN_FULFILLMENT"), 0);
    registerFull(actorA, "DHL Express", "EXPRESS_COURIER", "EXPRESS",
        List.of("US"), List.of("OWN_FULFILLMENT", "FBA_INBOUND"), 0);

    // 按覆盖国家筛：只有 SF 送到 JP
    CarrierPage jp =
        as(actorA, "channel.operation.read",
            () -> carriers.carriers(null, null, null, null, "JP", null, "NAME_ASC", 0, 50));
    assertThat(jp.items()).extracting(CarrierRow::partyName).containsExactly("顺丰速运");

    // 按业务类型筛：只有 DHL 接 FBA 入仓
    CarrierPage fba =
        as(actorA, "channel.operation.read",
            () -> carriers.carriers(null, null, null, null, null, "FBA_INBOUND", "NAME_ASC", 0, 50));
    assertThat(fba.items()).extracting(CarrierRow::partyName).containsExactly("DHL Express");

    // 多值关系真的每值一行，不是逗号串
    assertThat(byName(actorA, "顺丰速运").coverageCountries()).containsExactly("DE", "JP", "US");
    assertThat(byName(actorA, "顺丰速运").coverageCount()).isEqualTo(3);
    assertThat(countRows("select count(*) from carrier_country_coverage where profile_id='"
        + byName(actorA, "顺丰速运").id() + "'")).isEqualTo(3);
  }

  @Test
  @DisplayName("10.1.1.1 真分页真筛选，且汇总不受分页影响")
  void pagingIsRealAndSummaryIsIndependent() {
    for (int i = 1; i <= 7; i++) {
      upstreamCarrier("Bearer tenant-a", "C" + i, "Carrier " + i, true, "API");
    }
    sync(actorA, "Bearer tenant-a");

    // 第 1 页 3 条，第 2 页 3 条，第 3 页 1 条 —— 真分页
    CarrierPage p0 =
        as(actorA, "channel.operation.read",
            () -> carriers.carriers(null, null, null, null, null, null, "NAME_ASC", 0, 3));
    CarrierPage p1 =
        as(actorA, "channel.operation.read",
            () -> carriers.carriers(null, null, null, null, null, null, "NAME_ASC", 1, 3));
    CarrierPage p2 =
        as(actorA, "channel.operation.read",
            () -> carriers.carriers(null, null, null, null, null, null, "NAME_ASC", 2, 3));
    assertThat(p0.items()).hasSize(3);
    assertThat(p1.items()).hasSize(3);
    assertThat(p2.items()).hasSize(1);
    assertThat(p0.total()).isEqualTo(7);
    assertThat(p0.totalPages()).isEqualTo(3);
    // 三页互不重叠，合起来正好 7 条
    assertThat(
            java.util.stream.Stream.of(p0, p1, p2)
                .flatMap(p -> p.items().stream())
                .map(CarrierRow::id)
                .distinct()
                .count())
        .isEqualTo(7);

    // 🔴 汇总不受分页影响：每页拿到的 summary 都是全量口径的 7
    assertThat(p0.summary().total()).isEqualTo(7);
    assertThat(p1.summary().total()).isEqualTo(7);
    assertThat(p2.summary().total()).isEqualTo(7);
    assertThat(p0.summary().notRegistered()).isEqualTo(7);

    // 关键词是真筛选（LIKE 通配符已转义）：搜 "%" 不该变成「匹配全部」
    CarrierPage literal =
        as(actorA, "channel.operation.read",
            () -> carriers.carriers("%", null, null, null, null, null, "NAME_ASC", 0, 50));
    assertThat(literal.items()).isEmpty();
    CarrierPage hit =
        as(actorA, "channel.operation.read",
            () -> carriers.carriers("Carrier 3", null, null, null, null, null, "NAME_ASC", 0, 50));
    assertThat(hit.items()).extracting(CarrierRow::partyName).containsExactly("Carrier 3");
  }

  @Test
  @DisplayName("10.1.1.1 可用性优先级：主体不可用 > 配置缺口 > 只能人工")
  void availabilityLadder() {
    // 上游已拉黑：即便配置齐备也不可用
    upstreamCarrier("Bearer tenant-a", "BLACK", "Blocked Carrier", false, "API");
    // 只能人工处理
    upstreamCarrier("Bearer tenant-a", "PORTAL", "Portal Carrier", true, "WEB_PORTAL");
    // 配置齐备且系统可对接
    upstreamCarrier("Bearer tenant-a", "API", "Api Carrier", true, "API");
    sync(actorA, "Bearer tenant-a");

    registerFull(actorA, "Blocked Carrier", "EXPRESS_COURIER", "STANDARD",
        List.of("US"), List.of("OWN_FULFILLMENT"), 0);
    registerFull(actorA, "Portal Carrier", "EXPRESS_COURIER", "STANDARD",
        List.of("US"), List.of("OWN_FULFILLMENT"), 0);
    registerFull(actorA, "Api Carrier", "EXPRESS_COURIER", "STANDARD",
        List.of("US"), List.of("OWN_FULFILLMENT"), 0);

    CarrierRow blocked = byName(actorA, "Blocked Carrier");
    // 上游主体不可用排第一：配置齐备（readiness=READY）也仍然 PARTY_BLOCKED
    assertThat(blocked.readiness()).isEqualTo("READY");
    assertThat(blocked.availability()).isEqualTo("PARTY_BLOCKED");
    assertThat(blocked.availabilityMessage()).contains("/partners");

    assertThat(byName(actorA, "Portal Carrier").availability()).isEqualTo("MANUAL_ONLY");
    assertThat(byName(actorA, "Api Carrier").availability()).isEqualTo("AVAILABLE");

    // 按可用性筛选走 SQL，与统计同一条表达式
    CarrierPage blockedPage =
        as(actorA, "channel.operation.read",
            () -> carriers.carriers(null, null, null, "PARTY_BLOCKED", null, null, "NAME_ASC", 0, 50));
    assertThat(blockedPage.items()).extracting(CarrierRow::partyName).containsExactly("Blocked Carrier");
    // 列表里的 summary 覆盖的是**筛选结果**这一集，所以此处只有 blocked
    assertThat(blockedPage.summary().total()).isEqualTo(1);
    assertThat(blockedPage.summary().partyBlocked()).isEqualTo(1);
    assertThat(blockedPage.summary().available()).isZero();

    // 不筛选时 summary 覆盖全集：三种可用性各一个
    CarrierSummary all = list(actorA).summary();
    assertThat(all.total()).isEqualTo(3);
    assertThat(all.partyBlocked()).isEqualTo(1);
    assertThat(all.available()).isEqualTo(1);
    assertThat(all.manualOnly()).isEqualTo(1);
  }

  // ==================================================================
  // 多租户三断言（AGENTS.md 硬性要求，缺一不可）
  // ==================================================================

  @Test
  @DisplayName("10.1.1.1 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    upstreamCarrier("Bearer tenant-a", "SF", "顺丰速运", true, "API");
    upstreamCarrier("Bearer tenant-b", "FEDEX", "FedEx", true, "API");
    sync(actorA, "Bearer tenant-a");
    sync(actorB, "Bearer tenant-b");

    UUID aId = byName(actorA, "顺丰速运").id();

    // B 看不到 A 的承运商；B 只看到自己上游那一个
    assertThat(list(actorB).items()).extracting(CarrierRow::partyName).containsExactly("FedEx");
    assertThat(list(actorA).items()).extracting(CarrierRow::partyName).containsExactly("顺丰速运");

    // B 用 A 的 id 读详情：跨租户与不存在返回逐字相同的 404
    assertThatThrownBy(() -> as(actorB, "channel.operation.read", () -> carriers.detail(aId)))
        .hasMessageContaining("承运商不存在");
    assertThatThrownBy(
            () -> as(actorB, "channel.operation.read", () -> carriers.detail(UUID.randomUUID())))
        .hasMessageContaining("承运商不存在");

    // B 也改不动 A 的档案：拿 A 的 id 直接写，报的是**逐字相同**的 404
    assertThatThrownBy(
            () ->
                as(actorB, "channel.operation.update",
                    () -> carriers.save(aId, new SaveCarrierRequest(
                        "EXPRESS_COURIER", "EXPRESS", "", "", List.of("US"),
                        List.of("OWN_FULFILLMENT"), "", 0))))
        .hasMessageContaining("承运商不存在");
    // B 也撤不掉 A 的档案
    assertThatThrownBy(
            () ->
                access.run(
                    actorB,
                    "channel.operation.delete",
                    () -> {
                      carriers.deleteLogisticsProfile(aId);
                      return null;
                    }))
        .hasMessageContaining("承运商不存在");
  }

  @Test
  @DisplayName("10.1.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    upstreamCarrier("Bearer tenant-a", "SF", "顺丰速运", true, "API");
    upstreamCarrier("Bearer tenant-b", "FEDEX", "FedEx", true, "API");
    sync(actorA, "Bearer tenant-a");
    sync(actorB, "Bearer tenant-b");
    UUID aId = byName(actorA, "顺丰速运").id();
    UUID bId = byName(actorB, "FedEx").id();

    // A 的会话 + B 的头 ⇒ 看到的仍然只是 A 的数据
    http.perform(get("/channel/carriers")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.hasItem(aId.toString())))
        .andExpect(jsonPath("$.items[*].id",
            org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(bId.toString()))));

    // 无会话一律 401
    http.perform(get("/channel/carriers").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("10.1.1.1 断言三：连接池归还后不串租户")
  void pooledConnectionsDoNotBleedTenants() {
    upstreamCarrier("Bearer tenant-a", "SF", "顺丰速运", true, "API");
    upstreamCarrier("Bearer tenant-b", "FEDEX", "FedEx", true, "API");
    sync(actorA, "Bearer tenant-a");
    sync(actorB, "Bearer tenant-b");

    // 同一个池上交替跑 A/B/A/B/A —— 单连接下最容易暴露的串租户就在这里
    for (int i = 0; i < 3; i++) {
      assertThat(list(actorA).items()).extracting(CarrierRow::partyName).containsExactly("顺丰速运");
      assertThat(list(actorB).items()).extracting(CarrierRow::partyName).containsExactly("FedEx");
      assertThat(as(actorA, "channel.operation.read", () -> carriers.overview()).summary().total())
          .isEqualTo(1);
      assertThat(as(actorB, "channel.operation.read", () -> carriers.overview()).summary().total())
          .isEqualTo(1);
    }
  }

  // ==================================================================
  // 端点与能力声明
  // ==================================================================

  @Test
  @DisplayName("10.1.1.1 端点可真实操作（MockMvc 走完整控制器链路）")
  void endpointsDriveTheFlow() throws Exception {
    upstreamCarrier("Bearer tenant-a", "SF", "顺丰速运", true, "API");

    http.perform(post("/channel/carriers/sync").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(1));

    http.perform(get("/channel/carriers").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.summary.total").value(1))
        .andExpect(jsonPath("$.items[0].partyCode").value("SF"))
        .andExpect(jsonPath("$.items[0].availability").value("NOT_REGISTERED"));

    http.perform(get("/channel/carriers/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.total").value(1))
        .andExpect(jsonPath("$.byCategory[?(@.category=='EXPRESS_COURIER')].count").value(0));

    UUID id = byName(actorA, "顺丰速运").id();
    http.perform(put("/channel/carriers/{id}", id)
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(
                "{\"carrierCategory\":\"EXPRESS_COURIER\",\"serviceTier\":\"EXPRESS\","
                    + "\"secretRef\":\"kv://starcon/test/carriers/sf\",\"secretMasked\":\"sf_live_****3f2a\","
                    + "\"coverageCountries\":[\"DE\",\"US\"],"
                    + "\"businessTypes\":[\"OWN_FULFILLMENT\"],\"note\":\"走接口\",\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.readiness").value("READY"))
        .andExpect(jsonPath("$.availability").value("AVAILABLE"))
        .andExpect(jsonPath("$.coverageCountries", org.hamcrest.Matchers.hasSize(2)));

    // 乐观锁：版本不对给 409
    http.perform(put("/channel/carriers/{id}", id)
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(
                "{\"carrierCategory\":\"EXPRESS_COURIER\",\"serviceTier\":\"EXPRESS\","
                    + "\"secretRef\":\"kv://starcon/test/carriers/sf\",\"secretMasked\":\"sf_live_****3f2a\","
                    + "\"coverageCountries\":[\"DE\"],\"businessTypes\":[\"OWN_FULFILLMENT\"],\"version\":0}"))
        .andExpect(status().isConflict());

    // 撤下物流侧档案（204），随后 404
    http.perform(delete("/channel/carriers/{id}", id).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNoContent());
    http.perform(get("/channel/carriers/{id}", id).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("承运商不存在"));
  }

  @Test
  @DisplayName("10.1.1.1 capabilities 显式声明下游缺口（10.1.1.2 / 10.1.1.3 / 10.1.2.1 未交付）")
  void capabilitiesDeclareDownstreamGaps() {
    CarrierCapabilities caps =
        as(actorA, "channel.operation.read", () -> carriers.capabilities());

    assertThat(caps.dimensions())
        .filteredOn(d -> !d.available())
        .isNotEmpty()
        .allSatisfy(
            d -> {
              assertThat(d.ownerModule()).as("缺口必须点名归属模块").isNotBlank();
              assertThat(d.impact()).as("缺口必须说明影响").isNotBlank();
            });
    assertThat(caps.dimensions())
        .extracting(CarrierCapabilityDimension::dimension)
        .contains("channel_configuration", "transit_time_limits", "rate_calculation");
    assertThat(caps.dimensions())
        .filteredOn(d -> d.dimension().equals("channel_configuration"))
        .allSatisfy(d -> assertThat(d.ownerModule()).isEqualTo("10.1.1.2"));

    // 蓝图加粗纪律要出现在能力声明里：身份主权不在本服务
    assertThat(caps.boundaryNote()).contains("不在此处重复建档");
    // 上游不可达不得冒充空列表
    assertThat(caps.upstreamNote()).contains("503");
  }

  // ==================================================================
  // 直接查库的小助手（绕过 RLS，用迁移账号）
  // ==================================================================

  List<StubRow> rowsInDb() {
    List<StubRow> out = new ArrayList<>();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("set app.current_tenant = '" + a + "'");
      var rs = s.executeQuery("select secret_ref, secret_masked from carrier_logistics_profile");
      while (rs.next()) {
        var row = new java.util.LinkedHashMap<String, Object>();
        row.put("secret_ref", rs.getString("secret_ref"));
        row.put("secret_masked", rs.getString("secret_masked"));
        out.add(new StubRow(row));
      }
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
    return out;
  }

  int countRows(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("set app.current_tenant = '" + a + "'");
      var rs = s.executeQuery(sql);
      rs.next();
      return rs.getInt(1);
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 让 {@link #rowsInDb()} 的断言读起来像断言表行。 */
  record StubRow(Map<String, Object> values) {
    Object get(String key) {
      return values.get(key);
    }
  }
}
