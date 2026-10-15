package com.starcon.channel;

import static com.starcon.channel.LogisticsChannelModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 10.1.1.2 渠道配置。
 *
 * <p>本测试类钉六组事：
 *
 * <ol>
 *   <li><b>多租户三断言</b>（AGENTS.md 硬性要求，缺一不可）：两租户互不可见、
 *       伪造 tenant header 无效、连接池归还后不串租户
 *   <li><b>🔴 禁限品类 / 电池 / 液体 / 磁力 是真的服务端硬拦截</b>（蓝图加粗句）：
 *       写入时冲突被 DB 层双向拒绝，判定端点给出 BLOCK 结论与逐条理由
 *   <li><b>绕过服务层直接写 SQL 同样被拒</b>：硬拦截的边界是 DB，不是应用层的 if
 *   <li><b>保价上限 / 申报上下限是真判定输入</b>，不是存了没人读的字段
 *   <li><b>旺季附加费按固定时刻判定</b>：timestamptz 往返不偏 8 小时，
 *       区间内 / 区间外两种结果都被钉住
 *   <li><b>真分页真筛选</b>，汇总不受分页影响；适用维度是真多值关系
 * </ol>
 *
 * <p><b>时刻一律用固定的 {@link OffsetDateTime} 钉死</b>，不用 {@code now()}：
 * 旺季费有生效区间，用墙钟会让「今天在不在旺季里」成为一个 flaky 断言 ——
 * 而且更糟的是，一旦写库偏了 8 小时，它在旺季外仍然是绿的。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogisticsChannelIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

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
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired LogisticsChannelService channels;
  @Autowired CarrierService carriers;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  AccessService.Access actorA, actorB;

  @BeforeEach
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    truncateAll();
  }

  void truncateAll() {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      // ⚠️ cascade 是必需的，不是顺手加的：本表（logistics_channel_config）是**渠道配置的
      //   父表**，而下游模块 10.2.2.2 面单模板（V141 的 logistics_label_template）
      //   按「面单模板归属渠道」对它建了复合外键。TRUNCATE 一张被引用的表时
      //   PostgreSQL 要求把引用表一并处理，否则报 23503「cannot truncate a table
      //   referenced in a foreign key constraint」—— 症状是本类每个用例在 setUp 里
      //   就炸，与本模块的任何断言无关。
      //   刻意用 cascade 而不是把下游表名写进来（与 CarrierIntegrationTest 同一结论）：
      //   后者每多一个引用本表的下游模块就得回来改这里一次，漏改的表现是
      //   「别的模块的测试红了」而不是「这里漏了一条」，根因很难查。
      //   本意（渠道四张表 + 承运商三张表）一字未改。
      s.execute("truncate logistics_channel_scope, logistics_channel_surcharge,"
          + " logistics_channel_restriction, logistics_channel_config,"
          + " carrier_country_coverage, carrier_business_scope, carrier_logistics_profile"
          + " cascade");
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  // ==================================================================
  // 夹具
  // ==================================================================

  /**
   * 直接落一条齐备的承运商物流档案（10.1.1.1 的表），渠道配置挂在它之下。
   *
   * <p>写入顺序必须与 {@link CarrierService#save} 一致（照抄它踩过的次序坑）：
   * 先以「子表还空着时正确的 readiness」插入父行 → 写子表 → 再回填最终 readiness。
   * 直接插一行 {@code readiness='READY'} 会被 V134 的触发器当场拒。
   */
  UUID seedCarrier(String code, String name, boolean usable) {
    return seedCarrierFor(a, code, name, usable);
  }

  /** 为租户 B 落一条承运商（承运商档案的复合 FK 带 tenant_id，跨租户挂载本就不可能）。 */
  UUID seedCarrierFor(AccessService.Access who, String code, String name) {
    return seedCarrierFor(who.tenantId(), code, name, true);
  }

  UUID seedCarrierFor(UUID tenant, String code, String name, boolean usable) {
    UUID id = UUID.randomUUID();
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("set app.current_tenant = '" + tenant + "'");
      // ① 父行：此时覆盖国家/业务类型都还没写 ⇒ 正确的 readiness 是 NO_COVERAGE
      s.execute(
          "insert into carrier_logistics_profile (id,tenant_id,role_id,party_id,party_code,"
              + "party_name,party_usable,interface_type,carrier_category,service_tier,"
              + "secret_ref,secret_masked,readiness)"
              + " values ('" + id + "','" + tenant + "','" + UUID.randomUUID() + "','"
              + UUID.randomUUID() + "','" + code + "','" + name + "'," + usable + ","
              + "'API','EXPRESS_COURIER','EXPRESS',"
              + "'kv://starcon/test/carriers/" + code.toLowerCase() + "','sv_live_****3f2a',"
              + "'NO_COVERAGE')");
      // ② 子表
      s.execute("insert into carrier_country_coverage (tenant_id,profile_id,country_code)"
          + " values ('" + tenant + "','" + id + "','DE')");
      s.execute("insert into carrier_business_scope (tenant_id,profile_id,business_type)"
          + " values ('" + tenant + "','" + id + "','OWN_FULFILLMENT')");
      // ③ 回填最终 readiness（此时四项齐备 ⇒ READY）
      s.execute("update carrier_logistics_profile set readiness='READY' where id='" + id + "'");
    } catch (java.sql.SQLException e) {
      throw new RuntimeException(e);
    }
    return id;
  }

  /** 一个「什么限制都没有、什么范围都覆盖」的渠道配置。 */
  LogisticsChannelRow seedChannel(UUID carrierId, String code, String name) {
    return save(actorA, null, carrierId, code, name,
        List.of(new ChannelScopeItem("COUNTRY", "DE"), new ChannelScopeItem("SHOP", "SHOP-DE-1")),
        List.of(), List.of());
  }

  LogisticsChannelRow save(
      AccessService.Access who,
      UUID channelId,
      UUID carrierId,
      String code,
      String name,
      List<ChannelScopeItem> scopes,
      List<ChannelSurchargeItem> surcharges,
      List<ChannelRestrictionItem> restrictions) {
    return save(who, channelId, carrierId, code, name, scopes, surcharges, restrictions,
        "ACTIVE", "BY_WEIGHT", "USD", new BigDecimal("28.50"), BigDecimal.ONE,
        BigDecimal.ZERO, null, null, null, "NONE", BigDecimal.ZERO, BigDecimal.ZERO, null, 0);
  }

  @SuppressWarnings("java:S107")
  LogisticsChannelRow save(
      AccessService.Access who,
      UUID channelId,
      UUID carrierId,
      String code,
      String name,
      List<ChannelScopeItem> scopes,
      List<ChannelSurchargeItem> surcharges,
      List<ChannelRestrictionItem> restrictions,
      String status,
      String billingMode,
      String currency,
      BigDecimal baseRate,
      BigDecimal divisor,
      BigDecimal minCharge,
      BigDecimal declarationMin,
      BigDecimal declarationMax,
      String declarationCurrency,
      String insuranceMode,
      BigDecimal insuranceRate,
      BigDecimal insuranceMinFee,
      BigDecimal insuranceMax,
      long version) {
    return as(who, "channel.operation.create",
        () -> channels.save(new SaveChannelRequest(
            channelId, carrierId, code, name, status, billingMode, currency, baseRate, divisor,
            minCharge, declarationMin, declarationMax, declarationCurrency, insuranceMode,
            insuranceRate, insuranceMinFee, insuranceMax, scopes, surcharges, restrictions,
            "集成测试登记", version)));
  }

  LogisticsChannelPage list(AccessService.Access who) {
    return as(who, "channel.operation.read",
        () -> channels.channels(null, null, null, null, null, null, null, null, "NAME_ASC", 0, 50));
  }

  ChannelEligibilityResult judge(AccessService.Access who, ChannelEligibilityRequest request) {
    return as(who, "channel.operation.read", () -> channels.eligibility(request));
  }

  /** 一条「一切正常」的判定请求：三件不带电不带液不带磁的普通货。 */
  ChannelEligibilityRequest plainRequest() {
    return new ChannelEligibilityRequest(
        null, "DE", "", List.of("HOME_GOODS"), false, false, false,
        new BigDecimal("60"), null);
  }

  List<?> rowsInDb(String table) throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      var rs = s.executeQuery("select * from " + table);
      var meta = rs.getMetaData();
      var out = new ArrayList<Map<String, Object>>();
      while (rs.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          row.put(meta.getColumnLabel(i), rs.getObject(i));
        }
        out.add(row);
      }
      return out;
    }
  }

  // ==================================================================
  // 端到端
  // ==================================================================

  @Test
  @DisplayName("10.1.1.2 端到端：挂承运商 → 配渠道 → readiness 走到 READY → 判定端点给出 PASS")
  void endToEnd() {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);
    LogisticsChannelRow saved =
        save(actorA, null, carrier, "SF-DE-STD", "顺丰德国标准",
            List.of(new ChannelScopeItem("COUNTRY", "DE"), new ChannelScopeItem("SHOP", "SHOP-DE-1")),
            List.of(),
            List.of());

    assertThat(saved.readiness()).isEqualTo("READY");
    assertThat(saved.carrierName()).isEqualTo("顺丰速运");
    assertThat(saved.billingMode()).isEqualTo("BY_WEIGHT");
    assertThat(saved.billingCurrency()).isEqualTo("USD");
    // BigDecimal 往返不丢精度（金额列 NUMERIC(19,4)）
    assertThat(saved.baseRate()).isEqualByComparingTo("28.5000");
    assertThat(saved.scopes()).extracting(ChannelScopeItem::scopeValue)
        .containsExactlyInAnyOrder("DE", "SHOP-DE-1");

    // 判定端点：无任何限制命中 ⇒ PASS
    ChannelEligibilityResult result = judge(actorA, plainRequest());
    assertThat(result.pass()).isEqualTo(1);
    assertThat(result.block()).isZero();
    assertThat(result.verdicts()).singleElement()
        .satisfies(v -> {
          assertThat(v.verdict()).isEqualTo("PASS");
          assertThat(v.channelCode()).isEqualTo("SF-DE-STD");
          assertThat(v.carrierName()).isEqualTo("顺丰速运");
          assertThat(v.blockingReasons()).isEmpty();
          assertThat(v.reviewReasons()).isEmpty();
        });
  }

  @Test
  @DisplayName("10.1.1.2 readiness 不接受手工指定：绕过服务层谎报 READY 被 DB 拒")
  void readinessCannotBeForged() throws Exception {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);
    LogisticsChannelRow channel = seedChannel(carrier, "SF-DE-STD", "顺丰德国标准");

    // 超级账号（绕过 RLS）直接写 SQL 把状态改成 READY：仍然被触发器拒
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("set app.current_tenant = '" + a + "'");
      assertThatThrownBy(
              () ->
                  s.execute(
                      "update logistics_channel_config set base_rate=0, readiness='READY' where id='"
                          + channel.id() + "'"))
          .hasMessageContaining("不接受手工指定");
    }
    // 触发器拒绝 ⇒ 行没被改
    assertThat(as(actorA, "channel.operation.read", () -> channels.detail(channel.id())).baseRate())
        .isEqualByComparingTo("28.5000");
  }

  @Test
  @DisplayName("10.1.1.2 渠道代码建后不可改（下游单据对齐这条渠道配置的口径）")
  void channelCodeIsImmutable() {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);
    LogisticsChannelRow channel = seedChannel(carrier, "SF-DE-STD", "顺丰德国标准");

    // 服务层先拒，并说清为什么
    assertThatThrownBy(
            () -> save(actorA, channel.id(), carrier, "SF-DE-XXX", "改名了",
                channel.scopes(), List.of(), List.of(), "ACTIVE", "BY_WEIGHT", "USD",
                new BigDecimal("28.50"), BigDecimal.ONE, BigDecimal.ZERO, null, null, null,
                "NONE", BigDecimal.ZERO, BigDecimal.ZERO, null, channel.version()))
        .hasMessageContaining("渠道代码建后不可改");

    // 绕过服务层直接写 SQL 同样被拒
    assertThatThrownBy(
            () -> {
              try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                  var s = c.createStatement()) {
                s.execute("set app.current_tenant = '" + a + "'");
                s.execute("update logistics_channel_config set channel_code='SF-DE-XXX' where id='"
                    + channel.id() + "'");
              }
            })
        .hasMessageContaining("建后不可改");
  }

  // ==================================================================
  // 🔴 禁限品类 / 电池 / 液体 / 磁力：服务端硬拦截
  // ==================================================================

  @Test
  @DisplayName("10.1.1.2 🔴 禁限品类与适用品类冲突：写入时双向硬拒（含绕过服务层写 SQL）")
  void prohibitedCategoryCannotAlsoBeApplicable() throws Exception {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);
    UUID channelId = seedChannel(carrier, "SF-DE-STD", "顺丰德国标准").id();

    // ① 正向：把一个品类同时登记为适用**和**禁限 ⇒ 服务层拒，并点名是哪个品类
    assertThatThrownBy(
            () -> save(actorA, channelId, carrier, "SF-DE-STD", "顺丰德国标准",
                List.of(new ChannelScopeItem("COUNTRY", "DE"),
                    new ChannelScopeItem("CATEGORY", "BEAUTY_LIQUID")),
                List.of(),
                List.of(new ChannelRestrictionItem(
                    "PROHIBITED_CATEGORY", "BEAUTY_LIQUID", "BLOCK", "含液体，承运商拒收"))))
        .hasMessageContaining("既被登记为适用品类，又被列为禁限")
        .hasMessageContaining("BEAUTY_LIQUID");

    // ② 反向：先只加适用品类（此时无禁限，写得进去），再只加禁限 ⇒ 也被拒。
    //    没有这一条，「先加适用、后加禁限」这条正常运营路径就会造出一条
    //    「自己和自己打架」的渠道配置，而它的表现是**下单被承运商拒收**。
    LogisticsChannelRow scoped =
        save(actorA, channelId, carrier, "SF-DE-STD", "顺丰德国标准",
            List.of(new ChannelScopeItem("COUNTRY", "DE"),
                new ChannelScopeItem("CATEGORY", "BEAUTY_LIQUID")),
            List.of(), List.of());
    assertThat(scoped.scopes()).extracting(ChannelScopeItem::scopeValue)
        .contains("BEAUTY_LIQUID");

    assertThatThrownBy(
            () -> save(actorA, channelId, carrier, "SF-DE-STD", "顺丰德国标准",
                scoped.scopes(),
                List.of(),
                List.of(new ChannelRestrictionItem(
                    "PROHIBITED_CATEGORY", "BEAUTY_LIQUID", "BLOCK", "含液体"))))
        .hasMessageContaining("既被登记为适用品类，又被列为禁限");

    // ③ 正确的改法：去掉适用品类、只留禁限 ⇒ 写得进去（带上 ② 返回的版本号）
    LogisticsChannelRow fixed =
        save(actorA, channelId, carrier, "SF-DE-STD", "顺丰德国标准",
            List.of(new ChannelScopeItem("COUNTRY", "DE")),
            List.of(),
            List.of(new ChannelRestrictionItem(
                "PROHIBITED_CATEGORY", "BEAUTY_LIQUID", "BLOCK", "含液体")),
            "ACTIVE", "BY_WEIGHT", "USD", new BigDecimal("28.50"), BigDecimal.ONE,
            BigDecimal.ZERO, null, null, null, "NONE", BigDecimal.ZERO, BigDecimal.ZERO,
            null, scoped.version());
    assertThat(fixed.restrictions()).hasSize(1);
    assertThat(fixed.scopes()).extracting(ChannelScopeItem::scopeValue).doesNotContain("BEAUTY_LIQUID");
    // 版本号真的自增了（乐观锁不是摆设）
    assertThat(fixed.version()).isEqualTo(scoped.version() + 1);
  }

  @Test
  @DisplayName("10.1.1.2 🔴 硬拦截的边界在 DB：绕过服务层直接 INSERT 禁限也撞不动适用")
  void bypassServiceLayerStillRejected() throws Exception {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);
    // 先把 BEAUTY_LIQUID 登记成适用品类（此时没有禁限，写得进去）
    LogisticsChannelRow channel =
        save(actorA, null, carrier, "SF-DE-STD", "顺丰德国标准",
            List.of(new ChannelScopeItem("COUNTRY", "DE"),
                new ChannelScopeItem("CATEGORY", "BEAUTY_LIQUID")),
            List.of(), List.of());

    // 绕过服务层（超级账号 + RLS）直接 INSERT 一条禁限 —— 应当被反向触发器拒
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("set app.current_tenant = '" + a + "'");
      assertThatThrownBy(
              () ->
                  s.execute(
                      "insert into logistics_channel_restriction (tenant_id,channel_id,"
                          + "restriction_kind,subject,restriction_mode,carrier_reason) values ('"
                          + a + "','" + channel.id() + "','PROHIBITED_CATEGORY','BEAUTY_LIQUID',"
                          + "'BLOCK','含液体')"))
          .hasMessageContaining("不能同时列为禁限");
    }

    // 反过来：库里确实没有这条禁限（拦截生效，事务已回滚）
    assertThat(rowsInDb("logistics_channel_restriction")).isEmpty();
  }

  @Test
  @DisplayName("10.1.1.2 🔴 判定端点：电池 / 液体 / 磁力 三类限制逐条命中并给 BLOCK")
  void eligibilityBlocksBatteryLiquidMagnet() {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);
    save(actorA, null, carrier, "SF-DE-STD", "顺丰德国标准",
        List.of(new ChannelScopeItem("COUNTRY", "DE")),
        List.of(),
        List.of(
            new ChannelRestrictionItem("BATTERY", "", "BLOCK", "内置电池需走 DG 渠道"),
            new ChannelRestrictionItem("LIQUID", "", "BLOCK", ""),
            new ChannelRestrictionItem("MAGNET", "", "MANUAL_REVIEW", "磁性件需贴标")));

    // ① 不带电不带液不带磁 ⇒ PASS
    assertThat(judge(actorA, plainRequest()).verdicts().get(0).verdict()).isEqualTo("PASS");

    // ② 带电 ⇒ BLOCK，理由里点名「带电池」且带承运商侧理由
    var battery = judge(actorA, new ChannelEligibilityRequest(
        null, "DE", "", List.of("HOME_GOODS"), true, false, false,
        new BigDecimal("60"), null)).verdicts().get(0);
    assertThat(battery.verdict()).isEqualTo("BLOCK");
    assertThat(battery.blockingReasons()).anySatisfy(r -> {
      assertThat(r).contains("带电池");
      assertThat(r).contains("内置电池需走 DG 渠道");
    });

    // ③ 带液 ⇒ BLOCK
    assertThat(judge(actorA, new ChannelEligibilityRequest(
        null, "DE", "", List.of("HOME_GOODS"), false, true, false,
        new BigDecimal("60"), null)).verdicts().get(0).verdict()).isEqualTo("BLOCK");

    // ④ 磁力是 MANUAL_REVIEW ⇒ REVIEW（不拦，但不允许自动放行）
    var magnet = judge(actorA, new ChannelEligibilityRequest(
        null, "DE", "", List.of("HOME_GOODS"), false, false, true,
        new BigDecimal("60"), null)).verdicts().get(0);
    assertThat(magnet.verdict()).isEqualTo("REVIEW");
    assertThat(magnet.blockingReasons()).isEmpty();
    assertThat(magnet.reviewReasons()).anySatisfy(r -> assertThat(r).contains("带磁力件"));
  }

  @Test
  @DisplayName("10.1.1.2 🔴 禁限品类按包裹品类命中，不是按渠道适用范围命中")
  void prohibitedCategoryHitsByShipmentCategory() {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);
    save(actorA, null, carrier, "SF-DE-STD", "顺丰德国标准",
        List.of(new ChannelScopeItem("COUNTRY", "DE")),
        List.of(),
        List.of(new ChannelRestrictionItem("PROHIBITED_CATEGORY", "BEAUTY_LIQUID", "BLOCK", "")));

    // 包裹带的是普通家居品 ⇒ PASS（渠道没禁这个品类）
    assertThat(judge(actorA, plainRequest()).verdicts().get(0).verdict()).isEqualTo("PASS");

    // 包裹里有化妆品 ⇒ BLOCK
    var hit = judge(actorA, new ChannelEligibilityRequest(
        null, "DE", "", List.of("HOME_GOODS", "BEAUTY_LIQUID"), false, false, false,
        new BigDecimal("60"), null)).verdicts().get(0);
    assertThat(hit.verdict()).isEqualTo("BLOCK");
    assertThat(hit.blockingReasons()).anySatisfy(r -> assertThat(r).contains("BEAUTY_LIQUID"));
  }

  @Test
  @DisplayName("10.1.1.2 🔴 承运商被拉黑时判 BLOCK（对已拉黑的对手方下单，代价与被拒收同一种）")
  void blacklistedCarrierBlocks() {
    UUID blocked = seedCarrier("BAD", "问题承运商", false);
    UUID ok = seedCarrier("SF", "顺丰速运", true);
    save(actorA, null, blocked, "BAD-DE", "问题渠道",
        List.of(new ChannelScopeItem("COUNTRY", "DE")), List.of(), List.of());
    save(actorA, null, ok, "SF-DE", "顺丰德国",
        List.of(new ChannelScopeItem("COUNTRY", "DE")), List.of(), List.of());

    var result = judge(actorA, plainRequest());
    assertThat(result.block()).isEqualTo(1);
    assertThat(result.pass()).isEqualTo(1);
    assertThat(result.verdicts()).filteredOn(v -> v.channelCode().equals("BAD-DE"))
        .singleElement()
        .satisfies(v -> {
          assertThat(v.verdict()).isEqualTo("BLOCK");
          assertThat(v.blockingReasons()).anySatisfy(r -> assertThat(r).contains("已停用"));
        });
  }

  @Test
  @DisplayName("10.1.1.2 🔴 保价上限与申报上下限是真判定输入，不是存了没人读的字段")
  void insuranceAndDeclarationLimitsAreEnforced() {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);
    save(actorA, null, carrier, "SF-DE-STD", "顺丰德国标准",
        List.of(new ChannelScopeItem("COUNTRY", "DE")),
        List.of(),
        List.of(),
        "ACTIVE", "BY_WEIGHT", "USD",
        new BigDecimal("28.50"), BigDecimal.ONE, BigDecimal.ZERO,
        new BigDecimal("20"), new BigDecimal("800"), "EUR",
        "DECLARED_VALUE", new BigDecimal("1.5"), new BigDecimal("2.00"), new BigDecimal("500"),
        0);

    // 申报价值 900 > 上限 800 ⇒ REVIEW（多数渠道对申报价值有上下限）
    assertThat(judge(actorA, new ChannelEligibilityRequest(
        null, "DE", "", List.of("HOME_GOODS"), false, false, false,
        new BigDecimal("900"), null)).verdicts().get(0).verdict()).isEqualTo("REVIEW");

    // 保价 600 > 保价上限 500 ⇒ BLOCK（超过上限承运商不接保）
    var insured = judge(actorA, new ChannelEligibilityRequest(
        null, "DE", "", List.of("HOME_GOODS"), false, false, false,
        new BigDecimal("60"), new BigDecimal("600"))).verdicts().get(0);
    assertThat(insured.verdict()).isEqualTo("BLOCK");
    assertThat(insured.blockingReasons()).anySatisfy(r -> assertThat(r).contains("保价上限"));

    // 申报价值 60（区间内）+ 保价 400（上限内）⇒ PASS
    assertThat(judge(actorA, new ChannelEligibilityRequest(
        null, "DE", "", List.of("HOME_GOODS"), false, false, false,
        new BigDecimal("60"), new BigDecimal("400"))).verdicts().get(0).verdict())
        .isEqualTo("PASS");
  }

  @Test
  @DisplayName("10.1.1.2 🔴 不承保的渠道：提交保价值判 REVIEW 而不是悄悄放行")
  void uninsuredChannelReview() {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);
    seedChannel(carrier, "SF-DE-STD", "顺丰德国标准");
    var verdict = judge(actorA, new ChannelEligibilityRequest(
        null, "DE", "", List.of("HOME_GOODS"), false, false, false,
        new BigDecimal("60"), new BigDecimal("40"))).verdicts().get(0);
    assertThat(verdict.verdict()).isEqualTo("REVIEW");
    assertThat(verdict.reviewReasons()).anySatisfy(r -> assertThat(r).contains("不承保"));
  }

  // ==================================================================
  // 旺季附加费：固定时刻 + timestamptz 往返
  // ==================================================================

  @Test
  @DisplayName("10.1.1.2 旺季附加费：区间写回逐字相等（timestamptz 不偏 8 小时）")
  void peakSeasonWindowRoundTrips() {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);
    var peak = new ChannelSurchargeItem(
        "PEAK_SEASON", "PERCENT", new BigDecimal("18.00"),
        OffsetDateTime.parse("2026-11-01T00:00:00Z"),
        OffsetDateTime.parse("2026-12-01T00:00:00Z"),
        "双十一与黑五旺季");
    LogisticsChannelRow saved =
        save(actorA, null, carrier, "SF-DE-PEAK", "顺丰德国旺季线",
            List.of(new ChannelScopeItem("COUNTRY", "DE")), List.of(peak), List.of());

    // 🔴 写回值**逐字相等** —— timestamptz 必须绑 OffsetDateTime 且 values 位置写
    //    ?::timestamptz。绑 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，
    //    而偏 8 小时之后 `2026-11-01T00:00:00Z` 会变成 `2026-10-31T16:00Z` ——
    //    **日期直接从 1 号掉到 31 号**，这里用 toInstant() 钉死时刻而不是本地钟点。
    //    （不断言 getHour()：PG 按会话时区渲染，00:00Z 在 +08:00 会显示成 08:00，
    //    那是正确的时区转换，不是偏移错误。）
    ChannelSurchargeItem stored = saved.surcharges().get(0);
    assertThat(stored.effectiveFrom().toInstant())
        .isEqualTo(java.time.Instant.parse("2026-11-01T00:00:00Z"));
    assertThat(stored.effectiveTo().toInstant())
        .isEqualTo(java.time.Instant.parse("2026-12-01T00:00:00Z"));
    // 顺带钉住「同一个瞬间」这件事：偏移只可能来自会话时区渲染，不能来自写入
    assertThat(stored.effectiveFrom()).isEqualTo(OffsetDateTime.parse("2026-11-01T00:00:00Z"));
    assertThat(stored.effectiveTo()).isEqualTo(OffsetDateTime.parse("2026-12-01T00:00:00Z"));
    assertThat(stored.amount()).isEqualByComparingTo("18.0000");
    assertThat(stored.chargeMode()).isEqualTo("PERCENT");
    assertThat(stored.note()).isEqualTo("双十一与黑五旺季");
  }

  @Test
  @DisplayName("10.1.1.2 旺季费按生效区间判定：宽窗口生效、已过期窗口不生效（不依赖 now()）")
  void peakSeasonWindowIsHonoured() {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);
    // 宽窗口（2020 → 2099）：无论测试在哪一天跑它都生效 ⇒ 断言确定性。
    var wide = new ChannelSurchargeItem(
        "PEAK_SEASON", "PERCENT", new BigDecimal("18.00"),
        OffsetDateTime.parse("2020-01-01T00:00:00Z"),
        OffsetDateTime.parse("2099-01-01T00:00:00Z"), "");
    // 已过期窗口（2020 → 2021）：任何合理的运行时刻都在它之后 ⇒ 一定不生效。
    var expired = new ChannelSurchargeItem(
        "PEAK_SEASON", "PERCENT", new BigDecimal("25.00"),
        OffsetDateTime.parse("2020-01-01T00:00:00Z"),
        OffsetDateTime.parse("2021-01-01T00:00:00Z"), "");

    LogisticsChannelRow active =
        save(actorA, null, carrier, "SF-DE-WIDE", "顺丰德国旺季线",
            List.of(new ChannelScopeItem("COUNTRY", "DE")), List.of(wide), List.of());
    assertThat(active.activeSurcharges()).hasSize(1);
    assertThat(active.peakSeasonActive()).isTrue();

    LogisticsChannelRow inactive =
        save(actorA, null, carrier, "SF-DE-OLD", "顺丰德国过期线",
            List.of(new ChannelScopeItem("COUNTRY", "DE")), List.of(expired), List.of());
    // 全集里仍然有这一条（不是被删了），只是当前不在生效集合里
    assertThat(inactive.surcharges()).hasSize(1);
    assertThat(inactive.activeSurcharges()).isEmpty();
    assertThat(inactive.peakSeasonActive()).isFalse();
  }

  @Test
  @DisplayName("10.1.1.2 旺季费必须带生效区间（半配置状态在写库那一刻被拒）")
  void peakSeasonRequiresWindow() {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);
    UUID channelId = seedChannel(carrier, "SF-DE-STD", "顺丰德国标准").id();
    assertThatThrownBy(
            () -> save(actorA, channelId, carrier, "SF-DE-STD", "顺丰德国标准",
                List.of(new ChannelScopeItem("COUNTRY", "DE")),
                List.of(new ChannelSurchargeItem(
                    "PEAK_SEASON", "PERCENT", new BigDecimal("18.00"), null, null, "")),
                List.of()))
        .hasMessageContaining("violates check constraint");
  }

  @Test
  @DisplayName("10.1.1.2 按件计费时折算基数被 DB 钉死为 1（否则按件却按半公斤算）")
  void byPieceRequiresUnitDivisor() {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);
    UUID channelId = seedChannel(carrier, "SF-DE-STD", "顺丰德国标准").id();
    assertThatThrownBy(
            () -> save(actorA, channelId, carrier, "SF-DE-STD", "顺丰德国标准",
                List.of(new ChannelScopeItem("COUNTRY", "DE")), List.of(), List.of(),
                "ACTIVE", "BY_PIECE", "USD",
                new BigDecimal("12.00"), new BigDecimal("0.5"), BigDecimal.ZERO,
                null, null, null, "NONE", BigDecimal.ZERO, BigDecimal.ZERO, null, 0))
        .hasMessageContaining("violates check constraint");
  }

  @Test
  @DisplayName("10.1.1.2 不承保就不该有费率（幽灵配置在写库那一刻被拒）")
  void ghostInsuranceConfigRejected() {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);
    UUID channelId = seedChannel(carrier, "SF-DE-STD", "顺丰德国标准").id();
    assertThatThrownBy(
            () -> save(actorA, channelId, carrier, "SF-DE-STD", "顺丰德国标准",
                List.of(new ChannelScopeItem("COUNTRY", "DE")), List.of(), List.of(),
                "ACTIVE", "BY_WEIGHT", "USD",
                new BigDecimal("28.50"), BigDecimal.ONE, BigDecimal.ZERO,
                null, null, null, "NONE", new BigDecimal("2"), BigDecimal.ZERO, null, 0))
        .hasMessageContaining("violates check constraint");
  }

  // ==================================================================
  // 真分页 / 真筛选
  // ==================================================================

  @Test
  @DisplayName("10.1.1.2 真分页真筛选：3 页合起来恰好 7 条且互不重叠，汇总不受分页影响")
  void realPaginationAndFiltering() {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);
    for (int i = 1; i <= 7; i++) {
      seedChannel(carrier, String.format("SF-DE-%02d", i), "渠道" + i);
    }

    List<String> all = new ArrayList<>();
    for (int p = 0; p < 3; p++) {
      final int pageNo = p;
      LogisticsChannelPage slice =
          as(actorA, "channel.operation.read",
              () -> channels.channels(null, null, null, null, null, null, null, null,
                  "CODE_ASC", pageNo, 3));
      assertThat(slice.total()).isEqualTo(7);
      assertThat(slice.totalPages()).isEqualTo(3);
      assertThat(slice.page()).isEqualTo(pageNo);
      // 汇总**不受分页影响**：每一页的 summary.total 都等于全集行数
      assertThat(slice.summary().total()).isEqualTo(7);
      slice.items().forEach(r -> all.add(r.channelCode()));
    }
    // 7 条分 3 页（3+3+1），三页合起来恰好是全集且互不重叠
    assertThat(all).hasSize(7).doesNotHaveDuplicates();

    // 按适用国家真筛（走 EXISTS，不是逗号串）
    var byCountry = as(actorA, "channel.operation.read",
        () -> channels.channels(null, null, null, null, null, "COUNTRY", "DE", null,
            "NAME_ASC", 0, 50));
    assertThat(byCountry.total()).isEqualTo(7);
    var byOther = as(actorA, "channel.operation.read",
        () -> channels.channels(null, null, null, null, null, "COUNTRY", "US", null,
            "NAME_ASC", 0, 50));
    assertThat(byOther.total()).isZero();

    // 关键词 LIKE 通配符已转义（搜 % 不该变成匹配全部）
    var wildcard = as(actorA, "channel.operation.read",
        () -> channels.channels("%", null, null, null, null, null, null, null, "NAME_ASC", 0, 50));
    assertThat(wildcard.total()).isZero();
  }

  // ==================================================================
  // 多租户三断言（AGENTS.md 硬性要求）
  // ==================================================================

  @Test
  @DisplayName("10.1.1.2 断言一：两租户互不可见")
  void tenantsAreIsolated() {
    UUID carrierA = seedCarrierFor(actorA, "SF", "顺丰速运");
    UUID carrierB = seedCarrierFor(actorB, "FEDEX", "FedEx");
    seedChannel(carrierA, "SF-DE-STD", "顺丰德国标准");
    save(actorB, null, carrierB, "FX-DE-STD", "FedEx 德国标准",
        List.of(new ChannelScopeItem("COUNTRY", "DE")), List.of(), List.of());

    UUID aId = list(actorA).items().get(0).id();

    // B 看不到 A 的渠道，只看到自己那一个
    assertThat(list(actorB).items()).extracting(LogisticsChannelRow::channelCode)
        .containsExactly("FX-DE-STD");
    assertThat(list(actorA).items()).extracting(LogisticsChannelRow::channelCode)
        .containsExactly("SF-DE-STD");

    // B 用 A 的 id 读详情：跨租户与不存在返回**逐字相同**的 404
    assertThatThrownBy(
            () -> as(actorB, "channel.operation.read", () -> channels.detail(aId)))
        .hasMessageContaining("渠道配置不存在");
    assertThatThrownBy(
            () -> as(actorB, "channel.operation.read",
                () -> channels.detail(UUID.randomUUID())))
        .hasMessageContaining("渠道配置不存在");

    // B 改不动也删不掉 A 的渠道
    assertThatThrownBy(
            () -> save(actorB, aId, null, "SF-DE-STD", "改名",
                List.of(new ChannelScopeItem("COUNTRY", "DE")), List.of(), List.of(),
                "ACTIVE", "BY_WEIGHT", "USD", new BigDecimal("1"), BigDecimal.ONE,
                BigDecimal.ZERO, null, null, null, "NONE", BigDecimal.ZERO, BigDecimal.ZERO,
                null, 0))
        .hasMessageContaining("渠道配置不存在");
    assertThatThrownBy(
            () -> as(actorB, "channel.operation.delete",
                () -> {
                  channels.delete(aId);
                  return null;
                }))
        .hasMessageContaining("渠道配置不存在");

    // B 的判定端点同样看不到 A 的渠道：只判出 B 自己的那一条
    assertThat(judge(actorB, plainRequest()).verdicts()).extracting(
        LogisticsChannelModels.ChannelEligibilityVerdict::channelCode)
        .containsExactly("FX-DE-STD");
    assertThat(judge(actorB, new ChannelEligibilityRequest(
        List.of(aId), "DE", "", List.of("HOME_GOODS"), false, false, false, null, null))
        .verdicts()).isEmpty();
  }

  @Test
  @DisplayName("10.1.1.2 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID carrierA = seedCarrierFor(actorA, "SF", "顺丰速运");
    UUID carrierB = seedCarrierFor(actorB, "FEDEX", "FedEx");
    seedChannel(carrierA, "SF-DE-STD", "顺丰德国标准");
    save(actorB, null, carrierB, "FX-DE-STD", "FedEx 德国标准",
        List.of(new ChannelScopeItem("COUNTRY", "DE")), List.of(), List.of());
    String aId = list(actorA).items().get(0).id().toString();
    String bId = list(actorB).items().get(0).id().toString();

    // A 的会话 + B 的头 ⇒ 看到的仍然只是 A 的数据
    http.perform(get("/channel/logistics-channels")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.hasItem(aId)))
        .andExpect(jsonPath("$.items[*].id",
            org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(bId))));

    // 无会话一律 401
    http.perform(get("/channel/logistics-channels").header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("10.1.1.2 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID carrierA = seedCarrierFor(actorA, "SF", "顺丰速运");
    UUID carrierB = seedCarrierFor(actorB, "FEDEX", "FedEx");
    seedChannel(carrierA, "SF-DE-STD", "顺丰德国标准");
    save(actorB, null, carrierB, "FX-DE-STD", "FedEx 德国标准",
        List.of(new ChannelScopeItem("COUNTRY", "DE")), List.of(), List.of());

    for (int i = 0; i < 3; i++) {
      assertThat(list(actorA).items()).extracting(LogisticsChannelRow::channelCode)
          .containsExactly("SF-DE-STD");
      assertThat(list(actorB).items()).extracting(LogisticsChannelRow::channelCode)
          .containsExactly("FX-DE-STD");
      // 判定端点也交替跑 —— 它是下游真正会调的那个入口
      assertThat(judge(actorA, plainRequest()).verdicts()).hasSize(1);
      assertThat(judge(actorA, plainRequest()).verdicts().get(0).channelCode())
          .isEqualTo("SF-DE-STD");
      assertThat(judge(actorB, plainRequest()).verdicts().get(0).channelCode())
          .isEqualTo("FX-DE-STD");
      assertThat(as(actorA, "channel.operation.read", () -> channels.overview()).summary().total())
          .isEqualTo(1);
      assertThat(as(actorB, "channel.operation.read", () -> channels.overview()).summary().total())
          .isEqualTo(1);
    }
  }

  // ==================================================================
  // 端点与能力声明
  // ==================================================================

  @Test
  @DisplayName("10.1.1.2 端点可真实操作（MockMvc 走完整控制器链路）")
  void endpointsDriveTheFlow() throws Exception {
    UUID carrier = seedCarrier("SF", "顺丰速运", true);

    http.perform(get("/channel/logistics-channels/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.billingModes").value(
            org.hamcrest.Matchers.contains("BY_WEIGHT", "BY_PIECE", "BY_VOLUME")))
        // 每项未交付维度 available=false + ownerModule + impact 三者齐备
        .andExpect(jsonPath("$.dimensions[?(@.ownerModule=='10.1.2.1')].available").value(
            org.hamcrest.Matchers.contains(false)))
        .andExpect(jsonPath("$.dimensions[?(@.ownerModule=='10.1.2.1')].impact")
            .isNotEmpty());

    String body = new ObjectMapper().writeValueAsString(
        new SaveChannelRequest(
            null, carrier, "SF-DE-STD", "顺丰德国标准", "ACTIVE", "BY_WEIGHT", "USD",
            new BigDecimal("28.50"), BigDecimal.ONE, BigDecimal.ZERO,
            null, null, null, "NONE", BigDecimal.ZERO, BigDecimal.ZERO, null,
            List.of(new ChannelScopeItem("COUNTRY", "DE")),
            List.of(),
            List.of(new ChannelRestrictionItem("LIQUID", "", "BLOCK", "承运商拒收液体")),
            "走接口", 0));
    var created =
        http.perform(post("/channel/logistics-channels")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.readiness").value("READY"))
            .andExpect(jsonPath("$.carrierName").value("顺丰速运"))
            .andExpect(jsonPath("$.restrictions[0].restrictionKind").value("LIQUID"))
            .andReturn();
    String id = com.jayway.jsonpath.JsonPath.read(
        created.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id");

    http.perform(get("/channel/logistics-channels/{id}", id)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channelCode").value("SF-DE-STD"))
        .andExpect(jsonPath("$.baseRate").value(28.5000));

    http.perform(get("/channel/logistics-channels/overview")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.total").value(1))
        .andExpect(jsonPath("$.byBillingMode[?(@.code=='BY_WEIGHT')].count").value(1))
        .andExpect(jsonPath("$.byRestrictionKind[?(@.code=='LIQUID')].count").value(1));

    // 判定端点：带液 ⇒ BLOCK
    http.perform(post("/channel/logistics-channels/eligibility")
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(
                "{\"countryCode\":\"DE\",\"categoryCodes\":[\"HOME_GOODS\"],"
                    + "\"containsBattery\":false,\"containsLiquid\":true,\"containsMagnet\":false,"
                    + "\"declaredValue\":60}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.block").value(1))
        .andExpect(jsonPath("$.verdicts[0].verdict").value("BLOCK"))
        .andExpect(jsonPath("$.verdicts[0].blockingReasons[0]").value(
            org.hamcrest.Matchers.containsString("承运商拒收液体")));

    http.perform(delete("/channel/logistics-channels/{id}", id)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNoContent());

    // 撤下渠道配置**不动**承运商档案（那是 10.1.1.1 的主权）
    assertThat(as(actorA, "channel.operation.read", () -> carriers.detail(carrier)).partyName())
        .isEqualTo("顺丰速运");
  }

  @Test
  @DisplayName("10.1.1.2 capabilities 的未交付维度写清 ownerModule 与影响，不冒充已交付")
  void capabilitiesDeclareGapsHonestly() {
    var caps = as(actorA, "channel.operation.read", () -> channels.capabilities());
    assertThat(caps.restrictionKinds()).containsExactly(
        "PROHIBITED_CATEGORY", "BATTERY", "LIQUID", "MAGNET");
    // 硬拦截这一项必须 available=true 且说清来源
    var enforcement = caps.dimensions().stream()
        .filter(d -> d.dimension().equals("restriction_enforcement")).findFirst().orElseThrow();
    assertThat(enforcement.available()).isTrue();
    assertThat(enforcement.source()).contains("eligibility");

    // 未交付的三项：available=false + ownerModule + impact 三者齐备
    for (String owner : List.of("10.1.2.1", "10.1.2.2", "10.1.1.3")) {
      var gap = caps.dimensions().stream()
          .filter(d -> owner.equals(d.ownerModule())).findFirst().orElseThrow();
      assertThat(gap.available()).isFalse();
      assertThat(gap.impact()).isNotBlank();
      assertThat(gap.source()).isNotBlank();
    }
  }
}
